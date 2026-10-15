package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMathTest {
    private val eps = 1e-4f

    @Test
    fun easingsHitTheirEnds() {
        assertEquals(0f, InkMath.easeOutCubic(0f), eps)
        assertEquals(1f, InkMath.easeOutCubic(1f), eps)
        assertEquals(1f, InkMath.easeOutCubic(3f), eps)
        assertEquals(0f, InkMath.easeOutCubic(-1f), eps)
        assertTrue("ease-out is ahead of linear", InkMath.easeOutCubic(0.5f) > 0.5f)
        assertEquals(0f, InkMath.easeOutBack(0f), eps)
        assertEquals(1f, InkMath.easeOutBack(1f), eps)
    }

    @Test
    fun easeOutBackOvershootsThenSettles() {
        val peak = (1..99).maxOf { InkMath.easeOutBack(it / 100f) }
        assertTrue("overshoots past 1", peak > 1.05f)
        assertTrue("but only slightly", peak < 1.15f)
        // so a register mark crosses zero offset and comes back
        assertTrue((1..99).any { InkMath.registerOffset(it / 100f * Motion.registerMillis) < 0f })
        assertEquals(1f, InkMath.registerOffset(0f), eps)
        assertEquals(0f, InkMath.registerOffset(Motion.registerMillis.toFloat()), eps)
    }

    @Test
    fun staggerWashesAlongTheDiagonal() {
        val s = Motion.developStagger
        // top-left starts at once, bottom-right only after the stagger
        assertEquals(0.5f / (1f - s), InkMath.staggered(0.5f, 0f, 0f, s), eps)
        assertEquals(0f, InkMath.staggered(0.5f, 1f, 1f, s), eps)
        assertEquals(0f, InkMath.staggered(0f, 0f, 0f, s), eps)
        // every dot is done at t = 1
        for (x in listOf(0f, 0.3f, 1f)) for (y in listOf(0f, 0.7f, 1f)) assertEquals(1f, InkMath.staggered(1f, x, y, s), eps)
        // monotonic along the diagonal at a fixed time
        val a = InkMath.staggered(0.6f, 0.1f, 0.1f, s)
        val b = InkMath.staggered(0.6f, 0.5f, 0.5f, s)
        val c = InkMath.staggered(0.6f, 0.9f, 0.9f, s)
        assertTrue(a >= b && b >= c)
        // no stagger is plain time
        assertEquals(0.4f, InkMath.staggered(0.4f, 0.8f, 0.2f, 0f), eps)
        assertEquals(1f, InkMath.staggered(1f, 0.5f, 0.5f, 1f), eps)
    }

    @Test
    fun fillRisesFromMinimumToFull() {
        assertEquals(Motion.flowMinFill, InkMath.flowFill(0f), eps)
        assertEquals(1f, InkMath.flowFill(1f), eps)
        assertEquals(1f, InkMath.flowFill(4f), eps)
        assertTrue(InkMath.flowFill(0.5f) in Motion.flowMinFill..1f)
        assertTrue("loading is lighter", InkMath.flowFill(0f, loading = true) < Motion.flowMinFill)
    }

    @Test
    fun frameIntervalFollowsBatterySaver() {
        assertEquals(Motion.frameIntervalMillis, InkMath.frameIntervalMillis(lowPower = false))
        assertEquals(Motion.lowPowerFrameIntervalMillis, InkMath.frameIntervalMillis(lowPower = true))
    }

    @Test
    fun framesAreThrottledToTheInterval() {
        val ms = 1_000_000L
        assertTrue(InkMath.isFrameDue(-1, 0, 33))
        assertFalse(InkMath.isFrameDue(0, 16_667_000, 33))
        assertTrue("60 Hz: every second vsync", InkMath.isFrameDue(0, 33_333_000, 33))
        assertFalse("120 Hz: not the third vsync", InkMath.isFrameDue(0, 25_000_000, 33))
        assertTrue("120 Hz: the fourth", InkMath.isFrameDue(0, 33_333_000, 33))
        assertFalse(InkMath.isFrameDue(0, 50 * ms, 66))
        assertTrue(InkMath.isFrameDue(0, 66_667_000, 66))
        // the clock never sleeps past the next due vsync
        assertTrue(InkMath.idleMillis(33) + 16 <= 33)
        assertTrue(InkMath.idleMillis(66) + 16 <= 66)
        assertEquals(0L, InkMath.idleMillis(8))
    }

    @Test
    fun simulatedClockRunsAtThirtyFpsOnAnyDisplay() {
        for (hz in listOf(60, 90, 120)) {
            val vsync = 1_000_000_000L / hz
            var last = -1L
            var frames = 0
            var now = 0L
            var sleepUntil = 0L
            while (now < 1_000_000_000L) {
                if (now >= sleepUntil && InkMath.isFrameDue(last, now, Motion.frameIntervalMillis)) {
                    last = now
                    frames++
                    sleepUntil = now + InkMath.idleMillis(Motion.frameIntervalMillis) * 1_000_000L
                }
                now += vsync
            }
            assertTrue("$hz Hz gave $frames frames", frames in 28..31)
        }
    }

    @Test
    fun cellGrowsToCapTheDots() {
        assertEquals(10f, InkMath.cappedCell(200f, 200f, 10f), eps)
        val c = InkMath.cappedCell(1440f, 1440f, 6f)
        assertTrue(c > 6f)
        assertTrue(InkMath.dotCount(1440f, 1440f, c) <= InkMath.MAX_DOTS)
        assertEquals(1f, InkMath.cappedCell(0f, 100f, 0f), eps)
    }

    @Test
    fun printEndsAsThePlainImage() {
        val end = Motion.printMillis.toFloat()
        assertEquals(1f, InkMath.printMerge(0f), eps)
        assertEquals(Motion.printMergeScale, InkMath.printMerge(end), eps)
        assertEquals(0f, InkMath.printImageAlpha(0f), eps)
        assertEquals(0f, InkMath.printImageAlpha(end / 2), eps)
        assertEquals(1f, InkMath.printImageAlpha(end), eps)
        assertEquals(1f, InkMath.printDotsAlpha(0f), eps)
        assertEquals(0f, InkMath.printDotsAlpha(end), eps)
        assertEquals(1f, InkMath.plateAlpha(Motion.registerMillis.toFloat()), eps)
        assertEquals(0f, InkMath.plateAlpha(end), eps)
    }

    @Test
    fun stampAndEnterSettle() {
        assertEquals(Motion.stampScale, InkMath.stampScale(0f), eps)
        assertEquals(Motion.stampRotationDegrees, InkMath.stampRotation(0f), eps)
        assertEquals(0f, InkMath.stampAlpha(0f), eps)
        assertEquals(1f, InkMath.stampScale(1f), eps)
        assertEquals(0f, InkMath.stampRotation(1f), eps)
        assertEquals(1f, InkMath.stampAlpha(1f), eps)
        assertEquals(1f, InkMath.enterRise(0f), eps)
        assertEquals(0f, InkMath.enterRise(1f), eps)
        assertEquals(1f, InkMath.enterAlpha(1f), eps)
    }

    @Test
    fun pulseSwingsBetweenMinAndFull() {
        assertEquals(1f, InkMath.pulseAlpha(0f), eps)
        assertEquals(Motion.statusDotPulseMinAlpha, InkMath.pulseAlpha(Motion.statusDotPulseMillis.toFloat()), eps)
        assertEquals(1f, InkMath.pulseAlpha(2f * Motion.statusDotPulseMillis), eps)
    }

    @Test
    fun flowPhaseIntegratesSpeedWithoutJumps() {
        val flow = FlowPhase()
        assertEquals(0f, flow.at(0f, 1f), eps)
        assertEquals(2f * Motion.flowCyclesPerSecond, flow.at(1f, 1f), eps)
        assertEquals("same time, same phase", 2f * Motion.flowCyclesPerSecond, flow.at(1f, 1f), eps)
        val before = flow.phase
        flow.at(2f, InkMath.SHIMMER_SPEED)
        assertEquals(before + 2f * Motion.flowCyclesPerSecond * InkMath.SHIMMER_SPEED, flow.phase, eps)
    }

    @Test
    fun tweenRetargetsFromWhereItIs() {
        val tween = InkTween(0f, 1f)
        tween.retarget(1f, now = 0f)
        assertEquals(0f, tween.value(0f), eps)
        val mid = tween.value(0.5f)
        assertTrue(mid > 0.5f && mid < 1f)
        tween.retarget(0f, now = 0.5f)
        assertEquals("no jump on retarget", mid, tween.value(0.5f), eps)
        assertEquals(0f, tween.value(2f), eps)
        tween.snap(0.3f)
        assertEquals(0.3f, tween.value(5f), eps)
    }

    @Test
    fun tweenRowEasesEachSlot() {
        val row = InkTweenRow(3, 0.1f)
        row.retarget(floatArrayOf(1f, 0.5f), now = 0f)
        assertEquals(0f, row.value(0, 0f), eps)
        assertEquals(1f, row.value(0, 0.1f), eps)
        assertEquals(0.5f, row.value(1, 0.2f), eps)
        assertEquals("missing targets are 0", 0f, row.value(2, 0.2f), eps)
        row.retarget(floatArrayOf(0f, 0.5f, 1f), now = 0.2f)
        assertEquals(1f, row.value(0, 0.2f), eps)
        assertEquals(0f, row.value(0, 0.3f), eps)
    }
}
