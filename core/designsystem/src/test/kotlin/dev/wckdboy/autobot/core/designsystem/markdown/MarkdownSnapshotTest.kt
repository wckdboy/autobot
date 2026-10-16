package dev.wckdboy.autobot.core.designsystem.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
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
 * Renders a rich sample answer (headings, lists, tasks, a quote, a table, code, links, an image
 * that is never loaded) in dark and light to `build/markdown-snapshots/`, for looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1400dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MarkdownSnapshotTest {
    private val out = File("build/markdown-snapshots").apply { mkdirs() }

    @Test
    fun rendersARichAnswerInBothThemes() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        for (dark in listOf(true, false)) {
            activity.setContent {
                AutobotTheme(darkTheme = dark) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 16.dp, end = 16.dp, top = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ChatBubble(BubbleRole.USER, "When is the next train to **Berlin**?")
                        ChatBubble(BubbleRole.ASSISTANT, SAMPLE)
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val name = if (dark) "dark" else "light"
            File(out, "markdown-android-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bitmap.width > 0)
        }
    }

    companion object {
        val SAMPLE = """
            ## Next train to Berlin

            It arrives at **17:25** — the *ICE 527* from platform ~~4~~ **6**. Book on [bahn.de](https://int.bahn.de/en) or the [old site](http://bahn.example/old).

            1. Buy a ticket in the app.
            2. Validate it:
               - on the platform, or
               - on board (costs `€2` extra)
            3. Board the train.

            - [x] Seat reserved
            - [ ] Bike ticket

            > Evening trains are busy: book a seat.

            | Train | Departs | Price |
            |:------|:-------:|------:|
            | ICE 527 | 17:25 | €49.90 |
            | RE 7 | 18:02 | €12.00 |

            ```kotlin
            fun fare(km: Int) = km * 0.18
            ```

            ### Map
            ![Route map](https://maps.example/route.png) · energy ${'$'}E = mc^2${'$'}, snake_case_kept.

            ---
            Sources: <https://example.com/timetable>
        """.trimIndent()
    }
}
