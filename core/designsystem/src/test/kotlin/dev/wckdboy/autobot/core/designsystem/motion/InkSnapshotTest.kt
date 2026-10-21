package dev.wckdboy.autobot.core.designsystem.motion

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.EmptyPicture
import dev.wckdboy.autobot.core.designsystem.component.InlineLoading
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.JobState
import dev.wckdboy.autobot.core.designsystem.component.Loading
import dev.wckdboy.autobot.core.designsystem.component.TitleWithDot
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the kit's moving states (loading indicators, progress states, busy buttons, segmented
 * buttons, a card, the empty picture) in dark, light, AMOLED and paper to `build/ink-snapshots/`
 * at a few clock times, for looking at. Asserts only that each frame drew something: the PNGs are
 * for eyes. The compose test clock steps the frames (Material's indicators animate forever).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InkSnapshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File("build/ink-snapshots").apply { mkdirs() }

    private fun render(name: String, theme: @Composable (@Composable () -> Unit) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { theme { Sheet() } }
        var elapsed = 0L
        for (at in listOf(0L, 300L, 600L)) {
            compose.mainClock.advanceTimeBy(at - elapsed + 16)
            elapsed = at + 16
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "ink-$name-${at}ms.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("$name drew", distinctColors(bitmap) > 3)
        }
    }

    @Test fun dark() = render("dark") { c -> AutobotTheme(darkTheme = true) { c() } }

    @Test fun light() = render("light") { c -> AutobotTheme(darkTheme = false) { c() } }

    @Test fun amoled() = render("amoled") { c -> AutobotTheme(darkTheme = true, amoled = true) { c() } }

    @Test fun paper() = render("paper") { c -> AutobotTheme(darkTheme = true) { PaperTheme { c() } } }

    private fun distinctColors(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}

@Composable
private fun Sheet() {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Loading · inline / block")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            InlineLoading()
            Loading(Modifier.weight(1f), caption = "Decrypting")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) { BusyLabel("Generate", busy = true) }
            OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { BusyLabel("Test", busy = true) }
        }
        for ((label, p) in listOf("empty" to 0f, "25%" to 0.25f, "60%" to 0.6f, "100%" to 1f)) {
            Label("Progress · $label")
            JobProgress(p, Modifier.fillMaxWidth(), state = if (p == 1f) JobState.Idle else JobState.Running)
        }
        Label("Progress · loading")
        JobProgress(null, Modifier.fillMaxWidth())
        Label("Progress · paused 40%")
        JobProgress(0.4f, Modifier.fillMaxWidth(), state = JobState.Paused)
        Label("Progress · error 70%")
        JobProgress(0.7f, Modifier.fillMaxWidth(), state = JobState.Error)
        Choices(listOf("txt2img", "img2img", "inpaint"), "img2img", {}, Modifier.fillMaxWidth()) { it }
        ElevatedCard(Modifier.fillMaxWidth().height(96.dp)) {
            Column(Modifier.padding(16.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) { TitleWithDot("A card") }
                Text("Material colour roles only", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        EmptyPicture(Modifier.fillMaxWidth().height(90.dp), hint = "Nothing generated yet")
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
