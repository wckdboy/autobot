package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionBoardTest {
    private fun approval(session: String, call: String) =
        Interaction.Approval(session, call, "web_fetch", "https://example.com", null, CompletableDeferred())

    @Test
    fun approvalGoesToTheChatItWasGivenIn() = runTest {
        val board = InteractionBoard()
        // Two chats wait on the same (synthesized) call id.
        val inA = async { board.await<ApprovalOutcome>(approval("chat-a", "call_0")) }
        val inB = async { board.await<ApprovalOutcome>(approval("chat-b", "call_0")) }
        yield()
        assertEquals(2, board.items.value.size)

        assertTrue(board.answerApproval("chat-b", "call_0", ApprovalOutcome.REJECTED))
        assertEquals(ApprovalOutcome.REJECTED, inB.await())
        // Chat A is still waiting: the answer in B did not approve it.
        assertFalse(inA.isCompleted)
        assertEquals(listOf("chat-a"), board.items.value.map { it.sessionId })

        assertTrue(board.answerApproval("chat-a", "call_0", ApprovalOutcome.ALLOWED_ONCE))
        assertEquals(ApprovalOutcome.ALLOWED_ONCE, inA.await())
        assertTrue(board.items.value.isEmpty())
    }

    @Test
    fun questionNeedsItsSession() = runTest {
        val board = InteractionBoard()
        val asked = async {
            board.await<List<String>?>(Interaction.Question("chat-a", "q1", UserQuestion("Which?", listOf("x", "y")), CompletableDeferred()))
        }
        yield()
        assertFalse(board.answerQuestion("chat-b", "q1", listOf("x")))
        assertFalse(board.answerApproval("chat-a", "q1", ApprovalOutcome.ALLOWED_ONCE))
        assertTrue(board.answerQuestion("chat-a", "q1", listOf("y")))
        assertEquals(listOf("y"), asked.await())
    }
}
