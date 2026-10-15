package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnReceiptTest {

    /** Calls `fetch` then `missing` on the first request, answers on the second; records the trace it ran under. */
    private class Scripted : LlmAdapter {
        override val provider = "cloud"
        val traces = mutableListOf<TurnTrace?>()
        private var calls = 0

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            traces += TurnTrace.current()
            calls++
            if (calls % 2 == 1) {
                emit(StreamChunk.ToolCallDelta(0, "c$calls", "fetch", "{}"))
                emit(StreamChunk.ToolCallDelta(1, "m$calls", "missing", "{}"))
                emit(StreamChunk.Finish("tool_calls"))
            } else {
                emit(StreamChunk.TextDelta("done"))
                emit(StreamChunk.Finish("stop"))
            }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private class Fetch : ToolDefinition {
        override val name = "fetch"
        override val kind = ToolKind.READ
        override val description = "fetch"
        override val parameters = schema { }
        val traces = mutableListOf<TurnTrace?>()
        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            traces += TurnTrace.current()
            return ToolOutput("ok")
        }
    }

    private suspend fun TestScope.agent(adapter: LlmAdapter, tool: ToolDefinition, setup: (Harness) -> Unit = {}): Agent {
        val harness = Harness()
        harness.llm.register(adapter)
        harness.tools.register(tool)
        setup(harness)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        return harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "big")), scope, clock = { 0L })
    }

    @Test
    fun aTurnRunsUnderItsTraceAndAppendsAReceiptBeforeItEnds() = runTest {
        val adapter = Scripted()
        val fetch = Fetch()
        val agent = agent(adapter, fetch)
        agent.followup("hi")
        advanceUntilIdle()

        val trace = TurnTrace("s1", 1)
        assertEquals(listOf(trace, trace), adapter.traces)
        assertEquals(listOf(trace), fetch.traces)

        val events = agent.log.entries.value.map { it.event }
        val receipt = events[events.size - 2] as SessionEvent.TurnReceipt
        assertTrue(events.last() is SessionEvent.TurnEnd)
        assertEquals(1, receipt.turn)
        assertEquals(listOf("cloud" to "big"), receipt.models.map { it.provider to it.model })
        // `missing` never ran (unknown tool).
        assertEquals(listOf("fetch"), receipt.tools)
        assertNull(receipt.network) // nobody tracks traffic here

        agent.followup("again")
        advanceUntilIdle()
        assertEquals(TurnTrace("s1", 2), adapter.traces.last())
        assertEquals(listOf(1, 2), agent.log.entries.value.mapNotNull { (it.event as? SessionEvent.TurnReceipt)?.turn })
    }

    @Test
    fun hostsAddTheNetworkPartThroughTheHook() = runTest {
        val agent = agent(Scripted(), Fetch()) { h ->
            h.root.intercept(Hooks.TurnReceipt) { input, next ->
                next(input).copy(network = listOf(ReceiptHost("api.example.com", "tor", requests = 2, bytesSent = 1200, bytesReceived = 800, tags = listOf(input.agent.id))))
            }
        }
        agent.followup("hi")
        advanceUntilIdle()

        val receipt = agent.log.entries.value.map { it.event }.filterIsInstance<SessionEvent.TurnReceipt>().single()
        assertEquals("api.example.com", receipt.network!!.single().host)
        assertEquals(listOf("s1"), receipt.network!!.single().tags)
        assertEquals(listOf("fetch"), receipt.tools)
    }

    @Test
    fun aFailingHookStillLeavesTheBaseReceipt() = runTest {
        val agent = agent(Scripted(), Fetch()) { h ->
            h.root.intercept(Hooks.TurnReceipt) { _, _ -> error("audit log gone") }
        }
        agent.followup("hi")
        advanceUntilIdle()
        val receipt = agent.log.entries.value.map { it.event }.filterIsInstance<SessionEvent.TurnReceipt>().single()
        assertEquals(listOf("fetch"), receipt.tools)
    }
}
