package dev.wckdboy.autobot.feature.home

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Run keeps its way to every chat: archived chats are not "recent", but they are one tap away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun everyModeCardHasItsIcon() {
        compose.setContent { Themed { RunScreen(RunUiState(status = "ready"), LargeTextAudit.uncalled(), {}, {}, {}, {}, {}, {}) } }
        listOf("Image", "Chat", "Agent", "Code", "Studio").forEach { mode ->
            compose.onNodeWithTag("mode-icon-$mode", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun allChatsIsThereWithNothingRecent() {
        var opened = 0
        compose.setContent { Themed { RunScreen(RunUiState(status = "ready"), LargeTextAudit.uncalled(), {}, {}, {}, { opened++ }, {}, {}) } }
        // A lazy list composes what is in view: scroll it to the row first.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("All"))
        compose.onNodeWithText("All").performClick()
        assertEquals(1, opened)
    }

    /** The mode cards reflow: two columns on a phone, one row of five on a wide window. */
    @Test
    @Config(qualifiers = "w900dp-h1000dp")
    fun aWideWindowPutsTheModesInOneRow() {
        compose.setContent { Themed { RunScreen(RunUiState(status = "ready"), LargeTextAudit.uncalled(), {}, {}, {}, {}, {}, {}) } }
        val image = compose.onNodeWithText("Image").getUnclippedBoundsInRoot()
        val studio = compose.onNodeWithText("Studio").getUnclippedBoundsInRoot()
        assertEquals(image.top, studio.top)
        assertTrue("Studio sits to the right of Image", studio.left > image.right)
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun aPhoneStacksTheModesInTwoColumns() {
        compose.setContent { Themed { RunScreen(RunUiState(status = "ready"), LargeTextAudit.uncalled(), {}, {}, {}, {}, {}, {}) } }
        val image = compose.onNodeWithText("Image").getUnclippedBoundsInRoot()
        val studio = compose.onNodeWithText("Studio").getUnclippedBoundsInRoot()
        assertTrue("Studio sits under Image", studio.top > image.bottom)
    }
}
