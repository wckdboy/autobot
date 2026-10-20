package dev.wckdboy.autobot.feature.chat.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.wckdboy.autobot.agent.runtime.PendingDeletion
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat list's row actions, undoable delete, empty states and counts, from fixture state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xhdpi")
class ChatListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var openedProviders = 0
    private val now = System.currentTimeMillis()

    private val actions = ChatListActions(
        rename = { id, title -> calls += "rename($id,$title)" },
        setPinned = { ids, pinned -> calls += "pin($ids,$pinned)" },
        delete = { ids -> calls += "delete($ids)" },
        undoDelete = { batch -> calls += "undo($batch)" },
        clearSelection = { calls += "clearSelection" },
        consumeMessage = { calls += "consumeMessage" },
    )

    private fun item(id: String, title: String, pinned: Boolean = false) = ConversationItem(id, title, "DeepSeek · deepseek-chat", now - 60_000, incognito = false, pinned = pinned)

    private fun state(vararg items: ConversationItem, total: Int = items.size) =
        ChatListUiState(sections = listOf(ChatListSection("Today", items.toList())), total = total, loading = false)

    private fun show(initial: ChatListUiState): (ChatListUiState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatListScreen(state, actions, onOpenChat = {}, onOpenProviders = { openedProviders++ })
                }
            }
        }
        return { state = it }
    }

    @Test
    fun theRowMenuOffersRename() {
        show(state(item("c1", "Trip plans")))
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }



    @Test
    fun pinAndUnpinFromTheRowMenu() {
        val update = show(state(item("c1", "Trip plans")))
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Pin to top").performClick()
        update(state(item("c1", "Trip plans", pinned = true)))
        compose.onNodeWithContentDescription("Pinned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Chat actions").performClick()
        compose.onNodeWithText("Unpin").performClick()
        assertEquals(listOf("pin([c1],true)", "pin([c1],false)"), calls)
    }

    @Test
    fun deleteFromTheMenuThenUndo() {
        val update = show(state(item("c1", "Trip plans"), item("c2", "Groceries")))
        compose.onNodeWithText("Groceries").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Chat actions")[1].performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete([c2])"), calls)
        // The view model hides the chat and offers to undo (the deletion is only committed later).
        update(state(item("c1", "Trip plans")).copy(pendingDeletion = PendingDeletion(7L, listOf("c2"), "Deleted “Groceries”")))
        compose.onNodeWithText("Deleted “Groceries”").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        assertEquals("undo(7)", calls.last())
    }

    @Test
    fun swipeLeftDeletes() {
        show(state(item("c1", "Trip plans")))
        compose.onNodeWithText("Trip plans").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("delete([c1])"), calls)
    }

    @Test
    fun aSearchWithoutMatchesSaysSo() {
        show(ChatListUiState(query = "kubernetes", loading = false, total = 3))
        compose.onNodeWithText("No chats match that search.").assertIsDisplayed()
    }

    @Test
    fun theHeaderCountsOnlyWhatRuns() {
        val update = show(state(item("c1", "Trip plans"), total = 1))
        // The list shows how many there are: the header does not repeat it.
        compose.onAllNodesWithText("session", substring = true).assertCountEquals(0)
        update(state(item("c1", "Trip plans"), item("c2", "Groceries"), total = 2).copy(live = 1))
        compose.onNodeWithText("1 live").assertIsDisplayed()
    }

    @Test
    fun newStartsTheDefaultChatAndTheLockAnIncognitoOne() {
        val started = mutableListOf<Boolean?>()
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatListScreen(state(item("c1", "Trip plans")), ChatListActions(newChat = { started += it }), onOpenChat = {}, onOpenProviders = {})
                }
            }
        }
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithContentDescription("New incognito chat").performClick()
        assertEquals(listOf(null, true), started)
    }

    @Test
    fun everyChatIncognitoByDefaultLeavesOnlyNew() {
        show(state(item("c1", "Trip plans")).copy(incognito = true))
        compose.onAllNodesWithContentDescription("New incognito chat").assertCountEquals(0)
        compose.onNodeWithText("New").assertIsDisplayed()
    }

    @Test
    fun noProviderSaysSoAndLinksToProviders() {
        show(state(item("c1", "Trip plans")).copy(hasProviders = false))
        compose.onNodeWithText("No model yet.").assertIsDisplayed()
        compose.onNodeWithText("Add provider").performClick()
        assertEquals(1, openedProviders)
    }

    @Test
    fun aFailedActionIsShownOnce() {
        show(state(item("c1", "Trip plans")).copy(message = "Couldn't start a chat: Storage is full"))
        compose.onNodeWithText("Couldn't start a chat: Storage is full").assertIsDisplayed()
        assertEquals(listOf("consumeMessage"), calls)
    }
}
