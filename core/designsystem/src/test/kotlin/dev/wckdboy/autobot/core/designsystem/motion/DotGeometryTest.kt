package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The halftone grids' geometry: whole-px pitch, whole dots only, centred, half-pixel snapped. */
class DotGeometryTest {
    private val widths = listOf(1f, 7f, 37f, 96f, 97f, 240f, 333f, 361.5f, 720f, 1080f, 1441f)
    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)

    private fun assertAxis(a: DotAxis, length: Float, radius: Float, what: String, maximal: Boolean = true) {
        if (a.count == 0) return
        assertEquals("$what: pitch is whole px", floor(a.pitch), a.pitch, 0f)
        assertEquals("$what: centres on the half-pixel grid", 0f, (a.first * 2f) % 1f, 0f)
        val left = a.first - radius
        val right = length - (a.last + radius)
        assertTrue("$what: first dot clipped ($left)", left >= -1e-3f)
        assertTrue("$what: last dot clipped ($right)", right >= -1e-3f)
        assertTrue("$what: margins differ by ${abs(left - right)}", abs(left - right) <= 0.5f + 1e-3f)
        // Another dot would not have fitted.
        if (maximal) assertTrue("$what: room for one more dot", (a.count) * a.pitch + 2f * radius > length)
    }

    @Test
    fun axesFitWholeDotsCentred() {
        for (w in widths) for (pitch in listOf(1f, 4.4f, 10f, 16.8f, 29f)) {
            val r = pitch * 0.4f
            val a = DotGeometry.axis(w, pitch, r)
            assertAxis(a, w, r, "w=$w pitch=$pitch")
            if (w >= 2 * r) assertEquals(floor((w - 2 * r) / a.pitch).toInt() + 1, a.count)
        }
    }

    @Test
    fun barSlotsAreEvenCentredAndOnTheCentreLine() {
        for (d in densities) for (w in widths) for (hDp in listOf(2f, 3f, 4f, 6f, 8f)) {
            val h = hDp * d
            val bar = DotGeometry.bar(w * d, h, DotGeometry.BAR_MIN_DOT_DP * d)
            val swell = bar.full * (1f + Motion.barRunningWaveAmplitude)
            assertAxis(bar.slots, w * d, swell, "d=$d w=$w h=$hDp")
            assertEquals(h / 2f, bar.cy, 0f)
            if (bar.slots.count > 0) {
                assertTrue("dots never touch: ${2 * swell} vs ${bar.slots.pitch}", 2f * swell < bar.slots.pitch)
                assertEquals(maxOf(bar.full * Motion.barEmptyDotScale, 0.6f), bar.pin, 1e-6f)
            }
        }
    }

    @Test
    fun theFillFrontIsLinearAndRadiiFollowTheTokens() {
        val bar = DotGeometry.bar(1000f, 12f, 8f)
        val n = bar.slots.count
        // The front: a straight line in progress (no rounding jumps).
        for (k in 1..100) {
            val step = InkMath.barFront(k / 100f, n) - InkMath.barFront((k - 1) / 100f, n)
            assertEquals((n - 1 + InkMath.BAR_HEAD_SLOTS) / 100f, step, 1e-3f)
        }
        // Full: every dot a full circle; empty: none.
        for (i in 0 until n) assertEquals(bar.full, DotGeometry.barRadius(bar, i, 1f), 1e-4f)
        for (i in 0 until n) assertEquals(0f, DotGeometry.barRadius(bar, i, 0f), 0f)
        // The first filled dot starts at barFillStartScale of a full one (fully grown behind the head).
        val r0 = DotGeometry.barRadius(bar, 0, 0.5f)
        val ramp0 = Motion.barFillStartScale + (1f - Motion.barFillStartScale) * (0.5f / InkMath.barFront(0.5f, n))
        val done = 0.5f * 0.5f * 0.5f * 0.5f
        assertEquals(bar.full * (ramp0 + (1f - ramp0) * done), r0, 1e-3f)
        // The indeterminate wave has a wavelength of barWaveSlots slots.
        for (i in 0 until n - Motion.barWaveSlots) {
            assertEquals(DotGeometry.barWaveRadius(bar, i, 123f), DotGeometry.barWaveRadius(bar, i + Motion.barWaveSlots, 123f), 1e-3f)
        }
    }

    @Test
    fun loaderDotsFitTheirBoxAtEveryDensity() {
        for (d in densities) for ((cols, rows, pitchDp, hDp) in listOf(listOf(5f, 1f, 4.5f, 14f), listOf(5f, 1f, 5f, 16f), listOf(7f, 3f, 8f, 24f))) {
            val w = Math.round(pitchDp * cols * d).toFloat()
            val h = Math.round(hDp * d).toFloat()
            val l = DotGeometry.loader(w, h, pitchDp * d, cols.toInt(), rows.toInt(), 0.42f)
            assertAxis(l.x, w, l.full, "d=$d x", maximal = false)
            assertAxis(l.y, h, l.full, "d=$d y", maximal = false)
            assertEquals(cols.toInt(), l.x.count)
        }
    }

    @Test
    fun cardGridIsExactAndItsDotsNeverTouch() {
        for (d in densities) for ((w, h) in listOf(320f to 120f, 360f to 200f, 412f to 915f)) {
            val card = DotGeometry.card(w * d, h * d, Motion.cardDotPitch * d)
            assertAxis(card.x, w * d, card.x.pitch / 2f, "card d=$d x")
            assertAxis(card.y, h * d, card.y.pitch / 2f, "card d=$d y")
            assertEquals(card.x.pitch, card.y.pitch, 0f)
            assertTrue(card.x.count * card.y.count <= Motion.cardMaxDots)
            assertTrue("max diameter < pitch", 2 * card.x.pitch * DotGeometry.CARD_DOT < card.x.pitch)
        }
    }

    @Test
    fun cardWaveIsExactPeriodicAndContinuous() {
        val period = Motion.cardWavePeriodSeconds.toDouble()
        val base = 4.0
        for ((x, y) in listOf(0.0 to 0.0, 37.5 to 12.0, 300.0 to 90.0)) {
            for (t in listOf(0.0, 1.3, 7.0, 3_600.25)) {
                val a = DotGeometry.cardRadius(base, x, y, t, 352.0)
                assertEquals("one cycle per cardWavePeriodSeconds", a, DotGeometry.cardRadius(base, x, y, t + period, 352.0), 1e-9)
                val k = 2 * PI / 352.0
                val expected = base * (0.64 + 0.36 * sin(k * (x * 0.8660254037844386 + y * 0.5) - 2 * PI * (t % period) / period))
                assertEquals(expected, a, 1e-9)
                assertTrue("never below 0.28 of base", a >= 0.28 * base - 1e-9 && a <= base + 1e-9)
            }
            // Continuous: a frame (33 ms) never moves a radius by more than its rate allows.
            val maxRate = base * 0.36 * 2 * PI / period
            var t = 0.0
            while (t < 2 * period) {
                val step = abs(DotGeometry.cardRadius(base, x, y, t + 0.033, 352.0) - DotGeometry.cardRadius(base, x, y, t, 352.0))
                assertTrue(step <= maxRate * 0.033 + 1e-9)
                t += 0.5
            }
        }
    }

    @Test
    fun groundStaysInsideItsBox() {
        for (d in densities) for (w in listOf(200f, 360f, 412f)) {
            val g = DotGeometry.ground(w * d, 160f * d, 14f * d, 0.45f)
            assertAxis(g.x, w * d, g.x.pitch / 2f, "ground x")
            assertAxis(g.y, 160f * d, g.y.pitch / 2f, "ground y")
        }
    }
}
