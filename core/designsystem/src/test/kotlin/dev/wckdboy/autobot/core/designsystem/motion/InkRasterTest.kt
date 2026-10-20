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
import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderSize
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import java.time.Duration
import kotlin.math.abs
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
 * The progress components rendered to pixels (Robolectric's native Skia) at several sizes and
 * densities. They are flat (APP-UI v4: no halftones or dot fields): a bar is a track filled from
 * the start to the progress, within a pixel; a loader is three equal square pixels, centred in
 * its box; a preview panel without a picture is one flat colour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w2000dp-h2000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InkRasterTest {
    private val ink = Color(0xFFFF0000)
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

    /** The last column with ink in the middle row (-1 for none). */
    private fun inkedTo(cov: Array<DoubleArray>): Int {
        val row = cov[cov.size / 2]
        return row.indexOfLast { it > 0.5 }
    }

    @Test
    fun barsFillToTheProgressAtEverySizeAndDensity() {
        for (d in densities) for (wDp in listOf(120, 333)) for (hDp in listOf(2, 6)) {
            val w = Math.round(wDp * d)
            val h = Math.round(hDp * d)
            for (p in listOf(0f, 0.25f, 0.5f, 1f)) {
                val bitmap = render(w, h, d) { InkProgressBar(p, Modifier.size((w / d).dp, (h / d).dp), state = InkBarState.Idle, color = ink, track = Color.Transparent) }
                val cov = coverage(bitmap, 1.0)
                val head = inkedTo(cov) + 1
                assertTrue("bar d=$d ${wDp}x$hDp p=$p: filled to $head of $w", abs(head - p * w) <= 1.0)
                // Flat: every row of the bar is filled the same, top to bottom.
                if (p > 0f) for (y in cov.indices) assertTrue("bar d=$d p=$p row $y", cov[y][0] > 0.5 && cov[y][(head - 1).coerceAtLeast(0)] > 0.5)
            }
            val wave = render(w, h, d) { InkProgressBar(null, Modifier.size((w / d).dp, (h / d).dp), color = ink, track = Color.Transparent) }
            assertTrue("indeterminate d=$d ${wDp}x$hDp drew nothing at its start", inkedTo(coverage(wave, 1.0)) < w)
        }
    }

    @Test
    fun loadersAreThreeSquarePixelsCentredInTheirBox() {
        for (d in densities) for (size in InkLoaderSize.entries) {
            val w = Math.round(size.pitch.value * 3 * d)
            val h = Math.round(size.height.value * d)
            val bitmap = render(w, h, d) { InkLoader(size = size, color = ink, contentDescription = null) }
            val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            val inked = { x: Int, y: Int -> (px[y * w + x] ushr 24 and 0xFF) > 0 }
            // Columns with ink form three runs of the same width.
            val cols = (0 until w).map { x -> (0 until h).any { y -> inked(x, y) } }
            val runs = mutableListOf<IntRange>()
            var start = -1
            cols.forEachIndexed { x, on ->
                if (on && start < 0) start = x
                if (!on && start >= 0) { runs += start until x; start = -1 }
            }
            if (start >= 0) runs += start until w
            assertTrue("loader $size d=$d: ${runs.size} pixels", runs.size == 3)
            assertTrue("loader $size d=$d: unequal pixels $runs", runs.map { it.count() }.distinct().size == 1)
            val rows = (0 until h).filter { y -> (0 until w).any { x -> inked(x, y) } }
            assertTrue("loader $size d=$d: square", abs(rows.size - runs[0].count()) <= 1)
            assertTrue("loader $size d=$d: centred vertically", abs(rows.first() - (h - 1 - rows.last())) <= 1)
            assertTrue("loader $size d=$d: centred horizontally", abs(runs.first().first - (w - 1 - runs.last().last)) <= 1)
        }
    }

    @Test
    fun previewWithoutAPictureIsFlat() {
        for (d in densities) {
            val w = Math.round(170 * d)
            val h = Math.round(120 * d)
            val bitmap = render(w, h, d) { DevelopingPreview(null, Modifier.size((w / d).dp, (h / d).dp)) }
            val px = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
            assertTrue("preview d=$d: ${px.distinct().size} colours", px.distinct().size == 1)
        }
    }
}
