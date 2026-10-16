package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The log-side part of privacy receipts across normal, failed and cancelled turns. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptWorkflowTest {

    @Test
    fun aTurnCancelledMidStreamStillReceiptsTheModelThePromptWentTo() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(hang(StreamChunk.TextDelta("partial"))))
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("secret question")
        advanceUntilIdle()
        agent.cancel()
        advanceUntilIdle()
        val events = agent.events()
        val receipt = events.filterIsInstance<SessionEvent.TurnReceipt>().single()
        assertEquals(1, receipt.turn)
        assertEquals(listOf("fake" to "main-model"), receipt.models.map { it.provider to it.model })
        assertTrue(receipt.tools.isEmpty())
        assertEquals("the receipt comes right before the turn end", events.indexOf(receipt) + 1, events.indexOfFirst { it is SessionEvent.TurnEnd })
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
    }

    @Test
    fun aToolInterruptedMidRunIsListedBecauseItMayHaveActed() = runTest(timeout = 10.seconds) {
        val upload = FakeTool("upload", ToolKind.OTHER, safe = false) { _, _ -> awaitCancellation() }
        val read = FakeTool("read", safe = false)
        val model = FakeModel(replies = listOf(calls(Triple("c1", "upload", "{}"), Triple("c2", "read", "{}"))))
        val rig = Rig(this, model, upload, read)
        val agent = rig.open()
        agent.followup("go")
        advanceTimeBy(100)
        runCurrent()
        agent.cancel()
        advanceUntilIdle()
        assertEquals(listOf("upload"), agent.all<SessionEvent.TurnReceipt>().single().tools)
    }

    @Test
    fun eachTurnsReceiptHoldsOnlyThatTurnsModelsAndTools() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(replies = listOf(call("c1", "read"), say("one"), hang(StreamChunk.TextDelta("..")), say("three")))
        val rig = Rig(this, model, read)
        val agent = rig.open()
        agent.followup("one")
        advanceUntilIdle()
        agent.updateConfig { it.copy(llm = it.llm.copy(model = "other-model")) }
        agent.followup("two")
        advanceUntilIdle()
        agent.cancel()
        advanceUntilIdle()
        agent.followup("three")
        advanceUntilIdle()
        val receipts = agent.all<SessionEvent.TurnReceipt>()
        assertEquals(listOf(1, 2, 3), receipts.map { it.turn })
        assertEquals(listOf(listOf("main-model"), listOf("other-model"), listOf("other-model")), receipts.map { r -> r.models.map { it.model } })
        assertEquals(listOf(listOf("read"), emptyList(), emptyList()), receipts.map { it.tools })
    }

    @Test
    fun aFailedTurnHasAReceiptAndAnEmptyInboxTurnHasNone() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(fail(dev.wckdboy.autobot.agent.core.llm.LlmErrorCode.AUTH)))
        val agent = Rig(this, model).open()
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(1, agent.all<SessionEvent.TurnReceipt>().size)
        assertEquals(TurnEndReason.ERROR, agent.end().reason)
    }

    @Test
    fun subagentsWriteNoReceiptOfTheirOwnAndTheirTrafficIsTracedToTheParentTurn() = runTest(timeout = 10.seconds) {
        val traces = mutableListOf<TurnTrace?>()
        val probe = FakeTool("probe") { _, _ ->
            traces += TurnTrace.current()
            ToolOutput("ok")
        }
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent && request.toolResults.isEmpty() -> call("k1", "probe")(this, request)
                request.isSubagent -> say("report")(this, request)
                request.toolResults.isEmpty() -> chunks(
                    StreamChunk.ToolCallDelta(0, "t1", "task", buildJsonObject { put("description", "d"); put("prompt", "p") }.toString()),
                    StreamChunk.Finish("tool_calls"),
                )(this, request)
                else -> say("done")(this, request)
            }
        })
        val rig = Rig(this, model, probe)
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(listOf(TurnTrace("s1", 1)), traces)
        assertEquals(1, agent.all<SessionEvent.TurnReceipt>().size)
    }
}
