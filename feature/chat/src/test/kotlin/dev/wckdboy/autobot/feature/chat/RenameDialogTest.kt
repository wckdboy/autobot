package dev.wckdboy.autobot.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat list's rename dialog. One dialog per JVM (the module forks per class): in Robolectric a
 * dialog window with a focused field never settles after another window (the row menu's popup,
 * an earlier test's dialog) or once its text changes, so this neither types nor uses the menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenameDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun startsFromTheTitleConfirmsAndCancels() {
        val renamed = mutableListOf<String>()
        var dismissed = 0
        compose.setContent { AutobotTheme { NameDialog("Rename chat", "Trip plans", "RENAME", onDismiss = { dismissed++ }, onConfirm = { renamed += it }) } }
        compose.onNodeWithText("Rename chat").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Trip plans")).assertIsDisplayed()
        compose.onNodeWithText("RENAME").performClick()
        compose.onNodeWithText("CANCEL").performClick()
        assertEquals(listOf("Trip plans"), renamed)
        assertEquals(1, dismissed)
    }
}
