package dev.wckdboy.autobot.feature.home.node

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.shared.invincible.AgentAction
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The node agent's approval card: Allow, Always, Deny as before, and "Something else…" with a line of text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestionCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val row = AgentRow.Question("k1", "a1", "run: rm -rf build", AgentAction.Command("rm -rf build"))

    @Test
    fun somethingElseSendsTheTextInstead() {
        val answers = mutableListOf<ApprovalAnswer>()
        val instead = mutableListOf<Pair<String, String>>()
        compose.setContent { Themed { QuestionCard(row, { _, a -> answers += a }, { id, text -> instead += id to text }) } }
        compose.onNodeWithText("Deny").performClick()
        compose.onNodeWithText("Something else…").performClick()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("What to do instead").performTextInput("Only clean build/tmp")
        compose.onNodeWithText("Send").performClick()
        assertEquals(listOf(ApprovalAnswer.DENY), answers)
        assertEquals(listOf("a1" to "Only clean build/tmp"), instead)
    }

    @Test
    fun anAnswerInsteadIsShown() {
        compose.setContent { Themed { QuestionCard(row.copy(instead = "Only clean build/tmp"), { _, _ -> }) } }
        compose.onNodeWithText("Asked instead: Only clean build/tmp").assertExists()
    }
}
