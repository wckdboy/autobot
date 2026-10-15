package dev.wckdboy.autobot.shared.audiogen

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A text-to-audio request. [seconds] is clamped to 0.1…[StableAudio.MAX_SECONDS]; [steps] and
 * [cfg] default to what the distilled model was made for (8, 1 = no guidance; guidance doubles the
 * DiT work). The same [seed] gives the same audio for the same settings.
 */
data class AudioGenRequest(
    val prompt: String,
    val seconds: Double = 10.0,
    val steps: Int = StableAudio.DEFAULT_STEPS,
    val cfg: Float = StableAudio.DEFAULT_CFG,
    val seed: Long = 0,
) {
    init {
        require(steps in 1..StableAudio.MAX_STEPS) { "steps must be 1…${StableAudio.MAX_STEPS}" }
        require(cfg in 0f..20f) { "cfg must be 0…20" }
    }
}

/** Where a generation is. [step] counts finished DiT steps (0…[steps]). */
data class AudioGenProgress(val stage: Stage, val step: Int, val steps: Int) {
    enum class Stage { ENCODING, SAMPLING, DECODING, DONE }

    /** 0…1 overall: encoding ~5 %, sampling ~75 %, decoding ~20 % (roughly the time each takes). */
    val fraction: Float
        get() = when (stage) {
            Stage.ENCODING -> 0f
            Stage.SAMPLING -> 0.05f + 0.75f * step / steps.coerceAtLeast(1)
            Stage.DECODING -> 0.8f
            Stage.DONE -> 1f
        }
}

/**
 * The models a platform runs for [StableAudioPipeline]: blocking calls on float32 arrays in
 * row-major order (batch 1 left out). Implementations need not be thread-safe; the pipeline calls
 * them one at a time.
 */
interface StableAudioModels {
    /** T5-base encoder: [ids] / [mask] (64 each) → `[64, 768]`. */
    fun encodeText(ids: IntArray, mask: IntArray): FloatArray

    /** The `seconds_total` embedding → `[768]`. */
    fun embedSeconds(seconds: Double): FloatArray

    /** One DiT evaluation: latent `[64, 256]`, noise level [t], cross `[65, 768]`, global `[768]` → velocity `[64, 256]`. */
    fun velocity(latent: FloatArray, t: Float, cross: FloatArray, global: FloatArray): FloatArray

    /** Oobleck decoder: latent `[64, 256]` → planar stereo `[2, 524288]`. */
    fun decode(latent: FloatArray): FloatArray
}

/**
 * The whole text-to-audio loop on top of [models] (Android's ONNX Runtime engine; iOS mirrors it
 * in Swift on Core ML). Checks for cancellation between model calls.
 */
class StableAudioPipeline(private val models: StableAudioModels, private val tokenizer: T5Tokenizer) {
    suspend fun generate(request: AudioGenRequest, onProgress: (AudioGenProgress) -> Unit = {}): StereoAudio {
        val steps = request.steps
        onProgress(AudioGenProgress(AudioGenProgress.Stage.ENCODING, 0, steps))
        val tokens = tokenizer.encode(request.prompt)
        val seconds = StableAudio.clampSeconds(request.seconds)
        val text = models.encodeText(tokens.ids, tokens.mask)
        val secondsEmbedding = models.embedSeconds(seconds)
        val cross = StableAudio.crossConditioning(text, tokens.mask, secondsEmbedding)
        val guided = request.cfg != 1f
        // Unconditional branch: the cross-attention input zeroed, global conditioning kept (as the DiT's CFG batch).
        val nullCross = if (guided) FloatArray(cross.size) else null
        checkActive()

        val schedule = StableAudio.schedule(steps)
        val noise = GaussianNoise(request.seed)
        var x = noise.fill(StableAudio.LATENT_SIZE)
        onProgress(AudioGenProgress(AudioGenProgress.Stage.SAMPLING, 0, steps))
        for (i in 0 until steps) {
            val t = schedule[i]
            val v = models.velocity(x, t, cross, secondsEmbedding)
            checkActive()
            val vu = nullCross?.let { models.velocity(x, t, it, secondsEmbedding).also { checkActive() } }
            val denoised = StableAudio.denoised(x, t, v, vu, request.cfg)
            x = StableAudio.pingPong(denoised, schedule[i + 1], noise)
            onProgress(AudioGenProgress(AudioGenProgress.Stage.SAMPLING, i + 1, steps))
        }

        onProgress(AudioGenProgress(AudioGenProgress.Stage.DECODING, steps, steps))
        val audio = StableAudio.clip(models.decode(x), seconds)
        onProgress(AudioGenProgress(AudioGenProgress.Stage.DONE, steps, steps))
        return audio
    }

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()
}
