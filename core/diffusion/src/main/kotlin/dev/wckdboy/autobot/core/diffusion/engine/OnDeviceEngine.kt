package dev.wckdboy.autobot.core.diffusion.engine

import dev.wckdboy.autobot.core.diffusion.DiffusionEngine
import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.diffusion.LocalSd
import dev.wckdboy.autobot.engine.diffusion.LocalSdEvent
import dev.wckdboy.autobot.engine.diffusion.SdBundle
import dev.wckdboy.autobot.engine.diffusion.SdEngineException
import dev.wckdboy.autobot.engine.diffusion.SdLora
import dev.wckdboy.autobot.engine.diffusion.SdRequest
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.npu.LocalNpu
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * stable-diffusion.cpp on this phone (isolated `:sd` process). Models and LoRAs come from the
 * model library; nothing is fetched or sent anywhere. The chat engine's model is unloaded first
 * so both never compete for RAM.
 */
class OnDeviceEngine(
    private val library: ModelLibrary,
    private val sd: LocalSd,
    private val llm: LocalLlm,
    private val scratchDir: File,
    private val npu: LocalNpu? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiffusionEngine {
    private val npuImages = npu?.let { NpuImages(it, File(scratchDir.parentFile, "npu-io"), clock) }

    private fun runnable(m: InstalledModel) = m.isReady && (m.engine == EngineKind.DIFFUSION || (m.engine == EngineKind.NPU && npu != null))

    override val capabilities = EngineCapabilities(
        modes = DiffusionMode.entries.toSet(),
        runtimeLora = true,
        modelSwitching = true,
        livePreview = true,
        maskEncoding = MaskEncoding.OPAQUE_BLACK_WHITE,
        maxSteps = 60,
    )

    override suspend fun catalog(): EngineCatalog {
        val ready = library.all().filter(::runnable)
        val images = ready.filter { it.kind == ModelKind.IMAGE }
        return EngineCatalog(
            models = images.map { it.id },
            currentModel = images.firstOrNull()?.id,
            loras = ready.filter { it.kind == ModelKind.LORA }.map { it.title },
            samplers = SAMPLERS,
            schedulers = SCHEDULERS,
        )
    }

    override fun generate(request: DiffusionRequest, inputs: DiffusionInputs): Flow<DiffusionEvent> = flow {
        val all = library.all().filter(::runnable)
        val model = all.firstOrNull { it.id == request.model && it.kind == ModelKind.IMAGE }
            ?: all.firstOrNull { it.kind == ModelKind.IMAGE }
            ?: throw DiffusionException("No image model installed — get one in the Models tab", DiffusionException.Code.UNSUPPORTED)
        if (model.engine == EngineKind.NPU && npuImages != null) {
            llm.unloadModel()
            npuImages.generate(this, model, request, inputs)
            return@flow
        }
        // Out-of-range values would fail deep in the engine (or take it down): say what to change.
        validate(request, capabilities.maxSteps)?.let { throw DiffusionException(it, DiffusionException.Code.UNSUPPORTED) }
        npu?.unloadModels()
        val loras = request.loras.mapNotNull { ref ->
            all.firstOrNull { it.kind == ModelKind.LORA && (it.title == ref.name || it.id == ref.name) }
                ?.paths?.get(FileRole.MODEL)?.let { SdLora(it, ref.weight) }
        }
        val initFile = inputs.initImage?.takeIf { request.mode != DiffusionMode.TXT2IMG }?.let { write("init", it) }
        val maskFile = inputs.mask?.takeIf { request.mode == DiffusionMode.INPAINT }?.let { write("mask", it) }
        llm.unloadModel()
        val events = SdEvents(clock)
        // Waits for the model being loaded ahead of time (the engine runs requests in order).
        if (sd.isPreparing) emit(DiffusionEvent.Phase(LOADING))
        try {
            sd.generate(
                SdRequest(
                    bundle = bundleOf(model),
                    prompt = PromptSyntax.stripLoraTags(request.prompt),
                    negativePrompt = request.negativePrompt,
                    width = request.width,
                    height = request.height,
                    steps = request.steps,
                    cfg = request.cfgScale,
                    sampler = samplerId(request.sampler),
                    scheduler = schedulerId(request.scheduler),
                    seed = request.seed,
                    batch = request.batchCount.coerceIn(1, 8),
                    strength = request.denoiseStrength,
                    clipSkip = request.clipSkip ?: -1,
                    loras = loras,
                    initImagePath = initFile?.path,
                    maskPath = maskFile?.path,
                ),
            ).collect { event -> events.map(event)?.let { emit(it) } }
        } finally {
            initFile?.delete()
            maskFile?.delete()
        }
    }.catch { e ->
        if (e is SdEngineException) throw DiffusionException(e.message ?: "Engine error", DiffusionException.Code.BACKEND)
        throw e
    }.flowOn(Dispatchers.IO)

    /**
     * stable-diffusion.cpp events → [DiffusionEvent]s. The engine samples a batch one image after
     * another and reports steps without an index: the image is the number finished so far. A
     * preview carries no step (the bar keeps its place), and the model load is a phase.
     */
    internal class SdEvents(private val clock: () -> Long) {
        private val started = clock()
        private var done = 0

        fun map(event: LocalSdEvent): DiffusionEvent? = when (event) {
            is LocalSdEvent.Progress -> DiffusionEvent.Progress(done, event.step, event.steps)
            is LocalSdEvent.Preview -> DiffusionEvent.Progress(done, -1, -1, event.jpeg)
            is LocalSdEvent.Image -> {
                done = event.index + 1
                DiffusionEvent.Result(event.index, event.png, event.seed, event.width, event.height, clock() - started)
            }
            is LocalSdEvent.Status -> phaseOf(event.status)?.let(DiffusionEvent::Phase)
        }
    }

    /**
     * Loads the model [request] would run ahead of Generate (stable-diffusion.cpp models; the NPU
     * engine loads per job). Does nothing without one, or while another engine works.
     */
    override suspend fun prepare(request: DiffusionRequest): Boolean {
        val images = library.all().filter { runnable(it) && it.kind == ModelKind.IMAGE }
        val model = images.firstOrNull { it.id == request.model } ?: images.firstOrNull() ?: return true
        if (model.engine != EngineKind.DIFFUSION) return true
        return try {
            sd.prepare(bundleOf(model))
        } catch (e: SdEngineException) {
            // A warm-up that fails says nothing: Generate reports the problem properly.
            false
        }
    }

    private fun write(prefix: String, bytes: ByteArray): File {
        scratchDir.mkdirs()
        return File.createTempFile(prefix, ".png", scratchDir).apply { writeBytes(bytes) }
    }

    companion object {
        const val MIN_SIDE = 64
        const val MAX_SIDE = 2048

        /** Why stable-diffusion.cpp can't run [request] as asked (user-facing), or null when it can. */
        fun validate(request: DiffusionRequest, maxSteps: Int): String? {
            val (w, h) = request.width to request.height
            return when {
                w !in MIN_SIDE..MAX_SIDE || h !in MIN_SIDE..MAX_SIDE -> "Image size ${w}×$h is out of range: each side must be $MIN_SIDE–$MAX_SIDE px"
                w % 8 != 0 || h % 8 != 0 -> "Image size ${w}×$h doesn't work: each side must be a multiple of 8 px"
                request.steps !in 1..maxSteps -> "Steps must be between 1 and $maxSteps (got ${request.steps})"
                request.mode != DiffusionMode.TXT2IMG && request.denoiseStrength !in 0f..1f -> "Denoise strength must be between 0 and 1 (got ${request.denoiseStrength})"
                else -> null
            }
        }

        const val LOADING = "Loading the model"
        const val DECODING = "Decoding the image"

        /** A native engine status line → the phase the UI shows, or null for ones it doesn't. */
        fun phaseOf(status: String): String? = when {
            status.contains("load", ignoreCase = true) -> LOADING
            status.contains("decod", ignoreCase = true) -> DECODING
            else -> null
        }

        val SAMPLERS = listOf("euler_a", "euler", "dpm++2m", "dpm++2m_sde", "dpm++2s_a", "heun", "lcm", "ddim_trailing", "tcd", "ipndm")
        val SCHEDULERS = listOf("discrete", "karras", "exponential", "ays", "sgm_uniform", "simple", "beta")

        fun bundleOf(model: InstalledModel): SdBundle = with(model.paths) {
            SdBundle(
                model = get(FileRole.MODEL),
                diffusionModel = get(FileRole.DIFFUSION_MODEL),
                llm = get(FileRole.LLM),
                clipL = get(FileRole.CLIP_L),
                clipG = get(FileRole.CLIP_G),
                t5xxl = get(FileRole.T5XXL),
                vae = get(FileRole.VAE),
                taesd = get(FileRole.TAESD),
            )
        }

        /** A1111-style names → stable-diffusion.cpp ids; empty = the model's default. */
        fun samplerId(name: String): String {
            if (name in SAMPLERS) return name
            val n = name.lowercase()
            return when {
                n.isBlank() -> ""
                "lcm" in n -> "lcm"
                "2m sde" in n -> "dpm++2m_sde"
                "2m" in n -> "dpm++2m"
                "2s a" in n || "2s_a" in n -> "dpm++2s_a"
                "euler a" in n || "euler_a" in n || "ancestral" in n -> "euler_a"
                "euler" in n -> "euler"
                "heun" in n -> "heun"
                "ddim" in n -> "ddim_trailing"
                else -> ""
            }
        }

        fun schedulerId(name: String?): String {
            val n = name?.lowercase().orEmpty()
            return when {
                n in SCHEDULERS -> n
                "karras" in n -> "karras"
                "exponential" in n -> "exponential"
                "sgm" in n -> "sgm_uniform"
                "simple" in n -> "simple"
                else -> ""
            }
        }
    }
}
