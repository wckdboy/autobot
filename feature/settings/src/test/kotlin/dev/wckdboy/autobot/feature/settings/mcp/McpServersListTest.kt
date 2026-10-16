package dev.wckdboy.autobot.feature.settings.mcp

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An MCP server an agent session could not reach says so on its settings row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McpServersListTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unreachableAndConnectedServersShowTheirState() {
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    McpServersScreen(
                        state = McpServersUiState(
                            servers = listOf(
                                McpServerRow("1", "Notes", "https://notes.example.com/mcp", "notes.example.com", ProviderRouting.INHERIT, true, false, false,
                                    status = "unreachable · Can't reach notes.example.com: timeout · tap to test", unavailable = true),
                                McpServerRow("2", "Files", "https://files.example.com/mcp", "files.example.com", ProviderRouting.INHERIT, true, false, false,
                                    status = "connected · 1 tool"),
                            ),
                        ),
                        onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onEnabled = { _, _ -> }, onDraftChange = {},
                        onSave = {}, onTest = {}, onDismissEditor = {},
                    )
                }
            }
        }
        compose.onNodeWithText("unreachable · Can't reach notes.example.com: timeout · tap to test").assertIsDisplayed()
        compose.onNodeWithText("connected · 1 tool").assertIsDisplayed()
    }
}
