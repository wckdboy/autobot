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
    fun ambientGroundIsSlowAndBreathes() {
        assertEquals(0f, InkMath.ambientPhase(0f), eps)
        assertEquals("one wave cycle per ambientCycleSeconds", 2f, InkMath.ambientPhase(Motion.ambientCycleSeconds.toFloat()), eps)
        assertTrue("one cycle takes 40–60 s", Motion.ambientCycleSeconds in 40..60)
        assertEquals(0f, InkMath.ambientBreath(0f), eps)
        assertEquals(1f, InkMath.ambientBreath(Motion.ambientBreathSeconds / 4f), eps)
        assertEquals(-1f, InkMath.ambientBreath(Motion.ambientBreathSeconds * 3 / 4f), eps)
        assertTrue("≤5 fps", Motion.ambientFrameIntervalMillis >= 200)
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
        assertTrue("≤240 ms", Motion.transitionMillis <= 240)
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
