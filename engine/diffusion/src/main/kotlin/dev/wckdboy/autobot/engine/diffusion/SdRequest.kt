package dev.wckdboy.autobot.engine.diffusion

import android.os.Bundle

/**
 * Model files of one diffusion setup. SD1.x/SDXL use a single [model] checkpoint; newer
 * architectures (FLUX, Z-Image, SD3.5) split into [diffusionModel] + text encoder(s) + [vae].
 */
data class SdBundle(
    val model: String? = null,
    val diffusionModel: String? = null,
    val llm: String? = null,
    val clipL: String? = null,
    val clipG: String? = null,
    val t5xxl: String? = null,
    val vae: String? = null,
    val taesd: String? = null,
    val flashAttention: Boolean = true,
)

data class SdLora(val path: String, val weight: Float)

/** One generation on the on-device engine. Sampler/scheduler use stable-diffusion.cpp names. */
data class SdRequest(
    val bundle: SdBundle,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val sampler: String = "",
    val scheduler: String = "",
    val seed: Long = -1,
    val batch: Int = 1,
    val strength: Float = 0.75f,
    val clipSkip: Int = -1,
    val loras: List<SdLora> = emptyList(),
    /** PNG/JPEG file for img2img (resized to width × height by the engine). */
    val initImagePath: String? = null,
    /** PNG mask, white = repaint. */
    val maskPath: String? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(K_MODEL, bundle.model)
        putString(K_DIFFUSION, bundle.diffusionModel)
        putString(K_LLM, bundle.llm)
        putString(K_CLIP_L, bundle.clipL)
        putString(K_CLIP_G, bundle.clipG)
        putString(K_T5, bundle.t5xxl)
        putString(K_VAE, bundle.vae)
        putString(K_TAESD, bundle.taesd)
        putBoolean(K_FLASH, bundle.flashAttention)
        putString(K_PROMPT, prompt)
        putString(K_NEGATIVE, negativePrompt)
        putInt(K_WIDTH, width)
        putInt(K_HEIGHT, height)
        putInt(K_STEPS, steps)
        putFloat(K_CFG, cfg)
        putString(K_SAMPLER, sampler)
        putString(K_SCHEDULER, scheduler)
        putLong(K_SEED, seed)
        putInt(K_BATCH, batch)
        putFloat(K_STRENGTH, strength)
        putInt(K_CLIP_SKIP, clipSkip)
        putStringArray(K_LORA_PATHS, loras.map { it.path }.toTypedArray())
        putFloatArray(K_LORA_WEIGHTS, loras.map { it.weight }.toFloatArray())
        putString(K_INIT, initImagePath)
        putString(K_MASK, maskPath)
    }

    internal companion object {
        const val K_MODEL = "model"
        const val K_DIFFUSION = "diffusion_model"
        const val K_LLM = "llm"
        const val K_CLIP_L = "clip_l"
        const val K_CLIP_G = "clip_g"
        const val K_T5 = "t5xxl"
        const val K_VAE = "vae"
        const val K_TAESD = "taesd"
        const val K_FLASH = "flash_attn"
        const val K_PROMPT = "prompt"
        const val K_NEGATIVE = "negative"
        const val K_WIDTH = "width"
        const val K_HEIGHT = "height"
        const val K_STEPS = "steps"
        const val K_CFG = "cfg"
        const val K_SAMPLER = "sampler"
        const val K_SCHEDULER = "scheduler"
        const val K_SEED = "seed"
        const val K_BATCH = "batch"
        const val K_STRENGTH = "strength"
        const val K_CLIP_SKIP = "clip_skip"
        const val K_LORA_PATHS = "lora_paths"
        const val K_LORA_WEIGHTS = "lora_weights"
        const val K_INIT = "init"
        const val K_MASK = "mask"

        val BUNDLE_KEYS = listOf(K_MODEL, K_DIFFUSION, K_LLM, K_CLIP_L, K_CLIP_G, K_T5, K_VAE, K_TAESD)
    }
}
