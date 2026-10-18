package dev.wckdboy.autobot.feature.settings.recipes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeModelChoice
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeState
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Recipes list: the templates, a finished run with its result link, Run now, the editor's tools. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp-xhdpi")
class RecipesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val templates = RecipeTemplates.all(0)
    private val done = templates[0].copy(
        enabled = true,
        state = RecipeState(nextRunAt = 2 * 86_400_000L, lastRun = RecipeRunRecord(0, 60_000, RecipeRunStatus.DONE, sessionId = "chat-1", summary = "Five headlines.", notes = listOf("Needed approval for web_fetch"))),
    )

    @Test
    fun listShowsRunsAndOpensTheResult() {
        val opened = mutableListOf<String>()
        val ran = mutableListOf<String>()
        val rows = listOf(done, templates[1], templates[2]).map { it.toRow(86_400_000L, TimeZone.UTC, running = false) }
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    RecipesScreen(
                        state = RecipesUiState(loaded = true, recipes = rows, models = listOf(RecipeModelChoice("qwen", "Qwen3 4B"))),
                        onBack = {}, onAdd = {}, onEdit = {}, onEnabled = { _, _ -> }, onRunNow = { ran += it }, onOpenChat = { opened += it },
                        onSummary = {}, onDraftChange = {}, onSave = {}, onDelete = {}, onDismissEditor = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Morning brief from my feeds").assertIsDisplayed()
        compose.onNodeWithText("Weekly notes tidy").assertIsDisplayed()
        compose.onNodeWithText("Daily digest of my chats").assertIsDisplayed()
        compose.onNodeWithText("Five headlines.").assertIsDisplayed()
        compose.onNodeWithText("Needed approval for web_fetch").assertIsDisplayed()
        compose.onNodeWithText("Fridays at 18:00").assertIsDisplayed()
        compose.onNodeWithText(RecipeCopy.OPEN_RESULT).performClick()
        assertEquals(listOf("chat-1"), opened)
        compose.onAllNodesWithText(RecipeCopy.RUN_NOW)[0].performClick()
        assertEquals(listOf(RecipeTemplates.MORNING_BRIEF), ran)
        compose.onNodeWithText(RecipeCopy.SUMMARY_SWITCH).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theEditorOffersTheToolGroupsAndSaysWhatIsMissing() {
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    RecipesScreen(
                        state = RecipesUiState(loaded = true, editor = templates[0], problems = emptyList()),
                        onBack = {}, onAdd = {}, onEdit = {}, onEnabled = { _, _ -> }, onRunNow = {}, onOpenChat = {},
                        onSummary = {}, onDraftChange = {}, onSave = {}, onDelete = {}, onDismissEditor = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Web").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(RecipeCopy.NETWORK_NOTE).performScrollTo().assertIsDisplayed()
    }
}
