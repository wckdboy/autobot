package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.loop.chooseThinking
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskToolTest {

    /** Answers by who is asking: [respond] sees the request and returns the chunks to stream. */
    private class RoutingAdapter(private val helper: String? = null, private val respond: (LlmCall) -> List<StreamChunk>) : LlmAdapter {
        override val provider = "fake"
        override val helperModel: String? get() = helper
        val calls: MutableList<LlmCall> = Collections.synchronizedList(mutableListOf())

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            respond(call).forEach { emit(it) }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private class Probe(override val name: String, override val kind: ToolKind) : ToolDefinition {
        override val description = "test"
        override val parameters = schema { string("text", "text", required = true) }
        val running = AtomicInteger()
        val maxConcurrent = AtomicInteger()
        override fun isConcurrencySafe(args: JsonObject) = true
        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            maxConcurrent.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                delay(10)
                return ToolOutput("$name:${args.string("text")}")
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun reply(text: String) = listOf(StreamChunk.TextDelta(text), StreamChunk.Finish("stop"))

    private fun call(id: String, name: String, args: String) =
        listOf(StreamChunk.ToolCallDelta(0, id, name, args), StreamChunk.Finish("tool_calls"))

    private fun task(id: String, description: String, prompt: String, index: Int = 0) =
        StreamChunk.ToolCallDelta(index, id, "task", """{"description":"$description","prompt":"$prompt"}""")

    private val LlmCall.isSubagent get() = messages.any { it.role == ModelRole.SYSTEM && "You are a subagent" in it.text }
    private val LlmCall.prompt get() = messages.first { it.role == ModelRole.USER && !it.text.startsWith("<") }.text
    private val LlmCall.toolResults get() = messages.filter { it.role == ModelRole.TOOL }

    private suspend fun TestScope.agent(adapter: LlmAdapter, vararg tools: ToolDefinition): Agent {
        val harness = Harness()
        harness.llm.register(adapter)
        tools.forEach { harness.tools.register(it) }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        return harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("fake", "m")), scope, clock = { 0L })
    }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = log.entries.value.map { it.event }.filterIsInstance<T>()

    @Test
    fun subagentWorksWithToolsAndReportsBack() = runTest {
        val read = Probe("read", ToolKind.READ)
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent && call.toolResults.isEmpty() -> call("k1", "read", """{"text":"notes.md"}""")
                call.isSubagent -> reply("notes.md says hello")
                call.toolResults.isEmpty() -> listOf(task("t1", "read the notes", "Read notes.md and report."), StreamChunk.Finish("tool_calls"))
                else -> reply("done: ${call.toolResults.single().text}")
            }
        }
        val agent = agent(adapter, read)
        agent.followup("summarise my notes")
        advanceUntilIdle()

        val result = agent.all<SessionEvent.ToolResult>().single()
        assertEquals("task", result.name)
        assertFalse(result.isError)
        assertEquals("notes.md says hello", result.content)
        assertEquals(listOf(Triple("read", "text=notes.md", "ok")), TaskTool.steps(result.meta))
        assertEquals("done: notes.md says hello", agent.all<SessionEvent.AssistantMessage>().last().text)

        // The subagent saw only its prompt, never the parent conversation, and could not delegate further.
        val childCalls = adapter.calls.filter { it.isSubagent }
        assertEquals(2, childCalls.size)
        assertTrue(childCalls.all { c -> c.messages.none { "summarise my notes" in it.text } })
        assertEquals("Read notes.md and report.", childCalls.first().prompt)
        assertTrue(childCalls.all { c -> c.tools.none { it.name == "task" } })
        assertTrue(adapter.calls.filterNot { it.isSubagent }.first().tools.any { it.name == "task" })
        // Nothing of the subagent lands in the parent log except the task call and its result.
        assertEquals(listOf("task"), agent.all<SessionEvent.ToolCall>().map { it.name })
    }

    @Test
    fun aToolNotOfferedToAnAgentStaysOutOfItsRequests() = runTest {
        // Like show_ui: offered to the chat's own agent, never to a subagent.
        val mainOnly = object : ToolDefinition by Probe("main_only", ToolKind.INTERACT) {
            override fun offeredTo(agent: Agent) = !TaskTool.isSubagent(agent.id)
        }
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent -> reply("report")
                call.toolResults.isEmpty() -> listOf(task("t1", "job", "a"), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter, mainOnly, Probe("read", ToolKind.READ))
        agent.followup("go")
        advanceUntilIdle()

        assertTrue(adapter.calls.filterNot { it.isSubagent }.all { c -> c.tools.any { it.name == "main_only" } })
        val child = adapter.calls.filter { it.isSubagent }
        assertEquals(1, child.size)
        assertTrue(child.single().tools.none { it.name == "main_only" })
        assertTrue(child.single().tools.any { it.name == "read" })
    }

    @Test
    fun subagentsRunOnTheHelperModelUnlessAskedForTheMainOne() = runTest {
        val adapter = RoutingAdapter(helper = "fast") { call ->
            when {
                call.isSubagent -> reply("report ${call.prompt}")
                call.toolResults.isEmpty() -> listOf(
                    task("t1", "cheap", "a", 0),
                    StreamChunk.ToolCallDelta(1, "t2", "task", """{"description":"strong","prompt":"b","model":"main"}"""),
                    StreamChunk.Finish("tool_calls"),
                )
                else -> reply("ok")
            }
        }
        val agent = agent(adapter)
        agent.followup("fan out")
        advanceUntilIdle()

        val byPrompt = adapter.calls.filter { it.isSubagent }.associate { it.prompt to it.config.model }
        assertEquals(mapOf("a" to "fast", "b" to "m"), byPrompt)
        assertTrue(adapter.calls.filterNot { it.isSubagent }.all { it.config.model == "m" })
        assertTrue(adapter.calls.all { it.sessionId != null })
    }

    @Test
    fun withoutAHelperSubagentsUseTheMainModel() = runTest {
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent -> reply("report")
                call.toolResults.isEmpty() -> listOf(task("t1", "job", "a"), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter)
        agent.followup("go")
        advanceUntilIdle()

        assertEquals(listOf("m"), adapter.calls.filter { it.isSubagent }.map { it.config.model })
        assertEquals("report", agent.all<SessionEvent.ToolResult>().single().content)
    }

    @Test
    fun subagentsThinkNeverWhateverTheChatChose() = runTest {
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent -> reply("report")
                call.toolResults.isEmpty() -> listOf(task("t1", "job", "a"), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter)
        agent.chooseThinking(ThinkingSetting(ThinkingMode.ON, 2048))
        agent.followup("go")
        advanceUntilIdle()

        assertEquals(listOf(ThinkingSetting.OFF), adapter.calls.filter { it.isSubagent }.map { it.config.thinking })
        assertTrue(adapter.calls.filterNot { it.isSubagent }.all { it.config.thinking == ThinkingSetting(ThinkingMode.ON, 2048) })
    }

    @Test
    fun tasksInOneStepRunInParallel() = runTest {
        val read = Probe("read", ToolKind.READ)
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent && call.toolResults.isEmpty() -> call("k-${call.prompt}", "read", """{"text":"${call.prompt}"}""")
                call.isSubagent -> reply("report ${call.prompt}")
                call.toolResults.isEmpty() -> listOf(task("t1", "one", "a", 0), task("t2", "two", "b", 1), task("t3", "three", "c", 2), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter, read)
        agent.followup("fan out")
        advanceUntilIdle()

        assertEquals(listOf("report a", "report b", "report c"), agent.all<SessionEvent.ToolResult>().map { it.content })
        assertEquals(3, read.maxConcurrent.get())
    }

    @Test
    fun subagentApprovalsSurfaceOnTheParentTaskCard() = runTest {
        val fetch = Probe("fetch", ToolKind.FETCH)
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent && call.toolResults.isEmpty() -> call("k1", "fetch", """{"text":"example.org"}""")
                call.isSubagent -> reply("fetched: ${call.toolResults.single().text}")
                call.toolResults.isEmpty() -> listOf(task("t1", "fetch it", "Fetch example.org."), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter, fetch)
        val asked = mutableListOf<ApprovalRequest>()
        agent.ctx.intercept(Hooks.ApprovalRequest) { request, _ ->
            asked += request
            ApprovalOutcome.ALLOWED_FOR_SESSION
        }
        agent.followup("go")
        advanceUntilIdle()

        val request = asked.single()
        assertEquals(agent.id, request.agent.id)
        assertEquals("t1", request.callId)
        assertEquals("fetch", request.toolName)
        assertEquals("fetch · text=example.org", request.summary)
        assertEquals("fetched: fetch:example.org", agent.all<SessionEvent.ToolResult>().single().content)
        assertTrue("fetch" in agent.sessionGrants)
    }

    @Test
    fun rejectedSubagentStepIsReportedNotFatal() = runTest {
        val fetch = Probe("fetch", ToolKind.FETCH)
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent && call.toolResults.isEmpty() -> call("k1", "fetch", """{"text":"x"}""")
                call.isSubagent -> reply("could not fetch (${call.toolResults.single().text})")
                call.toolResults.isEmpty() -> listOf(task("t1", "fetch", "Fetch x."), StreamChunk.Finish("tool_calls"))
                else -> reply("ok")
            }
        }
        val agent = agent(adapter, fetch)
        agent.ctx.intercept(Hooks.ApprovalRequest) { _, _ -> ApprovalOutcome.REJECTED }
        agent.followup("go")
        advanceUntilIdle()

        val result = agent.all<SessionEvent.ToolResult>().single()
        assertFalse(result.isError)
        assertEquals(listOf(Triple("fetch", "text=x", "error")), TaskTool.steps(result.meta))
        assertEquals(0, fetch.maxConcurrent.get())
    }

    @Test
    fun cancellingTheParentStopsTheSubagent() = runTest {
        val adapter = RoutingAdapter { call ->
            when {
                call.isSubagent -> call("k${call.toolResults.size}", "slow", """{"text":"x"}""")
                else -> listOf(task("t1", "loop", "Loop."), StreamChunk.Finish("tool_calls"))
            }
        }
        val slow = object : ToolDefinition {
            override val name = "slow"
            override val kind = ToolKind.READ
            override val description = "slow"
            override val parameters = schema { string("text", "text") }
            override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
                delay(1_000)
                return ToolOutput("tick")
            }
        }
        val agent = agent(adapter, slow)
        agent.followup("go")
        testScheduler.advanceTimeBy(2_500)
        agent.cancel()
        advanceUntilIdle()

        assertEquals(TurnEndReason.ABORTED, agent.all<SessionEvent.TurnEnd>().single().reason)
        val requestsAtCancel = adapter.calls.size
        testScheduler.advanceTimeBy(10_000)
        advanceUntilIdle()
        assertEquals(requestsAtCancel, adapter.calls.size)
    }
}
