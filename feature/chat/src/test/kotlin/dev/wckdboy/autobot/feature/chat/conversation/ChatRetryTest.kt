package dev.wckdboy.autobot.feature.chat.conversation

import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRetryTest {
    private val ask = SessionItem.User("u1", "Who wrote Stiller?")
    private val answer = SessionItem.Assistant("a1", "Max Frisch.", null, false, null, null)

    @Test
    fun theLastAnswerAsksTheLastPromptAgain() {
        assertEquals(ChatRetry("a1", "Who wrote Stiller?"), ChatRetry.of(listOf(ask, answer), running = false))
    }

    @Test
    fun aFailureCanBeRetriedButNotAnInfoLine() {
        assertEquals(ChatRetry("n1", "Who wrote Stiller?"), ChatRetry.of(listOf(ask, SessionItem.Notice("n1", "Network off", NoticeKind.ERROR)), running = false))
        assertNull(ChatRetry.of(listOf(ask, answer, SessionItem.Notice("n2", "Stopped", NoticeKind.INFO)), running = false))
    }

    @Test
    fun nothingWhileRunningOrWithoutAPrompt() {
        assertNull(ChatRetry.of(listOf(ask, answer), running = true))
        assertNull(ChatRetry.of(listOf(answer), running = false))
        assertNull(ChatRetry.of(emptyList(), running = false))
        assertNull(ChatRetry.of(listOf(answer, ask), running = false))
    }

    @Test
    fun theMenuOffersWhatAppliesInOneOrder() {
        assertEquals(MessageAction.entries.toList(), messageActions(answer = true, retry = true))
        assertEquals(listOf(MessageAction.COPY, MessageAction.SHARE, MessageAction.SELECT), messageActions(answer = false, retry = false))
    }
}
