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
 * The picture-in-progress panel rendered to pixels (Robolectric's native Skia) at several sizes
 * and densities: without a picture it is one flat colour (no halftones or dot fields). Progress
 * itself is Material's indicators.
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
