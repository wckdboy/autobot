package dev.wckdboy.autobot.shared.audiogen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StableAudioTest {
    @Test
    fun scheduleMatchesStableAudioTools() {
        // build_schedule(8, dist_shift=LogSNRShift(rate=0, anchor_logsnr=-6.2, logsnr_end=2.0)).
        val expected = floatArrayOf(1f, 0.9943756f, 0.9844802f, 0.9579123f, 0.8909032f, 0.7455466f, 0.5124974f, 0.273885f, 0f)
        assertArrayEquals(expected, StableAudio.schedule(8), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0.9844802f, 0.8909032f, 0.5124974f, 0f), StableAudio.schedule(4), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f), StableAudio.schedule(1), 0f)
    }

    @Test
    fun noiseIsSplitMix64BoxMuller() {
        val g = GaussianNoise(1234567)
        // SplitMix64's reference values.
        assertEquals(6457827717110365317L, g.nextLong())
        assertEquals(3203168211198807973L, g.nextLong())
        assertEquals(-8629252141511181193L, g.nextLong()) // 9817491932198370423 unsigned
        // Same stream on iOS (AudioGenEngineTests): generated with Python from the same definition.
        val expected = floatArrayOf(0.41471976f, 0.65268123f, -0.89188623f, 1.3268336f, 1.7295930f, -1.8834168f)
        assertArrayEquals(expected, GaussianNoise(42).fill(6), 1e-6f)
        val many = GaussianNoise(7).fill(200_000)
        val mean = many.average()
        val std = sqrt(many.sumOf { (it - mean) * (it - mean) } / many.size)
        assertEquals(0.0, mean, 0.01)
        assertEquals(1.0, std, 0.01)
    }

    @Test
    fun secondsAreNormalizedToTheConditionerRange() {
        assertEquals(10f / 256f, StableAudio.normalizedSeconds(10.0), 0f)
        assertEquals(0f, StableAudio.normalizedSeconds(-3.0), 0f)
        assertEquals(1f, StableAudio.normalizedSeconds(999.0), 0f)
        assertEquals(441_000, StableAudio.frames(10.0))
        assertEquals(StableAudio.SAMPLES, StableAudio.frames(60.0))
        assertEquals(StableAudio.SAMPLES, StableAudio.frames(Double.NaN))
        assertEquals(11.888, StableAudio.MAX_SECONDS, 0.001)
    }

    @Test
    fun secondsEmbedderIsFourierThenLinear() {
        // 2 Fourier weights → 5 features; output 768 with weight[o][i] = 1 for i == o % 5.
        val fourier = floatArrayOf(0.5f, 2f)
        val weight = FloatArray(768 * 5) { if (it % 5 == (it / 5) % 5) 1f else 0f }
        val bias = FloatArray(768) { 0.25f }
        val e = SecondsEmbedder(fourier, weight, bias).embed(64.0)
        val x = 0.25f
        val f = floatArrayOf(x, sin(2 * PI * x * 0.5).toFloat(), sin(2 * PI * x * 2).toFloat(), cos(2 * PI * x * 0.5).toFloat(), cos(2 * PI * x * 2).toFloat())
        for (o in 0 until 10) assertEquals(f[o % 5] + 0.25f, e[o], 1e-6f)
    }

    @Test
    fun secondsEmbedderParsesTheBundleJson() {
        val json = """{"min_val": 0.0, "max_val": 256.0, "fourier_weights": [1.0], "linear_weight": [${List(768) { "[0.0, 1.0, 0.0]" }.joinToString()}], "linear_bias": [${List(768) { "0.0" }.joinToString()}]}"""
        val e = SecondsEmbedder.parse(json).embed(32.0)
        assertEquals(sin(2 * PI * 0.125).toFloat(), e[0], 1e-6f)
    }

    @Test
    fun crossConditioningZeroesPaddingAndAppendsSeconds() {
        val text = FloatArray(64 * 768) { 1f }.also { it[5] = Float.NaN }
        val mask = IntArray(64) { if (it < 3) 1 else 0 }
        val seconds = FloatArray(768) { 2f }
        val c = StableAudio.crossConditioning(text, mask, seconds)
        assertEquals(65 * 768, c.size)
        assertEquals(1f, c[0], 0f)
        assertEquals(0f, c[5], 0f) // non-finite → 0
        assertEquals(1f, c[2 * 768 + 767], 0f)
        assertTrue((3 * 768 until 64 * 768).all { c[it] == 0f })
        assertTrue((64 * 768 until 65 * 768).all { c[it] == 2f })
    }

    @Test
    fun pingPongStepsFollowTheReference() {
        val x = floatArrayOf(1f, -2f, 0.5f)
        val v = floatArrayOf(0.5f, 0.5f, -1f)
        val d = StableAudio.denoised(x, 0.5f, v)
        assertArrayEquals(floatArrayOf(0.75f, -2.25f, 1f), d, 1e-6f)
        // Last step: the denoised latent itself.
        assertArrayEquals(d, StableAudio.pingPong(d, 0f, GaussianNoise(1)), 0f)
        val n = GaussianNoise(3).fill(3)
        val next = StableAudio.pingPong(d, 0.25f, GaussianNoise(3))
        for (i in 0 until 3) assertEquals(0.75f * d[i] + 0.25f * n[i], next[i], 1e-6f)
    }

    @Test
    fun guidanceIsAdaptiveProjected() {
        val x = floatArrayOf(1f, 1f, 0f)
        val t = 1f
        val vc = floatArrayOf(0f, 1f, 0f) // cond denoised (1, 0, 0)
        val vu = floatArrayOf(1f, 0f, -1f) // uncond denoised (0, 1, 1): diff (1, -1, -1)
        // APG keeps only the part of diff orthogonal to cond: (0, -1, -1).
        val d = StableAudio.denoised(x, t, vc, vu, cfg = 3f)
        assertArrayEquals(floatArrayOf(1f, -2f, -2f), d, 1e-6f)
        // cfg 1 or no unconditional branch: plain denoised.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), StableAudio.denoised(x, t, vc, vu, cfg = 1f), 0f)
    }

    @Test
    fun clipTrimsFadesAndNormalizes() {
        val decoded = FloatArray(2 * StableAudio.SAMPLES) { if (it < StableAudio.SAMPLES) 0.25f else -0.5f }
        val clip = StableAudio.clip(decoded, 1.0)
        assertEquals(44_100, clip.frames)
        assertEquals(0.5f, clip.left[0], 1e-6f)
        assertEquals(-1f, clip.right[100], 1e-6f)
        assertEquals(0f, clip.right[44_099], 1e-6f) // faded out at the cut
        val raw = StableAudio.clip(decoded, 1.0, normalize = false)
        assertEquals(0.25f, raw.left[0], 0f)
        val silent = StableAudio.clip(FloatArray(2 * StableAudio.SAMPLES), 2.0)
        assertTrue(silent.left.all { it == 0f })
    }

    /** Records what the pipeline asks of the models. */
    private class FakeModels : StableAudioModels {
        val ts = mutableListOf<Float>()
        var zeroCross = 0
        val globals = mutableListOf<Float>()
        var ids: IntArray? = null

        override fun encodeText(ids: IntArray, mask: IntArray): FloatArray {
            this.ids = ids
            return FloatArray(64 * 768) { 0.1f }
        }

        override fun embedSeconds(seconds: Double): FloatArray = FloatArray(768) { seconds.toFloat() }

        override fun velocity(latent: FloatArray, t: Float, cross: FloatArray, global: FloatArray): FloatArray {
            assertEquals(StableAudio.LATENT_SIZE, latent.size)
            assertEquals(65 * 768, cross.size)
            globals += global[0]
            if (cross.all { it == 0f }) zeroCross++ else ts += t
            return FloatArray(latent.size) { latent[it] * 0.5f }
        }

        override fun decode(latent: FloatArray): FloatArray = FloatArray(2 * StableAudio.SAMPLES) { (it % 100) / 100f - 0.5f }
    }

    private val tokenizer = T5Tokenizer(mapOf("▁drum" to (5253 to -9.0), "▁loop" to (6494 to -9.0)))

    @Test
    fun pipelineRunsEveryStepAndReportsProgress() = runTest {
        val models = FakeModels()
        val progress = mutableListOf<AudioGenProgress>()
        val audio = StableAudioPipeline(models, tokenizer).generate(AudioGenRequest("drum loop", seconds = 5.0, seed = 9), progress::add)
        assertArrayEquals(intArrayOf(5253, 6494, 1), models.ids!!.copyOf(3))
        assertArrayEquals(StableAudio.schedule(8).copyOf(8), models.ts.toFloatArray(), 0f)
        assertEquals(0, models.zeroCross)
        assertTrue(models.globals.all { it == 5f })
        assertEquals(5 * 44_100, audio.frames)
        assertEquals(AudioGenProgress.Stage.DONE, progress.last().stage)
        assertEquals((0..8).toList(), progress.filter { it.stage == AudioGenProgress.Stage.SAMPLING }.map { it.step })
        assertTrue(progress.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
    }

    @Test
    fun guidanceRunsTheUnconditionalBranch() = runTest {
        val models = FakeModels()
        StableAudioPipeline(models, tokenizer).generate(AudioGenRequest("drum", seconds = 5.0, steps = 4, cfg = 4f))
        assertEquals(4, models.ts.size)
        assertEquals(4, models.zeroCross)
    }

    @Test
    fun sameSeedSameAudio() = runTest {
        val a = StableAudioPipeline(FakeModels(), tokenizer).generate(AudioGenRequest("drum", seconds = 1.0, seed = 5))
        val b = StableAudioPipeline(FakeModels(), tokenizer).generate(AudioGenRequest("drum", seconds = 1.0, seed = 5))
        assertArrayEquals(a.left, b.left, 0f)
        assertFalse(a.left.any { abs(it) > 1f })
    }

    @Test
    fun cancellationStopsBetweenSteps() = runTest {
        val job = Job()
        var steps = 0
        val models = object : StableAudioModels by FakeModels() {
            override fun velocity(latent: FloatArray, t: Float, cross: FloatArray, global: FloatArray): FloatArray {
                if (++steps == 2) job.cancel()
                return FloatArray(latent.size)
            }
        }
        try {
            kotlinx.coroutines.withContext(job) { StableAudioPipeline(models, tokenizer).generate(AudioGenRequest("drum", seconds = 5.0)) }
            fail("not cancelled")
        } catch (_: CancellationException) {
        }
        assertEquals(2, steps)
    }

    @Test
    fun requestsAreValidated() {
        runCatching { AudioGenRequest("x", steps = 0) }.onSuccess { fail("steps 0 accepted") }
        runCatching { AudioGenRequest("x", cfg = -1f) }.onSuccess { fail("negative cfg accepted") }
    }

    @Test
    fun interleavesChannels() {
        val a = StereoAudio(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f))
        assertArrayEquals(floatArrayOf(1f, -1f, 2f, -2f), a.interleaved(), 0f)
        assertEquals(2.0 / 44_100, a.seconds, 1e-12)
    }
}
