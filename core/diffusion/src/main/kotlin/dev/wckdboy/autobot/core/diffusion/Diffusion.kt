package dev.wckdboy.autobot.core.diffusion

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class DiffusionMode(val label: String) { TXT2IMG("txt2img"), IMG2IMG("img2img"), INPAINT("inpaint") }

/** A LoRA applied at generation time (`<lora:name:weight>` on A1111-style backends). */
@Serializable
data class LoraRef(val name: String, val weight: Float = 1f)

/**
 * A second, larger pass over a txt2img result (A1111 "hires fix"): upscale by [scale] with
 * [upscaler], then [steps] img2img steps (0 = the first pass's count) at [denoise].
 */
@Serializable
data class HiresFix(val scale: Float = 2f, val upscaler: String = "Latent", val steps: Int = 0, val denoise: Float = 0.5f)

/**
 * Engine-independent generation parameters. Pixel inputs travel separately in
 * [DiffusionInputs] so requests stay small, comparable and serializable (history, "reuse").
 *
 * @param seed `-1` for random.
 * @param maskBlur feather radius (px) applied by the backend to the mask edge.
 * @param inpaintOnlyMasked inpaint at full resolution around the mask, then paste back.
 */
@Serializable
data class DiffusionRequest(
    val mode: DiffusionMode = DiffusionMode.TXT2IMG,
    val prompt: String = "",
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfgScale: Float = 7f,
    val seed: Long = -1,
    val sampler: String = "DPM++ 2M",
    val scheduler: String? = "Karras",
    val batchCount: Int = 1,
    val denoiseStrength: Float = 0.6f,
    val maskBlur: Int = 4,
    val inpaintOnlyMasked: Boolean = true,
    val loras: List<LoraRef> = emptyList(),
    val clipSkip: Int? = null,
    val model: String? = null,
    /** VAE override (backends with [EngineCapabilities.vae]); null = the model's own. */
    val vae: String? = null,
    /** Second pass for txt2img on backends with [EngineCapabilities.hires]. */
    val hires: HiresFix? = null,
)

/** How a backend wants the inpaint mask encoded. */
enum class MaskEncoding {
    /** Opaque PNG: white = repaint, black = keep (A1111). */
    OPAQUE_BLACK_WHITE,

    /** PNG with alpha: opaque white strokes = repaint, transparent = keep. */
    ALPHA_WHITE,
}

/** PNG bytes for img2img / inpaint. */
class DiffusionInputs(val initImage: ByteArray? = null, val mask: ByteArray? = null) {
    override fun toString(): String = "DiffusionInputs(init=${initImage?.size}B, mask=${mask?.size}B)"
}

sealed interface DiffusionEvent {
    /** What the backend does before sampling starts (loading the model), for the UI. */
    class Phase(val text: String) : DiffusionEvent

    /** Sampling progress. [preview] is an encoded image when the backend sends one. */
    class Progress(val image: Int, val step: Int, val totalSteps: Int, val preview: ByteArray? = null) : DiffusionEvent {
        val fraction: Float get() = if (totalSteps <= 0) 0f else step.toFloat() / totalSteps
    }

    /** One finished image (PNG or JPEG bytes). */
    class Result(
        val index: Int,
        val image: ByteArray,
        val seed: Long,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val info: String? = null,
    ) : DiffusionEvent
}

class DiffusionException(message: String, val code: Code = Code.BACKEND) : Exception(message) {
    enum class Code { BLOCKED, UNREACHABLE, BACKEND, PROTOCOL, UNSUPPORTED }
}

/** What the UI should offer for this backend. */
data class EngineCapabilities(
    val modes: Set<DiffusionMode>,
    val runtimeLora: Boolean,
    val modelSwitching: Boolean,
    val livePreview: Boolean,
    val maskEncoding: MaskEncoding,
    /** Fixed output sizes, or empty when any multiple of [sizeMultiple] works. */
    val fixedSizes: List<Pair<Int, Int>> = emptyList(),
    val sizeMultiple: Int = 8,
    val maxSteps: Int = 150,
    /** Takes [DiffusionRequest.hires] (a second pass on the backend). */
    val hires: Boolean = false,
    /** Takes [DiffusionRequest.vae]. */
    val vae: Boolean = false,
) {
    /** The sizes this backend renders (per model where [DiffusionEngine.traits] says otherwise). */
    val sizes: SizeSupport get() = SizeSupport(multiple = sizeMultiple)
}

/**
 * What one model can do where it differs from its backend's [EngineCapabilities]: an NPU
 * package compiled for one square, a graph that cannot take LoRAs or an input image.
 */
data class ModelTraits(
    val sizes: SizeSupport = SizeSupport(),
    val modes: Set<DiffusionMode> = DiffusionMode.entries.toSet(),
    val runtimeLora: Boolean = true,
    /** Why the sizes are limited, for the UI (e.g. "compiled for 1024² on the NPU"). */
    val sizeNote: String? = null,
)

/** Discoverable resources of a backend. */
data class EngineCatalog(
    val models: List<String> = emptyList(),
    val currentModel: String? = null,
    val loras: List<String> = emptyList(),
    val samplers: List<String> = emptyList(),
    val schedulers: List<String> = emptyList(),
    val vaes: List<String> = emptyList(),
    val upscalers: List<String> = emptyList(),
)

/**
 * An image backend. [generate] is cold: collecting starts the job, cancelling the collector
 * cancels it (and interrupts the backend where the API allows). Failures are thrown as
 * [DiffusionException].
 */
interface DiffusionEngine {
    val capabilities: EngineCapabilities
    suspend fun catalog(): EngineCatalog
    fun generate(request: DiffusionRequest, inputs: DiffusionInputs = DiffusionInputs()): Flow<DiffusionEvent>

    /**
     * Loads what [request] needs ahead of Generate (on-device models); remote backends do nothing.
     * Returns false when it could not (another engine works, the load failed): worth trying again.
     */
    suspend fun prepare(request: DiffusionRequest): Boolean = true
}

/** Prompt helpers shared by engines and UI. */
object PromptSyntax {
    private val LORA_TAG = Regex("<lora:([^:>]+)(?::([-0-9.]+))?>")

    /** Appends `<lora:…>` tags for [loras] not already present in [prompt]. */
    fun withLoraTags(prompt: String, loras: List<LoraRef>): String {
        val present = LORA_TAG.findAll(prompt).map { it.groupValues[1] }.toSet()
        val tags = loras.filter { it.name !in present && it.weight != 0f }
            .joinToString(" ") { "<lora:${it.name}:${"%.2f".format(java.util.Locale.ROOT, it.weight)}>" }
        return if (tags.isEmpty()) prompt else "${prompt.trimEnd()} $tags"
    }

    /** Removes LoRA tags (for engines that cannot apply them). */
    fun stripLoraTags(prompt: String): String = prompt.replace(LORA_TAG, "").replace(Regex("\\s{2,}"), " ").trim()

    /**
     * Rough CLIP token estimate (BPE splits most words into 1–2 tokens). Good enough for an
     * "approaching 75" warning; exact counts need the tokenizer.
     */
    fun estimateTokens(prompt: String): Int {
        val clean = stripLoraTags(prompt)
        val words = Regex("[A-Za-z]+|\\d+|[^\\sA-Za-z\\d]").findAll(clean).map { it.value }.toList()
        return words.sumOf { w -> if (w.length > 7) 2 else 1 }
    }

    /** Snaps to the backend's multiple, clamped to [min]..[max]. */
    fun snap(value: Int, multiple: Int = 8, min: Int = 64, max: Int = 2048): Int =
        ((value + multiple / 2) / multiple * multiple).coerceIn(min, max)
}
