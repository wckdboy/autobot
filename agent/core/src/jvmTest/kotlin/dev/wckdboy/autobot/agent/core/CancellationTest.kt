package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.deriveMessages
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Cancelling a turn at every stage leaves a valid history, an idle agent and no coroutines behind. */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {

    /** Every assistant tool call in a request is followed by its result (strict providers require it). */
    private fun assertPaired(messages: List<dev.wckdboy.autobot.agent.core.llm.ModelMessage>) {
        val calls = messages.filter { it.role == ModelRole.ASSISTANT }.flatMap { m -> m.toolCalls.map { it.id } }
        val results = messages.filter { it.role == ModelRole.TOOL }.map { it.toolCallId }
        assertEquals(calls, results)
    }

    @Test
    fun cancelMidStreamCommitsTheInterruptedMessageAndTheNextTurnSeesIt() = runTest(timeout = 10.seconds) {
        val model = FakeModel(
            replies = listOf(
                hang(
                    StreamChunk.ReasoningDelta("let me think"),
                    StreamChunk.TextDelta("The answer is"),
                    StreamChunk.ToolCallDelta(0, "c1", "read", """{"text":"half"""),
                ),
                say("continuing"),
            ),
        )
        val rig = Rig(this, model, FakeTool("read"))
        val agent = rig.open()
        agent.followup("question")
        advanceUntilIdle()
        // Intended: live frames are throttled (one per 33 ms of wall clock); the committed message has it all.
        assertTrue(agent.live.value != null)

        agent.cancel()
        advanceUntilIdle()
        val interrupted = agent.all<SessionEvent.AssistantMessage>().single()
        assertTrue(interrupted.interrupted)
        assertEquals("The answer is", interrupted.text)
        assertEquals("let me think", interrupted.reasoning)
        assertTrue("a half-streamed tool call is dropped", interrupted.toolCalls.isEmpty())
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertNull(agent.live.value)
        assertEquals(AgentStatus.IDLE, agent.status.value)
        assertEquals(1, rig.active())

        agent.followup("go on")
        advanceUntilIdle()
        val next = model.calls[1].messages
        assertTrue(next.any { it.role == ModelRole.ASSISTANT && it.text == "The answer is" })
        assertPaired(next)
    }

    @Test
    fun cancelBeforeAnyContentCommitsNothing() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(hang(StreamChunk.ToolCallDelta(0, "c1", "read", "{")), say("ok")))
        val rig = Rig(this, model, FakeTool("read"))
        val agent = rig.open()
        agent.followup("q")
        advanceUntilIdle()
        agent.cancel()
        advanceUntilIdle()
        assertTrue(agent.all<SessionEvent.AssistantMessage>().isEmpty())
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertPaired(deriveMessages(agent.log.entries.value))
    }

    @Test
    fun cancelDuringToolsCancelsTheRunningToolAndAnswersEveryCall() = runTest(timeout = 10.seconds) {
        var cleanedUp = false
        val slow = FakeTool("slow", safe = false) { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        }
        val model = FakeModel(replies = listOf(calls(Triple("c1", "slow", "{}"), Triple("c2", "slow", "{}"), Triple("c3", "slow", "{}"))))
        val rig = Rig(this, model, slow)
        val agent = rig.open()
        agent.followup("go")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(setOf("c1"), agent.runningTools.value)

        agent.cancel()
        advanceUntilIdle()
        assertTrue("the tool's coroutine was cancelled", cleanedUp)
        assertEquals(listOf("TOOL_OUTCOME_UNKNOWN", "ABORTED_BEFORE_DISPATCH", "ABORTED_BEFORE_DISPATCH"), agent.results().map { it.code })
        assertTrue(agent.runningTools.value.isEmpty())
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertEquals(1, rig.active())
        assertPaired(deriveMessages(agent.log.entries.value))
    }

    @Test
    fun cancelDuringParallelToolsKeepsTheFinishedResults() = runTest(timeout = 10.seconds) {
        val tool = FakeTool("t") { args, _ ->
            if (args.text() == "fast") ToolOutput("quick") else awaitCancellation()
        }
        val model = FakeModel(replies = listOf(calls(Triple("c1", "t", """{"text":"slow"}"""), Triple("c2", "t", """{"text":"fast"}"""))))
        val rig = Rig(this, model, tool)
        val agent = rig.open()
        agent.followup("go")
        advanceTimeBy(100)
        runCurrent()
        agent.cancel()
        advanceUntilIdle()
        val results = agent.results()
        assertEquals(listOf("c1", "c2"), results.map { it.callId })
        assertEquals("TOOL_OUTCOME_UNKNOWN", results[0].code)
        assertEquals("quick", results[1].content)
    }

    @Test
    fun cancelDuringARetryBackoffStopsAtOnce() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(fail(LlmErrorCode.SERVER, "502"), say("never")))
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("hi")
        runCurrent()
        assertEquals(1, agent.all<SessionEvent.LlmRetry>().size)
        agent.cancel()
        advanceUntilIdle()
        assertEquals(1, model.calls.size)
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertEquals(1, model.remaining)
    }

    @Test
    fun cancelDropsQueuedInputUnlessAskedToKeepIt() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(hang(StreamChunk.TextDelta("a")), hang(StreamChunk.TextDelta("b")), say("kept")))
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("first")
        agent.followup("dropped")
        advanceUntilIdle()
        agent.cancel()
        advanceUntilIdle()
        assertEquals("the queued followup was dropped", 1, agent.all<SessionEvent.TurnStart>().size)

        agent.followup("second")
        agent.followup("third")
        advanceUntilIdle()
        agent.cancel(keepInbox = true)
        advanceUntilIdle()
        val prompts = agent.all<SessionEvent.UserMessage>().map { it.text }
        assertEquals(listOf("first", "second", "third"), prompts)
        assertEquals("kept", agent.all<SessionEvent.AssistantMessage>().last().text)
    }

    @Test
    fun cancelWhileIdleIsANoOp() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(say("hi")))
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.cancel()
        advanceUntilIdle()
        assertTrue(agent.events().isEmpty())
        agent.followup("hello")
        advanceUntilIdle()
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun disposeMidTurnLeavesNoCoroutinesBehind() = runTest(timeout = 10.seconds) {
        val slow = FakeTool("slow") { _, _ ->
            delay(1_000_000)
            ToolOutput("late")
        }
        val model = FakeModel(replies = listOf(call("c1", "slow")))
        val rig = Rig(this, model, slow)
        val a = rig.open("a")
        val b = rig.open("b")
        a.followup("go")
        advanceTimeBy(10)
        runCurrent()
        assertTrue(rig.active() > 2)
        a.dispose()
        b.dispose()
        advanceUntilIdle()
        assertEquals(0, rig.active())
        assertEquals(TurnEndReason.ABORTED, a.end().reason)
        assertFalse(slow.finished.get() > 0)
    }

    @Test
    fun manyTurnsWithCancelsInBetweenNeverLeak() = runTest(timeout = 10.seconds) {
        val tool = FakeTool("t") { _, _ ->
            delay(500)
            ToolOutput("ok")
        }
        val model = FakeModel(route = { request, n ->
            when {
                request.toolResults.size < 2 -> call("c$n", "t")(this, request)
                else -> say("done $n")(this, request)
            }
        })
        val rig = Rig(this, model, tool)
        val agent = rig.open()
        repeat(10) { i ->
            agent.followup("turn $i")
            advanceTimeBy(250L + i * 100)
            runCurrent()
            agent.cancel()
            advanceUntilIdle()
            assertEquals(1, rig.active())
            assertEquals(AgentStatus.IDLE, agent.status.value)
        }
        assertEquals(10, agent.all<SessionEvent.TurnEnd>().size)
        assertPaired(deriveMessages(agent.log.entries.value))
    }
}
