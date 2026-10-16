package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.shared.Cancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * The Studio queue: jobs wait per concurrency slot (every on-device engine shares one slot of
 * one, each remote backend has its own slot of one by default), run on a pluggable
 * [StudioEngine], and their outputs land in the project through an [OutputSink]. Jobs keep
 * running while the Studio screens are closed (the queue lives in the app scope).
 */

@Serializable
enum class JobState {
    QUEUED,
    RUNNING,
    DONE,
    FAILED,
    CANCELLED;

    val isFinished: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/** The latest preview frame of a running job (not persisted). */
class Preview(val mimeType: String, val bytes: ByteArray)

/**
 * One queued generation. [progress] is 0..1 ([step] of [steps] when the engine reports steps);
 * [outputs] are the ids of the assets it made; [preview] is the engine's latest preview frame.
 */
@Serializable
data class StudioJob(
    val id: String,
    val projectId: String,
    val recipe: Recipe,
    val label: String? = null,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val step: Int = 0,
    val steps: Int = 0,
    /** What the engine is doing now (`Loading model`, `KSampler`, …). */
    val status: String? = null,
    val outputs: List<String> = emptyList(),
    val error: String? = null,
    /** The asset this job was made from ("use as input", variations). */
    val parentAssetId: String? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    @Transient val preview: Preview? = null,
)

/**
 * What an engine can do. Jobs resolve to an engine by [kind] and [backendId] ([EngineRegistry]);
 * [slot] is the concurrency group.
 */
class EngineCapabilities(
    /** An [EngineKinds] value. */
    val kind: String,
    /** The remote backend's id; null for an on-device engine. */
    val backendId: String?,
    /** For "runs on <label>" (`This iPhone`, `Studio PC`). */
    val label: String,
    val outputs: Set<AssetType>,
    val onDevice: Boolean,
    /** Recipe params the engine understands (UIs show only these); empty = any. */
    val params: Set<String> = emptySet(),
    val maxBatch: Int = 1,
    val previews: Boolean = false,
) {
    val slot: String get() = if (onDevice) ON_DEVICE_SLOT else "backend:${backendId ?: kind}"

    companion object {
        /** The one slot every on-device engine shares (GPU/NPU memory). */
        const val ON_DEVICE_SLOT = "on-device"
    }
}

/** Something an engine reports while it runs. */
sealed interface EngineEvent {
    class Progress(val value: Int, val max: Int) : EngineEvent
    class Preview(val mimeType: String, val bytes: ByteArray) : EngineEvent
    class Status(val text: String) : EngineEvent
}

/** One file an engine made. */
class EngineOutput(
    val type: AssetType,
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    /** The backend's file name, when it has one. */
    val name: String? = null,
)

/** A failure an engine explains to the user. */
class StudioEngineException(message: String) : Exception(message)

/**
 * An executor for jobs: the platform's diffusion engines, the audio and speech engines, and
 * [dev.wckdboy.autobot.shared.studio.comfy.ComfyEngine]. [run] returns the outputs, throws
 * [StudioEngineException] to fail, and stops promptly when cancelled (remote engines interrupt
 * the backend).
 */
interface StudioEngine {
    val capabilities: EngineCapabilities

    suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput>
}

/** The engines available now; platforms register and unregister them as backends change. */
class EngineRegistry {
    private val engines = MutableStateFlow<List<StudioEngine>>(emptyList())

    val all: List<StudioEngine> get() = engines.value

    fun register(engine: StudioEngine) = engines.update { list ->
        list.filterNot { it.capabilities.kind == engine.capabilities.kind && it.capabilities.backendId == engine.capabilities.backendId } + engine
    }

    fun unregister(kind: String, backendId: String?) = engines.update { list ->
        list.filterNot { it.capabilities.kind == kind && it.capabilities.backendId == backendId }
    }

    /**
     * The engine for [recipe]: the exact kind and backend; with no backend named, an on-device
     * engine of the kind first, else any engine of the kind.
     */
    fun resolve(recipe: Recipe): StudioEngine? {
        val list = engines.value.filter { it.capabilities.kind == recipe.engine }
        return if (recipe.backendId != null) {
            list.firstOrNull { it.capabilities.backendId == recipe.backendId }
        } else {
            list.firstOrNull { it.capabilities.onDevice } ?: list.firstOrNull()
        }
    }
}

/** Saves one engine output (bytes encrypted by the platform) and returns its asset. */
fun interface OutputSink {
    suspend fun save(job: StudioJob, output: EngineOutput, index: Int): StudioAsset
}

/**
 * The usual [OutputSink]: [writeBytes] stores the bytes under the new asset id (the platform's
 * encrypted gallery store), then the asset record goes into [store] and the project is touched.
 */
class StoreOutputSink(
    private val store: StudioStore,
    private val writeBytes: suspend (assetId: String, output: EngineOutput) -> Unit,
    private val clock: () -> Long = ::currentTimeMillis,
    private val ids: () -> String = { StudioIds.new("a") },
) : OutputSink {
    override suspend fun save(job: StudioJob, output: EngineOutput, index: Int): StudioAsset {
        val asset = assetFor(job, output, ids(), clock())
        writeBytes(asset.id, output)
        store.putAsset(asset)
        store.projects().firstOrNull { it.id == job.projectId }?.let { p ->
            store.putProject(p.copy(updatedAt = asset.createdAt, coverAssetId = p.coverAssetId ?: asset.id.takeIf { asset.type == AssetType.IMAGE }))
        }
        return asset
    }

    companion object {
        /** The asset record for [output] of [job]. */
        fun assetFor(job: StudioJob, output: EngineOutput, id: String, now: Long) = StudioAsset(
            id = id,
            projectId = job.projectId,
            type = output.type,
            recipe = job.recipe,
            createdAt = now,
            mimeType = output.mimeType,
            sizeBytes = output.bytes.size.toLong(),
            durationMs = output.durationMs,
            width = output.width,
            height = output.height,
            parentId = job.parentAssetId ?: job.recipe.inputs.firstOrNull()?.assetId,
            label = job.label,
        )
    }
}

/** Keeps the queue across launches (platform: an encrypted table of [StudioCodec.encodeJob] rows). */
interface JobPersistence {
    suspend fun load(): List<StudioJob>
    suspend fun save(job: StudioJob)
    suspend fun remove(id: String)
}

/**
 * The job queue. [jobs] is the state the queue strip renders (oldest first). Jobs start in
 * submission order within each slot; a busy slot never holds up another slot. [slotLimits]
 * overrides the default of one job per slot (e.g. `backend:pc` → 2 for a two-GPU box).
 */
class JobQueue(
    private val scope: CoroutineScope,
    private val engines: EngineRegistry,
    private val sink: OutputSink,
    private val persistence: JobPersistence? = null,
    private val slotLimits: Map<String, Int> = emptyMap(),
    private val clock: () -> Long = ::currentTimeMillis,
    private val ids: () -> String = { StudioIds.new("j") },
) {
    private val _jobs = MutableStateFlow<List<StudioJob>>(emptyList())
    val jobs: StateFlow<List<StudioJob>> = _jobs.asStateFlow()

    private val active = MutableStateFlow<Map<String, Job>>(emptyMap())
    private val writes = Channel<suspend (JobPersistence) -> Unit>(Channel.UNLIMITED)

    init {
        if (persistence != null) scope.launch { for (write in writes) runCatching { write(persistence) } }
    }

    fun job(id: String): StudioJob? = _jobs.value.firstOrNull { it.id == id }

    /**
     * Restores persisted jobs: queued ones queue again, jobs that were running when the app
     * stopped are marked failed ("interrupted"; the user can retry).
     */
    suspend fun restore() {
        val saved = persistence?.load().orEmpty()
        val known = _jobs.value.mapTo(HashSet()) { it.id }
        val restored = saved.filter { it.id !in known }.map {
            if (it.state == JobState.RUNNING) it.copy(state = JobState.FAILED, error = INTERRUPTED, finishedAt = clock()).also(::persist) else it
        }
        _jobs.update { (restored + it).sortedBy { j -> j.createdAt } }
        pump()
    }

    fun submit(projectId: String, recipe: Recipe, label: String? = null, parentAssetId: String? = null): StudioJob {
        val job = StudioJob(ids(), projectId, recipe, label, parentAssetId = parentAssetId, createdAt = clock())
        _jobs.update { it + job }
        persist(job)
        pump()
        return job
    }

    /** Queues a planned batch or grid in order. */
    fun submitAll(projectId: String, planned: List<PlannedJob>, parentAssetId: String? = null): List<StudioJob> =
        planned.map { submit(projectId, it.recipe, it.label, parentAssetId) }

    /** Cancels a queued or running job; false when it already finished or is unknown. */
    fun cancel(id: String): Boolean {
        var wasQueued = false
        _jobs.update { list ->
            wasQueued = false
            list.map { if (it.id == id && it.state == JobState.QUEUED) it.copy(state = JobState.CANCELLED, finishedAt = clock()).also { wasQueued = true } else it }
        }
        if (wasQueued) {
            job(id)?.let(::persist)
            return true
        }
        val handle = active.value[id] ?: return false
        handle.cancel()
        return true
    }

    fun cancelAll() = _jobs.value.filterNot { it.state.isFinished }.forEach { cancel(it.id) }

    /** Queues a failed or cancelled job again (a new job with the same recipe). */
    fun retry(id: String): StudioJob? {
        val old = job(id)?.takeIf { it.state == JobState.FAILED || it.state == JobState.CANCELLED } ?: return null
        return submit(old.projectId, old.recipe, old.label, old.parentAssetId)
    }

    /** Drops finished jobs from the strip (their assets stay in the library). */
    fun clearFinished() {
        val done = _jobs.value.filter { it.state.isFinished }
        _jobs.update { list -> list.filterNot { it.state.isFinished } }
        done.forEach { j -> writes.trySend { it.remove(j.id) } }
    }

    /** Suspends until job [id] finished and returns it (null when unknown or cleared). */
    suspend fun await(id: String): StudioJob? {
        val list = _jobs.first { list -> list.firstOrNull { it.id == id }?.state?.isFinished != false }
        return list.firstOrNull { it.id == id }
    }

    private fun limit(slot: String) = (slotLimits[slot] ?: 1).coerceAtLeast(1)

    private fun pump() {
        var start = emptyList<Pair<StudioJob, StudioEngine>>()
        var failed = emptyList<StudioJob>()
        val now = clock()
        _jobs.update { list ->
            val busy = HashMap<String, Int>()
            list.filter { it.state == JobState.RUNNING }.forEach { j ->
                val slot = engines.resolve(j.recipe)?.capabilities?.slot ?: return@forEach
                busy[slot] = (busy[slot] ?: 0) + 1
            }
            val starting = ArrayList<Pair<StudioJob, StudioEngine>>()
            val failing = ArrayList<StudioJob>()
            val next = list.map { j ->
                if (j.state != JobState.QUEUED) return@map j
                val engine = engines.resolve(j.recipe)
                    ?: return@map j.copy(state = JobState.FAILED, error = noEngine(j.recipe), finishedAt = now).also { failing += it }
                val slot = engine.capabilities.slot
                if ((busy[slot] ?: 0) >= limit(slot)) return@map j
                busy[slot] = (busy[slot] ?: 0) + 1
                j.copy(state = JobState.RUNNING, startedAt = now, status = null).also { starting += it to engine }
            }
            start = starting
            failed = failing
            next
        }
        failed.forEach(::persist)
        start.forEach { (job, engine) -> launch(job, engine) }
    }

    private fun launch(job: StudioJob, engine: StudioEngine) {
        persist(job)
        val handle = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val outputs = engine.run(job) { event -> onEvent(job.id, event) }
                val current = job(job.id) ?: job
                val assets = outputs.mapIndexed { i, output -> sink.save(current, output, i) }
                finish(job.id) { it.copy(state = JobState.DONE, progress = 1f, outputs = assets.map(StudioAsset::id), preview = null, status = null) }
            } catch (e: CancellationException) {
                // Still active: the engine's own timeout (or a job it awaited) ended it, not a cancel.
                if (isActive) {
                    finish(job.id) { it.copy(state = JobState.FAILED, error = e.message ?: "The engine stopped", preview = null) }
                } else {
                    finish(job.id) { it.copy(state = JobState.CANCELLED, preview = null) }
                    throw e
                }
            } catch (e: Exception) {
                finish(job.id) { it.copy(state = JobState.FAILED, error = e.message ?: e::class.simpleName ?: "Failed", preview = null) }
            } finally {
                active.update { it - job.id }
                pump()
            }
        }
        active.update { it + (job.id to handle) }
        handle.start()
    }

    private fun onEvent(id: String, event: EngineEvent) = _jobs.update { list ->
        list.map { j ->
            if (j.id != id || j.state != JobState.RUNNING) {
                j
            } else {
                when (event) {
                    is EngineEvent.Progress -> j.copy(
                        step = event.value,
                        steps = event.max,
                        progress = if (event.max > 0) (event.value.toFloat() / event.max).coerceIn(0f, 1f) else j.progress,
                    )
                    is EngineEvent.Preview -> j.copy(preview = Preview(event.mimeType, event.bytes))
                    is EngineEvent.Status -> j.copy(status = event.text)
                }
            }
        }
    }

    private fun finish(id: String, change: (StudioJob) -> StudioJob) {
        val now = clock()
        _jobs.update { list -> list.map { if (it.id == id && !it.state.isFinished) change(it).copy(finishedAt = now) else it } }
        job(id)?.let(::persist)
    }

    private fun persist(job: StudioJob) {
        if (persistence != null) writes.trySend { it.save(job) }
    }

    private fun noEngine(recipe: Recipe): String = when {
        recipe.backendId != null -> "The backend ${recipe.backendId} isn't available for ${recipe.engine}"
        else -> "No ${recipe.engine} engine is available"
    }

    companion object {
        const val INTERRUPTED = "Interrupted: the app was closed while this was running"
    }
}

/** Callbacks for one [HostStudioEngine.run]: events, then outputs, then [onComplete] or [onFailure]. */
interface HostEngineSink {
    fun onProgress(value: Int, max: Int)
    fun onPreview(mimeType: String, bytes: ByteArray)
    fun onStatus(text: String)
    fun onOutput(output: EngineOutput)
    fun onComplete()
    fun onFailure(message: String)
}

/** The Swift-facing form of [StudioEngine] (callbacks instead of suspend); wrap with [HostEngineAdapter]. */
interface HostStudioEngine {
    val capabilities: EngineCapabilities

    /** Starts [jobJson] ([StudioCodec.encodeJob]); the handle cancels it. */
    fun run(jobJson: String, sink: HostEngineSink): Cancellable
}

/** A [StudioEngine] over a host engine. */
class HostEngineAdapter(private val host: HostStudioEngine) : StudioEngine {
    override val capabilities: EngineCapabilities get() = host.capabilities

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> = suspendCancellableCoroutine { cont ->
        val outputs = ArrayList<EngineOutput>()
        val call = host.run(
            StudioCodec.encodeJob(job),
            object : HostEngineSink {
                override fun onProgress(value: Int, max: Int) = onEvent(EngineEvent.Progress(value, max))
                override fun onPreview(mimeType: String, bytes: ByteArray) = onEvent(EngineEvent.Preview(mimeType, bytes))
                override fun onStatus(text: String) = onEvent(EngineEvent.Status(text))
                override fun onOutput(output: EngineOutput) {
                    outputs += output
                }

                override fun onComplete() {
                    if (cont.isActive) cont.resume(outputs.toList())
                }

                override fun onFailure(message: String) {
                    if (cont.isActive) cont.resumeWithException(StudioEngineException(message))
                }
            },
        )
        cont.invokeOnCancellation { call.cancel() }
    }
}
