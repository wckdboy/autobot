package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.UnsavedImages
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionEngineFactory
import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.shared.studio.PromptRuns
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Who asked for an image (shown in the progress UI). */
enum class GenerationSource { USER, AGENT }

/** Live state of the single generation slot. */
sealed interface GenerationState {
    data object Idle : GenerationState

    /**
     * [image] counts across the whole job (a wildcard prompt is one engine call per expanded
     * prompt, [totalImages] in all; [imageOffset] is where the current call starts). [phase] is
     * what the engine does before sampling (loading the model), cleared by the first step.
     */
    data class Running(
        val source: GenerationSource,
        val backend: String,
        val request: DiffusionRequest,
        val image: Int = 0,
        val step: Int = 0,
        val totalSteps: Int = 0,
        val preview: ByteArray? = null,
        val startedAt: Long,
        val done: List<String> = emptyList(),
        val totalImages: Int = request.batchCount.coerceAtLeast(1),
        val imageOffset: Int = 0,
        val phase: String? = null,
    ) : GenerationState {
        /** A step (or a preview, which carries no step: the step and bar stay where they are). */
        fun on(event: DiffusionEvent.Progress): Running =
            if (event.step < 0 || event.totalSteps <= 0) {
                copy(preview = event.preview ?: preview)
            } else {
                copy(image = imageOffset + event.image, step = event.step, totalSteps = event.totalSteps, preview = event.preview ?: preview, phase = null)
            }

        fun on(event: DiffusionEvent.Phase): Running = copy(phase = event.text)

        /** The next engine call of the job starts after the images already made. */
        fun next(call: DiffusionRequest): Running =
            copy(request = call, image = done.size, imageOffset = done.size, step = 0, totalSteps = call.steps, phase = null)
    }
}

/**
 * The engine calls of one user job: the request as is, or one per wildcard-expanded prompt
 * (each with the batch and the seed; see [PromptRuns]).
 */
fun DiffusionRequest.calls(): List<DiffusionRequest> =
    PromptRuns.plan(prompt, batchCount, seed).map { copy(prompt = it.prompt, batchCount = it.count, seed = it.seed) }

/** One stored result. */
data class GeneratedImage(val galleryId: String, val seed: Long, val width: Int, val height: Int, val durationMs: Long)

/**
 * The app's single image-generation slot. Jobs are serialized (one GPU/NPU, one backend at a
 * time), progress is published in [state], and every result is stored in the encrypted gallery
 * as it arrives — the gallery *is* the history. [generate] runs in the caller's coroutine (the
 * agent tool); [start] runs user jobs on the application scope.
 */
@Singleton
class ImageGenerator @Inject constructor(
    private val engines: DiffusionEngineFactory,
    private val gallery: GalleryRepository,
    private val unsaved: UnsavedImages,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var userJob: Job? = null

    private val _lastResults = MutableStateFlow<List<GeneratedImage>>(emptyList())

    /** Results of the latest user-started job (kept so the screen can be left and reopened). */
    val lastResults: StateFlow<List<GeneratedImage>> = _lastResults.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Starts a user job on the application scope so it keeps running while the user switches
     * tabs. Results stream into [lastResults]; failures land in [lastError]. An [incognito] job
     * keeps its results in memory only ([UnsavedImages]); the screen's earlier unsaved results
     * are dropped when a new job starts.
     */
    fun start(backend: DiffusionBackend, request: DiffusionRequest, inputs: DiffusionInputs, incognito: Boolean = false) {
        if (userJob?.isActive == true) return
        _lastError.value = null
        _lastResults.value = emptyList()
        unsaved.drop(UnsavedImages.IMAGE_SCREEN)
        val owner = UnsavedImages.IMAGE_SCREEN.takeIf { incognito }
        userJob = scope.launch {
            try {
                generate(backend, request.calls(), inputs, GenerationSource.USER, owner) { image -> _lastResults.update { it + image } }
            } catch (e: TimeoutCancellationException) {
                // A timeout inside the engine is a failure to show, not the user's cancel.
                _lastError.value = e.message ?: "The image engine did not answer in time"
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiffusionException) {
                _lastError.value = e.message
            } catch (e: Exception) {
                _lastError.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Cancels the user job (the backend is interrupted / the stream dropped). */
    fun cancel() {
        userJob?.cancel()
    }

    fun dismissError() {
        _lastError.value = null
    }
    private val mutex = Mutex()
    private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val state: StateFlow<GenerationState> = _state.asStateFlow()

    private val json = Json { encodeDefaults = true }

    val isBusy: Boolean get() = mutex.isLocked

    suspend fun generate(
        backend: DiffusionBackend,
        request: DiffusionRequest,
        inputs: DiffusionInputs = DiffusionInputs(),
        source: GenerationSource = GenerationSource.USER,
        incognitoOwner: String? = null,
        onResult: (GeneratedImage) -> Unit = {},
    ): List<GeneratedImage> = generate(backend, listOf(request), inputs, source, incognitoOwner, onResult)

    /**
     * Runs [calls] one after another as one job (one slot, one progress state). With an
     * [incognitoOwner] (an incognito chat's session id, or the Image screen) results stay in
     * memory under unsaved ids and never reach the gallery.
     */
    suspend fun generate(
        backend: DiffusionBackend,
        calls: List<DiffusionRequest>,
        inputs: DiffusionInputs = DiffusionInputs(),
        source: GenerationSource = GenerationSource.USER,
        incognitoOwner: String? = null,
        onResult: (GeneratedImage) -> Unit = {},
    ): List<GeneratedImage> = mutex.withLock {
        val engine = engines.create(backend)
        val started = System.currentTimeMillis()
        val saved = mutableListOf<GeneratedImage>()
        val first = calls.first()
        _state.value = GenerationState.Running(
            source, backend.name, first, totalSteps = first.steps, startedAt = started, totalImages = calls.sumOf { it.batchCount.coerceAtLeast(1) },
        )
        try {
            for ((i, request) in calls.withIndex()) {
                if (i > 0) _state.update { s -> (s as? GenerationState.Running)?.next(request) ?: s }
                engine.generate(request, inputs).collect { event ->
                    when (event) {
                        is DiffusionEvent.Phase -> _state.update { s -> (s as? GenerationState.Running)?.on(event) ?: s }
                        is DiffusionEvent.Progress -> _state.update { s -> (s as? GenerationState.Running)?.on(event) ?: s }
                        is DiffusionEvent.Result -> {
                            val stored = store(backend, request, event, incognitoOwner)
                            saved += stored
                            onResult(stored)
                            _state.update { s -> (s as? GenerationState.Running)?.copy(done = s.done + stored.galleryId, preview = event.image, phase = null) ?: s }
                        }
                    }
                }
            }
            if (saved.isEmpty()) throw DiffusionException("The backend finished without returning an image", DiffusionException.Code.PROTOCOL)
            saved
        } finally {
            _state.value = GenerationState.Idle
        }
    }

    private suspend fun store(backend: DiffusionBackend, request: DiffusionRequest, result: DiffusionEvent.Result, incognitoOwner: String?): GeneratedImage {
        val id = if (incognitoOwner != null) unsaved.newId() else gallery.newId()
        val thumb = withContext(Dispatchers.Default) { ImageCodec.thumbnail(result.image) } ?: result.image
        val effective = request.copy(seed = result.seed, width = result.width, height = result.height, batchCount = 1)
        val item = GalleryItem(
            id = id,
            createdAt = System.currentTimeMillis(),
            mode = request.mode.name,
            prompt = request.prompt,
            negativePrompt = request.negativePrompt,
            width = result.width,
            height = result.height,
            seed = result.seed,
            steps = request.steps,
            cfgScale = request.cfgScale,
            sampler = listOfNotNull(request.sampler, request.scheduler).joinToString(" · "),
            engine = backend.name,
            model = request.model,
            durationMs = result.durationMs,
            paramsJson = json.encodeToString(DiffusionRequest.serializer(), effective),
        )
        if (incognitoOwner != null) unsaved.put(item, result.image, thumb, incognitoOwner) else gallery.save(item, image = result.image, thumbnail = thumb)
        return GeneratedImage(id, result.seed, result.width, result.height, result.durationMs)
    }

    fun decodeParams(item: GalleryItem): DiffusionRequest? =
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(DiffusionRequest.serializer(), item.paramsJson) }.getOrNull()
}
