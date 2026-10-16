package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chat screen's setup banners, the approval card and the stop button, from fixture state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp-xhdpi")
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var openedProviders = 0
    private var openedPrivacy = 0

    private val actions: ChatActions = Proxy.newProxyInstance(ChatActions::class.java.classLoader, arrayOf(ChatActions::class.java)) { _, m, args ->
        if (m.name == "getDraft") return@newProxyInstance ""
        if (m.returnType == Boolean::class.javaPrimitiveType) return@newProxyInstance false
        calls += "${m.name}(${args.orEmpty().filter { it !is kotlin.coroutines.Continuation<*> }.joinToString(",")})"
        null
    } as ChatActions

    private val provider = ProviderOption("p1", "DeepSeek", "deepseek-chat", emptyList(), "", canRefresh = false)

    private fun show(initial: ChatUiState): (ChatUiState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ChatScreen(state, actions, onBack = {}, onOpenPrivacyCenter = { openedPrivacy++ }, onOpenProviders = { openedProviders++ }, onOpenGallery = {})
                }
            }
        }
        return { state = it }
    }

    @Test
    fun withoutAProviderItSaysWhatToDo() {
        show(ChatUiState(loaded = true))
        compose.onNodeWithText("No model yet.").assertIsDisplayed()
        compose.onNodeWithText("ADD").performClick()
        assertEquals(1, openedProviders)
    }

    @Test
    fun aRemoteProviderInOfflineModeSaysTheKillSwitchBlocksIt() {
        show(ChatUiState(loaded = true, providers = listOf(provider), showOfflineBanner = true))
        compose.onNodeWithText("Offline mode: this provider is remote, so the kill switch blocks it.").assertIsDisplayed()
        compose.onNodeWithText("PRIVACY").performClick()
        assertEquals(1, openedPrivacy)
    }

    @Test
    fun aFailedTurnExplainsTheRejectedKey() {
        val error = "The provider rejected the API key. Check it under Settings › Model providers (HTTP 401: invalid key)"
        show(ChatUiState(loaded = true, providers = listOf(provider), items = listOf(SessionItem.User("u1", "hi"), SessionItem.Notice("n1", error, NoticeKind.ERROR))))
        compose.onNodeWithText("// $error").assertIsDisplayed()
    }

    @Test
    fun theApprovalCardApprovesAndDenies() {
        val tool = SessionItem.Tool("t1", "call-1", "write_file", "notes.md", ToolState.WAITING_APPROVAL, null, null, emptyList())
        show(
            ChatUiState(
                loaded = true,
                providers = listOf(provider),
                running = true,
                items = listOf(SessionItem.User("u1", "Save my notes"), tool),
                stepNumbers = mapOf("t1" to 1),
                approvals = mapOf("call-1" to ApprovalUi("call-1", "write_file", "path=notes.md", "writes a file")),
            ),
        )
        compose.onNodeWithText("01 · APPROVAL NEEDED").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("write_file(path=notes.md)").assertIsDisplayed()
        compose.onNodeWithText("Approve", ignoreCase = true).performClick()
        compose.onNodeWithText("Deny", ignoreCase = true).performClick()
        compose.onNodeWithText("ALWAYS ALLOW WRITE_FILE IN THIS RUN").performClick()
        assertEquals(
            listOf("approve(call-1,ALLOWED_ONCE)", "approve(call-1,REJECTED)", "approve(call-1,ALLOWED_FOR_SESSION)"),
            calls.filter { it.startsWith("approve") },
        )
    }

    @Test
    fun stopShowsWhileAReplyStreamsAndStopsIt() {
        val update = show(ChatUiState(loaded = true, providers = listOf(provider), running = true, items = listOf(SessionItem.User("u1", "Write a poem"))))
        compose.onNodeWithContentDescription("Stop the reply").performClick()
        assertTrue(calls.toString(), "stop()" in calls)
        // The turn ends: the stop button gives way to an (empty, so disabled) send button.
        update(ChatUiState(loaded = true, providers = listOf(provider), running = false, items = listOf(SessionItem.User("u1", "Write a poem"))))
        compose.onNodeWithContentDescription("Stop the reply").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
}
