package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import dev.wckdboy.autobot.core.a2ui.A2uiChatState
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.a2ui.A2uiActionResult
import dev.wckdboy.autobot.shared.a2ui.A2uiCatalog
import dev.wckdboy.autobot.shared.a2ui.A2uiChat
import dev.wckdboy.autobot.shared.a2ui.A2uiSubmission
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A `show_ui` call in the chat transcript: its surface is drawn in place of the step row, and a tap reaches the actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp-xhdpi")
class ChatA2uiTest {
    @get:Rule
    val compose = createComposeRule()

    private val stream = """
        [{"version":"v1.0","createSurface":{"surfaceId":"rsvp","catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}","components":[
          {"id":"root","component":"Column","children":["q","name","go"]},
          {"id":"q","component":"Text","text":"Coming on Friday?"},
          {"id":"name","component":"TextField","label":"Your name","value":{"@path":"/name"}},
          {"id":"go","component":"Button","variant":"primary","child":"go_t","action":{"event":{"name":"rsvp","context":{"name":{"@path":"/name"}}}}},
          {"id":"go_t","component":"Text","text":"I'm in"}],"dataModel":{"name":""}}}]
    """.trimIndent()

    @Test
    fun aShowUiCallDrawsItsSurfaceAndSendsTheAction() {
        val tool = SessionItem.Tool("t1", "c1", A2uiChat.TOOL, "surface rsvp", ToolState.OK, "Shown", null, emptyList(), turn = 1, a2ui = stream)
        val items = listOf(SessionItem.User("u1", "Friday?"), tool)
        val sent = ArrayList<A2uiActionResult>()
        val a2ui = A2uiChatState { items }
        val actions = Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, m, args ->
            when (m.name) {
                "getDraft" -> ""
                "getA2ui" -> a2ui
                "a2uiAction" -> sent.add(args!![0] as A2uiActionResult).let { null }
                else -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as ChatActions
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatScreen(ChatUiState(loaded = true, items = items), actions, onBack = {}, onOpenPrivacyCenter = {}, onOpenProviders = {}, onOpenGallery = {})
                }
            }
        }
        compose.onNodeWithText("Coming on Friday?").assertExists()
        compose.onNodeWithTag("name").performTextReplacement("Ada")
        compose.onNodeWithText("I'm in").performClick()
        val event = sent.single() as A2uiActionResult.SendEvent
        assertEquals("rsvp", event.name)
        assertEquals("Rsvp", A2uiChat.actionLabel(event))
    }

    private fun actionsFor(a2ui: A2uiChatState, sent: MutableList<A2uiActionResult>) =
        Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, m, args ->
            when (m.name) {
                "getDraft" -> ""
                "getA2ui" -> a2ui
                // What the view model does: lock at the tap, then send.
                "a2uiAction" -> (args!![0] as A2uiActionResult).let { a2ui.onAction(it); sent.add(it) }.let { null }
                else -> if (m.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        } as ChatActions

    @Test
    fun aSentFormLocksWhenItsTurnArrives() {
        val tool = SessionItem.Tool("t1", "c1", A2uiChat.TOOL, "surface rsvp", ToolState.OK, "Shown", null, emptyList(), turn = 1, a2ui = stream)
        val items = mutableStateOf<List<SessionItem>>(listOf(SessionItem.User("u1", "Friday?"), tool))
        val sent = ArrayList<A2uiActionResult>()
        val a2ui = A2uiChatState { items.value }
        val actions = actionsFor(a2ui, sent)
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatScreen(ChatUiState(loaded = true, items = items.value), actions, onBack = {}, onOpenPrivacyCenter = {}, onOpenProviders = {}, onOpenGallery = {})
                }
            }
        }
        compose.onNodeWithTag("name").performTextReplacement("Ada")
        compose.onNodeWithText("I'm in").performClick()
        compose.onNodeWithText(A2uiChatState.SENDING).assertExists()

        val event = sent.single() as A2uiActionResult.SendEvent
        val user = SessionItem.User("u3", A2uiChat.actionLabel(event), a2ui = A2uiChat.submission(event))
        compose.runOnIdle { items.value = items.value + user }
        compose.onNodeWithText(A2uiChatState.SENT).assertExists()
        compose.onNodeWithTag("name").assertIsNotEnabled()
    }

    @Test
    fun aReopenedChatShowsTheSentInputLocked() {
        val tool = SessionItem.Tool("t1", "c1", A2uiChat.TOOL, "surface rsvp", ToolState.OK, "Shown", null, emptyList(), turn = 1, a2ui = stream)
        val user = SessionItem.User("u3", "Rsvp", a2ui = A2uiSubmission("rsvp", "rsvp", "Rsvp", """{"name":"Ada Lovelace"}"""))
        val items = listOf(SessionItem.User("u1", "Friday?"), tool, user)
        val a2ui = A2uiChatState { items }
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatScreen(ChatUiState(loaded = true, items = items), actionsFor(a2ui, ArrayList()), onBack = {}, onOpenPrivacyCenter = {}, onOpenProviders = {}, onOpenGallery = {})
                }
            }
        }
        compose.onNodeWithText("Ada Lovelace").assertExists()
        compose.onNodeWithTag("name").assertIsNotEnabled()
        compose.onNodeWithText(A2uiChatState.SENT).assertExists()
    }
}
