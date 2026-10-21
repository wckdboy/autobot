package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.EmptyPicture
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.JobState
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import java.io.File
import java.time.Duration
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/**
 * Animation cost of the key screens, measured on screen-shaped harnesses built from the same
 * design-system pieces the real screens use (the real screens take Hilt view models): over 2 s
 * of frames after the screen settles, how many recompose scopes ran, how many ink frame loops
 * were ticking and how many clock frames they produced. "idle" variants change no data, so
 * every recomposition there is caused by animation; the others stream data like the real thing.
 * Results go to stdout and `build/ink-perf.txt`; [assertions] holds the budget.
 *
 * Material's own indicators (LoadingIndicator, the wavy progress) are left out: they animate
 * forever on Material's clock, their cost is Material's, and under Robolectric's stepped frames
 * they never let the looper settle. Progress shows as the flat determinate bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY) // counts, not pixels: no native rendering per frame
@LooperMode(LooperMode.Mode.PAUSED)
class InkPerfTest {
    data class Result(val name: String, val scopes: Int, val compositions: Int, val loops: Int, val ticks: Int)

    private fun measure(name: String, onFrame: (Int) -> Unit = {}, content: @Composable () -> Unit): Result {
        // A 60 Hz vsync on the fake clock (frames would otherwise all land on one instant).
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent { AutobotTheme(darkTheme = true) { content() } }
        val looper = shadowOf(Looper.getMainLooper())
        repeat(30) { looper.idleFor(Duration.ofMillis(16)) } // settle: first frames, lazy items, one-shots
        val counter = RecompositionCounter.attach(activity.window.decorView)
        counter.reset()
        InkStats.reset()
        repeat(FRAMES) {
            onFrame(it)
            looper.idleFor(Duration.ofMillis(16))
        }
        val result = Result(name, counter.scopes, counter.compositions, InkStats.loops, InkStats.ticks)
        counter.close()
        controller.pause().stop().destroy()
        looper.idleFor(Duration.ofMillis(50))
        return result
    }

    @Test
    fun keyScreens() {
        val results = listOf(
            measure("chat · streaming (idle text)") { ChatHarness(mutableStateOf("Sure, here is the plan. ")) },
            mutableStateOf("Sure, here is the plan. ").let { text ->
                // ~20 tokens a second.
                measure("chat · streaming (20 tok/s)", { if (it % 3 == 0) text.value += "word$it " }) { ChatHarness(text) }
            },
            measure("chat list (2 live)") { ChatListHarness() },
            measure("models (3 downloads, idle)") { ModelsHarness(mutableFloatStateOf(0.2f)) },
            mutableFloatStateOf(0.2f).let { p ->
                measure("models (3 downloads, advancing)", { if (it % 15 == 0) p.floatValue += 0.01f }) { ModelsHarness(p) }
            },
            measure("gallery") { GalleryHarness() },
            measure("imagine · generating (idle)") { ImagineHarness(mutableFloatStateOf(0.1f)) },
            mutableFloatStateOf(0.1f).let { p ->
                // A sampling step every ~300 ms.
                measure("imagine · generating (steps)", { if (it % 18 == 0) p.floatValue += 0.05f }) { ImagineHarness(p) }
            },
            measure("home (ambient hero)") { HomeHarness(cards = false) },
            measure("home (hero + 3 living cards)") { HomeHarness(cards = true) },
        )
        val table = buildString {
            appendLine(String.format(Locale.ROOT, "%-34s %8s %8s %6s %8s", "screen (2 s @60 Hz)", "scopes", "passes", "loops", "ticks"))
            for (r in results) appendLine(String.format(Locale.ROOT, "%-34s %8d %8d %6d %8d", r.name, r.scopes, r.compositions, r.loops, r.ticks))
        }
        println(table)
        File("build").mkdirs()
        File("build/ink-perf.txt").writeText(table)
        assertions(results)
    }

    /** The budget: holds after the v3 performance pass. */
    private fun assertions(results: List<Result>) {
        for (r in results) {
            if (r.name.contains("idle") || r.name.startsWith("chat list") || r.name.startsWith("gallery") || r.name.startsWith("home")) {
                check(r.scopes == 0) { "${r.name}: animation recomposed ${r.scopes} scopes" }
            }
            check(r.loops <= 2) { "${r.name}: ${r.loops} frame loops (one per tier at most)" }
        }
    }

    private companion object {
        const val FRAMES = 125 // 2 s
    }
}

@Composable
private fun ChatHarness(text: MutableState<String>) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "live") {
                Column(Modifier.inkEnter(true)) {
                    ThinkingDisclosure("Planning the answer\nchecking the calendar")
                    ChatBubble(BubbleRole.ASSISTANT, text.value, isStreaming = true)
                    Text("composing", style = MaterialTheme.typography.labelMedium)
                }
            }
            items(30, key = { it }) { i ->
                Box(Modifier.inkEnter(false)) {
                    ChatBubble(if (i % 2 == 0) BubbleRole.USER else BubbleRole.ASSISTANT, "Message $i with **some** text and `code`.")
                }
            }
        }
        Row(Modifier.padding(8.dp)) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) { BusyLabel("Send", busy = false) }
        }
    }
}

@Composable
private fun ChatListHarness() {
    val dissolve = rememberInkDissolve()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        items(40, key = { it }) { i ->
            Column(Modifier.inkDissolve(dissolve, i)) {
                ListItem(
                    headlineContent = { Text("Chat number $i") },
                    supportingContent = { Text("Local · 12 messages") },
                    trailingContent = if (i < 2) {
                        { TonalLabel("live") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelsHarness(progress: MutableFloatState) {
    val dissolve = rememberInkDissolve()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Choices(listOf("library", "hugging face", "civitai"), "library", {}, Modifier.fillMaxWidth()) { it }
        LazyColumn(Modifier.weight(1f)) {
            items(30, key = { it }) { i ->
                Column(Modifier.inkDissolve(dissolve, i).padding(12.dp)) {
                    Text("Model $i", Modifier.stamp(i % 3 == 0))
                    if (i < 3) JobProgress(progress.floatValue, Modifier.fillMaxWidth(), state = JobState.Idle)
                }
            }
        }
    }
}

@Composable
private fun GalleryHarness() {
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        items(30) { i ->
            Box(Modifier.aspectRatio(1f).padding(2.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).stamp(i)) {
                if (i == 0) Text("decoding")
            }
        }
    }
}

@Composable
private fun ImagineHarness(progress: MutableFloatState) {
    PaperTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Choices(listOf("txt2img", "img2img", "inpaint"), "txt2img", {}, Modifier.fillMaxWidth()) { it }
            DevelopingPreview(null, Modifier.fillMaxWidth().aspectRatio(1f))
            JobProgress(progress.floatValue, Modifier.fillMaxWidth(), state = JobState.Idle)
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Generate") }
            for (i in 0 until 6) Text("Section $i", Modifier.inkStagger(i), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HomeHarness(cards: Boolean) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        EmptyPicture(Modifier.fillMaxWidth().height(220.dp), hint = "Nothing made yet")
        if (cards) {
            for (i in 0 until 3) {
                Box(Modifier.fillMaxWidth().height(110.dp).padding(4.dp).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    Text("Card $i")
                }
            }
        }
        for (i in 0 until 4) ListItem(headlineContent = { Text("Recent run $i") }, supportingContent = { Text("Image · 12 s") })
    }
}
