package dev.wckdboy.autobot.feature.home.node

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.CompositionLocalProvider
import dev.wckdboy.autobot.core.designsystem.component.LocalKeyboardUp
import androidx.compose.ui.test.hasSetTextAction
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.shared.invincible.NodeChatSample
import dev.wckdboy.autobot.shared.invincible.NodeImages
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A node chat: the photo sent with a message, what the agent kept, retry on the node, a model that cannot see. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeAgentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val photo = NodeChatDemo.photo(320, 240)
    private val state = NodeAgentUiState(node = "studio-mac", info = NodeChatSample.info, rows = NodeChatDemo.rows(photo))

    @Test
    fun theTranscriptShowsThePhotoWhatWasKeptAndRetriesTheLastTurn() {
        var retried = 0
        compose.setContent { Themed { NodeAgentScreen(state, {}, {}, { _, _ -> }, {}, {}, onRetryTurn = { retried++ }) } }
        compose.onNodeWithTag("node-agent").performScrollToNode(hasContentDescription("Photo 1 sent"))
        compose.onNodeWithContentDescription("Photo 1 sent").assertIsDisplayed()
        compose.onNodeWithTag("node-agent").performScrollToNode(hasText("kept · ${NodeChatSample.KEPT}"))
        compose.onNodeWithTag("kept", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("kept · ${NodeChatSample.KEPT}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("node-agent").performScrollToNode(hasText("The middle one", substring = true))
        // Retry only on the last answer (the first one is on screen too).
        compose.onNodeWithTag("node-agent").performScrollToNode(hasText("Three Raspberry Pi", substring = true))
        assertEquals(1, compose.onAllNodesWithContentDescription("Retry on the node").fetchSemanticsNodes().size)
        compose.onNodeWithTag("node-agent").performScrollToNode(hasText("The middle one", substring = true))
        compose.onNodeWithContentDescription("Retry on the node").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun theNewestRowSitsOnTheComposerAndTheHeaderStepsAsideWhileTyping() {
        var keyboard by mutableStateOf(false)
        val rows = listOf(AgentRow.User("u", "Which one runs Home Assistant?"), AgentRow.Reply("r", "The middle one.", null))
        compose.setContent { Themed { CompositionLocalProvider(LocalKeyboardUp provides keyboard) { NodeAgentScreen(state.copy(rows = rows), {}, {}, { _, _ -> }, {}, {}) } } }
        // The last answer is on screen without scrolling (the list is anchored at the bottom).
        compose.onNodeWithText("The middle one", substring = true).assertIsDisplayed()
        compose.onNodeWithText("runs on studio-mac", substring = true, ignoreCase = true).assertExists()
        keyboard = true
        compose.onNodeWithText("runs on studio-mac", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("The middle one", substring = true).assertIsDisplayed()
    }

    @Test
    fun sendWaitsForTheRunningTurn() {
        var sent = 0
        var current by mutableStateOf(state.copy(running = true))
        compose.setContent { Themed { NodeAgentScreen(current, {}, { sent++ }, { _, _ -> }, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("df -h")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        current = current.copy(running = false)
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test
    fun aMessageFromTheHistorySaysHowManyPhotosItHad() {
        val rows = listOf(AgentRow.User("u", "What is this?", photoCount = 2), AgentRow.Reply("r", "Two cats.", null))
        compose.setContent { Themed { NodeAgentScreen(state.copy(rows = rows), {}, {}, { _, _ -> }, {}, {}) } }
        compose.onNodeWithText(NodeImages.label(2)).assertIsDisplayed()
    }

    @Test
    fun aModelThatCannotSeeGivesTheTextBack() {
        var current by mutableStateOf(state.copy(photos = listOf(photo)))
        val removed = mutableListOf<Int>()
        compose.setContent {
            Themed {
                NodeAgentScreen(current, {}, {}, { _, _ -> }, {}, {}, onRemovePhoto = { removed += it }, onTookReturned = { current = current.copy(returned = null) })
            }
        }
        compose.onNodeWithContentDescription("Photo 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove photo 1").performClick()
        assertEquals(listOf(0), removed)
        current = current.copy(photos = emptyList(), returned = "What is on this shelf?", rows = current.rows + AgentRow.Note("e", NodeImages.CANNOT_SEE_MESSAGE, AgentRow.Tone.ERROR))
        compose.waitForIdle()
        compose.onNode(hasText("What is on this shelf?")).assertIsDisplayed()
        compose.onNodeWithText(NodeImages.CANNOT_SEE_MESSAGE).assertIsDisplayed()
    }

    private fun audit(width: Int, scale: Float, dark: Boolean) = LargeTextAudit.render(compose, "node-chat-${if (dark) "dark" else "light"}", width, scale) {
        Themed(dark) { NodeAgentScreen(state, {}, {}, { _, _ -> }, {}, {}) }
    }

    @Test fun onInk() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f, dark = true))

    @Test fun onPaper() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(411, 1f, dark = false))

    @Test fun atTheLargestText() = assertEquals(emptyList<LargeTextAudit.Finding>(), audit(360, 2f, dark = true))

    @Test
    fun aPhotoIsDecodedOffTheMainThreadAndKeepsItsPlace() {
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        thumbDecodeProbe = { threads += it }
        try {
            compose.setContent { Themed { PhotoThumb(photo, 96, "Photo 1 sent") } }
            // The square and its description are there before the decode finishes.
            compose.onNodeWithContentDescription("Photo 1 sent").assertIsDisplayed()
            compose.waitUntil(5_000) { threads.isNotEmpty() }
            val main = android.os.Looper.getMainLooper().thread
            assertEquals(false, threads.any { it == main })
        } finally {
            thumbDecodeProbe = null
        }
    }
}
