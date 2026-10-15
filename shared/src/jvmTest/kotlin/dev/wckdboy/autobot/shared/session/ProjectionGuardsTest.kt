package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transcript rows must have unique keys (Compose and SwiftUI trap on duplicates), whatever the log holds. */
class ProjectionGuardsTest {

    private object Echo : ToolDefinition {
        override val name = "echo"
        override val kind = ToolKind.READ
        override val description = "test"
        override val parameters = schema { }

        override suspend fun execute(args: JsonObject, run: ToolRunContext) = ToolOutput("ok")
    }

    /** An engine that numbers tool calls per answer, so every answer repeats `local_1_0`. */
    private class RepeatingIds : LlmAdapter {
        override val provider = "fake"
        var answers = 0

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            if (++answers <= 3) {
                emit(StreamChunk.ToolCallDelta(0, "local_1_0", "echo", "{}"))
                emit(StreamChunk.ToolCallDelta(1, "local_1_0", "echo", "{}"))
                emit(StreamChunk.Finish("tool_calls"))
            } else {
                emit(StreamChunk.TextDelta("done"))
                emit(StreamChunk.Finish("stop"))
            }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    @Test
    fun duplicateToolCallIdsFromTheEngineProjectToUniqueKeys() = runBlocking {
        val harness = Harness()
        harness.llm.register(RepeatingIds())
        harness.tools.register(Echo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val agent = harness.openAgent("s", InMemorySessionStore(), AgentConfig(LlmCallConfig("fake", "m")), scope)
        agent.followup("go")
        withTimeout(10_000) {
            while (agent.log.entries.value.none { it.event is SessionEvent.TurnEnd } || agent.status.value != AgentStatus.IDLE) delay(10)
        }
        scope.cancel()

        val items = projectSession(agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }.items
        val tools = items.filterIsInstance<SessionItem.Tool>()
        assertEquals(6, tools.size)
        assertEquals(items.size, items.map { it.key }.toSet().size)
        assertTrue(tools.all { it.state == ToolState.OK })
    }

    @Test
    fun logsWrittenWithRepeatedIdsStillHaveUniqueKeys() {
        // What older builds stored: the same id in two steps, and twice in one message.
        val log = listOf(
            SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("local_1_0", "echo", "{}"), ToolCallRecord("local_1_0", "echo", "{}"))),
            SessionEvent.ToolResult("local_1_0", "echo", "first"),
            SessionEvent.ToolResult("local_1_0", "echo", "second"),
            SessionEvent.AssistantMessage(1, 2, "", toolCalls = listOf(ToolCallRecord("local_1_0", "echo", "{}"))),
            SessionEvent.ToolResult("local_1_0", "echo", "third"),
        ).mapIndexed { i, e -> LogEntry(i + 1L, 0L, e) }

        val items = projectSession(log, emptySet(), emptySet()) { _, _ -> "" }.items
        assertEquals(3, items.size)
        assertEquals(3, items.map { it.key }.toSet().size)
    }

    @Test
    fun malformedMetaAndThrowingSummariesNeverThrow() {
        val weird = listOf(
            buildJsonObject { put("gallery", JsonArray(listOf(JsonObject(emptyMap()), JsonArray(emptyList()), JsonNull, JsonPrimitive("g1")))) },
            buildJsonObject { put("gallery", "not-an-array") },
            buildJsonObject { put("subagent", buildJsonObject { put("steps", JsonArray(listOf(JsonPrimitive(3), JsonNull))) }) },
            buildJsonObject { put("subagent", "x") },
        )
        val log = buildList {
            weird.forEachIndexed { i, meta ->
                add(SessionEvent.AssistantMessage(1, i + 1, "", toolCalls = listOf(ToolCallRecord("c$i", "echo", "{not json"))))
                add(SessionEvent.ToolResult("c$i", "echo", "x", meta = meta))
            }
            // Results for calls that were never made, and a receipt with no answer to sit on.
            add(SessionEvent.ToolResult("ghost", "echo", "x"))
            add(SessionEvent.TurnReceipt(1, models = listOf(ReceiptModel("p", "m"))))
        }.mapIndexed { i, e -> LogEntry(i + 1L, 0L, e) }

        val p = projectSession(log, emptySet(), emptySet()) { _, _ -> error("describe blew up") }
        val tools = p.items.filterIsInstance<SessionItem.Tool>()
        assertEquals(4, tools.size)
        assertEquals(listOf("g1"), tools[0].galleryIds)
        assertTrue(tools.all { it.summary == "" })
    }
}
