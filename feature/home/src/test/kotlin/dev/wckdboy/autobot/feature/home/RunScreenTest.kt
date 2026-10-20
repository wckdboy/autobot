package dev.wckdboy.autobot.feature.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        compose.onNodeWithText("All").performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
