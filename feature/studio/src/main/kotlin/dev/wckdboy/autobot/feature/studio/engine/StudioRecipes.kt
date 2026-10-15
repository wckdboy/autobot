package dev.wckdboy.autobot.feature.studio.engine

import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.HiresFix
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.shared.studio.AssetRef
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Recipe

/**
 * The Studio's recipe vocabulary for the Android engines, both ways: a [Recipe] (what the queue,
 * the library's "reuse recipe" and the X/Y grid work with) to the engine requests, and back.
 * Pure, so it is unit-tested on the JVM.
 *
 * Diffusion params: `prompt`, `negative`, `steps`, `cfg`, `sampler`, `scheduler`, `width`,
 * `height`, `strength` (img2img / inpaint denoise), `mask_blur`, `only_masked`, `clip_skip`, `vae`,
 * `hires_scale` (0 = off), `hires_upscaler`, `hires_steps`, `hires_denoise`, and one
 * `lora.<name>` per LoRA with its weight (so a grid can vary a LoRA's weight). Inputs: role
 * `image` (img2img), plus role `mask` (inpaint / outpaint).
 */
object StudioRecipes {
    const val PROMPT = "prompt"
    const val NEGATIVE = "negative"
    const val STEPS = "steps"
    const val CFG = "cfg"
    const val SAMPLER = "sampler"
    const val SCHEDULER = "scheduler"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val STRENGTH = "strength"
    const val MASK_BLUR = "mask_blur"
    const val ONLY_MASKED = "only_masked"
    const val CLIP_SKIP = "clip_skip"
    const val VAE = "vae"
    const val HIRES_SCALE = "hires_scale"
    const val HIRES_UPSCALER = "hires_upscaler"
    const val HIRES_STEPS = "hires_steps"
    const val HIRES_DENOISE = "hires_denoise"
    const val LORA_PREFIX = "lora."
    const val SECONDS = "seconds"
    const val TEXT = "text"
    const val VOICE = "voice"
    const val SPEED = "speed"
    const val SCALE = "scale"
    const val BATCH = "batch"

    const val ROLE_IMAGE = "image"
    const val ROLE_MASK = "mask"

    /** Engine kinds of helper assets (masks, extended canvases) the library hides. */
    const val MASK_ENGINE = "mask"

    /** An image brought in from the phone ("use as input" from outside the Studio). */
    const val IMPORT_ENGINE = "import"

    val HIDDEN_ENGINES = setOf(MASK_ENGINE)

    /** The diffusion request a recipe describes (one image: batches are separate jobs). */
    fun diffusionRequest(recipe: Recipe): DiffusionRequest {
        val d = DiffusionRequest()
        fun str(name: String) = recipe.string(name)
        fun num(name: String) = recipe.params[name]?.asDouble()
        val mode = when {
            recipe.inputs.any { it.role == ROLE_MASK } -> DiffusionMode.INPAINT
            recipe.inputs.any { it.role == ROLE_IMAGE } -> DiffusionMode.IMG2IMG
            else -> DiffusionMode.TXT2IMG
        }
        val hiresScale = num(HIRES_SCALE)?.toFloat()?.takeIf { it > 1f }
        return d.copy(
            mode = mode,
            prompt = str(PROMPT).orEmpty(),
            negativePrompt = str(NEGATIVE).orEmpty(),
            width = num(WIDTH)?.toInt() ?: d.width,
            height = num(HEIGHT)?.toInt() ?: d.height,
            steps = num(STEPS)?.toInt() ?: d.steps,
            cfgScale = num(CFG)?.toFloat() ?: d.cfgScale,
            seed = recipe.seed ?: -1,
            sampler = str(SAMPLER) ?: d.sampler,
            scheduler = if (recipe.params.containsKey(SCHEDULER)) str(SCHEDULER)?.takeIf { it.isNotBlank() } else d.scheduler,
            batchCount = 1,
            denoiseStrength = num(STRENGTH)?.toFloat() ?: d.denoiseStrength,
            maskBlur = num(MASK_BLUR)?.toInt() ?: d.maskBlur,
            inpaintOnlyMasked = (recipe.params[ONLY_MASKED] as? ParamValue.BoolValue)?.value ?: d.inpaintOnlyMasked,
            loras = recipe.params.filterKeys { it.startsWith(LORA_PREFIX) }
                .map { (k, v) -> LoraRef(k.removePrefix(LORA_PREFIX), v.asDouble()?.toFloat() ?: 1f) }
                .sortedBy { it.name },
            clipSkip = num(CLIP_SKIP)?.toInt(),
            model = recipe.model,
            vae = str(VAE)?.takeIf { it.isNotBlank() },
            hires = hiresScale?.let {
                HiresFix(it, str(HIRES_UPSCALER) ?: "Latent", num(HIRES_STEPS)?.toInt() ?: 0, num(HIRES_DENOISE)?.toFloat() ?: 0.5f)
            },
        )
    }

    /** The recipe for [request] on [backendId] (null = on device) with input [inputs]. */
    fun diffusionRecipe(request: DiffusionRequest, backendId: String?, inputs: List<AssetRef> = emptyList()): Recipe {
        val params = LinkedHashMap<String, ParamValue>()
        params[PROMPT] = ParamValue.of(request.prompt)
        if (request.negativePrompt.isNotBlank()) params[NEGATIVE] = ParamValue.of(request.negativePrompt)
        params[STEPS] = ParamValue.of(request.steps)
        params[CFG] = ParamValue.of(request.cfgScale.toDouble())
        params[SAMPLER] = ParamValue.of(request.sampler)
        params[SCHEDULER] = ParamValue.of(request.scheduler.orEmpty())
        params[WIDTH] = ParamValue.of(request.width)
        params[HEIGHT] = ParamValue.of(request.height)
        if (inputs.any { it.role == ROLE_IMAGE }) params[STRENGTH] = ParamValue.of(request.denoiseStrength.toDouble())
        if (inputs.any { it.role == ROLE_MASK }) {
            params[MASK_BLUR] = ParamValue.of(request.maskBlur)
            params[ONLY_MASKED] = ParamValue.of(request.inpaintOnlyMasked)
        }
        request.clipSkip?.let { params[CLIP_SKIP] = ParamValue.of(it) }
        request.vae?.let { params[VAE] = ParamValue.of(it) }
        request.hires?.let { h ->
            params[HIRES_SCALE] = ParamValue.of(h.scale.toDouble())
            params[HIRES_UPSCALER] = ParamValue.of(h.upscaler)
            params[HIRES_STEPS] = ParamValue.of(h.steps)
            params[HIRES_DENOISE] = ParamValue.of(h.denoise.toDouble())
        }
        request.loras.forEach { params[LORA_PREFIX + it.name] = ParamValue.of(it.weight.toDouble()) }
        return Recipe(
            engine = EngineKinds.DIFFUSION,
            backendId = backendId,
            model = request.model,
            params = params,
            inputs = inputs,
            seed = request.seed.takeIf { it >= 0 },
            output = AssetType.IMAGE,
        )
    }

    /** Text to audio (Stable Audio Open Small). */
    fun audioRecipe(prompt: String, seconds: Double, steps: Int, cfg: Float, seed: Long?): Recipe = Recipe(
        engine = EngineKinds.AUDIO,
        params = mapOf(PROMPT to ParamValue.of(prompt), SECONDS to ParamValue.of(seconds), STEPS to ParamValue.of(steps), CFG to ParamValue.of(cfg.toDouble())),
        seed = seed,
        output = AssetType.AUDIO,
    )

    /** Kokoro voiceover. */
    fun speechRecipe(text: String, voice: String, speed: Float): Recipe = Recipe(
        engine = EngineKinds.SPEECH,
        params = mapOf(TEXT to ParamValue.of(text), VOICE to ParamValue.of(voice), SPEED to ParamValue.of(speed.toDouble())),
        output = AssetType.AUDIO,
    )

    /** A one-line description of a recipe for the library and the queue strip. */
    fun summary(recipe: Recipe): String {
        fun s(name: String) = recipe.value(name)?.display()
        return when (recipe.engine) {
            EngineKinds.DIFFUSION -> listOfNotNull(s(STEPS)?.let { "$it steps" }, s(CFG)?.let { "cfg $it" }, recipe.seed?.let { "seed $it" }).joinToString(" · ")
            EngineKinds.AUDIO -> listOfNotNull(s(SECONDS)?.let { "$it s" }, s(STEPS)?.let { "$it steps" }, recipe.seed?.let { "seed $it" }).joinToString(" · ")
            EngineKinds.SPEECH -> listOfNotNull(s(VOICE), s(SPEED)?.let { "×$it" }).joinToString(" · ")
            EngineKinds.UPSCALE -> "upscale ×${s(SCALE) ?: "4"}"
            EngineKinds.COMFYUI -> listOfNotNull("workflow", recipe.seed?.let { "seed $it" }).joinToString(" · ")
            IMPORT_ENGINE -> "imported"
            else -> recipe.engine
        }
    }

    /** The prompt or text of a recipe (titles in the library). */
    fun title(recipe: Recipe): String =
        (recipe.string(PROMPT) ?: recipe.string(TEXT) ?: recipe.params.values.filterIsInstance<ParamValue.StringValue>().firstOrNull { it.value.length > 12 }?.value)
            ?.trim().orEmpty()
}
