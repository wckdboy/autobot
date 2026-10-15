package dev.wckdboy.autobot.feature.studio.ui.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.HiresFix
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.imagine.mask.MaskRasterizer
import dev.wckdboy.autobot.feature.imagine.mask.MaskState
import dev.wckdboy.autobot.feature.imagine.mask.MaskStroke
import dev.wckdboy.autobot.feature.studio.engine.DiffusionStudioEngine
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.ImageSniff
import dev.wckdboy.autobot.feature.studio.media.Outpaint
import dev.wckdboy.autobot.feature.studio.ui.GridParam
import dev.wckdboy.autobot.shared.studio.AssetRef
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.BatchPlanner
import dev.wckdboy.autobot.shared.studio.GridAxis
import dev.wckdboy.autobot.shared.studio.GridPlanner
import dev.wckdboy.autobot.shared.studio.PromptLibrary
import dev.wckdboy.autobot.shared.studio.PromptSnippet
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.Seeds
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioIds
import dev.wckdboy.autobot.shared.studio.StudioJob
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An image backend as the room lists it. [id] is the diffusion backend's id. */
@Immutable
data class BackendChoice(val id: String, val name: String, val onDevice: Boolean, val capabilities: EngineCapabilities, val kind: BackendKind = BackendKind.SD_API)

/** A model to pick: an installed on-device model, or a checkpoint of the selected PC backend. */
@Immutable
data class ModelOption(val id: String?, val label: String)

/** The last grid queued from the room: its axes and the job of each cell. */
@Immutable
data class GridRun(val xLabel: String, val yLabel: String?, val columns: List<String>, val rows: List<String>, val cells: Map<Pair<Int, Int>, String>)

/** Extend-canvas margins (px) for outpainting. */
@Immutable
data class Extend(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    val any: Boolean get() = left + top + right + bottom > 0
}

@Immutable
data class ImageRoomState(
    val loaded: Boolean = false,
    val backends: List<BackendChoice> = emptyList(),
    val backendId: String? = null,
    val catalog: EngineCatalog = EngineCatalog(),
    val catalogError: String? = null,
    val models: List<ModelOption> = emptyList(),
    val request: DiffusionRequest = DiffusionRequest(),
    val seedMode: SeedMode = SeedMode.RANDOM,
    val seed: Long? = null,
    val batch: Int = 1,
    val hires: Boolean = false,
    /** The input image ("use as input"); its mode is img2img, or inpaint with a mask / extended canvas. */
    val input: StudioAsset? = null,
    val inputImage: ImageBitmap? = null,
    val inpaint: Boolean = false,
    val mask: MaskState = MaskState(),
    val extend: Extend = Extend(),
    /** Jobs started from this room, newest last (the canvas shows their outputs). */
    val jobIds: List<String> = emptyList(),
    val grid: GridRun? = null,
    val snippets: List<PromptSnippet> = emptyList(),
    val error: String? = null,
) {
    val backend: BackendChoice? get() = backends.firstOrNull { it.id == backendId } ?: backends.firstOrNull()
    val capabilities: EngineCapabilities? get() = backend?.capabilities
    val mode: DiffusionMode
        get() = when {
            input == null -> DiffusionMode.TXT2IMG
            inpaint || extend.any -> DiffusionMode.INPAINT
            else -> DiffusionMode.IMG2IMG
        }
    val canRun: Boolean
        get() = backend != null && request.prompt.isNotBlank() && (mode != DiffusionMode.INPAINT || extend.any || !mask.isEmpty) &&
            (capabilities?.modes?.contains(mode) != false)

    /** The parameters an X/Y grid can vary here. */
    val gridParams: List<GridParam>
        get() = buildList {
            add(GridParam(StudioRecipes.STEPS, "Steps", true, "10, 20, 30"))
            add(GridParam(StudioRecipes.CFG, "CFG", false, "3-9 (+1.5)"))
            add(GridParam(Recipe.SEED, "Seed", true, "1, 2, 3, 4"))
            add(GridParam(StudioRecipes.SAMPLER, "Sampler", false, "Euler a, DPM++ 2M"))
            if (mode != DiffusionMode.TXT2IMG) add(GridParam(StudioRecipes.STRENGTH, "Denoise", false, "0.3-0.8 (+0.1)"))
            if (capabilities?.modelSwitching == true) add(GridParam(Recipe.MODEL, "Model", false, "model-a, model-b"))
            request.loras.forEach { add(GridParam(StudioRecipes.LORA_PREFIX + it.name, "LoRA ${it.name}", false, "0-1 (+0.25)")) }
        }
}

/** What the image room's controls do. */
interface ImageActions {
    fun selectBackend(id: String)
    fun selectModel(id: String?)
    fun refreshCatalog()
    fun setPrompt(text: String)
    fun setNegative(text: String)
    fun setSteps(steps: Int)
    fun setCfg(cfg: Float)
    fun setSize(width: Int, height: Int)
    fun setSampler(sampler: String)
    fun setScheduler(scheduler: String?)
    fun setVae(vae: String?)
    fun setClipSkip(skip: Int?)
    fun setSeedMode(mode: SeedMode)
    fun setSeed(seed: Long?)
    fun setBatch(count: Int)
    fun setDenoise(value: Float)
    fun setHires(enabled: Boolean)
    fun setHiresScale(scale: Float)
    fun setHiresUpscaler(name: String)
    fun setHiresDenoise(value: Float)
    fun addLora(name: String)
    fun setLoraWeight(name: String, weight: Float)
    fun removeLora(name: String)
    fun useAsInput(assetId: String)
    fun pickInput(uri: Uri)
    fun clearInput()
    fun setInpaint(enabled: Boolean)
    fun addStroke(stroke: MaskStroke)
    fun undoStroke()
    fun redoStroke()
    fun clearMask()
    fun invertMask()
    fun setExtend(extend: Extend)
    fun applyStyle(style: PromptSnippet)
    fun insertSnippet(snippet: PromptSnippet, cursor: Int)
    fun saveSnippet(name: String, kind: String)
    fun deleteSnippet(snippet: PromptSnippet)
    fun generate()
    fun queueGrid(x: GridAxis, y: GridAxis?)
    fun upscale(assetId: String)
    fun dismissError()
}

/**
 * The image room: every control the selected backend supports, run through the Studio queue
 * (batches, wildcards and grids become one job per image, each a plate in the strip). Inputs are
 * assets: an image from the library or the phone, a painted mask, an extended canvas.
 */
@HiltViewModel
class ImageRoomViewModel @Inject constructor(
    application: Application,
    private val host: StudioHost,
    private val backendsRepo: DiffusionBackendRepository,
    private val library: ModelLibrary,
) : AndroidViewModel(application), ImageActions {

    private val local = MutableStateFlow(ImageRoomState())
    private val catalogs = MutableStateFlow<Map<String, EngineCatalog>>(emptyMap())
    private var catalogJob: Job? = null

    val state: StateFlow<ImageRoomState> = combine(local, host.diffusion, catalogs, library.models, host.store.observeSnippets()) { l, engines, cats, models, snippets ->
        val backends = engines.map { (b, e) -> BackendChoice(b.id, if (b.kind == BackendKind.ON_DEVICE) DiffusionStudioEngine.ON_DEVICE_LABEL else b.name, b.kind == BackendKind.ON_DEVICE, e.diffusion, b.kind) }
        val backend = backends.firstOrNull { it.id == l.backendId } ?: backends.firstOrNull()
        val catalog = backend?.let { cats[it.id] } ?: EngineCatalog()
        val options = when {
            backend == null -> emptyList()
            backend.onDevice -> models.filter { it.isReady && it.kind == ModelKind.IMAGE && (it.engine == EngineKind.DIFFUSION || it.engine == EngineKind.NPU) }
                .map { ModelOption(it.id, it.title) }
            else -> catalog.models.map { ModelOption(it, it.substringBefore(" [").substringAfterLast('/').substringBeforeLast('.')) }
        }
        l.copy(loaded = true, backends = backends, backendId = backend?.id, catalog = catalog, models = options, snippets = snippets)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageRoomState())

    init {
        viewModelScope.launch {
            val stored = backendsRepo.lastRequest.first()
            val default = backendsRepo.defaultBackendId.first()
            local.update { it.copy(request = stored.copy(batchCount = 1), backendId = it.backendId ?: default) }
            host.diffusion.first { it.isNotEmpty() }
            refreshCatalog()
        }
    }

    private fun edit(transform: (DiffusionRequest) -> DiffusionRequest) = local.update { it.copy(request = transform(it.request)) }

    override fun selectBackend(id: String) {
        local.update { it.copy(backendId = id, catalogError = null) }
        refreshCatalog()
    }

    override fun selectModel(id: String?) {
        edit { it.copy(model = id) }
        val s = state.value
        if (s.backend?.onDevice == true && id != null) {
            viewModelScope.launch {
                val rec = library.get(id)?.manifest?.recommended ?: return@launch
                edit { r ->
                    r.copy(
                        steps = rec.steps ?: r.steps,
                        cfgScale = rec.cfg ?: r.cfgScale,
                        sampler = rec.sampler ?: r.sampler,
                        scheduler = rec.scheduler ?: if (rec.sampler != null) null else r.scheduler,
                        width = rec.width ?: r.width,
                        height = rec.height ?: r.height,
                        negativePrompt = r.negativePrompt.ifBlank { rec.negativePrompt.orEmpty() },
                    )
                }
            }
        }
    }

    override fun refreshCatalog() {
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            val engines = host.diffusion.value
            val id = local.value.backendId ?: engines.firstOrNull()?.first?.id ?: return@launch
            val (backend, _) = engines.firstOrNull { it.first.id == id } ?: engines.firstOrNull() ?: return@launch
            if (backend.kind == BackendKind.ON_DEVICE) return@launch
            try {
                val catalog = host.diffusionCatalog(backend)
                catalogs.update { it + (backend.id to catalog) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                local.update { it.copy(catalogError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    override fun setPrompt(text: String) = edit { it.copy(prompt = text) }
    override fun setNegative(text: String) = edit { it.copy(negativePrompt = text) }
    override fun setSteps(steps: Int) = edit { it.copy(steps = steps.coerceIn(1, 150)) }
    override fun setCfg(cfg: Float) = edit { it.copy(cfgScale = (cfg * 2).toInt() / 2f) }
    override fun setSize(width: Int, height: Int) {
        val multiple = state.value.capabilities?.sizeMultiple ?: 8
        edit { it.copy(width = PromptSyntax.snap(width, multiple), height = PromptSyntax.snap(height, multiple)) }
    }
    override fun setSampler(sampler: String) = edit { it.copy(sampler = sampler) }
    override fun setScheduler(scheduler: String?) = edit { it.copy(scheduler = scheduler) }
    override fun setVae(vae: String?) = edit { it.copy(vae = vae) }
    override fun setClipSkip(skip: Int?) = edit { it.copy(clipSkip = skip) }
    override fun setSeedMode(mode: SeedMode) = local.update { it.copy(seedMode = mode, seed = it.seed ?: if (mode == SeedMode.RANDOM) null else Seeds.random()) }
    override fun setSeed(seed: Long?) = local.update { it.copy(seed = seed) }
    override fun setBatch(count: Int) = local.update { it.copy(batch = count.coerceIn(1, MAX_BATCH)) }
    override fun setDenoise(value: Float) = edit { it.copy(denoiseStrength = (value * 100).toInt() / 100f) }
    override fun setHires(enabled: Boolean) {
        local.update { it.copy(hires = enabled) }
        edit { it.copy(hires = if (enabled) it.hires ?: HiresFix() else null) }
    }
    override fun setHiresScale(scale: Float) = edit { it.copy(hires = (it.hires ?: HiresFix()).copy(scale = (scale * 4).toInt() / 4f)) }
    override fun setHiresUpscaler(name: String) = edit { it.copy(hires = (it.hires ?: HiresFix()).copy(upscaler = name)) }
    override fun setHiresDenoise(value: Float) = edit { it.copy(hires = (it.hires ?: HiresFix()).copy(denoise = (value * 100).toInt() / 100f)) }
    override fun addLora(name: String) = edit { r -> if (r.loras.any { it.name == name }) r else r.copy(loras = r.loras + LoraRef(name, 0.8f)) }
    override fun setLoraWeight(name: String, weight: Float) = edit { r -> r.copy(loras = r.loras.map { if (it.name == name) it.copy(weight = (weight * 20).toInt() / 20f) else it }) }
    override fun removeLora(name: String) = edit { r -> r.copy(loras = r.loras.filterNot { it.name == name }) }

    // ---------------------------------------------------------------------------------------
    // Inputs

    override fun useAsInput(assetId: String) {
        viewModelScope.launch {
            val asset = host.store.asset(assetId)?.takeIf { it.type == AssetType.IMAGE } ?: return@launch
            setInput(asset)
        }
    }

    override fun pickInput(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { ImageCodec.read(getApplication<Application>().contentResolver, uri) }
            if (bytes == null || ImageCodec.decode(bytes, 64) == null) {
                local.update { it.copy(error = "Could not read that image") }
                return@launch
            }
            val asset = host.addAsset(
                host.currentProject(), AssetType.IMAGE, ImageSniff.mimeType(bytes), bytes,
                Recipe(StudioRecipes.IMPORT_ENGINE, output = AssetType.IMAGE), label = "imported",
            )
            setInput(asset)
        }
    }

    private suspend fun setInput(asset: StudioAsset) {
        val bytes = host.store.bytes(asset.id) ?: return
        val bitmap = withContext(Dispatchers.Default) { ImageCodec.decode(bytes, MAX_INPUT_SIDE) } ?: return
        inputBitmap = bitmap
        local.update { it.copy(input = asset, inputImage = bitmap.asImageBitmap(), mask = MaskState(), extend = Extend()) }
        val longSide = maxOf(bitmap.width, bitmap.height).coerceIn(512, 1024)
        val scale = longSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        setSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    private var inputBitmap: Bitmap? = null

    override fun clearInput() {
        inputBitmap = null
        local.update { it.copy(input = null, inputImage = null, inpaint = false, mask = MaskState(), extend = Extend()) }
    }

    override fun setInpaint(enabled: Boolean) = local.update { it.copy(inpaint = enabled) }
    override fun addStroke(stroke: MaskStroke) = local.update { it.copy(mask = it.mask.add(stroke), inpaint = true) }
    override fun undoStroke() = local.update { it.copy(mask = it.mask.undo()) }
    override fun redoStroke() = local.update { it.copy(mask = it.mask.redoLast()) }
    override fun clearMask() = local.update { it.copy(mask = it.mask.clear()) }
    override fun invertMask() = local.update { it.copy(mask = it.mask.invert()) }
    override fun setExtend(extend: Extend) = local.update { it.copy(extend = extend) }

    // ---------------------------------------------------------------------------------------
    // Prompt library

    override fun applyStyle(style: PromptSnippet) {
        val r = local.value.request
        val (p, n) = PromptLibrary.applyStyle(r.prompt, r.negativePrompt, style)
        edit { it.copy(prompt = p, negativePrompt = n) }
    }

    override fun insertSnippet(snippet: PromptSnippet, cursor: Int) {
        val (p, _) = PromptLibrary.insert(local.value.request.prompt, cursor, snippet.text)
        edit { it.copy(prompt = p) }
    }

    override fun saveSnippet(name: String, kind: String) {
        val r = local.value.request
        val text = if (kind == PromptSnippet.STYLE && "{prompt}" !in r.prompt) "{prompt}, ${r.prompt}" else r.prompt
        viewModelScope.launch {
            host.store.putSnippet(PromptSnippet(StudioIds.new("s"), name.trim(), text, kind, if (kind == PromptSnippet.STYLE) r.negativePrompt else ""))
        }
    }

    override fun deleteSnippet(snippet: PromptSnippet) {
        viewModelScope.launch { host.store.deleteSnippet(snippet.id) }
    }

    /** "Reuse recipe": every parameter of [asset] into the controls (inputs too, when still there). */
    fun loadRecipe(asset: StudioAsset) {
        viewModelScope.launch {
            val recipe = asset.recipe
            val request = StudioRecipes.diffusionRequest(recipe)
            val backendId = recipe.backendId ?: host.diffusion.value.firstOrNull { it.first.kind == BackendKind.ON_DEVICE }?.first?.id
            local.update {
                it.copy(
                    request = request.copy(mode = DiffusionMode.TXT2IMG),
                    backendId = backendId ?: it.backendId,
                    seed = recipe.seed,
                    seedMode = if (recipe.seed != null) SeedMode.LOCKED else SeedMode.RANDOM,
                    hires = request.hires != null,
                )
            }
            recipe.inputs.firstOrNull { it.role == StudioRecipes.ROLE_IMAGE }?.let { ref -> host.store.asset(ref.assetId)?.let { setInput(it) } } ?: clearInput()
            refreshCatalog()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Run

    private suspend fun baseRecipe(s: ImageRoomState): Recipe? {
        val backend = s.backend ?: return null
        val projectId = host.currentProject()
        var request = s.request.copy(prompt = s.request.prompt.trim(), batchCount = 1, hires = if (s.hires && s.capabilities?.hires == true) s.request.hires ?: HiresFix() else null)
        if (s.capabilities?.vae != true) request = request.copy(vae = null)
        val inputs = ArrayList<AssetRef>()
        val input = s.input
        val source = inputBitmap
        if (input != null && source != null) {
            if (s.extend.any) {
                val (canvas, mask, plan) = withContext(Dispatchers.Default) { outpaint(source, s.extend, request) }
                val c = host.addAsset(projectId, AssetType.IMAGE, "image/png", canvas, Recipe(StudioRecipes.MASK_ENGINE), input.id, "canvas")
                val m = host.addAsset(projectId, AssetType.IMAGE, "image/png", mask, Recipe(StudioRecipes.MASK_ENGINE), input.id, "mask")
                request = request.copy(width = plan.width, height = plan.height)
                inputs += AssetRef(c.id, StudioRecipes.ROLE_IMAGE)
                inputs += AssetRef(m.id, StudioRecipes.ROLE_MASK)
            } else {
                inputs += AssetRef(input.id, StudioRecipes.ROLE_IMAGE)
                if (s.inpaint && !s.mask.isEmpty) {
                    val png = withContext(Dispatchers.Default) {
                        ImageCodec.png(MaskRasterizer.render(s.mask, request.width, request.height, MaskEncoding.OPAQUE_BLACK_WHITE))
                    }
                    val m = host.addAsset(projectId, AssetType.IMAGE, "image/png", png, Recipe(StudioRecipes.MASK_ENGINE), input.id, "mask")
                    inputs += AssetRef(m.id, StudioRecipes.ROLE_MASK)
                }
            }
        }
        val seed = if (s.seedMode == SeedMode.RANDOM) null else s.seed
        return StudioRecipes.diffusionRecipe(request.copy(seed = seed ?: -1), if (backend.onDevice) null else backend.id, inputs)
    }

    override fun generate() {
        val s = state.value
        if (!s.canRun) return
        viewModelScope.launch {
            val base = baseRecipe(s) ?: return@launch
            val planned = BatchPlanner.plan(base, s.batch, s.seedMode)
            val jobs = host.queue.submitAll(host.currentProject(), planned, s.input?.id)
            local.update { it.copy(jobIds = (it.jobIds + jobs.map(StudioJob::id)).takeLast(MAX_TRACKED), grid = null) }
            backendsRepo.saveLastRequest(s.request.copy(batchCount = 1, seed = -1))
        }
    }

    override fun queueGrid(x: GridAxis, y: GridAxis?) {
        val s = state.value
        if (!s.canRun) return
        viewModelScope.launch {
            val base = baseRecipe(s) ?: return@launch
            val recipe = if (base.seed == null) base.copy(seed = Seeds.random()) else base
            val plan = runCatching { GridPlanner.plan(recipe, x, y) }.getOrElse {
                local.update { st -> st.copy(error = it.message) }
                return@launch
            }
            val jobs = host.queue.submitAll(host.currentProject(), plan.jobs, s.input?.id)
            val cells = plan.jobs.zip(jobs).associate { (p, j) -> (p.row to p.column) to j.id }
            local.update {
                it.copy(
                    grid = GridRun(x.label, y?.label, plan.columns, plan.rows, cells),
                    jobIds = (it.jobIds + jobs.map(StudioJob::id)).takeLast(MAX_TRACKED),
                    error = if (plan.truncated) "Only the first ${GridPlanner.DEFAULT_CAP} cells were queued" else null,
                )
            }
        }
    }

    override fun upscale(assetId: String) {
        viewModelScope.launch {
            val recipe = Recipe(dev.wckdboy.autobot.shared.studio.EngineKinds.UPSCALE, params = mapOf(StudioRecipes.SCALE to dev.wckdboy.autobot.shared.studio.ParamValue.of(2)), inputs = listOf(AssetRef(assetId, StudioRecipes.ROLE_IMAGE)))
            val job = host.queue.submit(host.currentProject(), recipe, "Upscale ×2", assetId)
            local.update { it.copy(jobIds = (it.jobIds + job.id).takeLast(MAX_TRACKED)) }
        }
    }

    override fun dismissError() = local.update { it.copy(error = null, catalogError = null) }

    /** The source on a larger canvas (edges smeared outwards as a start) and the mask of the new area. */
    private fun outpaint(source: Bitmap, extend: Extend, request: DiffusionRequest): Triple<ByteArray, ByteArray, Outpaint.Plan> {
        val scaled = ImageCodec.cover(source, request.width, request.height)
        val plan = Outpaint.plan(scaled.width, scaled.height, extend.left, extend.top, extend.right, extend.bottom, state.value.capabilities?.sizeMultiple ?: 8)
        val canvas = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        Canvas(canvas).apply {
            // A blurred stretch of the image underneath gives the sampler colours to start from.
            drawBitmap(Bitmap.createScaledBitmap(scaled, plan.width, plan.height, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            drawBitmap(scaled, plan.left.toFloat(), plan.top.toFloat(), null)
        }
        val mask = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        val row = IntArray(plan.width)
        for (y in 0 until plan.height) {
            for (x in 0 until plan.width) row[x] = if (Outpaint.masked(plan, x, y)) Color.WHITE else Color.BLACK
            mask.setPixels(row, 0, plan.width, 0, y, plan.width, 1)
        }
        return Triple(ImageCodec.png(canvas), ImageCodec.png(mask), plan)
    }

    companion object {
        const val MAX_BATCH = 16
        private const val MAX_INPUT_SIDE = 2048
        private const val MAX_TRACKED = 64
    }
}
