package dev.wckdboy.autobot

import dev.wckdboy.autobot.navigation.Chat
import dev.wckdboy.autobot.navigation.Run
import dev.wckdboy.autobot.shared.a2ui.A2uiListener
import dev.wckdboy.autobot.shared.a2ui.A2uiError
import dev.wckdboy.autobot.shared.a2ui.A2uiTranscript
import dev.wckdboy.autobot.shared.a2ui.A2uiTranscriptItems
import dev.wckdboy.autobot.shared.a2ui.RenderNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo's generative UI chat (`--es open ui`): its `show_ui` stream draws every input without errors. */
class DemoGenerativeUiTest {
    @Test
    fun theDemoFormRendersWithoutErrors() {
        val transcript = A2uiTranscript()
        transcript.host.addListener(object : A2uiListener {
            override fun onSurfaceUpdated(surfaceId: String, root: RenderNode?) = Unit
            override fun onSurfaceDeleted(surfaceId: String) = Unit
            override fun onErrors(errors: List<A2uiError>) {
                seen += errors
            }
        })
        transcript.sync(listOf(A2uiTranscriptItems.stream("t1", DemoSeed.UI_STREAM)))
        assertEquals(seen.toString(), listOf("dinner"), transcript.surfaces("t1"))
        val root = transcript.host.render("dinner")
        assertNotNull(root)
        assertEquals(emptyList<A2uiError>(), seen)
        val types = mutableSetOf<String>()
        fun walk(n: RenderNode) {
            assertTrue("placeholder ${n.id}", !n.isPlaceholder)
            types += n.type
            n.children.forEach(::walk)
            n.props.values.forEach { v -> (v as? dev.wckdboy.autobot.shared.a2ui.RenderValue.NodeValue)?.let { walk(it.node) } }
        }
        walk(root!!)
        assertTrue(types.toString(), types.containsAll(listOf("Card", "TextField", "ChoicePicker", "Slider", "CheckBox", "Button")))
    }

    @Test
    fun uiOpensTheDemoChat() {
        assertEquals(listOf(Run, Chat(DebugLaunch.DEMO_UI)), DebugLaunch.destinations("ui"))
    }

    private val seen = mutableListOf<A2uiError>()
}
