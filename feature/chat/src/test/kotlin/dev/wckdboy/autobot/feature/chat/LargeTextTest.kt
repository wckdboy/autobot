package dev.wckdboy.autobot.feature.chat

import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.feature.chat.conversation.ChatActions
import dev.wckdboy.autobot.feature.chat.conversation.ChatScreen
import dev.wckdboy.autobot.feature.chat.conversation.ChatUiState
import dev.wckdboy.autobot.feature.chat.conversation.ProviderOption
import dev.wckdboy.autobot.feature.chat.list.ChatListActions
import dev.wckdboy.autobot.feature.chat.list.ChatListScreen
import dev.wckdboy.autobot.feature.chat.list.ChatListSection
import dev.wckdboy.autobot.feature.chat.list.ChatListUiState
import dev.wckdboy.autobot.feature.chat.list.ConversationItem
import dev.wckdboy.autobot.shared.session.SessionItem
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chat list and a conversation at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_000_000_000L

    private val listActions = ChatListActions(rename = { _, _ -> }, setPinned = { _, _ -> }, delete = {}, undoDelete = {}, clearSelection = {}, consumeMessage = {})

    private val list = ChatListUiState(
        sections = listOf(
            ChatListSection(
                "Today",
                listOf(
                    ConversationItem("c1", "Plan a weekend in Lisbon with the kids", "Qwen3 4B · on this phone", now - 60_000, incognito = false, pinned = true),
                    ConversationItem("c2", "Fix the flaky upload test", "Anthropic · claude-opus-5-5", now - 3_600_000, incognito = true, pinned = false),
                ),
            ),
        ),
        total = 2,
        loading = false,
    )

    private val chatActions: ChatActions = Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, m, _ ->
        when {
            m.name == "getDraft" -> ""
            m.returnType == Boolean::class.javaPrimitiveType -> false
            else -> null
        }
    } as ChatActions

    private val chat = ChatUiState(
        loaded = true,
        providers = listOf(ProviderOption("p1", "Anthropic", "claude-opus-5-5", emptyList(), "", canRefresh = false)),
        items = listOf(
            SessionItem.User("u1", "What should we do on a rainy Saturday in Lisbon?"),
            SessionItem.Assistant("a1", "Try the Oceanário, then a tram ride up to Alfama for pastéis de nata.", null, false, 24, "claude-opus-5-5"),
        ),
    )

    private fun auditList(width: Int, scale: Float) = LargeTextAudit.render(compose, "chat-list", width, scale) {
        Themed { ChatListScreen(list, listActions, onOpenChat = {}, onOpenProviders = {}) }
    }

    // The composer is pinned to the bottom: rendered at a real phone's height.
    private fun auditChat(width: Int, scale: Float) = LargeTextAudit.render(compose, "chat", width, scale, heightDp = 800) {
        Themed { ChatScreen(chat, chatActions, onBack = {}, onOpenPrivacyCenter = {}, onOpenProviders = {}, onOpenGallery = {}) }
    }

    @Test fun listDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditList(411, 1f))

    @Test fun listLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditList(360, 2f))

    @Test fun chatDefaultTextFits() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditChat(411, 1f))

    @Test fun chatLargestTextFitsOnANarrowPhone() = assertEquals(emptyList<LargeTextAudit.Finding>(), auditChat(360, 2f))
}
