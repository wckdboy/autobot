package dev.wckdboy.autobot.shared.audiogen

import dev.wckdboy.autobot.shared.SystemFileSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/**
 * Stable Audio Open Small (Stability AI, 341M DiT + Oobleck VAE + T5-base; Stability AI Community
 * License): the platform-independent half of text-to-audio. Both apps run the same pipeline:
 * prompt → [T5Tokenizer] → T5 encoder (64 × 768, padding zeroed) + `seconds_total` embedding →
 * cross-attention conditioning (65 × 768) and global conditioning (768) → 8 ping-pong steps of the
 * distilled rectified-flow DiT on a 64 × 256 latent → Oobleck decoder → 524 288 stereo samples at
 * 44.1 kHz (11.9 s), trimmed to the requested length. Follows Stability's `stable-audio-tools`
 * (`sample_flow_pingpong`, `LogSNRShift(rate=0)` schedule, APG guidance); the models themselves
 * run on ONNX Runtime (Android) or Core ML (iOS, which mirrors this math in Swift).
 */
object StableAudio {
    const val SAMPLE_RATE = 44_100
    const val CHANNELS = 2
    const val LATENT_CHANNELS = 64
    const val LATENT_LENGTH = 256
    const val LATENT_SIZE = LATENT_CHANNELS * LATENT_LENGTH

    /** Audio samples per latent frame (Oobleck's 2 · 4 · 4 · 8 · 8). */
    const val DOWNSAMPLING = 2048

    /** Samples per channel the decoder returns: 11.89 s. */
    const val SAMPLES = LATENT_LENGTH * DOWNSAMPLING

    /** Longest clip the model makes. */
    const val MAX_SECONDS = SAMPLES.toDouble() / SAMPLE_RATE

    const val TEXT_TOKENS = 64
    const val COND_DIM = 768

    /** Cross-attention tokens: the prompt's 64 and `seconds_total`. */
    const val COND_TOKENS = TEXT_TOKENS + 1

    /** The distilled model's sampler settings (ARC post-training: 8 steps, no guidance). */
    const val DEFAULT_STEPS = 8
    const val DEFAULT_CFG = 1f
    const val MAX_STEPS = 50

    /** `seconds_total`'s range in the model config (`NumberConditioner(min_val=0, max_val=256)`). */
    const val SECONDS_MIN = 0.0
    const val SECONDS_MAX = 256.0

    /**
     * Noise levels from 1 to 0, `steps + 1` of them: `linspace(1, 0)` mapped through
     * `LogSNRShift(rate=0, anchor_logsnr=-6.2, logsnr_end=2)`, i.e. `t = sigmoid(8.2 · u - 2)`
     * with the end points kept exactly.
     */
    fun schedule(steps: Int): FloatArray {
        require(steps in 1..MAX_STEPS) { "steps must be 1…$MAX_STEPS" }
        return FloatArray(steps + 1) { i ->
            val u = 1.0 - i.toDouble() / steps
            when (i) {
                0 -> 1f
                steps -> 0f
                else -> (1.0 / (1.0 + exp(-(8.2 * u - 2.0)))).toFloat()
            }
        }
    }

    /** `seconds_total` as the number conditioner sees it: clamped to 0…256, then scaled to 0…1. */
    fun normalizedSeconds(seconds: Double): Float =
        ((seconds.coerceIn(SECONDS_MIN, SECONDS_MAX) - SECONDS_MIN) / (SECONDS_MAX - SECONDS_MIN)).toFloat()

    /** Clip length the user may ask for (a little audio at least, at most [MAX_SECONDS]). */
    fun clampSeconds(seconds: Double): Double = if (seconds.isNaN()) MAX_SECONDS else seconds.coerceIn(0.1, MAX_SECONDS)

    /** Samples per channel kept for [seconds]. */
    fun frames(seconds: Double): Int = (clampSeconds(seconds) * SAMPLE_RATE).roundToInt().coerceIn(1, SAMPLES)

    /**
     * Cross-attention conditioning `[65, 768]`: the text encoder's `[64, 768]` output with the
     * padded positions zeroed (the T5 conditioner's `padding_mode="zero"`; non-finite values, which
     * 8-bit encoders occasionally return, are zeroed too), then the seconds embedding.
     */
    fun crossConditioning(text: FloatArray, mask: IntArray, seconds: FloatArray): FloatArray {
        require(text.size == TEXT_TOKENS * COND_DIM && mask.size == TEXT_TOKENS && seconds.size == COND_DIM) { "bad conditioning shapes" }
        val out = FloatArray(COND_TOKENS * COND_DIM)
        for (t in 0 until TEXT_TOKENS) {
            if (mask[t] == 0) continue
            for (c in 0 until COND_DIM) {
                val v = text[t * COND_DIM + c]
                out[t * COND_DIM + c] = if (v.isFinite()) v else 0f
            }
        }
        seconds.copyInto(out, TEXT_TOKENS * COND_DIM)
        return out
    }

    /**
     * The denoised latent at noise level [t] from the DiT's velocity: `x - t · v`. With guidance
     * ([cfg] ≠ 1 and an unconditional velocity, i.e. the prompt's conditioning zeroed), the
     * difference between the two predictions is applied as APG (`apg_scale=1`: only its component
     * orthogonal to the conditional prediction), as `DiffusionTransformer.forward`.
     */
    fun denoised(x: FloatArray, t: Float, velocity: FloatArray, uncondVelocity: FloatArray? = null, cfg: Float = 1f): FloatArray {
        val n = x.size
        val cond = FloatArray(n) { x[it] - t * velocity[it] }
        if (uncondVelocity == null || cfg == 1f) return cond
        val diff = DoubleArray(n) { (cond[it] - (x[it] - t * uncondVelocity[it])).toDouble() }
        var norm = 0.0
        for (v in cond) norm += v.toDouble() * v
        norm = sqrt(norm).coerceAtLeast(1e-12)
        var dot = 0.0
        for (i in 0 until n) dot += diff[i] * (cond[i] / norm)
        val scale = cfg - 1.0
        return FloatArray(n) { i -> (cond[i] + scale * (diff[i] - dot * cond[i] / norm)).toFloat() }
    }

    /**
     * One ping-pong step (`sample_flow_pingpong`): the next latent is the denoised one re-noised to
     * [tNext] with fresh noise; at the last step ([tNext] = 0) it is the denoised latent itself.
     */
    fun pingPong(denoised: FloatArray, tNext: Float, noise: GaussianNoise): FloatArray {
        if (tNext == 0f) return denoised
        return FloatArray(denoised.size) { (1 - tNext) * denoised[it] + tNext * noise.next() }
    }

    /**
     * Planar decoder output (`[2, 524288]`) as a clip of [seconds]: trimmed, a 5 ms fade-out at the
     * cut, and peak-normalized as Stability's examples do (`audio / max(|audio|)`), unless silent.
     */
    fun clip(decoded: FloatArray, seconds: Double, normalize: Boolean = true): StereoAudio {
        require(decoded.size == CHANNELS * SAMPLES) { "decoder output has ${decoded.size} samples" }
        val frames = frames(seconds)
        val left = decoded.copyOfRange(0, frames)
        val right = decoded.copyOfRange(SAMPLES, SAMPLES + frames)
        val fade = minOf(frames, SAMPLE_RATE / 200)
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            left[frames - 1 - i] *= g
            right[frames - 1 - i] *= g
        }
        if (normalize) {
            var peak = 0f
            for (v in left) if (v.isFinite()) peak = maxOf(peak, abs(v))
            for (v in right) if (v.isFinite()) peak = maxOf(peak, abs(v))
            if (peak > 1e-4f) {
                val g = 1f / peak
                for (i in 0 until frames) {
                    left[i] = (left[i] * g).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                    right[i] = (right[i] * g).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                }
            }
        }
        return StereoAudio(left, right)
    }
}

/**
 * Seeded standard normal noise, identical on both apps for the same seed: SplitMix64 → 53-bit
 * uniforms in (0, 1] → Box–Muller in double precision (cosine then sine of each pair). The
 * initial latent is the first 16 384 values in `[64, 256]` row-major order; each ping-pong step
 * continues the stream.
 */
class GaussianNoise(seed: Long) {
    private var state = seed
    private var spare = Double.NaN

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in (0, 1]. */
    fun nextUniform(): Double = ((nextLong() ushr 11) + 1).toDouble() * (1.0 / (1L shl 53))

    fun next(): Float {
        if (!spare.isNaN()) return spare.toFloat().also { spare = Double.NaN }
        val r = sqrt(-2.0 * ln(nextUniform()))
        val theta = 2.0 * PI * nextUniform()
        spare = r * sin(theta)
        return (r * cos(theta)).toFloat()
    }

    fun fill(size: Int): FloatArray = FloatArray(size) { next() }
}

/**
 * The `seconds_total` conditioner (`NumberEmbedder`: learned Fourier features, `[x, sin 2πxw,
 * cos 2πxw]`, then a 257 → 768 linear layer), from the ONNX bundle's `seconds_embedder.json`.
 * Core ML ships it as its own model instead.
 */
class SecondsEmbedder(
    private val fourier: FloatArray,
    /** `[768, 257]` row-major. */
    private val weight: FloatArray,
    private val bias: FloatArray,
    private val minValue: Double = StableAudio.SECONDS_MIN,
    private val maxValue: Double = StableAudio.SECONDS_MAX,
) {
    private val features = 2 * fourier.size + 1

    init {
        require(bias.size == StableAudio.COND_DIM && weight.size == bias.size * features) { "bad seconds embedder shapes" }
    }

    /** The `[768]` embedding of [seconds]. */
    fun embed(seconds: Double): FloatArray {
        val x = ((seconds.coerceIn(minValue, maxValue) - minValue) / (maxValue - minValue)).toFloat()
        val f = FloatArray(features)
        f[0] = x
        val half = fourier.size
        for (k in 0 until half) {
            val a = x * fourier[k] * 2f * PI.toFloat()
            f[1 + k] = sin(a)
            f[1 + half + k] = cos(a)
        }
        return FloatArray(bias.size) { o ->
            var s = bias[o]
            val row = o * features
            for (i in 0 until features) s += weight[row + i] * f[i]
            s
        }
    }

    companion object {
        const val FILE = "seconds_embedder.json"

        /** `{min_val, max_val, fourier_weights[128], linear_weight[768][257], linear_bias[768]}`. */
        fun parse(json: String): SecondsEmbedder {
            val o = Json.parseToJsonElement(json).jsonObject
            fun floats(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
            val weight = o.getValue("linear_weight").jsonArray.flatMap { row -> row.jsonArray.map { it.jsonPrimitive.float } }.toFloatArray()
            return SecondsEmbedder(
                floats("fourier_weights"), weight, floats("linear_bias"),
                o["min_val"]?.jsonPrimitive?.double ?: StableAudio.SECONDS_MIN, o["max_val"]?.jsonPrimitive?.double ?: StableAudio.SECONDS_MAX,
            )
        }

        fun load(path: String): SecondsEmbedder = parse(SystemFileSystem.read(path.toPath()) { readUtf8() })
    }
}

/** 44.1 kHz stereo float PCM, one array per channel. */
class StereoAudio(val left: FloatArray, val right: FloatArray, val sampleRate: Int = StableAudio.SAMPLE_RATE) {
    init {
        require(left.size == right.size) { "channels differ in length" }
    }

    val frames: Int get() = left.size
    val seconds: Double get() = frames.toDouble() / sampleRate

    /** Left/right interleaved. */
    fun interleaved(): FloatArray = FloatArray(frames * 2) { if (it % 2 == 0) left[it / 2] else right[it / 2] }
}
