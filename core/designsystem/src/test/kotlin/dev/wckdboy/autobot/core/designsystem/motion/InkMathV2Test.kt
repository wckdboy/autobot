package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motion spec v2: loaders, bars, ambient grounds, transitions, dissolve. */
class InkMathV2Test {
    private val eps = 1e-4f

    @Test
    fun easeInOutIsSymmetric() {
        assertEquals(0f, InkMath.easeInOutCubic(0f), eps)
        assertEquals(0.5f, InkMath.easeInOutCubic(0.5f), eps)
        assertEquals(1f, InkMath.easeInOutCubic(1f), eps)
        for (t in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(1f - InkMath.easeInOutCubic(1f - t), InkMath.easeInOutCubic(t), eps)
        }
        assertTrue("ease-in starts slow", InkMath.easeInCubic(0.5f) < 0.5f)
        assertEquals(1f, InkMath.easeInCubic(2f), eps)
    }

    @Test
    fun cycleWraps() {
        assertEquals(0f, InkMath.cycle(0f, 1000), eps)
        assertEquals(0.25f, InkMath.cycle(1250f, 1000), eps)
        assertEquals(0.75f, InkMath.cycle(-250f, 1000), eps)
        assertEquals(0f, InkMath.cycle(500f, 0), eps)
    }

    @Test
    fun loaderDropCrossesTheSquareEachCycleWithoutASeam() {
        val c = Motion.loaderCycleMillis.toFloat()
        val start = InkMath.loaderDrop(0f)
        val end = InkMath.loaderDrop(c - 0.01f)
        assertTrue("starts off the plate", start < 0f)
        assertTrue("ends off the plate", end > 1f)
        assertEquals("loops", start, InkMath.loaderDrop(c), eps)
        // moves monotonically across one cycle
        var last = Float.NEGATIVE_INFINITY
        for (i in 0..20) {
            val d = InkMath.loaderDrop(c * i / 21f)
            assertTrue(d >= last)
            last = d
        }
        // off the plate every dot rests at nearly the same size, so the loop seam is invisible
        val restA = InkMath.loaderSwell(0f, start)
        val restB = InkMath.loaderSwell(1f, end)
        assertTrue(restA < 0.7f && restB < 0.7f)
    }

    @Test
    fun loaderDotsSwellAsTheDropPasses() {
        assertEquals(1f, InkMath.loaderSwell(0.4f, 0.4f), eps)
        assertTrue(InkMath.loaderSwell(0.6f, 0.4f) < 1f)
        assertTrue(InkMath.loaderSwell(0.6f, 0.4f) > InkMath.loaderSwell(0.9f, 0.4f))
        assertTrue(InkMath.loaderSwell(0f, 5f) >= InkMath.LOADER_REST - eps)
        assertEquals(0f, InkMath.loaderDiagonal(0, 0, 4), eps)
        assertEquals(1f, InkMath.loaderDiagonal(3, 3, 4), eps)
        assertEquals(0.5f, InkMath.loaderDiagonal(3, 0, 4), eps)
        assertEquals(0.5f, InkMath.loaderDiagonal(0, 0, 1), eps)
    }

    @Test
    fun barIsDensestAtTheHead() {
        assertEquals(InkMath.BAR_TAIL_DENSITY, InkMath.barDensity(0f), eps)
        assertEquals(1f, InkMath.barDensity(1f), eps)
        assertTrue(InkMath.barDensity(0.5f) < InkMath.barDensity(0.9f))
    }

    @Test
    fun indeterminateSweepCrossesTheTrackAndTrailFades() {
        val s = Motion.indeterminateSweepMillis.toFloat()
        assertEquals(0f, InkMath.sweepHead(0f), eps)
        assertTrue("ends with the whole segment past the end", InkMath.sweepHead(s - 0.01f) > 1f + InkMath.SWEEP_SEGMENT - 0.01f)
        assertEquals("loops", 0f, InkMath.sweepHead(s), eps)
        assertTrue("eases in", InkMath.sweepHead(s * 0.1f) < (1f + InkMath.SWEEP_SEGMENT) * 0.1f)
        assertTrue(InkMath.sweepTrail(0, 6) > InkMath.sweepTrail(5, 6))
        assertTrue(InkMath.sweepTrail(5, 6) > 0f)
        assertEquals(0f, InkMath.sweepTrail(6, 6), eps)
        assertEquals(0f, InkMath.sweepTrail(0, 0), eps)
    }

    @Test
    fun ambientGroundIsSlowAndBreathes() {
        assertEquals(0f, InkMath.ambientPhase(0f), eps)
        assertEquals("one wave cycle per ambientCycleSeconds", 2f, InkMath.ambientPhase(Motion.ambientCycleSeconds.toFloat()), eps)
        assertTrue("one cycle takes 40–60 s", Motion.ambientCycleSeconds in 40..60)
        assertEquals(0f, InkMath.ambientBreath(0f), eps)
        assertEquals(1f, InkMath.ambientBreath(Motion.ambientBreathSeconds / 4f), eps)
        assertEquals(-1f, InkMath.ambientBreath(Motion.ambientBreathSeconds * 3 / 4f), eps)
        assertTrue("≤10 fps", Motion.ambientFrameIntervalMillis >= 100)
    }

    @Test
    fun ambientGroundStopsForEveryReason() {
        assertTrue(InkMath.ambientRuns(reduced = false, lowPower = false, heavyWork = false, idle = false))
        assertFalse(InkMath.ambientRuns(reduced = true, lowPower = false, heavyWork = false, idle = false))
        assertFalse(InkMath.ambientRuns(reduced = false, lowPower = true, heavyWork = false, idle = false))
        assertFalse(InkMath.ambientRuns(reduced = false, lowPower = false, heavyWork = true, idle = false))
        assertFalse(InkMath.ambientRuns(reduced = false, lowPower = false, heavyWork = false, idle = true))
        val stop = Motion.ambientIdleStopSeconds * 1000L
        assertEquals(stop, InkMath.ambientIdleRemainingMillis(1_000L, 1_000L))
        assertEquals(stop - 500, InkMath.ambientIdleRemainingMillis(1_000L, 1_500L))
        assertEquals(0L, InkMath.ambientIdleRemainingMillis(1_000L, 1_000L + stop))
        assertEquals(0L, InkMath.ambientIdleRemainingMillis(0L, 10 * stop))
    }

    @Test
    fun misregistrationSettlesAndClears() {
        assertEquals(1f, InkMath.misregisterOffset(0f), eps)
        assertEquals(0f, InkMath.misregisterOffset(1f), eps)
        assertEquals(InkMath.MISREGISTER_ALPHA, InkMath.misregisterAlpha(0f), eps)
        assertEquals(0f, InkMath.misregisterAlpha(1f), eps)
        assertTrue("≤240 ms", Motion.transitionMillis <= 240)
    }

    @Test
    fun plateMatrixInksTheMarksNotThePaper() {
        fun alpha(m: FloatArray, lum: Float, a: Float = 1f): Float {
            val v = lum * 255f
            return ((m[15] + m[16] + m[17]) * v + m[18] * a * 255f + m[19]).coerceIn(0f, 255f) / 255f
        }
        val dark = InkMath.inkPlateMatrix(1f, 0.2f, 0.1f, darkTheme = true)
        assertEquals("plate colour", 255f, dark[4], eps)
        assertEquals(0.2f * 255f, dark[9], eps)
        assertEquals("black paper stays clear", 0f, alpha(dark, 0.04f), eps)
        assertEquals("white text prints", 1f, alpha(dark, 1f), eps)
        val light = InkMath.inkPlateMatrix(1f, 0.2f, 0.1f, darkTheme = false)
        assertEquals("cream paper stays clear", 0f, alpha(light, 0.95f), eps)
        assertEquals("black text prints", 1f, alpha(light, 0f), eps)
        assertEquals("transparent never prints (light)", 0f, alpha(light, 0f, a = 0f), eps)
        assertEquals("transparent never prints (dark)", 0f, alpha(dark, 0f, a = 0f), eps)
    }

    @Test
    fun staggerStepsAndCaps() {
        assertEquals(0, InkMath.staggerDelayMillis(0))
        assertEquals(Motion.staggerMillis, InkMath.staggerDelayMillis(1))
        assertEquals(5 * Motion.staggerMillis, InkMath.staggerDelayMillis(5))
        assertEquals("max six sections", 5 * Motion.staggerMillis, InkMath.staggerDelayMillis(12))
        assertEquals(0, InkMath.staggerDelayMillis(-3))
    }

    @Test
    fun ghostLastsOneFrame() {
        assertTrue(InkMath.ghostVisible(0f))
        assertTrue(InkMath.ghostVisible(Motion.frameIntervalMillis - 1f))
        assertFalse(InkMath.ghostVisible(Motion.frameIntervalMillis.toFloat()))
        assertFalse(InkMath.ghostVisible(-1f))
    }

    @Test
    fun dissolveShrinksDotsToNothingAndCollapses() {
        for (x in listOf(0f, 0.5f, 1f)) {
            assertEquals(1f, InkMath.dissolveDot(0f, x, x), eps)
            assertEquals(0f, InkMath.dissolveDot(1f, x, x), eps)
        }
        assertTrue("staggered along the diagonal", InkMath.dissolveDot(0.4f, 0f, 0f) < InkMath.dissolveDot(0.4f, 1f, 1f))
        assertEquals(1f, InkMath.dissolveHeight(0f), eps)
        assertEquals("holds height while the dots go", 1f, InkMath.dissolveHeight(InkMath.DISSOLVE_COLLAPSE_START), eps)
        assertEquals(0f, InkMath.dissolveHeight(1f), eps)
    }
}
