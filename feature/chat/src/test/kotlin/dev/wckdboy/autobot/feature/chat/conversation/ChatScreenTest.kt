package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput

import androidx.compose.ui.test.performScrollTo
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.component.LocalKeyboardUp
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.shared.ChatBannerWords
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.Receipts
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

    private var keyboard by mutableStateOf(false)

    private fun show(initial: ChatUiState): (ChatUiState) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true), LocalKeyboardUp provides keyboard) {
                    ChatScreen(state, actions, onBack = {}, onOpenPrivacyCenter = { openedPrivacy++ }, onOpenProviders = { openedProviders++ }, onOpenGallery = {})
                }
            }
        }
        return { state = it }
    }

    @Test
    fun withoutAProviderItSaysWhatToDo() {
        show(ChatUiState(loaded = true))
        compose.onNodeWithText(ChatBannerWords.NO_MODEL).assertIsDisplayed()
        compose.onNodeWithText("Add").performClick()
        assertEquals(1, openedProviders)
    }

    @Test
    fun oneBannerAtATimeTheMostPressingFirst() {
        show(ChatUiState(loaded = true, providers = listOf(provider), showOfflineBanner = true, missingKeyProvider = "DeepSeek"))
        compose.onNodeWithText(ChatBannerWords.OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(ChatBannerWords.missingKey("DeepSeek")).assertDoesNotExist()
    }

    @Test
    fun whileTypingOnlyTheFieldTheTranscriptAndTheQuestionStay() {
        val state = ChatUiState(
            loaded = true,
            providers = listOf(provider),
            toolsEnabled = true,
            missingKeyProvider = "DeepSeek",
            running = true,
            items = listOf(SessionItem.User("u1", "Plan my week")),
            todos = listOf(TodoItem("Draft the plan", TodoStatus.IN_PROGRESS)),
            question = QuestionUi("q1", UserQuestion("Which days?", listOf("Mon", "Tue"))),
        )
        keyboard = true
        show(state)
        compose.onNodeWithText("Plan my week").assertIsDisplayed()
        compose.onNodeWithText("Which days?").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertIsDisplayed()
        compose.onNodeWithText(ChatBannerWords.missingKey("DeepSeek")).assertDoesNotExist()
        compose.onNodeWithText("Draft the plan").assertDoesNotExist()
        compose.onNodeWithText("policy", substring = true, ignoreCase = true).assertDoesNotExist()
        // The keyboard goes down: everything comes back.
        keyboard = false
        compose.onNodeWithText(ChatBannerWords.missingKey("DeepSeek")).assertIsDisplayed()
        compose.onNodeWithText("Draft the plan").assertIsDisplayed()
        compose.onNodeWithText("policy", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun anEmptyChatDropsItsArtWhileTyping() {
        keyboard = true
        show(ChatUiState(loaded = true, providers = listOf(provider)))
        compose.onNodeWithText("What should it do?").assertDoesNotExist()
        keyboard = false
        compose.onNodeWithText("What should it do?").assertIsDisplayed()
    }

    @Test
    fun aRemoteProviderInOfflineModeSaysTheKillSwitchBlocksIt() {
        show(ChatUiState(loaded = true, providers = listOf(provider), showOfflineBanner = true))
        compose.onNodeWithText("Offline mode: this provider is remote, so the kill switch blocks it.").assertIsDisplayed()
        compose.onNodeWithText("Privacy").performClick()
        assertEquals(1, openedPrivacy)
    }

    @Test
    fun aCloudProviderWithoutAKeySaysSoBeforeSending() {
        show(ChatUiState(loaded = true, providers = listOf(provider), missingKeyProvider = "DeepSeek"))
        // The words iOS shows, from the shared ChatBannerWords.
        compose.onNodeWithText("DeepSeek has no API key, so messages will fail. Add it in Remote › Providers.").assertIsDisplayed()
        compose.onNodeWithText("Add key").performClick()
        assertEquals(1, openedProviders)
    }

    @Test
    fun aFailedTurnExplainsTheRejectedKey() {
        val error = "The provider rejected the API key. Check it in Remote › Providers (HTTP 401: invalid key)"
        show(ChatUiState(loaded = true, providers = listOf(provider), items = listOf(SessionItem.User("u1", "hi"), SessionItem.Notice("n1", error, NoticeKind.ERROR))))
        compose.onNodeWithText("✗ $error").assertIsDisplayed()
        // The failed turn can be asked again in one tap.
        compose.onNodeWithText("Retry").performClick()
        assertTrue(calls.toString(), "send(hi)" in calls)
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
        compose.onNodeWithText("▪ Approval needed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("write_file(path=notes.md)").assertIsDisplayed()
        compose.onNodeWithText("Approve", ignoreCase = true).performClick()
        compose.onNodeWithText("Deny", ignoreCase = true).performClick()
        compose.onNodeWithText("Always allow write_file in this run").performClick()
        assertEquals(
            listOf("approve(call-1,ALLOWED_ONCE)", "approve(call-1,REJECTED)", "approve(call-1,ALLOWED_FOR_SESSION)"),
            calls.filter { it.startsWith("approve") },
        )
    }

    @Test
    fun stopShowsWhileAReplyStreamsAndStopsIt() {
        val update = show(ChatUiState(loaded = true, providers = listOf(provider), running = true, items = listOf(SessionItem.User("u1", "Write a poem"))))
        compose.onNodeWithContentDescription("Stop").performClick()
        assertTrue(calls.toString(), "stop()" in calls)
        // The turn ends: the stop button gives way to an (empty, so disabled) send button.
        update(ChatUiState(loaded = true, providers = listOf(provider), running = false, items = listOf(SessionItem.User("u1", "Write a poem"))))
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    @Test
    fun aCloudChatShowsTheRedactionSwitchAndTheReceiptCountsWhatWasHidden() {
        val receipt = Receipts.summarize(
            SessionEvent.TurnReceipt(
                turn = 1,
                network = listOf(ReceiptHost("api.deepseek.com", "direct", 1, bytesSent = 2048)),
                redacted = mapOf("PERSON" to 2, "EMAIL" to 1),
            ),
        )
        val answer = SessionItem.Assistant("a1", "Wrote to Hannah.", null, false, null, null, receipt = receipt)
        val update = show(ChatUiState(loaded = true, providers = listOf(provider), redaction = RedactionUi.ON, items = listOf(SessionItem.User("u1", "hi"), answer)))
        // A filter chip: selected while redacting, a tap switches it.
        compose.onNodeWithText("Redact").assertIsSelected().performClick()
        assertTrue(calls.toString(), "setRedaction(false)" in calls)
        update(ChatUiState(loaded = true, providers = listOf(provider), redaction = RedactionUi.OFF, items = listOf(SessionItem.User("u1", "hi"), answer)))
        compose.onNodeWithText("Redact").assertIsNotSelected().assertContentDescriptionEquals("Redaction off").performClick()
        assertTrue(calls.toString(), "setRedaction(true)" in calls)

        // The receipt line says how much was hidden; the sheet says what kind, never the values.
        compose.onNodeWithText("3 redacted", substring = true, ignoreCase = true).performClick()
        compose.onNodeWithText("2 names", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("1 email", ignoreCase = true).assertIsDisplayed()

        // On this phone there is nothing to hide: no switch.
        update(ChatUiState(loaded = true, providers = listOf(provider), redaction = RedactionUi.NONE))
        compose.onNodeWithText("Redact").assertDoesNotExist()
    }

    @Test
    fun aPlainCloudChatShowsPhoneFirstTheOfferAndTheCascadeReceipt() {
        val local = Receipts.summarize(
            SessionEvent.TurnReceipt(
                turn = 1,
                network = emptyList(),
                cascade = dev.wckdboy.autobot.agent.core.session.ReceiptCascade(
                    role = "local", policy = "ASK", verdict = "WEAK", reasons = listOf("refusal"), action = "OFFERED", judge = "heuristic", judgeMs = 3,
                ),
            ),
        )
        val answer = SessionItem.Assistant("a1", "I can't help with that.", null, false, null, "qwen3-1.7b", receipt = local)
        val offer = dev.wckdboy.autobot.shared.session.CascadeOffer(1, "The phone's answer looks weak: it declined.")
        show(
            ChatUiState(
                loaded = true, providers = listOf(provider), toolsEnabled = false, modelLabel = "deepseek-chat",
                cascade = dev.wckdboy.autobot.shared.cascade.CascadePolicy.ASK, cascadeOffer = offer,
                items = listOf(SessionItem.User("u1", "hi"), answer),
            ),
        )
        compose.onNodeWithText("The phone's answer looks weak: it declined.").assertIsDisplayed()
        compose.onNodeWithText("Ask deepseek-chat").performClick()
        assertTrue(calls.toString(), "escalate()" in calls)

        // The chip opens the choice; picking one sets it for this chat.
        compose.onNodeWithText("Phone first · ask").assertIsSelected().performClick()
        compose.onNodeWithText("auto", ignoreCase = true).performClick()
        assertTrue(calls.toString(), "setCascade(AUTO)" in calls)
        compose.onNodeWithText("Plain chats only", substring = true).assertExists()
    }

    @Test
    fun theCascadeReceiptSaysWhatHappenedAndWhy() {
        val local = Receipts.summarize(
            SessionEvent.TurnReceipt(
                turn = 1,
                network = emptyList(),
                cascade = dev.wckdboy.autobot.agent.core.session.ReceiptCascade(
                    role = "local", policy = "AUTO", verdict = "WEAK", reasons = listOf("dont-know"), action = "OFFERED", risk = listOf("health"), judge = "self-check", judgeMs = 412,
                ),
            ),
        )
        assertEquals("on device · judge weak", local.line)
        val answer = SessionItem.Assistant("a1", "I don't know.", null, false, null, "qwen3-1.7b", receipt = local)
        show(ChatUiState(loaded = true, providers = listOf(provider), toolsEnabled = false, items = listOf(SessionItem.User("u1", "hi"), answer)))
        compose.onNodeWithText("judge weak", substring = true, ignoreCase = true).performClick()
        compose.onNodeWithText("Not sent on its own: the prompt involves health.", substring = true).assertExists()
        compose.onNodeWithText("judge · self-check · 412 ms").assertExists()
    }

    @Test
    fun theLastAnswerOffersCopyShareRetryAndReadAloud() {
        val answer = SessionItem.Assistant("a1", "Use reversed().", null, false, 12, null)
        val items = listOf(SessionItem.User("u1", "Reverse a list"), answer)
        val update = show(ChatUiState(loaded = true, providers = listOf(provider), items = items))
        compose.onNodeWithContentDescription("Copy message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        compose.onNodeWithContentDescription("Read aloud").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry").performClick()
        assertTrue(calls.toString(), "send(Reverse a list)" in calls)
        // While a turn runs there is nothing to retry yet.
        update(ChatUiState(loaded = true, providers = listOf(provider), running = true, items = items))
        compose.onNodeWithContentDescription("Retry").assertDoesNotExist()
    }

    @Test
    fun longPressOpensTheSameMenuAsIos() {
        val answer = SessionItem.Assistant("a1", "Use reversed().", null, false, 12, null)
        show(ChatUiState(loaded = true, providers = listOf(provider), items = listOf(SessionItem.User("u1", "Reverse a list"), answer)))
        compose.onNodeWithText("Use reversed().").performTouchInput { longClick() }
        listOf("Copy", "Share", "Retry", "Read aloud", "Select text").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Read aloud").performClick()
        assertTrue(calls.toString(), calls.any { it.startsWith("speak(a1") })
    }

    @Test
    fun stepsUseTheTerminalSquare() {
        val tool = SessionItem.Tool("t1", "call-1", "read", "notes.md", ToolState.OK, "# Notes", null, emptyList())
        show(ChatUiState(loaded = true, providers = listOf(provider), toolsEnabled = true, items = listOf(SessionItem.User("u1", "Read my notes"), tool), stepNumbers = mapOf("t1" to 1)))
        compose.onNodeWithText("▪").assertIsDisplayed()
        compose.onNodeWithText("└ read · notes.md").assertIsDisplayed()
        compose.onNodeWithText("01").assertDoesNotExist()
    }
}
