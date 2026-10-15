package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.StoredLog
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions for crashes and stuck states found in the crash audit (S1, S2, S8). */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashGuardsTest {

    private class ScriptedAdapter(private val script: MutableList<List<StreamChunk>>) : LlmAdapter {
        override val provider = "fake"

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            (script.removeFirstOrNull() ?: listOf(StreamChunk.TextDelta("done"), StreamChunk.Finish("stop"))).forEach { emit(it) }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private object Echo : ToolDefinition {
        override val name = "echo"
        override val kind = ToolKind.READ
        override val description = "test"
        override val parameters = schema { }

        override suspend fun execute(args: JsonObject, run: ToolRunContext) = ToolOutput("ok")
    }

    /** Fails every append for which [failing] says so (a full disk, a broken foreign key, …). */
    private class FlakyStore(var failing: (SessionEvent) -> Boolean = { false }) : SessionStore {
        private val inner = InMemorySessionStore()

        override suspend fun load(sessionId: String) = inner.load(sessionId)

        override suspend fun append(sessionId: String, entries: List<LogEntry>) {
            if (entries.any { failing(it.event) }) throw IllegalStateException("SQLITE_FULL")
            inner.append(sessionId, entries)
        }
    }

    private fun calls(vararg ids: String?): List<StreamChunk> =
        ids.mapIndexed { i, id -> StreamChunk.ToolCallDelta(i, id, "echo", "{}") } + StreamChunk.Finish("tool_calls")

    private fun TestScope.agentScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    private suspend fun TestScope.agent(adapter: ScriptedAdapter, store: SessionStore = InMemorySessionStore()): Agent {
        val harness = Harness()
        harness.llm.register(adapter)
        harness.tools.register(Echo)
        return harness.openAgent("s1", store, AgentConfig(LlmCallConfig("fake", "m")), agentScope(), clock = { 0L })
    }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = log.entries.value.map { it.event }.filterIsInstance<T>()

    @Test
    fun repeatedToolCallIdsAreMadeUniqueAcrossTheSession() = runTest {
        // An engine that numbers calls per answer (the old `local_<n>_<i>`) repeats ids.
        val adapter = ScriptedAdapter(
            mutableListOf(calls("local_1_0", "local_1_0"), calls("local_1_0"), calls(null, null)),
        )
        val agent = agent(adapter)

        agent.followup("go")
        advanceUntilIdle()

        val ids = agent.all<SessionEvent.AssistantMessage>().flatMap { m -> m.toolCalls.map { it.id } }
        assertEquals(5, ids.size)
        assertEquals(ids.toSet().size, ids.size)
        assertEquals("local_1_0", ids.first())
        // Every call got its own result, under the id its call was logged with.
        assertEquals(ids, agent.all<SessionEvent.ToolResult>().map { it.callId })
    }

    @Test
    fun aStoreFailingAtTurnEndLeavesTheAgentIdleAndTheDriverAlive() = runTest {
        val store = FlakyStore { it is SessionEvent.TurnEnd || it is SessionEvent.TurnReceipt }
        val agent = agent(ScriptedAdapter(mutableListOf()), store)

        agent.followup("first")
        advanceUntilIdle()
        assertEquals(AgentStatus.IDLE, agent.status.value)

        store.failing = { false }
        agent.followup("second")
        advanceUntilIdle()
        assertEquals(AgentStatus.IDLE, agent.status.value)
        assertEquals(2, agent.all<SessionEvent.AssistantMessage>().size)
        assertEquals(1, agent.all<SessionEvent.TurnEnd>().size)
    }

    @Test
    fun aStoreFailingOnToolResultsDoesNotLeaveCardsSpinning() = runTest {
        val store = FlakyStore { it is SessionEvent.ToolResult }
        val agent = agent(ScriptedAdapter(mutableListOf(calls("a", "b"))), store)

        agent.followup("go")
        advanceUntilIdle()

        assertEquals(AgentStatus.IDLE, agent.status.value)
        assertTrue(agent.runningTools.value.isEmpty())

        store.failing = { false }
        agent.followup("again")
        advanceUntilIdle()
        assertEquals(AgentStatus.IDLE, agent.status.value)
        assertTrue(agent.all<SessionEvent.AssistantMessage>().any { it.text == "done" })
    }

    @Test
    fun aFailingStoreAtTurnStartEndsTheTurnNotTheDriver() = runTest {
        val store = FlakyStore { it is SessionEvent.TurnStart }
        val agent = agent(ScriptedAdapter(mutableListOf()), store)

        agent.followup("first")
        advanceUntilIdle()
        assertEquals(AgentStatus.IDLE, agent.status.value)

        store.failing = { false }
        agent.followup("second")
        advanceUntilIdle()
        assertEquals("done", agent.all<SessionEvent.AssistantMessage>().single().text)
    }

    @Test
    fun nextSeqGoesAboveRowsTheStoreCouldNotDecode() = runTest {
        val readable = LogEntry(1, 0, SessionEvent.TurnStart(1))
        val store = object : SessionStore {
            val appended = mutableListOf<LogEntry>()

            override suspend fun load(sessionId: String) = listOf(readable)

            // Rows 2..5 exist but did not decode.
            override suspend fun loadLog(sessionId: String) = StoredLog(listOf(readable), 5)

            override suspend fun append(sessionId: String, entries: List<LogEntry>) {
                appended += entries
            }
        }
        val log = SessionLog.open("s", store)
        assertEquals(5, log.lastSeq)
        assertEquals(6, log.append(SessionEvent.TurnEnd(1, dev.wckdboy.autobot.agent.core.session.TurnEndReason.COMPLETED)).seq)
    }

    @Test
    fun aFailedAppendDoesNotReuseItsSeq() = runTest {
        val store = FlakyStore { it is SessionEvent.SessionTitle }
        val log = SessionLog.open("s", store)
        log.append(SessionEvent.TurnStart(1))
        runCatching { log.append(SessionEvent.SessionTitle("x")) }
        // The failed row may have landed: the next append must not collide with it.
        assertEquals(3, log.append(SessionEvent.TurnStart(2)).seq)
        assertEquals(listOf(1L, 3L), log.entries.value.map { it.seq })
    }
}
