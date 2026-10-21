package dev.wckdboy.autobot.core.designsystem.motion

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.JobState
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer

/**
 * The kit's components with motion of their own (the thinking disclosure, the progress bar, a
 * button's busy label) and the ink motion modifiers, each alone under a parent: over 1 s of
 * frames nothing recomposes, neither the parent nor anything else. A control that reads the ink
 * clock during composition proves the harness would see recompositions if there were any.
 * Material's endless indicators are left out: under Robolectric's stepped frames they never let
 * the looper settle (see InkPerfTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class InkComponentRecompositionTest {
    private class Measured(val parent: Int, val scopes: Int, val ticks: Int)

    private var parentCompositions = 0

    @Composable
    private fun Parent(content: @Composable () -> Unit) {
        SideEffect { parentCompositions++ }
        Column { content() }
    }

    private fun measure(content: @Composable () -> Unit): Measured {
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { AutobotTheme(darkTheme = true) { Parent(content) } }
        val looper = shadowOf(Looper.getMainLooper())
        repeat(30) { looper.idleFor(Duration.ofMillis(16)) }
        val counter = RecompositionCounter.attach(controller.get().window.decorView)
        counter.reset()
        parentCompositions = 0
        InkStats.reset()
        repeat(FRAMES) { looper.idleFor(Duration.ofMillis(16)) }
        val result = Measured(parentCompositions, counter.scopes, InkStats.ticks)
        counter.close()
        controller.pause().stop().destroy()
        looper.idleFor(Duration.ofMillis(50))
        return result
    }

    @Test
    fun animationTicksRedrawWithoutRecomposing() {
        val components: List<Pair<String, @Composable () -> Unit>> = listOf(
            "JobProgress(paused 40%)" to { JobProgress(0.4f, Modifier.fillMaxWidth(), state = JobState.Paused) },
            "Button(BusyLabel idle)" to { Button(onClick = {}) { BusyLabel("Send", busy = false) } },
            "ThinkingDisclosure(thought)" to { ThinkingDisclosure("Planning\nchecking the calendar", tokens = 12) },
            "inkStagger" to { Text("Entering", Modifier.inkStagger(2)) },
            "stamp" to { Text("Stamped", Modifier.stamp(1)) },
        )
        val results = components.map { (name, content) -> name to measure(content) }
        val control = measure { ClockReader() }
        results.forEach { (name, r) -> println("InkComponentRecompositionTest: $name parent=${r.parent} scopes=${r.scopes} ticks=${r.ticks}") }
        println("InkComponentRecompositionTest: control parent=${control.parent} scopes=${control.scopes} ticks=${control.ticks}")

        assertTrue("the control recomposes on ticks, so recompositions would be seen: ${control.scopes}", control.scopes > 10)
        assertTrue("the control's frame loop ticked", control.ticks > 0)
        for ((name, r) in results) {
            assertEquals("$name recomposed its parent on animation ticks", 0, r.parent)
            assertEquals("$name recomposed on animation ticks", 0, r.scopes)
        }
    }

    /** The wrong way, on purpose: reads the clock in composition, so every tick recomposes it. */
    @Composable
    private fun ClockReader() {
        val clock = rememberInkClock(true)
        Text("t=${clock.time}")
    }

    private companion object {
        const val FRAMES = 62 // 1 s
    }
}
