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
        npu?.unloadModels()
        val loras = request.loras.mapNotNull { ref ->
            all.firstOrNull { it.kind == ModelKind.LORA && (it.title == ref.name || it.id == ref.name) }
                ?.paths?.get(FileRole.MODEL)?.let { SdLora(it, ref.weight) }
        }
        val initFile = inputs.initImage?.takeIf { request.mode != DiffusionMode.TXT2IMG }?.let { write("init", it) }
        val maskFile = inputs.mask?.takeIf { request.mode == DiffusionMode.INPAINT }?.let { write("mask", it) }
        llm.unloadModel()
        val started = clock()
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
            ).collect { event ->
                when (event) {
                    is LocalSdEvent.Progress -> emit(DiffusionEvent.Progress(0, event.step, event.steps))
                    is LocalSdEvent.Preview -> emit(DiffusionEvent.Progress(0, -1, -1, event.jpeg))
                    is LocalSdEvent.Image -> emit(
                        DiffusionEvent.Result(event.index, event.png, event.seed, event.width, event.height, clock() - started),
                    )
                    is LocalSdEvent.Status -> Unit
                }
            }
        } finally {
            initFile?.delete()
            maskFile?.delete()
        }
    }.catch { e ->
        if (e is SdEngineException) throw DiffusionException(e.message ?: "Engine error", DiffusionException.Code.BACKEND)
        throw e
    }.flowOn(Dispatchers.IO)

    private fun write(prefix: String, bytes: ByteArray): File {
        scratchDir.mkdirs()
        return File.createTempFile(prefix, ".png", scratchDir).apply { writeBytes(bytes) }
    }

    companion object {
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
