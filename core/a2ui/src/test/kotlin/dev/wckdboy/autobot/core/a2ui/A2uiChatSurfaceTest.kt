package dev.wckdboy.autobot.core.a2ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.a2ui.A2uiActionResult
import dev.wckdboy.autobot.shared.a2ui.A2uiChat
import dev.wckdboy.autobot.shared.a2ui.A2uiError
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A chat's surfaces: sent forms drawn read-only, stretched rows, weighted columns, image hints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp-xhdpi")
class A2uiChatSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    private val basic = A2uiFixtures.BASIC

    private fun show(stream: String, surfaceId: String, media: A2uiMedia = A2uiMedia.None, note: () -> String? = { null }): A2uiHost {
        val host = A2uiHost()
        assertEquals(emptyList<A2uiError>(), host.receive(stream))
        compose.setContent {
            AutobotTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    A2uiSurfaceView(host, surfaceId, onAction = {}, media = media, note = note)
                }
            }
        }
        return host
    }

    @Test
    fun aReadOnlySurfaceDisablesInputsAndSendButtonsAndShowsItsNote() {
        var sent = false
        val host = show(A2uiFixtures.gallery, "all", note = { if (sent) "Sent" else null })
        compose.onNodeWithTag("name").assertIsEnabled()
        compose.onNodeWithTag("a2ui:note:all").assertDoesNotExist()

        sent = true
        compose.runOnIdle { host.setReadOnly("all", true) }
        compose.onNodeWithTag("name").assertIsNotEnabled()
        compose.onNodeWithText("Order").assertExists()
        compose.onNodeWithTag("order").assertExists()
        compose.onNodeWithText("Extra cheese").assertExists()
        compose.onNodeWithTag("a2ui:note:all").assertExists()
        compose.onNodeWithText("Sent").assertExists()
        // The failing check under the send button is not shown on a sent form.
        compose.onNodeWithText("Add your name first.").assertDoesNotExist()

        sent = false
        compose.runOnIdle { host.setReadOnly("all", false) }
        compose.onNodeWithTag("name").assertIsEnabled()
        compose.onNodeWithText("Sent").assertDoesNotExist()
    }

    @Test
    fun aChatLocksAFormAtTheTapAndKeepsItLockedOnceLogged() {
        val form = """[{"version":"v1.0","createSurface":{"surfaceId":"f","catalogId":"$basic","components":[
            {"id":"root","component":"Column","children":["name","go"]},
            {"id":"name","component":"TextField","label":"Name","value":{"@path":"/name"}},
            {"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"send"}}},
            {"id":"go_t","component":"Text","text":"Go"}],"dataModel":{"name":"Ada"}}}]"""
        val tool = SessionItem.Tool(
            key = "t1", callId = "t1", name = A2uiChat.TOOL, summary = "", state = ToolState.OK,
            output = "ok", code = null, galleryIds = emptyList(), a2ui = form,
        )
        val items = mutableStateOf<List<SessionItem>>(listOf(tool))
        val state = A2uiChatState { items.value }
        val sent = ArrayList<A2uiActionResult.SendEvent>()
        compose.setContent {
            AutobotTheme {
                // What the chat screen does on every new transcript.
                LaunchedEffect(items.value) { state.sync() }
                A2uiToolSurfaces(state, "t1", onAction = { a ->
                    state.onAction(a)
                    if (a is A2uiActionResult.SendEvent) sent += a
                })
            }
        }
        compose.onNodeWithText("Go").performClick()
        compose.onNodeWithText(A2uiChatState.SENDING).assertExists()
        compose.onNodeWithTag("name").assertIsNotEnabled()

        // The turn reaches the log: "Sent", still locked.
        val user = SessionItem.User("u2", "Send", a2ui = A2uiChat.submission(sent.single()))
        compose.runOnIdle { items.value = listOf(tool, user) }
        compose.onNodeWithText(A2uiChatState.SENT).assertExists()
        compose.onNodeWithTag("name").assertIsNotEnabled()

        // The agent updates the form: editable again.
        val update = tool.copy(key = "t3", callId = "t3", a2ui = """[{"version":"v1.0","updateDataModel":{"surfaceId":"f","path":"/name","value":"Ada L."}}]""")
        compose.runOnIdle { items.value = listOf(tool, user, update) }
        compose.onNodeWithText(A2uiChatState.SENT).assertDoesNotExist()
        compose.onNodeWithTag("name").assertIsEnabled()
    }

    @Test
    fun aStretchedRowGivesItsChildrenOneHeight() {
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"r","catalogId":"$basic","components":[
            {"id":"root","component":"Row","children":["a","b"]},
            {"id":"a","component":"Card","child":"a_t","weight":1},
            {"id":"a_t","component":"Text","text":"Short"},
            {"id":"b","component":"Card","child":"b_col","weight":1},
            {"id":"b_col","component":"Column","children":["b1","b2","b3","b4"]},
            {"id":"b1","component":"Text","text":"One"},{"id":"b2","component":"Text","text":"Two"},
            {"id":"b3","component":"Text","text":"Three"},{"id":"b4","component":"Text","text":"Four"}]}}]"""
        show(stream, "r")
        val a = compose.onNodeWithTag("a").getBoundsInRoot()
        val b = compose.onNodeWithTag("b").getBoundsInRoot()
        assertEquals(b.bottom - b.top, a.bottom - a.top)
        assertTrue(b.bottom - b.top > 80.dp)
    }

    @Test
    fun aWeightedColumnSharesTheHeightOfAStretchedRow() {
        // The right column pins its button to the bottom with a weighted spacer.
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"w","catalogId":"$basic","components":[
            {"id":"root","component":"Row","children":["left","right"]},
            {"id":"left","component":"Column","weight":1,"children":["l1","l2","l3","l4","l5"]},
            {"id":"l1","component":"Text","text":"One"},{"id":"l2","component":"Text","text":"Two"},
            {"id":"l3","component":"Text","text":"Three"},{"id":"l4","component":"Text","text":"Four"},{"id":"l5","component":"Text","text":"Five"},
            {"id":"right","component":"Column","weight":1,"children":["title","gap","go"]},
            {"id":"title","component":"Text","text":"Plan"},
            {"id":"gap","component":"Column","weight":1,"children":[]},
            {"id":"go","component":"Button","child":"go_t","action":{"event":{"name":"pick"}}},
            {"id":"go_t","component":"Text","text":"Pick"}]}}]"""
        show(stream, "w")
        val left = compose.onNodeWithTag("left").getBoundsInRoot()
        val right = compose.onNodeWithTag("right").getBoundsInRoot()
        val go = compose.onNodeWithTag("go").getBoundsInRoot()
        assertEquals(left.bottom, right.bottom)
        assertEquals(right.bottom, go.bottom)
        assertTrue(compose.onNodeWithTag("gap").getBoundsInRoot().let { it.bottom - it.top } > 0.dp)
    }

    @Test
    fun aWeightedChildKeepsItsHeightInTheScrollingChat() {
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"c","catalogId":"$basic","components":[
            {"id":"root","component":"Column","children":["top","mid"]},
            {"id":"top","component":"Text","text":"Top"},
            {"id":"mid","component":"Card","weight":1,"child":"mid_t"},
            {"id":"mid_t","component":"Text","text":"Weighted card"}]}}]"""
        show(stream, "c")
        compose.onNodeWithText("Weighted card").assertExists()
        assertTrue(compose.onNodeWithTag("mid").getBoundsInRoot().let { it.bottom - it.top } > 20.dp)
    }

    @Test
    fun aRowWithTabsIsDrawnWithoutStretching() {
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"t","catalogId":"$basic","components":[
            {"id":"root","component":"Row","children":["tabs","side"]},
            {"id":"tabs","component":"Tabs","weight":1,"tabs":[{"title":"A","child":"ta"},{"title":"B","child":"tb"}]},
            {"id":"ta","component":"Text","text":"Tab A"},{"id":"tb","component":"Text","text":"Tab B"},
            {"id":"side","component":"Text","text":"Side"}]}}]"""
        show(stream, "t")
        compose.onNodeWithText("Tab A").assertExists()
        compose.onNodeWithText("Side").assertExists()
    }

    @Test
    fun anImageSaysWhereATapLoadsItFrom() {
        val media = object : A2uiMedia {
            override suspend fun image(url: String): ImageBitmap? = null
            override val loadHint = "Tap to load · goes through Tor"
        }
        val stream = """[{"version":"v1.0","createSurface":{"surfaceId":"i","catalogId":"$basic","components":[
            {"id":"root","component":"Image","url":"https://example.com/a.jpg","description":"A cat"}]}}]"""
        show(stream, "i", media)
        compose.onNodeWithText("Tap to load · goes through Tor").assertExists()
        compose.onNodeWithText("Load image").assertExists()
    }
}
