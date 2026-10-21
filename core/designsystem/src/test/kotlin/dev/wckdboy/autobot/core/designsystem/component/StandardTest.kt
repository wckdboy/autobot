package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Material 3 helpers: the dotted title reads as the title alone, and a chip gains a long press without losing its tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StandardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDottedTitleIsReadAsTheTitleAndAHeading() {
        compose.setContent { AutobotTheme { TitleWithDot("Chats") } }
        compose.onNodeWithText("Chats")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Chats"))))
    }

    @Test
    fun aLongPressRunsItsActionInsteadOfTheTap() {
        val events = mutableListOf<String>()
        compose.setContent {
            AutobotTheme {
                FilterChip(
                    selected = false,
                    onClick = { events += "tap" },
                    label = { Text("Work 3") },
                    modifier = Modifier.longPressAction("Folder actions") { events += "long" },
                )
            }
        }
        compose.onNodeWithText("Work 3").performClick()
        compose.onNodeWithText("Work 3").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(listOf("tap", "long"), events)
        // TalkBack gets the long press as an action of its own.
        compose.onNodeWithText("Work 3")
            .assert(SemanticsMatcher("long-click labelled") { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Folder actions" })
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf("tap", "long", "long"), events)
    }

    @Test
    fun aSegmentLabelStaysOnOneLineOnAPhone() {
        // The chat's permission presets at a phone's sheet width: "Workspace-write" broke as
        // "Workspace-writ / e" and made its segment taller than the others.
        val options = listOf("Read-only", "Workspace-write", "Full-access")
        compose.setContent {
            AutobotTheme {
                Choices(options, "Workspace-write", {}, Modifier.width(328.dp).fillMaxWidth()) { it }
            }
        }
        for (label in options) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("$label: lines", 1, layout.lineCount)
            assertEquals("$label: ellipsized", false, layout.hasVisualOverflow)
        }
    }
}
