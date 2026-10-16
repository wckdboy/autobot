package dev.wckdboy.autobot.core.designsystem.motion

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.DevelopingHalftone
import dev.wckdboy.autobot.core.designsystem.component.HalftoneStrip
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkBusyLabel
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderBlock
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.component.inkCard
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
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
 * Each animated ink component, alone under a parent: over 1 s of frames its animation must
 * redraw only (no recomposition of the parent, nor of anything else), while its frame loop
 * really ticks. A control that reads the clock during composition proves the harness would see
 * recompositions if there were any.
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
            "InkLoader" to { InkLoader() },
            "InkLoaderBlock" to { InkLoaderBlock("loading") },
            "InkBusyLabel" to { InkBusyLabel(busy = true) { Text("Save") } },
            "PrimaryButton(busy)" to { PrimaryButton("Send", {}, busy = true) },
            "StatusDot(live)" to { StatusDot(AutobotColors.Acid, live = true) },
            "InkProgressBar(indeterminate)" to { InkProgressBar(null, Modifier.fillMaxWidth().height(3.dp)) },
            "InkProgressBar(running)" to { InkProgressBar(0.4f, Modifier.fillMaxWidth().height(3.dp), state = InkBarState.Running) },
            "HalftoneStrip" to { HalftoneStrip() },
            "DevelopingHalftone" to { DevelopingHalftone(null, Modifier.size(120.dp), progress = 0.3f) },
            "ThinkingDisclosure(thinking)" to { ThinkingDisclosure("Planning\nchecking the calendar", isThinking = true) },
            "inkGround" to { Box(Modifier.size(200.dp).inkGround()) },
            "inkCard" to { Box(Modifier.size(200.dp).inkCard()) },
        )
        val results = components.map { (name, content) -> name to measure(content) }
        val control = measure { ClockReader() }
        results.forEach { (name, r) -> println("InkComponentRecompositionTest: $name parent=${r.parent} scopes=${r.scopes} ticks=${r.ticks}") }
        println("InkComponentRecompositionTest: control parent=${control.parent} scopes=${control.scopes} ticks=${control.ticks}")

        assertTrue("the control recomposes on ticks, so recompositions would be seen: ${control.scopes}", control.scopes > 10)
        for ((name, r) in results) {
            assertTrue("$name: its frame loop never ticked", r.ticks > 0)
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
