package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkMathV3Test {
    private val eps = 1e-4f

    @Test
    fun barSlotsFollowThePitch() {
        assertEquals(0, InkMath.barSlots(0f, 6f))
        assertEquals(0, InkMath.barSlots(100f, 0f))
        assertEquals("pitch = height × barDotPitch", (300f / (6f * Motion.barDotPitch)).toInt(), InkMath.barSlots(300f, 6f))
        assertEquals("at least one slot", 1, InkMath.barSlots(3f, 6f))
    }

    @Test
    fun emptyBarHasNoInkAndFullBarIsAllFullCircles() {
        val n = 40
        val empty = InkMath.barFront(0f, n)
        for (i in 0 until n) assertEquals(0f, InkMath.barFillScale(i, empty, 0f), eps)
        val full = InkMath.barFront(1f, n)
        for (i in 0 until n) assertEquals("slot $i", 1f, InkMath.barFillScale(i, full, 1f), eps)
    }

    @Test
    fun filledDotsRampFromSmallAtTheStartToFullAtTheHead() {
        val n = 40
        val p = 0.6f
        val front = InkMath.barFront(p, n)
        val first = InkMath.barFillScale(0, front, p)
        assertTrue("starts near barFillStartScale", first < Motion.barFillStartScale + 0.1f && first > Motion.barFillStartScale * 0.9f)
        // Fully grown dots grow towards the head.
        val grown = (0 until n).filter { front - it >= InkMath.BAR_HEAD_SLOTS }
        for (i in 1 until grown.size) assertTrue(InkMath.barFillScale(grown[i], front, p) >= InkMath.barFillScale(grown[i - 1], front, p))
        assertTrue("past the front: empty", InkMath.barFillScale(front.toInt() + 1, front, p) == 0f)
    }

    @Test
    fun growthIsContinuousAsProgressAdvances() {
        val n = 30
        var last = FloatArray(n) { 0f }
        var p = 0f
        while (p <= 1f) {
            val front = InkMath.barFront(p, n)
            val now = FloatArray(n) { InkMath.barFillScale(it, front, p) }
            // A step of 0.2% never pops a dot by more than a small fraction of its radius.
            for (i in 0 until n) assertTrue("slot $i at $p jumps ${now[i] - last[i]}", abs(now[i] - last[i]) < 0.1f)
            last = now
            p += 0.002f
        }
    }

    @Test
    fun waveTravelsTowardsTheHeadOncePerCycle() {
        val c = Motion.loaderCycleMillis.toFloat()
        val lambda = Motion.barWaveSlots.toFloat()
        assertEquals("one cycle", InkMath.dotWave(2f, 100f), InkMath.dotWave(2f, 100f + c), eps)
        assertEquals("one wavelength", InkMath.dotWave(1f, 300f), InkMath.dotWave(1f + lambda, 300f), eps)
        // The crest at slot 0 at t = 0 sits at slot λ/4 · … later: it moved to higher slots.
        val shift = lambda * 0.25f
        assertEquals(InkMath.dotWave(0f, 0f), InkMath.dotWave(shift, c * 0.25f), eps)
        assertTrue(InkMath.waveScale(0f) >= InkMath.WAVE_REST - eps && InkMath.waveScale(1f) == 1f)
        assertEquals(1f + Motion.barRunningWaveAmplitude, InkMath.runningWave(1f), eps)
        assertEquals(1f - Motion.barRunningWaveAmplitude, InkMath.runningWave(0f), eps)
    }

    @Test
    fun cardFieldIsCappedSlowAndStaysInRange() {
        val pitch = InkMath.cardPitch(1080f, 2000f, 30f)
        assertTrue(InkMath.dotCount(1080f, 2000f, pitch) <= Motion.cardMaxDots)
        assertEquals(0f, InkMath.cardTimePhase(0f), eps)
        assertEquals("one cycle per cardWavePeriodSeconds", 0f, InkMath.cardTimePhase(Motion.cardWavePeriodSeconds.toFloat()), eps)
        assertEquals("one wavelength per card width (along the wave)", 2f * Math.PI.toFloat() * 0.866f, InkMath.cardPhase(400f, 0f, 400f), 1e-3f)
        for (s in listOf(0f, 1f, 3f, 7.5f)) for (x in listOf(0f, 1f, 2f, 5f)) {
            val r = InkMath.cardDot(x, s)
            assertTrue(r in 0.1f..1f)
        }
        assertEquals(InkMath.CARD_FALLOFF_MIN, InkMath.cardFalloff(0f, 0f), eps)
        assertEquals(1f, InkMath.cardFalloff(1f, 1f), eps)
    }
}
