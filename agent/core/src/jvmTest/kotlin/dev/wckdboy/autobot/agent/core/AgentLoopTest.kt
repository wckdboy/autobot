package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SurfaceOp
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopTest {

    /** Replays one scripted response per request and records every call. */
    private class ScriptedAdapter(
        private val script: MutableList<suspend () -> List<StreamChunk>>,
        private val contextWindow: Int = 100_000,
    ) : LlmAdapter {
        override val provider = "fake"
        val calls = mutableListOf<LlmCall>()

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            val next = script.removeFirstOrNull() ?: error("Unexpected request #${calls.size}")
            next().forEach { emit(it) }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = contextWindow)
    }

    private fun reply(text: String, usage: TokenUsage? = null): suspend () -> List<StreamChunk> = {
        listOfNotNull(StreamChunk.TextDelta(text), usage?.let { StreamChunk.Usage(it) }, StreamChunk.Finish("stop"))
    }

    private fun toolCalls(vararg calls: Triple<String, String, String>): suspend () -> List<StreamChunk> = {
        calls.mapIndexed { i, (id, name, args) -> StreamChunk.ToolCallDelta(i, id, name, args) } + StreamChunk.Finish("tool_calls")
    }

    private class TestTool(
        override val name: String,
        override val kind: ToolKind,
        private val safe: Boolean = true,
        private val work: suspend (JsonObject) -> ToolOutput = { ToolOutput("echo:${it.string("text")}") },
    ) : ToolDefinition {
        override val description = "test"
        override val parameters = schema { string("text", "text", required = true) }
        val running = AtomicInteger()
        val maxConcurrent = AtomicInteger()

        override fun isConcurrencySafe(args: JsonObject) = safe

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            val now = running.incrementAndGet()
            maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            try {
                delay(10)
                return work(args)
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private suspend fun TestScope.agent(
        adapter: ScriptedAdapter,
        vararg tools: ToolDefinition,
        permission: PermissionPreset = PermissionPreset.WORKSPACE,
        scope: CoroutineScope = agentScope(),
    ): Agent {
        val harness = Harness()
        harness.llm.register(adapter)
        tools.forEach { harness.tools.register(it) }
        return harness.openAgent(
            "s1",
            InMemorySessionStore(),
            AgentConfig(LlmCallConfig("fake", "m")),
            scope,
            permission,
            clock = { 0L },
        )
    }

    /** Foreground scope on the test scheduler (advanceUntilIdle ignores backgroundScope-only work). */
    private fun TestScope.agentScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

    private fun Agent.events() = log.entries.value.map { it.event }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = events().filterIsInstance<T>()

    @Test
    fun plainTurnLogsPromptSystemAndAnswer() = runTest {
        val adapter = ScriptedAdapter(mutableListOf(reply("hello", TokenUsage(10, 2))))
        val agent = agent(adapter)

        agent.followup("hi")
        advanceUntilIdle()

        assertEquals("hello", agent.all<SessionEvent.AssistantMessage>().single().text)
        assertEquals(TurnEndReason.COMPLETED, agent.all<SessionEvent.TurnEnd>().single().reason)
        assertEquals(AgentStatus.IDLE, agent.status.value)
        val request = adapter.calls.single()
        assertEquals(ModelRole.SYSTEM, request.messages.first().role)
        assertTrue(request.messages.first().text.contains("Autobot"))
        assertEquals("hi", request.messages.last().text)
        assertTrue("built-in tools are offered", request.tools.any { it.name == "todo_write" })
    }

    @Test
    fun parallelSafeToolsRunTogetherAndResultsFeedTheNextStepInOrder() = runTest {
        val read = TestTool("read", ToolKind.READ)
        val adapter = ScriptedAdapter(
            mutableListOf(
                toolCalls(
                    Triple("c1", "read", """{"text":"a"}"""),
                    Triple("c2", "read", """{"text":"b"}"""),
                    Triple("c3", "read", """{"text":"c"}"""),
                ),
                reply("done"),
            ),
        )
        val agent = agent(adapter, read)

        agent.followup("go")
        advanceUntilIdle()

        assertEquals(3, read.maxConcurrent.get())
        assertEquals(listOf("c1", "c2", "c3"), agent.all<SessionEvent.ToolResult>().map { it.callId })
        val second = adapter.calls[1].messages.filter { it.role == ModelRole.TOOL }.map { it.text }
        assertEquals(listOf("echo:a", "echo:b", "echo:c"), second)
        assertEquals("done", agent.all<SessionEvent.AssistantMessage>().last().text)
    }

    @Test
    fun exclusiveToolIsABarrier() = runTest {
        val write = TestTool("write", ToolKind.EDIT, safe = false)
        val adapter = ScriptedAdapter(
            mutableListOf(
                toolCalls(Triple("c1", "write", """{"text":"a"}"""), Triple("c2", "write", """{"text":"b"}""")),
                reply("ok"),
            ),
        )
        val agent = agent(adapter, write)
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(1, write.maxConcurrent.get())
    }

    @Test
    fun askRoutesToAnswererAndRejectionBecomesErrorResult() = runTest {
        val delete = TestTool("rm", ToolKind.DELETE)
        val adapter = ScriptedAdapter(
            mutableListOf(
                toolCalls(Triple("c1", "rm", """{"text":"x"}""")),
                toolCalls(Triple("c2", "rm", """{"text":"y"}""")),
                reply("fine"),
            ),
        )
        val agent = agent(adapter, delete)
        val answers = ArrayDeque(listOf(ApprovalOutcome.ALLOWED_ONCE, ApprovalOutcome.REJECTED))
        agent.ctx.intercept(Hooks.ApprovalRequest) { _, _ -> answers.removeFirst() }

        agent.followup("clean")
        advanceUntilIdle()

        val results = agent.all<SessionEvent.ToolResult>()
        assertEquals("echo:x", results[0].content)
        assertEquals("USER_REJECTED", results[1].code)
        assertEquals(2, agent.all<SessionEvent.ApprovalAsked>().size)
        assertEquals(TurnEndReason.COMPLETED, agent.all<SessionEvent.TurnEnd>().single().reason)
    }

    @Test
    fun askWithoutAnswererFailsClosed() = runTest {
        val fetch = TestTool("fetch", ToolKind.FETCH)
        val adapter = ScriptedAdapter(mutableListOf(toolCalls(Triple("c1", "fetch", """{"text":"u"}""")), reply("ok")))
        val agent = agent(adapter, fetch)
        agent.followup("get")
        advanceUntilIdle()
        assertEquals("APPROVAL_UNAVAILABLE", agent.all<SessionEvent.ToolResult>().single().code)
        assertEquals(0, fetch.maxConcurrent.get())
        assertEquals(ApprovalOutcome.UNAVAILABLE, agent.all<SessionEvent.ApprovalDecided>().single().outcome)
    }

    @Test
    fun sessionGrantSkipsLaterPrompts() = runTest {
        val fetch = TestTool("fetch", ToolKind.FETCH)
        val adapter = ScriptedAdapter(
            mutableListOf(
                toolCalls(Triple("c1", "fetch", """{"text":"1"}""")),
                toolCalls(Triple("c2", "fetch", """{"text":"2"}""")),
                reply("ok"),
            ),
        )
        val agent = agent(adapter, fetch)
        var asked = 0
        agent.ctx.intercept(Hooks.ApprovalRequest) { _, _ ->
            asked++
            ApprovalOutcome.ALLOWED_FOR_SESSION
        }
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(1, asked)
        assertTrue(agent.all<SessionEvent.ToolResult>().none { it.isError })
    }

    @Test
    fun readOnlyPresetDeniesEdits() = runTest {
        val write = TestTool("write", ToolKind.EDIT)
        val adapter = ScriptedAdapter(mutableListOf(toolCalls(Triple("c1", "write", """{"text":"x"}""")), reply("ok")))
        val agent = agent(adapter, write, permission = PermissionPreset.READ_ONLY)
        agent.followup("edit")
        advanceUntilIdle()
        assertEquals("PERMISSION_DENIED", agent.all<SessionEvent.ToolResult>().single().code)
    }

    @Test
    fun badCallsBecomeErrorResultsNotTurnFailures() = runTest {
        val read = TestTool("read", ToolKind.READ)
        val adapter = ScriptedAdapter(
            mutableListOf(
                toolCalls(
                    Triple("c1", "nope", "{}"),
                    Triple("c2", "read", "{not json"),
                    Triple("c3", "read", """{"text":1}"""),
                    Triple("c4", "read", """{"text":"x","extra":true}"""),
                ),
                reply("recovered"),
            ),
        )
        val agent = agent(adapter, read)
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(
            listOf("UNKNOWN_TOOL", "INVALID_ARGS", "INVALID_ARGS", "INVALID_ARGS"),
            agent.all<SessionEvent.ToolResult>().map { it.code },
        )
        assertEquals(TurnEndReason.COMPLETED, agent.all<SessionEvent.TurnEnd>().single().reason)
    }

    @Test
    fun retryableFailureIsRetriedWithinTheStep() = runTest {
        val adapter = ScriptedAdapter(
            mutableListOf(
                { throw LlmException(LlmFailure(LlmErrorCode.RATE_LIMIT, "slow down", 429)) },
                reply("ok"),
            ),
        )
        val agent = agent(adapter)
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(1, agent.all<SessionEvent.LlmRetry>().size)
        assertEquals("ok", agent.all<SessionEvent.AssistantMessage>().single().text)
        assertEquals(1, agent.all<SessionEvent.StepStart>().size)
    }

    @Test
    fun nonRetryableFailureEndsTurnWithError() = runTest {
        val adapter = ScriptedAdapter(mutableListOf({ throw LlmException(LlmFailure(LlmErrorCode.BLOCKED, "offline")) }))
        val agent = agent(adapter)
        agent.followup("hi")
        advanceUntilIdle()
        val end = agent.all<SessionEvent.TurnEnd>().single()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertTrue(end.detail!!.contains("offline"))
        assertTrue(agent.all<SessionEvent.LlmRetry>().isEmpty())
    }

    @Test
    fun cancelMidStreamCommitsInterruptedMessage() = runTest {
        val adapter = ScriptedAdapter(
            mutableListOf({
                // Emit is done by the flow; this lambda only returns chunks, so stall before returning.
                awaitCancellation()
            }),
        )
        val partial = object : LlmAdapter by adapter {
            override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
                emit(StreamChunk.ReasoningDelta("thinking"))
                emit(StreamChunk.TextDelta("half an ans"))
                awaitCancellation()
            }
        }
        val harness = Harness()
        harness.llm.register(partial)
        val agent = harness.openAgent("s", InMemorySessionStore(), AgentConfig(LlmCallConfig("fake", "m")), agentScope())

        agent.followup("long task")
        advanceUntilIdle()
        assertNotNull(agent.live.value)
        agent.cancel()
        advanceUntilIdle()

        val message = agent.all<SessionEvent.AssistantMessage>().single()
        assertTrue(message.interrupted)
        assertEquals("half an ans", message.text)
        assertEquals(TurnEndReason.ABORTED, agent.all<SessionEvent.TurnEnd>().single().reason)
        assertEquals(AgentStatus.IDLE, agent.status.value)
    }

    @Test
    fun cancelDuringToolsAnswersEveryCall() = runTest {
        val slow = TestTool("slow", ToolKind.READ, safe = false, work = { awaitCancellation() })
        val adapter = ScriptedAdapter(
            mutableListOf(toolCalls(Triple("c1", "slow", """{"text":"a"}"""), Triple("c2", "slow", """{"text":"b"}"""))),
        )
        val agent = agent(adapter, slow)
        agent.followup("go")
        advanceTimeBy(1_000)
        runCurrent()
        agent.cancel()
        advanceUntilIdle()
        assertEquals(
            listOf("TOOL_OUTCOME_UNKNOWN", "ABORTED_BEFORE_DISPATCH"),
            agent.all<SessionEvent.ToolResult>().map { it.code },
        )
    }

    @Test
    fun steerIsDeliveredAtNextStepBoundary() = runTest {
        val read = TestTool("read", ToolKind.READ)
        val adapter = ScriptedAdapter(mutableListOf(toolCalls(Triple("c1", "read", """{"text":"a"}""")), reply("ok")))
        val agent = agent(adapter, read)
        agent.followup("start")
        agent.steer("also check b")
        advanceUntilIdle()
        // Steer queued before the turn began is claimed together with the turn input.
        assertEquals(1, agent.all<SessionEvent.TurnStart>().size)
        assertTrue(adapter.calls[0].messages.any { it.text == "also check b" })
    }

    @Test
    fun contextsAreLoggedOnlyWhenChanged() = runTest {
        val adapter = ScriptedAdapter(mutableListOf(reply("1"), reply("2"), reply("3")))
        val agent = agent(adapter)
        var cwd = "/a"
        agent.ctx.require(dev.wckdboy.autobot.agent.core.prompt.SystemPromptService.Key)
            .context(dev.wckdboy.autobot.agent.core.prompt.PromptContext("cwd", 100) { "cwd=$cwd" })

        agent.followup("x")
        advanceUntilIdle()
        agent.followup("y")
        advanceUntilIdle()
        cwd = "/b"
        agent.followup("z")
        advanceUntilIdle()

        val contexts = agent.all<SessionEvent.UserMessage>().filter { it.kind == UserMessageKind.CONTEXT }.map { it.text }
        assertEquals(listOf("cwd=/a", "cwd=/b"), contexts)
    }

    @Test
    fun pressureTriggersCompactionThatReplacesOlderHistory() = runTest {
        val big = "x".repeat(4_000)
        val adapter = ScriptedAdapter(
            mutableListOf(reply(big), reply("SUMMARY TEXT"), reply("after")),
            contextWindow = 4_000,
        )
        val agent = agent(adapter)
        agent.followup("first")
        advanceUntilIdle()
        // The next pre-step is over budget: the summarizer request runs before the turn's own request.
        agent.followup("second")
        advanceUntilIdle()

        val summary = agent.log.entries.value.single {
            (it.event as? SessionEvent.UserMessage)?.kind == UserMessageKind.SUMMARY
        }
        assertTrue(summary.surface is SurfaceOp.Replace)
        val lastRequest = adapter.calls.last().messages.map { it.text }
        assertTrue(lastRequest.any { "SUMMARY TEXT" in it })
        assertFalse("compacted history is not resent", lastRequest.any { it == "first" })
        assertEquals("second", lastRequest.last())
        assertEquals("after", agent.all<SessionEvent.AssistantMessage>().last().text)
    }
}
