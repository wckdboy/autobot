package dev.wckdboy.autobot.feature.chat.list

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Typing into the chat-list search must not recompose every visible chat row per keystroke: rows
 * only change when their own data does. Rows are counted through [chatListProbe]. The host below
 * applies each query to the state at once, as the view model's state would (worst case: no
 * debounce), so the count measures the screen, not the view model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w380dp-h760dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ChatListRecompositionTest {
    private var rows = 0

    @After
    fun tearDown() {
        chatListProbe = null
    }

    @Test
    fun typingInSearchDoesNotRecomposeRows() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val queries = mutableListOf<String>()
        val items = (1..20).map { ConversationItem("c$it", "Chat number $it", "Provider · model", 1_700_000_000_000L - it * 60_000L, false) }
        val holder = mutableStateOf(ChatListUiState(sections = listOf(ChatListSection("Today", items)), loading = false))
        activity.setContent {
            var state by remember { holder }
            val actions = remember {
                ChatListActions(setQuery = { q ->
                    queries += q
                    state = state.copy(query = q)
                })
            }
            AutobotTheme { ChatListScreen(state, actions, onOpenChat = {}, onOpenProviders = {}) }
        }
        // Let the first composition settle (a slow machine may still be laying out or animating
        // rows) before counting: idle until rows stop recomposing on their own.
        chatListProbe = { if (it == "row") rows++ }
        for (pass in 0 until 50) {
            val before = rows
            idle()
            if (rows == before && pass >= 2) break
        }
        rows = 0
        val typed = "refactoring"
        for (i in 1..typed.length) {
            setSearchText(activity, typed.take(i))
            idle()
        }
        idle()
        println("ChatListRecompositionTest: ${typed.length} keystrokes -> $rows row recompositions")
        assertEquals(typed, queries.last())
        assertTrue("rows recomposed $rows times while typing ${typed.length} characters", rows == 0)
        // The probe works: a row whose data changes does recompose (and only that one).
        holder.value = holder.value.copy(sections = listOf(ChatListSection("Today", items.mapIndexed { i, it -> if (i == 0) it.copy(title = "Renamed") else it })))
        idle()
        assertEquals(1, rows)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun setSearchText(activity: ComponentActivity, text: String) {
        val root = composeRoot(activity.window.decorView) ?: error("no compose root")
        val field = find(root.semanticsOwner.unmergedRootSemanticsNode) { SemanticsActions.SetText in it.config } ?: error("no text field")
        field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))
    }

    private fun composeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun find(node: SemanticsNode, predicate: (SemanticsNode) -> Boolean): SemanticsNode? {
        if (predicate(node)) return node
        for (child in node.children) find(child, predicate)?.let { return it }
        return null
    }
}
