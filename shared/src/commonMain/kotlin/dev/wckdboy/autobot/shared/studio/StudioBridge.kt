package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.shared.BinaryHttpTransport
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.IoDispatcher
import dev.wckdboy.autobot.shared.WebSocketTransport
import dev.wckdboy.autobot.shared.studio.comfy.ComfyBackend
import dev.wckdboy.autobot.shared.studio.comfy.ComfyClient
import dev.wckdboy.autobot.shared.studio.comfy.ComfyEngine
import dev.wckdboy.autobot.shared.studio.comfy.ObjectInfo
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Studio for hosts that are not Kotlin (iOS): the store, queue, engines, ComfyUI backends and
 * pipelines behind plain callbacks, like [dev.wckdboy.autobot.shared.AgentBridge]. Pure helpers
 * (prompt tools, planners, workflow schema edits) are called directly; this class only bridges
 * what suspends or streams.
 */

/** The host's encrypted asset files: bytes sealed at rest under the asset id. Called off the main thread. */
interface StudioFiles {
    fun write(assetId: String, mimeType: String, bytes: ByteArray)
    fun read(assetId: String): ByteArray?
}

/**
 * The Studio core wired for a host. [store] holds the records (projects, assets, workflows,
 * snippets and the queue's jobs as [JOBS] rows), [files] the asset bytes; ComfyUI goes through
 * the host transports (kill switch, route, audit).
 */
class StudioBridge(
    store: StudioStoreBridge,
    private val files: StudioFiles,
    private val http: HttpTransport,
    private val binary: BinaryHttpTransport,
    private val sockets: WebSocketTransport?,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> println("StudioBridge: ${t::class.simpleName}: ${t.message}") },
    )
    /** The records, read and written through the host bridge on the I/O dispatcher. */
    val store: StudioStore = IoStudioStore(BridgedStudioStore(store))

    val engines = EngineRegistry()

    val queue = JobQueue(
        scope,
        engines,
        StoreOutputSink(this.store, writeBytes = { id, out -> withContext(IoDispatcher) { files.write(id, out.mimeType, out.bytes) } }),
        JobRows(store),
    )

    private val comfy = MutableStateFlow<Map<String, Pair<ComfyBackend, ComfyClient>>>(emptyMap())

    /** `run_workflow`'s runner: the agent's generations go to the "Agent" project. */
    val runner: StudioRunner = QueueStudioRunner(
        this.store,
        queue,
        projectId = { QueueStudioRunner.agentProject(this.store, "Agent", currentTimeMillis()) },
        backendLabel = { id -> id?.let { comfy.value[it]?.first?.label ?: it } },
    )

    /** Restores the persisted queue (once, after the store is readable); [done] runs after. */
    fun restore(done: () -> Unit) {
        scope.launch {
            try {
                queue.restore()
            } finally {
                done()
            }
        }
    }

    /** The queue as it changes (a state: intermediate values may be skipped); [listener] runs on a background thread. */
    fun observeJobs(listener: (List<StudioJob>) -> Unit): Cancellable {
        val job = scope.launch { queue.jobs.collect { listener(it) } }
        return Cancellable { job.cancel() }
    }

    /** Registers a host engine (on-device diffusion, audio, speech, a remote diffusion backend). */
    fun register(engine: HostStudioEngine) = engines.register(HostEngineAdapter(engine))

    fun unregister(kind: String, backendId: String?) = engines.unregister(kind, backendId)

    /** The ComfyUI backends now configured: each gets a [ComfyEngine]; removed ones go. */
    fun setComfyBackends(backends: List<ComfyBackend>) {
        val old = comfy.value
        val next = backends.associate { b ->
            val existing = old[b.id]?.takeIf { (o, _) -> o.baseUrl == b.baseUrl && o.route == b.route && o.authHeader == b.authHeader && o.label == b.label }
            b.id to (existing ?: (b to ComfyClient(b, http, binary, sockets, StudioIds.new("autobot"), 1_000, 5_000)))
        }
        comfy.value = next
        old.keys.filter { it !in next }.forEach { engines.unregister(EngineKinds.COMFYUI, it) }
        for ((id, pair) in next) {
            if (old[id] === pair) continue
            val (backend, client) = pair
            engines.register(ComfyEngine(backend, client, { wid -> store.workflows().firstOrNull { it.id == wid } }, AssetReader { read(it) }))
        }
    }

    private suspend fun read(assetId: String): AssetBytes? {
        val asset = store.asset(assetId) ?: return null
        val bytes = withContext(IoDispatcher) { files.read(assetId) } ?: return null
        return AssetBytes("$assetId.${StudioCodec.extension(asset.mimeType)}", asset.mimeType, bytes)
    }

    private fun client(backendId: String): ComfyClient? = comfy.value[backendId]?.second

    /** "Test": `/system_stats` and the node count of `/object_info`; [callback] gets ok and a summary. */
    fun testComfy(backend: ComfyBackend, callback: (Boolean, String) -> Unit) = answering(callback = { (ok, text): Pair<Boolean, String> -> callback(ok, text) }, failed = { false to it }) {
        val client = ComfyClient(backend, http, binary, sockets, StudioIds.new("autobot"), 1_000, 5_000)
        val stats = client.systemStats()
        val info = client.objectInfo()
        val gpu = stats.devices.firstOrNull()?.let { d -> " · ${d.name.substringBefore(" : ").take(40)}" }.orEmpty()
        true to "ok · ComfyUI ${stats.comfyVersion ?: "?"} · ${info.nodes.size} nodes$gpu"
    }

    /** The server's `/object_info` (node classes and model lists), or an error message. */
    fun objectInfo(backendId: String, callback: (ObjectInfo?, String?) -> Unit) = answering(callback = { (info, error): Pair<ObjectInfo?, String?> -> callback(info, error) }, failed = { null to it }) {
        val client = client(backendId) ?: return@answering null to "That ComfyUI backend is not configured"
        client.objectInfo() to null
    }

    /**
     * Imports an API-format workflow: parameters detected (with the server's `/object_info` when
     * [backendId] is reachable, else from the graph alone). [callback] gets the workflow (not yet
     * saved), the validation message (null = everything is on the server, or it could not be
     * checked: see [checked]) or the error.
     */
    fun importWorkflow(name: String, json: String, backendId: String?, callback: (StudioWorkflow?, String?, Boolean, String?) -> Unit) = answering(
        callback = { r: ImportAnswer -> callback(r.workflow, r.validation, r.checked, r.error) },
        failed = { ImportAnswer(null, null, false, it) },
    ) {
        val info = backendId?.let { client(it) }?.let { runCatching { it.objectInfo() }.getOrNull() }
        try {
            val workflow = WorkflowImport.create(name, json, info, StudioIds.new("w"), currentTimeMillis(), backendId)
            val validation = info?.let { WorkflowValidator.validate(workflow.graph, it).message }
            ImportAnswer(workflow, validation, info != null, null)
        } catch (e: WorkflowException) {
            ImportAnswer(null, null, false, e.message ?: "Not a workflow")
        }
    }

    private class ImportAnswer(val workflow: StudioWorkflow?, val validation: String?, val checked: Boolean, val error: String?)

    /** Validates [workflow] on [backendId]; [callback] gets the message (null = ok) or an error. */
    fun validate(workflow: StudioWorkflow, backendId: String, callback: (String?, String?) -> Unit) = answering(
        callback = { (message, error): Pair<String?, String?> -> callback(message, error) },
        failed = { null to it },
    ) {
        val client = client(backendId) ?: return@answering null to "That ComfyUI backend is not configured"
        WorkflowValidator.validate(workflow.graph, client.objectInfo()).message to null
    }

    /** [workflow] with ENUM options and ranges from [backendId]'s `/object_info` (unchanged on failure). */
    fun refreshOptions(workflow: StudioWorkflow, backendId: String, callback: (StudioWorkflow) -> Unit) = answering(callback = callback, failed = { workflow }) {
        val client = client(backendId) ?: return@answering workflow
        WorkflowSchema.refreshOptions(workflow, client.objectInfo())
    }

    /** Waits for job [id] to finish; [callback] gets it (null when it was cleared). */
    fun awaitJob(id: String, callback: (StudioJob?) -> Unit) = answering(callback = callback, failed = { null }) { queue.await(id) }

    /**
     * Runs [pipeline] in [projectId]; [onStep] hears each step, [done] the result or the error.
     * Cancelling the handle cancels the jobs it queued.
     */
    fun runPipeline(
        pipeline: Pipeline,
        projectId: String,
        vars: Map<String, String>,
        input: List<String>,
        onStep: (StepResult) -> Unit,
        done: (PipelineResult?, String?) -> Unit,
    ): Cancellable {
        val answered = AtomicFlag()
        val job = scope.launch {
            val result = try {
                PipelineRunner(queue).run(pipeline, projectId, vars, input, null, onStep)
            } catch (e: PipelineException) {
                if (answered.set()) done(null, e.message ?: "The recipe cannot run")
                return@launch
            }
            if (answered.set()) done(result, null)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && answered.set()) done(null, if (cause is CancellationException) "Cancelled" else cause.message ?: "Failed")
        }
        return Cancellable { job.cancel() }
    }

    /** Stops everything the bridge runs (panic wipe). */
    fun close() {
        queue.cancelAll()
        scope.cancel()
    }

    private fun <R> answering(callback: (R) -> Unit, failed: (String) -> R, block: suspend () -> R) {
        val answered = AtomicFlag()
        scope.launch { val r = block(); if (answered.set()) callback(r) }.invokeOnCompletion { cause ->
            if (cause != null && answered.set()) callback(failed(if (cause is CancellationException) "Cancelled" else cause.message ?: (cause::class.simpleName ?: "error")))
        }
    }

    /** The queue's rows in the host store. */
    private class JobRows(private val bridge: StudioStoreBridge) : JobPersistence {
        override suspend fun load(): List<StudioJob> = withContext(IoDispatcher) {
            bridge.records(JOBS).mapNotNull { runCatching { StudioCodec.decodeJob(it) }.getOrNull() }
        }

        override suspend fun save(job: StudioJob) = withContext(IoDispatcher) { bridge.put(JOBS, job.id, StudioCodec.encodeJob(job)) }

        override suspend fun remove(id: String) = withContext(IoDispatcher) { bridge.remove(JOBS, id) }
    }

    companion object {
        /** The [StudioStoreBridge] kind of the queue's rows. */
        const val JOBS = "jobs"
    }
}

/** A [StudioStore] whose calls run on the I/O dispatcher (the host's store blocks). */
private class IoStudioStore(private val inner: StudioStore) : StudioStore {
    override suspend fun projects() = withContext(IoDispatcher) { inner.projects() }
    override suspend fun putProject(project: StudioProject) = withContext(IoDispatcher) { inner.putProject(project) }
    override suspend fun deleteProject(id: String) = withContext(IoDispatcher) { inner.deleteProject(id) }
    override suspend fun assets(filter: AssetFilter) = withContext(IoDispatcher) { inner.assets(filter) }
    override suspend fun asset(id: String) = withContext(IoDispatcher) { inner.asset(id) }
    override suspend fun putAsset(asset: StudioAsset) = withContext(IoDispatcher) { inner.putAsset(asset) }
    override suspend fun deleteAsset(id: String) = withContext(IoDispatcher) { inner.deleteAsset(id) }
    override suspend fun workflows() = withContext(IoDispatcher) { inner.workflows() }
    override suspend fun putWorkflow(workflow: StudioWorkflow) = withContext(IoDispatcher) { inner.putWorkflow(workflow) }
    override suspend fun deleteWorkflow(id: String) = withContext(IoDispatcher) { inner.deleteWorkflow(id) }
    override suspend fun snippets() = withContext(IoDispatcher) { inner.snippets() }
    override suspend fun putSnippet(snippet: PromptSnippet) = withContext(IoDispatcher) { inner.putSnippet(snippet) }
    override suspend fun deleteSnippet(id: String) = withContext(IoDispatcher) { inner.deleteSnippet(id) }
}

/** Set once. */
@OptIn(ExperimentalAtomicApi::class)
private class AtomicFlag {
    private val flag = AtomicBoolean(false)

    /** True the first time only. */
    fun set(): Boolean = flag.compareAndSet(expectedValue = false, newValue = true)
}
