package dev.wckdboy.autobot.feature.imagine.generate

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionEngineFactory
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.feature.imagine.mask.MaskRasterizer
import dev.wckdboy.autobot.feature.imagine.mask.MaskState
import dev.wckdboy.autobot.feature.imagine.mask.MaskStroke
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class RunningUi(
    val step: Int,
    val totalSteps: Int,
    val image: Int,
    val batch: Int,
    val preview: ImageBitmap?,
    val startedAt: Long,
    val byAgent: Boolean,
)

@Immutable
data class ResultUi(val galleryId: String, val bitmap: ImageBitmap, val seed: Long, val width: Int, val height: Int, val durationMs: Long)

enum class CatalogStatus { IDLE, LOADING, READY, ERROR }

@Immutable
data class ImagineUiState(
    val backends: List<DiffusionBackend> = emptyList(),
    val backend: DiffusionBackend? = null,
    val capabilities: EngineCapabilities? = null,
    val catalog: EngineCatalog = EngineCatalog(),
    val catalogStatus: CatalogStatus = CatalogStatus.IDLE,
    val catalogError: String? = null,
    val request: DiffusionRequest = DiffusionRequest(),
    val initImage: ImageBitmap? = null,
    val mask: MaskState = MaskState(),
    /** Show the init image (and mask) on the canvas instead of the latest result. */
    val preferInit: Boolean = false,
    val running: RunningUi? = null,
    val results: List<ResultUi> = emptyList(),
    val error: String? = null,
    val loaded: Boolean = false,
) {
    val tokenEstimate: Int get() = PromptSyntax.estimateTokens(request.prompt)
    val canGenerate: Boolean
        get() = backend != null && running == null && request.prompt.isNotBlank() &&
            (request.mode == DiffusionMode.TXT2IMG || initImage != null) &&
            (request.mode != DiffusionMode.INPAINT || !mask.isEmpty)
}

/**
 * Imagine screen state. The request is persisted (debounced) so the agent's `generate_image`
 * tool and the next session start from the same settings. The init image only lives in memory.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImagineViewModel @Inject constructor(
    application: Application,
    private val backendsRepo: DiffusionBackendRepository,
    private val engines: DiffusionEngineFactory,
    private val generator: ImageGenerator,
    private val gallery: GalleryRepository,
) : AndroidViewModel(application) {

    private data class Local(
        val selectedBackendId: String? = null,
        val request: DiffusionRequest? = null,
        val initImage: ImageBitmap? = null,
        val mask: MaskState = MaskState(),
        val preferInit: Boolean = false,
        val catalog: Map<String, EngineCatalog> = emptyMap(),
        val catalogStatus: CatalogStatus = CatalogStatus.IDLE,
        val catalogError: String? = null,
        val error: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var initSource: Bitmap? = null
    private var catalogJob: Job? = null

    private val running = generator.state.mapLatest { state ->
        (state as? GenerationState.Running)?.let { s ->
            RunningUi(
                step = s.step,
                totalSteps = s.totalSteps,
                image = s.image,
                batch = s.request.batchCount,
                preview = s.preview?.let { bytes -> withContext(Dispatchers.Default) { ImageCodec.decode(bytes, PREVIEW_SIDE)?.asImageBitmap() } },
                startedAt = s.startedAt,
                byAgent = s.source == GenerationSource.AGENT,
            )
        }
    }

    private val results = generator.lastResults.mapLatest { list ->
        list.mapNotNull { r ->
            val bytes = gallery.image(r.galleryId) ?: return@mapNotNull null
            val bitmap = withContext(Dispatchers.Default) { ImageCodec.decode(bytes, RESULT_SIDE)?.asImageBitmap() } ?: return@mapNotNull null
            ResultUi(r.galleryId, bitmap, r.seed, r.width, r.height, r.durationMs)
        }
    }

    val uiState: StateFlow<ImagineUiState> = combine(
        backendsRepo.backends,
        backendsRepo.defaultBackendId,
        local,
        combine(running, results, generator.lastError) { r, res, e -> Triple(r, res, e) },
        backendsRepo.lastRequest,
    ) { backends, defaultId, l, (run, res, genError), stored ->
        val backend = backends.firstOrNull { it.id == (l.selectedBackendId ?: defaultId) } ?: backends.firstOrNull()
        ImagineUiState(
            backends = backends,
            backend = backend,
            capabilities = backend?.let { engines.create(it).capabilities },
            catalog = backend?.let { l.catalog[it.id] } ?: EngineCatalog(),
            catalogStatus = l.catalogStatus,
            catalogError = l.catalogError,
            request = l.request ?: stored,
            initImage = l.initImage,
            mask = l.mask,
            preferInit = l.preferInit,
            running = run,
            results = res,
            error = l.error ?: genError,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImagineUiState())

    init {
        viewModelScope.launch {
            local.update { it.copy(request = backendsRepo.lastRequest.first()) }
            local.map { it.request }.distinctUntilChanged().drop(1).debounce(400).collect { r -> r?.let { backendsRepo.saveLastRequest(it) } }
        }
        viewModelScope.launch {
            combine(backendsRepo.backends, backendsRepo.defaultBackendId, local.map { it.selectedBackendId }) { b, d, s ->
                b.firstOrNull { it.id == (s ?: d) } ?: b.firstOrNull()
            }.distinctUntilChanged().collect { backend -> if (backend != null) refreshCatalog(backend) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Request edits

    private fun edit(transform: (DiffusionRequest) -> DiffusionRequest) {
        local.update { it.copy(request = transform(it.request ?: DiffusionRequest())) }
    }

    fun setMode(mode: DiffusionMode) {
        edit { it.copy(mode = mode) }
        local.update { it.copy(preferInit = mode != DiffusionMode.TXT2IMG) }
    }
    fun setPrompt(text: String) = edit { it.copy(prompt = text) }
    fun setNegative(text: String) = edit { it.copy(negativePrompt = text) }
    fun setSteps(steps: Int) = edit { it.copy(steps = steps) }
    fun setCfg(cfg: Float) = edit { it.copy(cfgScale = (cfg * 2).toInt() / 2f) }
    fun setSize(width: Int, height: Int) = edit {
        val multiple = uiState.value.capabilities?.sizeMultiple ?: 8
        it.copy(width = PromptSyntax.snap(width, multiple), height = PromptSyntax.snap(height, multiple))
    }
    fun setSeed(seed: Long) = edit { it.copy(seed = seed) }
    fun randomizeSeed() = edit { it.copy(seed = Random.nextLong(0, MAX_SEED)) }
    fun setRandomSeed() = edit { it.copy(seed = -1) }
    fun setSampler(sampler: String, scheduler: String?) = edit { it.copy(sampler = sampler, scheduler = scheduler) }
    fun setScheduler(scheduler: String?) = edit { it.copy(scheduler = scheduler) }
    fun setBatch(count: Int) = edit { it.copy(batchCount = count.coerceIn(1, MAX_BATCH)) }
    fun setDenoise(value: Float) = edit { it.copy(denoiseStrength = (value * 100).toInt() / 100f) }
    fun setMaskBlur(px: Int) = edit { it.copy(maskBlur = px) }
    fun setOnlyMasked(enabled: Boolean) = edit { it.copy(inpaintOnlyMasked = enabled) }
    fun setModel(model: String?) = edit { it.copy(model = model) }
    fun setClipSkip(skip: Int?) = edit { it.copy(clipSkip = skip) }

    fun addLora(name: String) = edit { r -> if (r.loras.any { it.name == name }) r else r.copy(loras = r.loras + LoraRef(name, 0.8f)) }
    fun setLoraWeight(name: String, weight: Float) = edit { r ->
        r.copy(loras = r.loras.map { if (it.name == name) it.copy(weight = (weight * 20).toInt() / 20f) else it })
    }
    fun removeLora(name: String) = edit { r -> r.copy(loras = r.loras.filterNot { it.name == name }) }

    // ---------------------------------------------------------------------------------------
    // Backend

    fun selectBackend(id: String) {
        local.update { it.copy(selectedBackendId = id) }
        viewModelScope.launch { backendsRepo.setDefault(id) }
    }

    fun refreshCatalog() {
        uiState.value.backend?.let { refreshCatalog(it) }
    }

    private fun refreshCatalog(backend: DiffusionBackend) {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            local.update { it.copy(catalogStatus = CatalogStatus.LOADING, catalogError = null) }
            try {
                val catalog = engines.create(backend).catalog()
                local.update { it.copy(catalog = it.catalog + (backend.id to catalog), catalogStatus = CatalogStatus.READY) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                local.update { it.copy(catalogStatus = CatalogStatus.ERROR, catalogError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Init image and mask

    fun setInitImage(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                ImageCodec.read(getApplication<Application>().contentResolver, uri)?.let { ImageCodec.decode(it, MAX_INIT_SIDE) }
            }
            if (bitmap == null) {
                local.update { it.copy(error = "Could not read that image") }
                return@launch
            }
            useInit(bitmap)
        }
    }

    /** Takes a gallery image as the init image (img2img), keeping its aspect ratio. */
    fun useAsInit(galleryId: String) {
        viewModelScope.launch {
            val bitmap = gallery.image(galleryId)?.let { withContext(Dispatchers.Default) { ImageCodec.decode(it, MAX_INIT_SIDE) } } ?: return@launch
            useInit(bitmap)
        }
    }

    private fun useInit(bitmap: Bitmap) {
        initSource = bitmap
        local.update { it.copy(initImage = bitmap.asImageBitmap(), mask = MaskState(), preferInit = true) }
        val longSide = maxOf(bitmap.width, bitmap.height).coerceAtMost(1024).coerceAtLeast(512)
        val scale = longSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        setSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        if (uiState.value.request.mode == DiffusionMode.TXT2IMG) setMode(DiffusionMode.IMG2IMG)
    }

    fun clearInitImage() {
        initSource = null
        local.update { it.copy(initImage = null, mask = MaskState()) }
        setMode(DiffusionMode.TXT2IMG)
    }

    /** Copies every parameter of a gallery item into the form. */
    fun reuseParams(galleryId: String) {
        viewModelScope.launch {
            val item = gallery.get(galleryId) ?: return@launch
            generator.decodeParams(item)?.let { params -> local.update { it.copy(request = params.copy(batchCount = 1)) } }
        }
    }

    fun addStroke(stroke: MaskStroke) = local.update { it.copy(mask = it.mask.add(stroke), preferInit = true) }
    fun undoStroke() = local.update { it.copy(mask = it.mask.undo()) }
    fun redoStroke() = local.update { it.copy(mask = it.mask.redoLast()) }
    fun clearMask() = local.update { it.copy(mask = it.mask.clear()) }
    fun invertMask() = local.update { it.copy(mask = it.mask.invert()) }

    // ---------------------------------------------------------------------------------------
    // Run

    fun generate() {
        val state = uiState.value
        val backend = state.backend ?: return
        if (!state.canGenerate) return
        val request = state.request.copy(prompt = state.request.prompt.trim())
        val encoding = state.capabilities?.maskEncoding
        viewModelScope.launch {
            val inputs = withContext(Dispatchers.Default) {
                val source = initSource
                if (request.mode == DiffusionMode.TXT2IMG || source == null || encoding == null) {
                    DiffusionInputs()
                } else {
                    val init = ImageCodec.png(ImageCodec.cover(source, request.width, request.height))
                    val mask = if (request.mode == DiffusionMode.INPAINT) {
                        ImageCodec.png(MaskRasterizer.render(state.mask, request.width, request.height, encoding))
                    } else {
                        null
                    }
                    DiffusionInputs(init, mask)
                }
            }
            local.update { it.copy(preferInit = false) }
            backendsRepo.saveLastRequest(request)
            generator.start(backend, request, inputs)
        }
    }

    fun cancel() = generator.cancel()

    fun dismissError() {
        local.update { it.copy(error = null) }
        generator.dismissError()
    }

    companion object {
        private const val PREVIEW_SIDE = 768
        private const val RESULT_SIDE = 1536
        private const val MAX_INIT_SIDE = 2048
        const val MAX_BATCH = 8
        const val MAX_SEED = 4_294_967_295L
    }
}
