package dev.wckdboy.autobot.core.designsystem.motion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.DotBatch
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderBlock
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderSize
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import java.io.File
import java.time.Duration
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
 * Renders the v2 ink states (loaders, bar states, busy buttons, segmented, ambient ground) in
 * dark, light, AMOLED and paper to `build/ink-snapshots/` at a few clock times, for looking at.
 * Asserts only that each frame drew something: the PNGs are for eyes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class InkSnapshotTest {
    private val out = File("build/ink-snapshots").apply { mkdirs() }

    @Test
    fun rendersKeyStatesInEveryTheme() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val themes: List<Pair<String, @Composable (@Composable () -> Unit) -> Unit>> = listOf(
            "dark" to { c -> AutobotTheme(darkTheme = true) { c() } },
            "light" to { c -> AutobotTheme(darkTheme = false) { c() } },
            "amoled" to { c -> AutobotTheme(darkTheme = true, amoled = true) { c() } },
            "paper" to { c -> AutobotTheme(darkTheme = true) { PaperTheme { c() } } },
        )
        for ((name, theme) in themes) {
            activity.setContent { theme { Sheet() } }
            var elapsed = 0L
            for (at in listOf(0L, 350L, 700L)) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(at - elapsed + 1))
                elapsed = at + 1
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                File(out, "ink-$name-${at}ms.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("$name drew", distinctColors(bitmap) > 3)
            }
        }
    }

    private fun distinctColors(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}

@Composable
private fun Sheet() {
    Column(
        // Top padding clears the bare activity's title bar.
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 64.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // One-shots are pinned to a moment (Robolectric's frame clock runs them to the end at once).
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Column(Modifier.misregisterPlate({ 0.1f }, MaterialTheme.colorScheme.primary, dark)) {
            Text("Pushed screen", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            MicroLabel("misregistration push · 10%")
        }
        MicroLabel("loaders · inline / button / block")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            InkLoader()
            InkLoader(size = InkLoaderSize.Button)
            InkLoader(size = InkLoaderSize.Block)
            InkLoaderBlock("decrypting")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Generate", {}, Modifier.weight(1f), busy = true)
            GhostButton("Test", {}, Modifier.weight(1f), busy = true)
        }
        MicroLabel("determinate · running 62%")
        InkProgressBar(0.62f, Modifier.fillMaxWidth().height(3.dp))
        MicroLabel("determinate · tall")
        InkProgressBar(0.4f, Modifier.fillMaxWidth().height(8.dp))
        MicroLabel("indeterminate")
        InkProgressBar(null, Modifier.fillMaxWidth().height(3.dp))
        MicroLabel("paused")
        InkProgressBar(0.35f, Modifier.fillMaxWidth().height(3.dp), state = InkBarState.Paused)
        MicroLabel("error")
        InkProgressBar(0.35f, Modifier.fillMaxWidth().height(3.dp), state = InkBarState.Error)
        Segmented(listOf("txt2img", "img2img", "inpaint"), "img2img", {}, { it }, Modifier.fillMaxWidth())
        for (t in listOf(0.3f, 0.6f)) {
            val batch = remember { DotBatch() }
            Text(
                "Row dissolving · ${(t * 100).toInt()}%",
                Modifier.dissolveAt(batch) { t }.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MicroLabel("below the dissolving rows")
        Box(Modifier.fillMaxWidth().height(160.dp).inkGround(), contentAlignment = Alignment.Center) {
            MicroLabel("nothing generated yet")
        }
    }
}
