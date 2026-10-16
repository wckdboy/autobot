package dev.wckdboy.autobot.core.designsystem.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderSize
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.inkCard
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import java.time.Duration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * The ink components rendered to pixels (Robolectric's native Skia) at several sizes and
 * densities. Dots are found as blobs of ink; each blob's coverage-weighted centroid and area
 * (r = √(area/π)) are compared with the geometry: centres on an exact pitch (spacing deviation
 * < 0.5 px), symmetric margins and vertical centring within 0.5 px, radii within 0.5 px of the
 * token formulas, and no dot cut by the bounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w2000dp-h2000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InkRasterTest {
    private class Blob(val x: Double, val y: Double, val r: Double)

    private val ink = Color(0xFFFF0000)

    /** Edge coverage above this means a dot is cut; a dot that only grazes the edge leaves a fainter fringe. */
    private val EDGE = 0.5
    private val densities = listOf(1f, 2f, 2.625f, 3.5f)

    /** Renders [content] at exactly [w]×[h] px with [density] and returns its pixels. */
    private fun render(w: Int, h: Int, density: Float, content: @Composable () -> Unit): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            key(w, h, density) {
                CompositionLocalProvider(LocalDensity provides Density(density), LocalInkMotion provides InkMotion(reduced = true)) {
                    Box(Modifier.size((w / density).dp, (h / density).dp)) { content() }
                }
            }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val root = composeRoot(activity.window.decorView) ?: error("no compose view")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.view.draw(Canvas(bitmap))
        controller.pause().stop().destroy()
        return bitmap
    }

    private fun composeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Red ink coverage per pixel, 0…1 of a dot drawn at [full] opacity. */
    private fun coverage(b: Bitmap, full: Double): Array<DoubleArray> {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return Array(b.height) { y -> DoubleArray(b.width) { x -> val c = px[y * b.width + x]; ((c ushr 24 and 0xFF) / 255.0) * ((c ushr 16 and 0xFF) / 255.0) / full } }
    }

    /** True when ink reaches the left/right edge (or, with [vertical], the top/bottom one). */
    private fun touchesEdge(cov: Array<DoubleArray>, vertical: Boolean = true): Boolean {
        val h = cov.size
        val w = cov[0].size
        // A dot that only grazes the edge leaves a faint antialiased fringe there; a cut one fills edge pixels.
        val sides = (0 until h).any { cov[it][0] > EDGE || cov[it][w - 1] > EDGE }
        val ends = vertical && (0 until w).any { cov[0][it] > EDGE || cov[h - 1][it] > EDGE }
        return sides || ends
    }

    /**
     * Each expected dot measured from the pixels of its own cell (every pixel goes to its nearest
     * expected centre): coverage-weighted centroid and r = √(area/π). Antialiased neighbours that
     * touch at small sizes are still told apart.
     */
    private fun measure(cov: Array<DoubleArray>, centres: List<Pair<Float, Float>>): List<Blob> {
        val sums = Array(centres.size) { DoubleArray(3) }
        for (y in cov.indices) for (x in cov[0].indices) {
            val c = cov[y][x]
            if (c < 0.01) continue
            val cx = x + 0.5
            val cy = y + 0.5
            var best = -1
            var bestD = Double.MAX_VALUE
            for ((i, e) in centres.withIndex()) {
                val d = (e.first - cx) * (e.first - cx) + (e.second - cy) * (e.second - cy)
                if (d < bestD) { bestD = d; best = i }
            }
            sums[best][0] += c
            sums[best][1] += c * cx
            sums[best][2] += c * cy
        }
        return sums.map { (a, sx, sy) -> if (a == 0.0) Blob(Double.NaN, Double.NaN, 0.0) else Blob(sx / a, sy / a, sqrt(a / PI)) }
    }

    /** Checks measured dots against expected centres and radii; returns how many were compared. */
    private fun check(what: String, b: Bitmap, full: Double, expected: List<Triple<Float, Float, Float>>, verticalEdges: Boolean = true): Int {
        val cov = coverage(b, full)
        assertTrue("$what: a dot touches the bounds", !touchesEdge(cov, verticalEdges))
        val found = measure(cov, expected.map { it.first to it.second })
        var compared = 0
        for ((i, e) in expected.withIndex()) {
            val (x, y, r) = e
            if (r < 1.5f) continue
            val blob = found[i]
            assertTrue("$what: centre (${blob.x}, ${blob.y}) vs ($x, $y), r=$r", abs(blob.x - x) < 0.5 && abs(blob.y - y) < 0.5)
            assertTrue("$what: radius ${blob.r} vs $r at ($x, $y)", abs(blob.r - r) < 0.5)
            compared++
        }
        return compared
    }

    /** Spacing deviation and margin symmetry of the measured centres of one row of equal dots. */
    private fun checkRow(what: String, b: Bitmap, full: Double, centres: List<Pair<Float, Float>>, length: Int) {
        val row = measure(coverage(b, full), centres)
        val gaps = row.zipWithNext { a, c -> c.x - a.x }
        val mean = gaps.average()
        val dev = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
        assertTrue("$what: spacing deviation $dev px", dev < 0.5)
        val left = row.first().x - row.first().r
        val right = length - row.last().x - row.last().r
        assertTrue("$what: margins $left vs $right", abs(left - right) < 0.5)
        row.forEach { assertTrue("$what: dot at y=${it.y}", abs(it.y - centres[0].second) < 0.5) }
    }

    @Test
    fun barsAreExactAtEveryProgressSizeAndDensity() {
        for (d in densities) for (wDp in listOf(120, 333)) for (hDp in listOf(3, 6)) {
            val w = Math.round(wDp * d)
            val h = Math.round(hDp * d)
            val bar = DotGeometry.bar(w.toFloat(), h.toFloat(), DotGeometry.BAR_MIN_DOT_DP * d)
            for (p in listOf(0.25f, 0.5f, 1f)) {
                val bitmap = render(w, h, d) { InkProgressBar(p, Modifier.size((w / d).dp, (h / d).dp), state = InkBarState.Idle, color = ink, track = Color.Transparent) }
                val expected = (0 until bar.slots.count).map { Triple(bar.slots.at(it), bar.cy, DotGeometry.barRadius(bar, it, p)) }
                val thin = bar.full * 2f >= h // the dot is as tall as the bar, or (min dot size) taller
                val n = check("bar d=$d ${wDp}x$hDp p=$p", bitmap, 1.0, expected, verticalEdges = !thin)
                assertTrue("bar d=$d p=$p compared nothing", n > 0)
                if (p == 1f && !thin) checkRow("bar d=$d ${wDp}x$hDp full", bitmap, 1.0, expected.map { it.first to it.second }, w)
            }
            // Indeterminate, at wave time 0 (reduced motion holds the clock).
            val wave = render(w, h, d) { InkProgressBar(null, Modifier.size((w / d).dp, (h / d).dp), color = ink, track = Color.Transparent) }
            val thin = bar.full * 2f >= h
            check("wave d=$d ${wDp}x$hDp", wave, 1.0, (0 until bar.slots.count).map { Triple(bar.slots.at(it), bar.cy, DotGeometry.barWaveRadius(bar, it, 0f)) }, verticalEdges = !thin)
        }
    }

    @Test
    fun loadersAreCentredInTheirBox() {
        for (d in densities) for (size in InkLoaderSize.entries) {
            val w = Math.round(size.pitch.value * size.cols * d)
            val h = Math.round(size.height.value * d)
            val g = DotGeometry.loader(w.toFloat(), h.toFloat(), size.pitch.value * d, size.cols, size.rows, 0.42f)
            val bitmap = render(w, h, d) { InkLoader(size = size, color = ink, contentDescription = null) }
            val expected = (0 until size.rows).flatMap { r ->
                (0 until size.cols).map { c -> Triple(g.x.at(c), g.y.at(r), g.full * InkMath.waveScale(InkMath.dotWave((c + r).toFloat(), 0f))) }
            }
            check("loader $size d=$d", bitmap, 1.0, expected)
        }
    }

    @Test
    fun livingCardsFollowTheWaveFormula() {
        val opacity = Motion.cardDotOpacity.toDouble()
        for (d in densities) for ((wDp, hDp) in listOf(320 to 120, 200 to 200)) {
            val w = Math.round(wDp * d)
            val h = Math.round(hDp * d)
            val card = DotGeometry.card(w.toFloat(), h.toFloat(), Motion.cardDotPitch * d)
            val bitmap = render(w, h, d) { Box(Modifier.size((w / d).dp, (h / d).dp).inkCard(dot = ink)) }
            val expected = (0 until card.y.count).flatMap { r ->
                (0 until card.x.count).map { c ->
                    val x = card.x.at(c)
                    val y = card.y.at(r)
                    val base = DotGeometry.cardBase(card.x.pitch, x, y, w.toFloat(), h.toFloat())
                    Triple(x, y, DotGeometry.cardRadius(base, x.toDouble(), y.toDouble(), 0.0, card.wavelength).toFloat())
                }
            }
            val n = check("card d=$d ${wDp}x$hDp", bitmap, opacity, expected)
            assertTrue("card d=$d compared only $n dots", n >= 10)
        }
    }

    @Test
    fun paperGroundStaysInsideItsBox() {
        for (d in densities) {
            val w = Math.round(320 * d)
            val h = Math.round(160 * d)
            val bitmap = render(w, h, d) { Box(Modifier.size((w / d).dp, (h / d).dp).inkGround(dot = ink, plate = Color.Transparent)) }
            assertTrue("ground d=$d: a dot is cut by the bounds", !touchesEdge(coverage(bitmap, Motion.ambientOpacity.toDouble())))
        }
    }
}
