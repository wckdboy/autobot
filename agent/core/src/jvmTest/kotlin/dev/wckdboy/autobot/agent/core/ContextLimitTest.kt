package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SurfaceOp
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** History longer than the model's context: compaction and its failure modes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ContextLimitTest {

    private fun LlmCall.chars() = messages.sumOf { it.text.length + (it.reasoning?.length ?: 0) + it.toolCalls.sumOf { c -> c.arguments.length } }

    /** A provider that refuses requests over [limitChars], the way real ones do. */
    private fun strictModel(limitChars: Int, contextWindow: Int = 100_000, helper: String? = null, answer: suspend kotlinx.coroutines.flow.FlowCollector<dev.wckdboy.autobot.agent.core.llm.StreamChunk>.(LlmCall, Int) -> Unit) =
        FakeModel(helper = helper, contextWindow = contextWindow, route = { request, n ->
            if (request.chars() > limitChars) throw LlmException(LlmFailure(LlmErrorCode.CONTEXT_WINDOW_EXCEEDED, "prompt is too long: ${request.chars()} chars"))
            answer(request, n)
        })

    private fun summaries(agent: dev.wckdboy.autobot.agent.core.loop.Agent) =
        agent.log.entries.value.filter { (it.event as? SessionEvent.UserMessage)?.kind == UserMessageKind.SUMMARY }

    @Test
    fun anOverflowCompactsAndRetriesEvenWhenTheWholeHistoryNoLongerFits() = runTest(timeout = 10.seconds) {
        val model = strictModel(30_000) { request, _ ->
            when {
                request.isSummary -> say("SUMMARY of the early turns")(this, request)
                else -> say((request.messages.last().text.first().toString()).repeat(if (request.messages.last().text == "c") 3_000 else 14_000))(this, request)
            }
        }
        val rig = Rig(this, model)
        val agent = rig.open()
        for (prompt in listOf("a", "b", "c", "d")) {
            agent.followup(prompt)
            advanceUntilIdle()
        }
        val ends = agent.all<SessionEvent.TurnEnd>()
        assertEquals(listOf(TurnEndReason.COMPLETED, TurnEndReason.COMPLETED, TurnEndReason.COMPLETED, TurnEndReason.COMPLETED), ends.map { it.reason })
        assertEquals("overflow", agent.all<SessionEvent.CompactionStart>().single().reason)
        assertEquals(null, agent.all<SessionEvent.CompactionEnd>().single().error)
        val last = model.calls.last()
        assertTrue(last.messages.any { "SUMMARY of the early turns" in it.text })
        assertTrue(last.chars() <= 30_000)
        assertEquals("d", last.messages.last { it.role == ModelRole.USER }.text)
    }

    @Test
    fun aSinglePromptLargerThanTheWindowFailsWithAnExplanationNotALoop() = runTest(timeout = 10.seconds) {
        val model = strictModel(5_000) { request, _ -> say("never")(this, request) }
        val agent = Rig(this, model).open()
        agent.followup("x".repeat(10_000))
        advanceUntilIdle()
        val end = agent.end()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertTrue(end.detail!!, end.detail!!.startsWith("CONTEXT_WINDOW_EXCEEDED"))
        assertEquals("nothing to compact, no retries", 1, model.calls.size)
        assertTrue(summaries(agent).isEmpty())
    }

    @Test
    fun pressureCompactionRunsOnTheHelperModelAndTheReceiptSaysSo() = runTest(timeout = 10.seconds) {
        val model = FakeModel(helper = "cheap", contextWindow = 4_000, route = { request, _ ->
            if (request.isSummary) say("SUMMARY")(this, request) else say("y".repeat(4_000))(this, request)
        })
        val agent = Rig(this, model).open()
        agent.followup("first")
        advanceUntilIdle()
        agent.followup("second")
        advanceUntilIdle()
        val summary = model.calls.single { it.isSummary }
        assertEquals("cheap", summary.config.model)
        assertEquals("pressure", agent.all<SessionEvent.CompactionStart>().single().reason)
        val receipt = agent.all<SessionEvent.TurnReceipt>().last()
        assertEquals(setOf("main-model", "cheap"), receipt.models.map { it.model }.toSet())
    }

    @Test
    fun aFailingSummaryIsReportedAndTheTurnStillRuns() = runTest(timeout = 10.seconds) {
        val model = FakeModel(contextWindow = 4_000, route = { request, _ ->
            when {
                request.isSummary -> throw LlmException(LlmFailure(LlmErrorCode.SERVER, "summarizer down"))
                else -> say("z".repeat(4_000))(this, request)
            }
        })
        val agent = Rig(this, model).open()
        agent.followup("first")
        advanceUntilIdle()
        agent.followup("second")
        advanceUntilIdle()
        assertEquals("summarizer down", agent.all<SessionEvent.CompactionEnd>().single().error)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertTrue(summaries(agent).isEmpty())
    }

    @Test
    fun anEmptySummaryReplacesNothing() = runTest(timeout = 10.seconds) {
        val model = FakeModel(contextWindow = 4_000, route = { request, _ ->
            if (request.isSummary) say("   ")(this, request) else say("w".repeat(4_000))(this, request)
        })
        val agent = Rig(this, model).open()
        agent.followup("first")
        advanceUntilIdle()
        agent.followup("second")
        advanceUntilIdle()
        assertEquals("empty summary", agent.all<SessionEvent.CompactionEnd>().single().error)
        assertTrue(model.calls.last().messages.any { it.text == "first" })
    }

    @Test
    fun repeatedCompactionKeepsOneSummaryAndNeverSplitsCallsFromResults() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read") { _, _ -> ToolOutput("r".repeat(3_000)) }
        val model = FakeModel(contextWindow = 4_000, route = { request, n ->
            when {
                request.isSummary -> say("SUMMARY $n")(this, request)
                request.messages.last().role == ModelRole.USER -> call("c$n", "read")(this, request)
                else -> say("answer $n " + "v".repeat(1_500))(this, request)
            }
        })
        val agent = Rig(this, model, read).open()
        repeat(6) { i ->
            agent.followup("prompt $i")
            advanceUntilIdle()
        }
        assertTrue(agent.all<SessionEvent.CompactionStart>().size >= 2)
        assertTrue(agent.all<SessionEvent.TurnEnd>().all { it.reason == TurnEndReason.COMPLETED })
        for (request in model.calls.filterNot { it.isSummary }) {
            val calls = request.messages.filter { it.role == ModelRole.ASSISTANT }.flatMap { m -> m.toolCalls.map { it.id } }
            val results = request.messages.filter { it.role == ModelRole.TOOL }.map { it.toolCallId }
            assertEquals(calls, results)
            assertTrue("one summary at most", request.messages.count { "<compacted-summary>" in it.text } <= 1)
            assertFalse(request.messages.any { it.text == "prompt 0" } && request.messages.any { "<compacted-summary>" in it.text })
        }
        assertTrue(summaries(agent).all { it.surface is SurfaceOp.Replace })
    }

    @Test
    fun reportedUsageDrivesTheEstimate() = runTest(timeout = 10.seconds) {
        // Short texts, but the provider reports a nearly full context: compaction runs.
        val model = FakeModel(contextWindow = 10_000, route = { request, _ ->
            if (request.isSummary) say("SUMMARY")(this, request) else say("short", usage = TokenUsage(inputTokens = 9_000, outputTokens = 10))(this, request)
        })
        val agent = Rig(this, model).open()
        agent.followup("one")
        advanceUntilIdle()
        agent.followup("two")
        advanceUntilIdle()
        assertEquals(1, agent.all<SessionEvent.CompactionStart>().size)
    }
}
