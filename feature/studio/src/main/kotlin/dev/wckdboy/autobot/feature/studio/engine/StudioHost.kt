package dev.wckdboy.autobot.feature.studio.engine

import dev.wckdboy.autobot.core.data.StudioRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionEngineFactory
import dev.wckdboy.autobot.core.diffusion.Upscaler
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.OkHttpTransport
import dev.wckdboy.autobot.engine.audiogen.LocalAudioGen
import dev.wckdboy.autobot.engine.tts.LocalVoice
import dev.wckdboy.autobot.feature.studio.media.MediaInfo
import dev.wckdboy.autobot.shared.studio.AssetBytes
import dev.wckdboy.autobot.shared.studio.AssetReader
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.EngineOutput
import dev.wckdboy.autobot.shared.studio.EngineRegistry
import dev.wckdboy.autobot.shared.studio.JobQueue
import dev.wckdboy.autobot.shared.studio.OutputSink
import dev.wckdboy.autobot.shared.studio.ParamType
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Pipeline
import dev.wckdboy.autobot.shared.studio.PipelineException
import dev.wckdboy.autobot.shared.studio.Seeds
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import dev.wckdboy.autobot.shared.studio.WorkflowApplier
import dev.wckdboy.autobot.shared.studio.PipelineRunner
import dev.wckdboy.autobot.shared.studio.QueueStudioRunner
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.StoreOutputSink
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioCodec
import dev.wckdboy.autobot.shared.studio.StudioIds
import dev.wckdboy.autobot.shared.studio.StudioProject
import dev.wckdboy.autobot.shared.studio.comfy.ComfyBackend
import dev.wckdboy.autobot.shared.studio.comfy.ComfyClient
import dev.wckdboy.autobot.shared.studio.comfy.ComfyEngine
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A built-in pipeline run: steps finished so far, and its outcome. */
data class PipelineRun(val id: String, val name: String, val done: Int = 0, val finished: Boolean = false, val error: String? = null, val assets: List<String> = emptyList())

/** A ComfyUI backend as the rooms use it: its settings, its client and its engine. */
class ComfyHandle(val config: ComfyBackendConfig, val client: ComfyClient, val engine: ComfyEngine)

/**
 * The Studio's engine room, alive for the whole app (application scope), so jobs keep running when
 * the Studio is left: the [queue], the [engines] registered from the configured backends (this
 * phone's diffusion models, A1111 / Forge, Local SSE, ComfyUI) and the on-device upscaler, Stable
 * Audio and Kokoro, and the [store]. Started once at app launch ([start]); queued jobs come back
 * after the engines are registered (a job that was running when the app died comes back failed).
 */
@Singleton
class StudioHost @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    val store: StudioRepository,
    val settings: StudioSettings,
    private val diffusionBackends: DiffusionBackendRepository,
    private val diffusionFactory: DiffusionEngineFactory,
    upscaler: Upscaler,
    audio: LocalAudioGen,
    voice: LocalVoice,
    private val library: ModelLibrary,
    clients: HttpClientFactory,
) {
    val engines = EngineRegistry()
    private val transport = OkHttpTransport(clients)
    private val started = AtomicBoolean(false)
    private val synced = CompletableDeferred<Unit>()

    /** Reads an asset's decrypted bytes (engine inputs, workflow uploads). */
    val assets = AssetReader { id ->
        val asset = store.asset(id) ?: return@AssetReader null
        store.bytes(id)?.let { AssetBytes("${asset.id}.${StudioCodec.extension(asset.mimeType)}", asset.mimeType, it) }
    }

    private val sink = OutputSink { job, output, index ->
        // Remote engines often report no size or length: read it from the bytes.
        val fixed = if (output.width == 0 && output.durationMs == 0L) {
            val info = withContext(Dispatchers.Default) { MediaInfo.of(output.type, output.mimeType, output.bytes) }
            EngineOutput(output.type, output.mimeType, output.bytes, info.width, info.height, info.durationMs, output.name)
        } else {
            output
        }
        StoreOutputSink(store, writeBytes = { id, out -> writeAsset(id, out.type, out.mimeType, out.bytes) }).save(job, fixed, index)
    }

    val queue = JobQueue(scope, engines, sink, store)
    val pipelines = PipelineRunner(queue)

    private val _comfy = MutableStateFlow<Map<String, ComfyHandle>>(emptyMap())

    /** ComfyUI backends by id. */
    val comfy: StateFlow<Map<String, ComfyHandle>> = _comfy.asStateFlow()

    private val _diffusion = MutableStateFlow<List<Pair<DiffusionBackend, DiffusionStudioEngine>>>(emptyList())

    /** Image backends with their engines (the image room's backend list). */
    val diffusion: StateFlow<List<Pair<DiffusionBackend, DiffusionStudioEngine>>> = _diffusion.asStateFlow()

    /** The agent's `run_workflow` runner: saved workflows on the queue, outputs in the "Agent" project. */
    val agentRunner = QueueStudioRunner(store, queue, projectId = { QueueStudioRunner.agentProject(store) }, backendLabel = ::backendLabel)

    init {
        engines.register(UpscaleStudioEngine(upscaler, assets))
        engines.register(AudioStudioEngine(audio, library))
        engines.register(SpeechStudioEngine(voice, library))
    }

    /** Starts following the backends and restores the queue (once; main process only). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(diffusionBackends.backends, settings.comfyBackends) { d, c -> d to c }.collect { (d, c) ->
                runCatching { sync(d, c) }
                synced.complete(Unit)
            }
        }
        scope.launch {
            synced.await()
            runCatching { queue.restore() }
        }
    }

    private suspend fun sync(diffusion: List<DiffusionBackend>, comfyConfigs: List<ComfyBackendConfig>) {
        val previous = _diffusion.value
        val next = diffusion.map { b -> b to DiffusionStudioEngine(b, diffusionFactory.create(b), assets) }
        previous.filter { (b, _) -> next.none { it.first.id == b.id } }.forEach { (b, _) ->
            engines.unregister(EngineKinds.DIFFUSION, if (b.kind == BackendKind.ON_DEVICE) null else b.id)
        }
        next.forEach { engines.register(it.second) }
        _diffusion.value = next

        val handles = comfyConfigs.mapNotNull { c -> runCatching { handle(c) }.getOrNull() }.associateBy { it.config.id }
        _comfy.value.keys.filter { it !in handles }.forEach { engines.unregister(EngineKinds.COMFYUI, it) }
        handles.values.forEach { engines.register(it.engine) }
        _comfy.value = handles
    }

    /** A client for [config] (also for testing a backend before it is saved). */
    suspend fun handle(config: ComfyBackendConfig): ComfyHandle {
        val backend = ComfyBackend(config.id, config.name, config.baseUrl, config.routing, settings.authHeader(config))
        val client = ComfyClient(backend, transport, transport, transport)
        val engine = ComfyEngine(backend, client, workflows = { id -> store.workflows().firstOrNull { it.id == id } }, assets = assets)
        return ComfyHandle(config, client, engine)
    }

    /** Models, LoRAs, samplers, VAEs and upscalers of an image backend (through the network policy). */
    suspend fun diffusionCatalog(backend: DiffusionBackend) = diffusionFactory.create(backend).catalog()

    /** "runs on <label>" for a backend id (null = this phone). */
    fun backendLabel(id: String?): String? = when (id) {
        null -> null
        else -> _comfy.value[id]?.config?.name ?: _diffusion.value.firstOrNull { it.first.id == id }?.first?.name ?: id
    }

    /** Stores bytes and their encrypted preview under [id]. */
    suspend fun writeAsset(id: String, type: AssetType, mimeType: String, bytes: ByteArray) {
        store.writeBytes(id, bytes)
        withContext(Dispatchers.Default) { MediaInfo.preview(type, mimeType, bytes) }?.let { store.writePreview(id, it) }
    }

    /**
     * Adds bytes made outside the queue (an image from the phone, a painted mask, a trimmed clip,
     * a video frame) to [projectId] as an asset with [recipe].
     */
    suspend fun addAsset(projectId: String, type: AssetType, mimeType: String, bytes: ByteArray, recipe: Recipe, parentId: String? = null, label: String? = null): StudioAsset {
        val info = withContext(Dispatchers.Default) { MediaInfo.of(type, mimeType, bytes) }
        val asset = StudioAsset(
            id = StudioIds.new("a"),
            projectId = projectId,
            type = type,
            recipe = recipe,
            createdAt = System.currentTimeMillis(),
            mimeType = mimeType,
            sizeBytes = bytes.size.toLong(),
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            parentId = parentId,
            label = label,
        )
        writeAsset(asset.id, type, mimeType, bytes)
        store.putAsset(asset)
        return asset
    }

    /** The project new work goes to: the remembered one, else the newest, else a new "Studio" project. */
    suspend fun currentProject(): String {
        val projects = store.projects()
        settings.currentProjectId.first()?.let { id -> if (projects.any { it.id == id }) return id }
        val id = projects.firstOrNull()?.id ?: createProject("Studio").id
        settings.setCurrentProject(id)
        return id
    }

    suspend fun createProject(name: String): StudioProject {
        val now = System.currentTimeMillis()
        val project = StudioProject(StudioIds.new("p"), name.trim().ifEmpty { "Untitled" }, null, now, now)
        store.putProject(project)
        settings.setCurrentProject(project.id)
        return project
    }

    /**
     * Queues [workflow] with [values] on ComfyUI backend [backendId] (any backend can run a
     * saved workflow). Bad values fail here, before anything is queued ([WorkflowException]).
     */
    suspend fun submitWorkflow(workflow: StudioWorkflow, values: Map<String, ParamValue>, backendId: String, randomizeSeed: Boolean): StudioJob {
        val seedParam = workflow.params.firstOrNull { it.type == ParamType.SEED }
        val seed = when {
            seedParam == null -> null
            randomizeSeed -> Seeds.random()
            else -> values[seedParam.name]?.asLong() ?: seedParam.default?.asLong()
        }
        val params = if (seedParam != null && seed != null) values + (seedParam.name to ParamValue.IntValue(seed)) else values
        WorkflowApplier.apply(workflow, params, uploads = params.filterValues { it is ParamValue.AssetValue }.mapValues { "pending" })
        val recipe = Recipe(EngineKinds.COMFYUI, backendId, null, params, emptyList(), workflow.id, workflow.version, seed, workflow.output)
        val parent = params.values.filterIsInstance<ParamValue.AssetValue>().firstOrNull()?.ref?.assetId
        return queue.submit(currentProject(), recipe, workflow.name, parent)
    }

    private val _pipelineRuns = MutableStateFlow<List<PipelineRun>>(emptyList())

    /** Built-in pipelines started from the Workflows room (they run on in the app scope). */
    val pipelineRuns: StateFlow<List<PipelineRun>> = _pipelineRuns.asStateFlow()

    fun runPipeline(pipeline: Pipeline, vars: Map<String, String>, input: List<String>) {
        val id = StudioIds.new("r")
        _pipelineRuns.update { (it + PipelineRun(id, pipeline.name)).takeLast(8) }
        scope.launch {
            val result = try {
                pipelines.run(pipeline, currentProject(), vars, input) { step ->
                    _pipelineRuns.update { list -> list.map { r -> if (r.id == id) r.copy(done = r.done + 1) else r } }
                }
            } catch (e: PipelineException) {
                _pipelineRuns.update { list -> list.map { r -> if (r.id == id) r.copy(finished = true, error = e.message) else r } }
                return@launch
            }
            _pipelineRuns.update { list -> list.map { r -> if (r.id == id) r.copy(finished = true, error = result.error.takeUnless { result.ok }, assets = result.assetIds) else r } }
        }
    }

    /** Whether an on-device model for [engine] (`audio` / `speech`) is installed. */
    suspend fun hasModel(engine: String): Boolean = when (engine) {
        EngineKinds.AUDIO -> AudioStudioEngine.folder(library) != null
        EngineKinds.SPEECH -> SpeechStudioEngine.folder(library) != null
        else -> true
    }
}
