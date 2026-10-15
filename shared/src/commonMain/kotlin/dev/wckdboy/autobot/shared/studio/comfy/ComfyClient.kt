package dev.wckdboy.autobot.shared.studio.comfy

import dev.wckdboy.autobot.shared.BinaryHttpTransport
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.SocketEvent
import dev.wckdboy.autobot.shared.WebSocketSpec
import dev.wckdboy.autobot.shared.WebSocketTransport
import dev.wckdboy.autobot.shared.events
import dev.wckdboy.autobot.shared.fetch
import dev.wckdboy.autobot.shared.fetchAll
import dev.wckdboy.autobot.shared.studio.AssetReader
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineCapabilities
import dev.wckdboy.autobot.shared.studio.EngineEvent
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.EngineOutput
import dev.wckdboy.autobot.shared.studio.ParamType
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.StudioCodec
import dev.wckdboy.autobot.shared.studio.StudioEngine
import dev.wckdboy.autobot.shared.studio.StudioEngineException
import dev.wckdboy.autobot.shared.studio.StudioIds
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import dev.wckdboy.autobot.shared.studio.Validation
import dev.wckdboy.autobot.shared.studio.WorkflowApplier
import dev.wckdboy.autobot.shared.studio.WorkflowException
import dev.wckdboy.autobot.shared.studio.WorkflowValidator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A ComfyUI server the user configured: base URL (https, or http only to loopback / LAN rules
 * the kill switch enforces), its route, and an optional auth header (reverse proxies).
 */
class ComfyBackend(
    val id: String,
    val label: String,
    val baseUrl: String,
    val route: String = "INHERIT",
    val authHeader: Pair<String, String>? = null,
)

/** A ComfyUI request that failed; [blocked] when the network policy refused it. */
class ComfyException(message: String, val blocked: Boolean = false) : Exception(message)

/** A finished prompt: its id and output files by node. */
class ComfyRun(val promptId: String, val outputs: Map<String, List<ComfyFile>>, val animated: Set<String>)

/**
 * ComfyUI over the host transports: REST through [http] (JSON) and [bytes] (uploads and
 * `/view`), progress through [sockets] (`/ws`), falling back to polling `/history` when there is
 * no socket or it fails. Every request carries the backend's route and the `studio:comfyui` tag,
 * so the kill switch, audit log and receipts see it like any other backend.
 */
class ComfyClient(
    private val backend: ComfyBackend,
    private val http: HttpTransport,
    private val bytes: BinaryHttpTransport,
    private val sockets: WebSocketTransport?,
    val clientId: String = StudioIds.new("autobot"),
    private val pollIntervalMs: Long = 1_000,
    private val socketOpenTimeoutMs: Long = 5_000,
) {
    private val base: String = backend.baseUrl.trim().trimEnd('/')

    init {
        require(BASE.matches(base)) { "Invalid ComfyUI URL" }
    }

    private fun headers(accept: String = "application/json", contentType: String? = null): Map<String, String> = buildMap {
        put("Accept", accept)
        contentType?.let { put("Content-Type", it) }
        backend.authHeader?.let { (k, v) -> put(k, v) }
    }

    private fun spec(method: String, path: String, body: String? = null, maxBytes: Long = MAX_JSON_BYTES, accept: String = "application/json") =
        HttpRequestSpec(method, base + path, headers(accept, body?.let { "application/json" }), body, TAG, backend.route, maxBytes)

    private suspend fun json(method: String, path: String, body: String? = null, maxBytes: Long = MAX_JSON_BYTES): Pair<Int, String> {
        val r = fetchAll(http, spec(method, path, body, maxBytes))
        if (r.failure != null) throw ComfyException(if (r.blocked) "Blocked by network policy: ${r.failure}" else "Can't reach ${backend.label}: ${r.failure}", r.blocked)
        return r.status to r.body
    }

    private suspend fun ok(method: String, path: String, body: String? = null, maxBytes: Long = MAX_JSON_BYTES): String {
        val (status, text) = json(method, path, body, maxBytes)
        if (status !in 200..299) throw ComfyException("${backend.label} answered HTTP $status for $path${text.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" }.orEmpty()}")
        return text
    }

    suspend fun objectInfo(): ObjectInfo = ObjectInfo.parse(ok("GET", "/object_info", maxBytes = MAX_OBJECT_INFO_BYTES))

    suspend fun systemStats(): SystemStats = SystemStats.parse(ok("GET", "/system_stats"))

    suspend fun queue(): QueueStatus = QueueStatus.parse(ok("GET", "/queue", maxBytes = MAX_OBJECT_INFO_BYTES))

    suspend fun submit(graph: JsonObject): SubmitResult {
        val (status, body) = json("POST", "/prompt", ComfyRequests.promptBody(graph, clientId))
        return SubmitResult.parse(status, body)
    }

    /** The history entry of [promptId]; null while the server has none yet. */
    suspend fun history(promptId: String): HistoryEntry? {
        val (status, body) = json("GET", ComfyRequests.historyPath(promptId), maxBytes = MAX_OBJECT_INFO_BYTES)
        if (status !in 200..299) throw ComfyException("${backend.label} answered HTTP $status for /history")
        return HistoryEntry.parse(body, promptId)
    }

    /** Downloads an output (or input / temp) file. */
    suspend fun view(file: ComfyFile): ByteArray {
        val r = bytes.fetch(spec("GET", ComfyRequests.viewPath(file), maxBytes = MAX_FILE_BYTES, accept = "*/*"))
        if (r.failure != null) throw ComfyException(if (r.blocked) "Blocked by network policy: ${r.failure}" else "Can't download ${file.filename}: ${r.failure}", r.blocked)
        if (!r.isSuccessful) throw ComfyException("${backend.label} answered HTTP ${r.status} for ${file.filename}")
        return r.body
    }

    /** Uploads an input image (or audio file) into the server's `input/<subfolder>`. */
    suspend fun upload(name: String, mimeType: String, data: ByteArray, subfolder: String = "autobot", overwrite: Boolean = true): ComfyFile {
        val boundary = "autobot-" + StudioIds.new("b").substringAfter('-')
        val (contentType, body) = ComfyRequests.uploadBody(name, mimeType, data, subfolder, overwrite, boundary)
        val request = HttpRequestSpec("POST", "$base/upload/image", headers(contentType = contentType), null, TAG, backend.route, MAX_JSON_BYTES)
        val r = bytes.fetch(request, body)
        if (r.failure != null) throw ComfyException(if (r.blocked) "Blocked by network policy: ${r.failure}" else "Upload failed: ${r.failure}", r.blocked)
        if (!r.isSuccessful) throw ComfyException("${backend.label} refused the upload (HTTP ${r.status}): ${r.body.decodeToString().take(200)}")
        return ComfyRequests.parseUpload(r.body.decodeToString()) ?: throw ComfyException("Unexpected upload answer from ${backend.label}")
    }

    suspend fun interrupt(promptId: String?) {
        json("POST", "/interrupt", ComfyRequests.interruptBody(promptId))
    }

    /** Removes a prompt that has not started from the server's queue. */
    suspend fun dequeue(promptId: String) {
        json("POST", "/queue", ComfyRequests.deleteFromQueueBody(promptId))
    }

    /**
     * Queues [graph] and follows it to the end: progress, previews and node names over the
     * socket (or `/queue` status while polling), then the outputs from `/history`. Cancelling
     * removes the prompt from the queue and interrupts it on the server.
     */
    suspend fun execute(graph: JsonObject, onEvent: (EngineEvent) -> Unit = {}): ComfyRun = coroutineScope {
        val events = Channel<SocketEvent>(Channel.UNLIMITED)
        var socketJob: Job? = null
        var socketOpen = false
        if (sockets != null) {
            val socketSpec = WebSocketSpec(ComfyRequests.socketUrl(base, clientId), headers(), TAG, backend.route)
            socketJob = launch { sockets.events(socketSpec).collect { events.send(it) } }
            socketOpen = withTimeoutOrNull(socketOpenTimeoutMs) { awaitOpen(events) } == true
            if (!socketOpen) socketJob.cancel()
        }
        val promptId = try {
            when (val submitted = submit(graph)) {
                is SubmitResult.Queued -> submitted.promptId
                is SubmitResult.Refused -> throw ComfyException("${backend.label} refused the workflow: ${submitted.message}")
            }
        } catch (e: Throwable) {
            socketJob?.cancel()
            throw e
        }
        try {
            val socketOutputs = LinkedHashMap<String, List<ComfyFile>>()
            if (socketOpen) followSocket(events, promptId, graph, socketOutputs, onEvent)
            socketJob?.cancel()
            val entry = pollHistory(promptId, onEvent)
            if (entry.status == "error") throw ComfyException(entry.error ?: "The workflow failed on ${backend.label}")
            ComfyRun(promptId, entry.outputs.ifEmpty { socketOutputs }, entry.animated)
        } catch (e: CancellationException) {
            socketJob?.cancel()
            withContext(NonCancellable) {
                runCatching { dequeue(promptId) }
                runCatching { interrupt(promptId) }
            }
            throw e
        } catch (e: Throwable) {
            socketJob?.cancel()
            throw e
        }
    }

    private suspend fun awaitOpen(events: Channel<SocketEvent>): Boolean {
        while (true) {
            when (events.receive()) {
                SocketEvent.Open -> return true
                is SocketEvent.Failed, is SocketEvent.Closed -> return false
                else -> Unit
            }
        }
    }

    /** Reads socket events until [promptId] ends (returns) or the socket breaks (returns; polling takes over). */
    private suspend fun followSocket(
        events: Channel<SocketEvent>,
        promptId: String,
        graph: JsonObject,
        outputs: MutableMap<String, List<ComfyFile>>,
        onEvent: (EngineEvent) -> Unit,
    ) {
        var started = false
        while (true) {
            when (val event = events.receiveCatching().getOrNull() ?: return) {
                is SocketEvent.Text -> when (val m = ComfyMessage.parse(event.text)) {
                    is ComfyMessage.ExecutionStart -> if (m.promptId == promptId) {
                        started = true
                        onEvent(EngineEvent.Status("Running on ${backend.label}"))
                    }
                    is ComfyMessage.Executing -> if (m.promptId == null || m.promptId == promptId) {
                        if (m.node == null) {
                            if (m.promptId == promptId) return
                        } else {
                            started = true
                            onEvent(EngineEvent.Status(nodeName(graph, m.node)))
                        }
                    }
                    is ComfyMessage.Progress -> if (m.promptId == promptId || (m.promptId == null && started)) onEvent(EngineEvent.Progress(m.value, m.max))
                    is ComfyMessage.Executed -> if (m.promptId == promptId) outputs[m.node] = m.files
                    is ComfyMessage.ExecutionCached -> if (m.promptId == promptId && m.nodes.isNotEmpty()) onEvent(EngineEvent.Status("Reusing ${m.nodes.size} cached steps"))
                    is ComfyMessage.ExecutionSuccess -> if (m.promptId == promptId) return
                    is ComfyMessage.ExecutionError -> if (m.promptId == promptId) throw ComfyException(m.message)
                    is ComfyMessage.ExecutionInterrupted -> if (m.promptId == promptId) throw ComfyException("Interrupted on ${backend.label}")
                    else -> Unit
                }
                is SocketEvent.Binary -> {
                    val frame = ComfyBinary.parse(event.bytes)
                    if (frame is ComfyBinary.PreviewImage && started && (frame.promptId == null || frame.promptId == promptId)) {
                        onEvent(EngineEvent.Preview(frame.mimeType, frame.bytes))
                    }
                }
                is SocketEvent.Closed, is SocketEvent.Failed -> return
                SocketEvent.Open -> Unit
            }
        }
    }

    private suspend fun pollHistory(promptId: String, onEvent: (EngineEvent) -> Unit): HistoryEntry {
        var said: String? = null
        while (true) {
            val entry = history(promptId)
            if (entry != null && (entry.completed || entry.status != null)) return entry
            val q = runCatching { queue() }.getOrNull()
            val status = when {
                q == null -> null
                promptId in q.running -> "Running on ${backend.label}"
                promptId in q.pending -> "Waiting in ${backend.label}'s queue (${q.pending.indexOf(promptId) + q.running.size} ahead)"
                else -> null
            }
            if (status != null && status != said) {
                said = status
                onEvent(EngineEvent.Status(status))
            }
            delay(pollIntervalMs)
        }
    }

    private fun nodeName(graph: JsonObject, node: String): String {
        val n = graph[node] as? JsonObject
        val title = ((n?.get("_meta") as? JsonObject)?.get("title") as? JsonPrimitive)?.content
        return title ?: (n?.get("class_type") as? JsonPrimitive)?.content ?: "Node $node"
    }

    companion object {
        const val TAG = "studio:comfyui"
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_OBJECT_INFO_BYTES = 64L * 1024 * 1024
        const val MAX_FILE_BYTES = 1024L * 1024 * 1024
        private val BASE = Regex("(?i)https?://[^\\s/?#]+[^\\s?#]*")
    }
}

/**
 * Runs saved workflows on one ComfyUI backend as a [StudioEngine]. A job's recipe names the
 * workflow ([Recipe.workflowId]) and carries its values ([Recipe.params], the seed in
 * [Recipe.seed]); input assets (asset values or [Recipe.inputs] by role) are uploaded first.
 */
class ComfyEngine(
    private val backend: ComfyBackend,
    private val client: ComfyClient,
    private val workflows: suspend (id: String) -> StudioWorkflow?,
    private val assets: AssetReader,
    private val random: Random = Random.Default,
) : StudioEngine {
    override val capabilities = EngineCapabilities(
        kind = EngineKinds.COMFYUI,
        backendId = backend.id,
        label = backend.label,
        outputs = AssetType.entries.toSet(),
        onDevice = false,
        previews = true,
    )

    /** Checks [workflow] against the server's nodes and models. */
    suspend fun validate(workflow: StudioWorkflow): Validation = WorkflowValidator.validate(workflow.graph, client.objectInfo())

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        val recipe = job.recipe
        val id = recipe.workflowId ?: throw StudioEngineException("This job names no workflow")
        val workflow = workflows(id) ?: throw StudioEngineException("The workflow is no longer saved")
        try {
            val values = valuesFor(workflow, recipe)
            val uploads = upload(workflow, recipe, values, onEvent)
            val applied = WorkflowApplier.apply(workflow, values, randomizeSeed = false, random = random, uploads = uploads)
            onEvent(EngineEvent.Status("Queued on ${backend.label}"))
            val run = client.execute(applied.graph, onEvent)
            val files = select(run, workflow.outputNodes)
            if (files.isEmpty()) throw StudioEngineException("${backend.label} finished but returned no files")
            return files.map { (node, file) ->
                val data = client.view(file)
                val (mime, type) = StudioCodec.typeOf(file.filename)
                val actual = if (node in run.animated && type == AssetType.IMAGE) AssetType.VIDEO else type
                EngineOutput(actual, mime, data, name = file.filename)
            }
        } catch (e: ComfyException) {
            throw StudioEngineException(e.message ?: "ComfyUI failed")
        } catch (e: WorkflowException) {
            throw StudioEngineException(e.message ?: "Invalid workflow values")
        }
    }

    private fun valuesFor(workflow: StudioWorkflow, recipe: Recipe): Map<String, ParamValue> {
        val values = recipe.params.filterKeys { workflow.param(it) != null }.toMutableMap()
        val seedParam = workflow.params.firstOrNull { it.type == ParamType.SEED }
        if (seedParam != null && recipe.seed != null && seedParam.name !in values) values[seedParam.name] = ParamValue.IntValue(recipe.seed)
        // Inputs by role ("image", "audio") fill media params the values leave open.
        for (p in workflow.params.filter { it.type == ParamType.IMAGE || it.type == ParamType.AUDIO }) {
            if (p.name in values) continue
            val role = p.role ?: p.name
            recipe.inputs.firstOrNull { it.role == p.name || it.role == role }?.let { values[p.name] = ParamValue.AssetValue(it) }
        }
        return values
    }

    private suspend fun upload(workflow: StudioWorkflow, recipe: Recipe, values: Map<String, ParamValue>, onEvent: (EngineEvent) -> Unit): Map<String, String> {
        val out = HashMap<String, String>()
        for ((name, value) in values) {
            val ref = (value as? ParamValue.AssetValue)?.ref ?: continue
            if (workflow.param(name)?.type !in setOf(ParamType.IMAGE, ParamType.AUDIO)) continue
            val asset = assets.read(ref.assetId) ?: throw StudioEngineException("The input asset is gone from the library")
            onEvent(EngineEvent.Status("Uploading ${asset.name}"))
            val file = client.upload(asset.name, asset.mimeType, asset.bytes)
            out[name] = ComfyRequests.imageInputValue(file)
        }
        return out
    }

    companion object {
        /**
         * The files a run produced, in output-node order: saved (`output`) files of the
         * workflow's output nodes; temp previews only when nothing was saved.
         */
        fun select(run: ComfyRun, outputNodes: List<String>): List<Pair<String, ComfyFile>> {
            val nodes = outputNodes.filter { it in run.outputs }.ifEmpty { run.outputs.keys.toList() }
            val all = nodes.flatMap { n -> run.outputs[n].orEmpty().map { n to it } }
            val saved = all.filter { it.second.type == "output" }
            return saved.ifEmpty { all }
        }
    }
}
