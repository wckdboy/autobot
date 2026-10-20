package dev.wckdboy.autobot.feature.home

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import org.junit.Assert.assertEquals
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
    fun allChatsIsThereWithNothingRecent() {
        var opened = 0
        compose.setContent { Themed { RunScreen(RunUiState(status = "ready"), LargeTextAudit.uncalled(), {}, {}, {}, { opened++ }, {}, {}) } }
        // A lazy list composes what is in view: scroll it to the row first.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("All"))
        compose.onNodeWithText("All").performClick()
        assertEquals(1, opened)
    }
}
