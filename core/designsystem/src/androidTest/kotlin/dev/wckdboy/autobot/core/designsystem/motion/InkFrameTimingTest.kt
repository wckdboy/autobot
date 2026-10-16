package dev.wckdboy.autobot.core.designsystem.motion

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.DevelopingHalftone
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.HalftoneStrip
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.component.rememberInkAmbient
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.PaperTheme
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing of the animated screen harnesses on a device or emulator, via FrameMetrics:
 * frames rendered per second (every ink tick that lands on its own vsync costs a frame) and the
 * total-duration percentiles. Logs a table under the `InkFrames` tag; asserts nothing (emulator
 * timings are for before/after comparison on the same machine, not absolute budgets).
 */
@RunWith(AndroidJUnit4::class)
class InkFrameTimingTest {
    private fun measure(name: String, drive: (Int) -> Unit = {}, content: @Composable () -> Unit): String {
        val durations = ArrayList<Long>()
        val thread = HandlerThread("frame-metrics").apply { start() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { AutobotTheme(darkTheme = true) { content() } } }
            Thread.sleep(SETTLE_MILLIS)
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) synchronized(durations) { durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
            }
            scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
            val end = SystemClock.uptimeMillis() + RUN_MILLIS
            var i = 0
            while (SystemClock.uptimeMillis() < end) {
                val step = i++
                scenario.onActivity { drive(step) }
                Thread.sleep(STEP_MILLIS)
            }
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
        }
        thread.quitSafely()
        val ms = synchronized(durations) { durations.map { it / 1e6 }.sorted() }
        fun pct(p: Double) = if (ms.isEmpty()) 0.0 else ms[((ms.size - 1) * p).toInt()]
        val janky = ms.count { it > 16.7 }
        return String.format(
            Locale.ROOT,
            "%-30s %6.1f %7.2f %7.2f %7.2f %6d",
            name,
            ms.size * 1000.0 / RUN_MILLIS,
            pct(0.5),
            pct(0.9),
            pct(0.99),
            janky,
        )
    }

    @Test
    fun keyScreens() {
        val text = mutableStateOf("Sure, here is the plan. ")
        val downloads = mutableFloatStateOf(0.2f)
        val steps = mutableFloatStateOf(0.1f)
        val rows = listOf(
            measure("chat · streaming", { text.value += "word$it " }) { Chat(text) },
            measure("chat list (2 live)") { ChatList() },
            measure("models (3 downloads)", { if (it % 5 == 0) downloads.floatValue = (downloads.floatValue + 0.01f) % 1f }) { Models(downloads) },
            measure("imagine · generating", { if (it % 6 == 0) steps.floatValue = (steps.floatValue + 0.03f).coerceAtMost(0.95f) }) { Imagine(steps) },
            measure("home (ambient hero)") { Home() },
        )
        val table = buildString {
            appendLine(String.format(Locale.ROOT, "%-30s %6s %7s %7s %7s %6s", "screen", "fps", "p50 ms", "p90 ms", "p99 ms", ">16.7"))
            rows.forEach { appendLine(it) }
        }
        table.lines().forEach { Log.i("InkFrames", it) }
    }

    private companion object {
        const val SETTLE_MILLIS = 1500L
        const val RUN_MILLIS = 5000L
        const val STEP_MILLIS = 50L // ~20 tokens a second while streaming
    }
}

@Composable
private fun Chat(text: MutableState<String>) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "live") {
                Column {
                    ThinkingDisclosure("Planning the answer\nchecking the calendar", isThinking = true)
                    ChatBubble(BubbleRole.ASSISTANT, text.value.takeLast(1200), isStreaming = true)
                    Row { HalftoneStrip(cell = 3.dp); MicroLabel("composing") }
                }
            }
            items(30, key = { it }) { i ->
                Box(Modifier.inkEnter(false)) {
                    ChatBubble(if (i % 2 == 0) BubbleRole.USER else BubbleRole.ASSISTANT, "Message $i with **some** text and `code`.")
                }
            }
        }
        Row(Modifier.padding(8.dp)) {
            StatusDot(AutobotColors.Acid, live = true)
            PrimaryButton("Send", {}, Modifier.weight(1f), busy = true)
        }
    }
}

@Composable
private fun ChatList() {
    val dissolve = rememberInkDissolve()
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        items(40, key = { it }) { i ->
            Column(Modifier.inkDissolve(dissolve, i)) {
                RunRow("Chat number $i", "LOCAL · 12 MSG", leading = { StatusDot(AutobotColors.Acid, live = i < 2) })
            }
        }
    }
}

@Composable
private fun Models(progress: MutableFloatState) {
    val dissolve = rememberInkDissolve()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InkLoader()
        LazyColumn(Modifier.weight(1f)) {
            items(30, key = { it }) { i ->
                Column(Modifier.inkDissolve(dissolve, i).padding(12.dp)) {
                    Text("Model $i", Modifier.stamp(i % 3 == 0))
                    if (i < 3) InkProgressBar(progress.floatValue, Modifier.fillMaxWidth().height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun Imagine(progress: MutableFloatState) {
    PaperTheme {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            DevelopingHalftone(preview = null, modifier = Modifier.fillMaxWidth().aspectRatio(1f), progress = progress.floatValue)
            InkProgressBar(progress.floatValue, Modifier.fillMaxWidth().height(2.dp), state = InkBarState.Running)
            PrimaryButton("Generate", {}, Modifier.fillMaxWidth(), busy = true)
        }
    }
}

@Composable
private fun Home() {
    val ambient = rememberInkAmbient()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HalftoneField(Modifier.fillMaxWidth().height(260.dp), cell = 8.dp, ambient = ambient)
        for (i in 0 until 4) RunRow("Recent run $i", "IMAGE · 12 S")
    }
}
