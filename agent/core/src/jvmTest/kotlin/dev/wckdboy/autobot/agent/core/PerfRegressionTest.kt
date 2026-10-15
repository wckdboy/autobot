package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.approval.taintTrackingPlugin
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.PartialToolCall
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import java.util.Collections
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the agent-core hot paths of the performance audit (S1, S2, S6, S11). */
class PerfRegressionTest {

    private class Script(private val steps: List<List<StreamChunk>>) : LlmAdapter {
        override val provider = "fake"
        private var n = 0
        override fun stream(call: LlmCall): Flow<StreamChunk> = flow { (steps.getOrNull(n++) ?: reply("done")).forEach { emit(it) } }
        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 1_000_000)
    }

    private class Tool(
        override val name: String,
        override val kind: ToolKind,
        override val privateSource: PrivateSource? = null,
        private val output: (String) -> String = { "ok" },
    ) : ToolDefinition {
        override val description = "test"
        override val parameters = schema { string("text", "text", required = true) }
        val runs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun isConcurrencySafe(args: JsonObject) = true
        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            val text = args.string("text").orEmpty()
            runs += text
            return ToolOutput(output(text))
        }
    }

    private companion object {
        fun reply(text: String) = listOf(StreamChunk.TextDelta(text), StreamChunk.Finish("stop"))
        fun call(id: String, name: String, text: String) =
            listOf(StreamChunk.ToolCallDelta(0, id, name, buildJsonObject { put("text", text) }.toString()), StreamChunk.Finish("tool_calls"))
    }

    private suspend fun run(harness: Harness, script: List<List<StreamChunk>>, approve: Boolean = true): Agent {
        harness.llm.register(Script(script))
        harness.root.intercept(Hooks.ApprovalRequest) { _, _ ->
            if (approve) dev.wckdboy.autobot.agent.core.session.ApprovalOutcome.ALLOWED_ONCE else dev.wckdboy.autobot.agent.core.session.ApprovalOutcome.REJECTED
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val agent = harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("fake", "m")), scope, PermissionPreset.FULL_ACCESS)
        agent.followup("help me")
        withTimeout(20_000) {
            while (agent.log.entries.value.none { it.event is SessionEvent.TurnEnd }) delay(5)
        }
        return agent
    }

    // S1 ------------------------------------------------------------------------------------

    @Test
    fun taintIndexIsBroughtUpToDateOnlyBeforeNetworkTools() = runBlocking {
        val trackers = mutableListOf<TaintTracker>()
        val harness = Harness()
        harness.root.plugin(taintTrackingPlugin(TaintTracker.PRIORITY + 1) { TaintTracker().also { trackers += it } })
        val memory = Tool("memory_search", ToolKind.READ, PrivateSource("memory", names = true)) { "User's sister is Hannah Schmidt, mobile 0151 2345 6789." }
        val read = Tool("read", ToolKind.READ)
        val web = Tool("web_fetch", ToolKind.FETCH)
        listOf(memory, read, web).forEach { harness.tools.register(it) }
        val agent = run(
            harness,
            listOf(
                call("c1", "memory_search", "sister"),
                call("c2", "read", "a"),
                call("c3", "read", "b"),
                call("c4", "web_fetch", "https://example.com/?q=Hannah"),
            ),
            approve = false,
        )
        val tracker = trackers.single()
        // Three local tools, one network tool: one ingest pass.
        assertEquals(1, tracker.ingestAllCalls)
        // The private result was indexed once (by record), not again by the ingest.
        assertEquals(1, tracker.sizes(agent.id)["indexed"])
        assertEquals(0, tracker.sizes(agent.id)["recorded"])
        // And the leak was still caught.
        assertTrue(web.runs.isEmpty())
    }

    @Test
    fun hugeResultsAreCappedAndKeySetsStayBounded() = runBlocking {
        // 2 MB of private text full of ids, and a big public result: indexing is capped.
        val big = buildString { var i = 0; while (length < 2_000_000) append("id${i++}x${i * 7} Hannah${i} ") }
        val harness = Harness()
        val trackers = mutableListOf<TaintTracker>()
        harness.root.plugin(taintTrackingPlugin(TaintTracker.PRIORITY + 1) { TaintTracker().also { trackers += it } })
        harness.tools.register(Tool("memory_search", ToolKind.READ, PrivateSource("memory", names = true)) { big })
        harness.tools.register(Tool("read", ToolKind.READ) { big })
        harness.tools.register(Tool("web_fetch", ToolKind.FETCH))
        val started = TimeSource.Monotonic.markNow()
        val agent = run(
            harness,
            listOf(call("c1", "memory_search", "x"), call("c2", "read", "y"), call("c3", "web_fetch", "https://example.com/")),
        )
        assertTrue("took ${started.elapsedNow()}", started.elapsedNow() < 15.seconds)
        val sizes = trackers.single().sizes(agent.id)
        // 64K characters hold far fewer keys than 2 MB would.
        assertTrue("$sizes", sizes.getValue("fragments") in 100 until 20_000)
        assertTrue("$sizes", sizes.getValue("seen") <= TaintTracker.MAX_KEYS)
    }

    // S2 ------------------------------------------------------------------------------------

    @Test
    fun streamedArgumentsAccumulateInLinearTime() {
        val acc = Agent.StreamAccumulator(1, 1, 0L)
        val piece = "abcdefgh"
        val total = 1_048_576
        val started = TimeSource.Monotonic.markNow()
        var n = 0
        acc.accept(StreamChunk.ToolCallDelta(0, "w", "write", ""))
        while (n < total) {
            acc.accept(StreamChunk.ToolCallDelta(0, null, null, piece))
            n += piece.length
            // A UI snapshot every ~30 chunks, like the 33 ms publisher.
            if ((n / piece.length) % 30 == 0) {
                val live = acc.snapshot().toolCalls.single()
                assertEquals(n, live.argumentsLength)
                assertTrue(live.arguments.length <= PartialToolCall.LIVE_ARGUMENTS_TAIL)
            }
        }
        val calls = acc.completedCalls(1)
        assertTrue("took ${started.elapsedNow()}", started.elapsedNow() < 3.seconds)
        assertEquals(total, calls.single().arguments.length)
        assertEquals("write", calls.single().name)
    }

    @Test
    fun aOneMegabyteStreamedWriteCompletesQuickly() = runBlocking {
        val content = "x".repeat(1_048_576)
        val args = buildJsonObject { put("text", content) }.toString()
        val chunks = ArrayList<StreamChunk>()
        chunks += StreamChunk.ToolCallDelta(0, "w1", "write", "")
        args.chunked(16).forEach { chunks += StreamChunk.ToolCallDelta(0, null, null, it) }
        chunks += StreamChunk.Finish("tool_calls")
        val harness = Harness()
        val write = Tool("write", ToolKind.EDIT) { "written ${it.length}" }
        harness.tools.register(write)
        val started = TimeSource.Monotonic.markNow()
        val agent = run(harness, listOf(chunks))
        assertTrue("took ${started.elapsedNow()}", started.elapsedNow() < 15.seconds)
        assertEquals(listOf(content.length), write.runs.map { it.length })
        // S6: the ToolCall event no longer repeats the megabyte the assistant message holds.
        val toolCall = agent.log.entries.value.map { it.event }.filterIsInstance<SessionEvent.ToolCall>().single()
        assertEquals("", toolCall.arguments)
        assertEquals(args, agent.log.entries.value.map { it.event }.filterIsInstance<SessionEvent.AssistantMessage>().first().toolCalls.single().arguments)
    }

    // S6 ------------------------------------------------------------------------------------

    @Test
    fun toolCallEventsDecodeOldLogsAndKeepTheKey() {
        val old = """{"seq":3,"time":1,"event":{"type":"tool/call","callId":"c1","name":"read","arguments":"{\"file_path\":\"a.md\"}"}}"""
        val decoded = SessionJson.decodeFromString(LogEntry.serializer(), old).event as SessionEvent.ToolCall
        assertEquals("{\"file_path\":\"a.md\"}", decoded.arguments)
        val written = SessionJson.encodeToString(LogEntry.serializer(), LogEntry(4, 1, SessionEvent.ToolCall("c2", "read")))
        // Older readers (the iOS interim runtime) require the key; it is written, empty.
        assertTrue(written, "\"arguments\":\"\"" in written)
        val missing = """{"seq":5,"time":1,"event":{"type":"tool/call","callId":"c3","name":"read"}}"""
        assertEquals("", (SessionJson.decodeFromString(LogEntry.serializer(), missing).event as SessionEvent.ToolCall).arguments)
    }

    // S11 -----------------------------------------------------------------------------------

    @Test
    fun compactionCutPointMatchesTheQuadraticDefinition() {
        val compactor = Compactor()
        val random = Random(7)
        repeat(50) {
            var seq = 0L
            val visible = (0 until random.nextInt(2, 120)).map {
                val event = when (random.nextInt(4)) {
                    0 -> SessionEvent.UserMessage("u".repeat(random.nextInt(1, 2_000)))
                    1 -> SessionEvent.AssistantMessage(1, 1, "a".repeat(random.nextInt(0, 3_000)), toolCalls = listOf(ToolCallRecord("c$seq", "read", "{}")))
                    2 -> SessionEvent.ToolResult("c$seq", "read", "r".repeat(random.nextInt(0, 9_000)))
                    else -> SessionEvent.UserMessage("ctx", UserMessageKind.CONTEXT, "cwd")
                }
                LogEntry(++seq, 0L, event)
            }
            val boundaries = visible.filter {
                val e = it.event
                (e is SessionEvent.UserMessage && e.kind == UserMessageKind.PROMPT) || e is SessionEvent.AssistantMessage
            }
            if (boundaries.size < 2) return@repeat
            val total = visible.sumOf { tokens(it) }.coerceAtLeast(1)
            val target = (total * 0.16).toInt()
            val expected = boundaries.drop(1).lastOrNull { b -> visible.filter { it.seq >= b.seq }.sumOf { tokens(it) } >= target }
            assertEquals(expected, compactor.cutPoint(visible, boundaries))
        }
    }

    /** Compactor.entryTokens, restated. */
    private fun tokens(entry: LogEntry): Int = when (val e = entry.event) {
        is SessionEvent.UserMessage -> e.text.length / 4 + 4
        is SessionEvent.AssistantMessage -> (e.text.length + (e.reasoning?.length ?: 0) + e.toolCalls.sumOf { it.arguments.length + 16 }) / 4
        is SessionEvent.ToolResult -> e.content.length / 4 + 8
        else -> 0
    }
}
