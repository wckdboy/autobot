package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.policy.quotaFallbackPlugin
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
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
class QuotaFallbackTest {

    /** A provider whose [exhausted] models fail with a usage-limit error; the rest answer with their name. */
    private class LimitedAdapter(
        override val provider: String,
        private val helper: String? = null,
        private val exhausted: Set<String> = emptySet(),
        private val respond: (LlmCall) -> List<StreamChunk> = { reply("from ${it.config.model}") },
    ) : LlmAdapter {
        override val helperModel: String? get() = helper
        val calls = mutableListOf<LlmCall>()

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            if (call.config.model in exhausted) {
                throw LlmException(LlmFailure(LlmErrorCode.QUOTA, "Provider's 5-hour limit is used up", status = 429))
            }
            respond(call).forEach { emit(it) }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000, supportsTools = provider != "phone")
    }

    private object Echo : ToolDefinition {
        override val name = "echo"
        override val kind = ToolKind.READ
        override val description = "echo"
        override val parameters = schema { }
        override suspend fun execute(args: JsonObject, run: ToolRunContext) = ToolOutput("echoed")
    }

    private suspend fun TestScope.agent(
        vararg adapters: LlmAdapter,
        enabled: Boolean = true,
        onDevice: LlmCallConfig? = null,
        eligible: (LlmCallConfig) -> Boolean = { true },
    ): Agent {
        val harness = Harness()
        adapters.forEach { harness.llm.register(it) }
        harness.tools.register(Echo)
        harness.root.plugin(quotaFallbackPlugin({ enabled }, { onDevice }, { eligible(it) }))
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        return harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("go", "big")), scope, clock = { 0L })
    }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = log.entries.value.map { it.event }.filterIsInstance<T>()

    @Test
    fun offByDefaultTheLimitEndsTheTurn() = runTest {
        val go = LimitedAdapter("go", helper = "small", exhausted = setOf("big"))
        val agent = agent(go, enabled = false)
        agent.followup("hi")
        advanceUntilIdle()

        val end = agent.all<SessionEvent.TurnEnd>().single()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertTrue(end.detail!!.startsWith("QUOTA: "))
        assertEquals(listOf("big"), go.calls.map { it.config.model }) // not retried: QUOTA is final
    }

    @Test
    fun theTurnRetriesOnceOnTheHelperWithANotice() = runTest {
        var step = 0
        val go = LimitedAdapter("go", helper = "small", exhausted = setOf("big")) {
            step++
            if (step == 1) listOf(StreamChunk.ToolCallDelta(0, "c1", "echo", "{}"), StreamChunk.Finish("tool_calls")) else reply("done")
        }
        val agent = agent(go)
        agent.followup("hi")
        advanceUntilIdle()

        assertEquals(TurnEndReason.COMPLETED, agent.all<SessionEvent.TurnEnd>().single().reason)
        // The rest of the turn stays on the helper; only the first answer carries the notice.
        assertEquals(listOf("big", "small", "small"), go.calls.map { it.config.model })
        val answers = agent.all<SessionEvent.AssistantMessage>()
        assertEquals(listOf("small", "small"), answers.map { it.model })
        assertEquals("big hit its usage limit — answered by the helper model", answers.first().fallback)
        assertNull(answers.last().fallback)

        // The next turn starts on the chat's own model again.
        agent.followup("again")
        advanceUntilIdle()
        assertEquals("big", go.calls[3].config.model)
    }

    @Test
    fun withoutAHelperTheOnDeviceModelStandsIn() = runTest {
        val go = LimitedAdapter("go", exhausted = setOf("big"))
        val phone = LimitedAdapter("phone")
        val agent = agent(go, phone, onDevice = LlmCallConfig("phone", "gemma"))
        agent.followup("hi")
        advanceUntilIdle()

        val answer = agent.all<SessionEvent.AssistantMessage>().single()
        assertEquals("from gemma", answer.text)
        assertEquals("big hit its usage limit — answered by the on-device model", answer.fallback)
        assertTrue(phone.calls.single().tools.isEmpty()) // the stand-in's own capabilities apply
    }

    @Test
    fun aHelperThatIsAlsoExhaustedOrIneligibleIsSkipped() = runTest {
        val go = LimitedAdapter("go", helper = "small", exhausted = setOf("big", "small"))
        val phone = LimitedAdapter("phone")
        val agent = agent(go, phone, onDevice = LlmCallConfig("phone", "gemma"), eligible = { it.model != "small" })
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals("from gemma", agent.all<SessionEvent.AssistantMessage>().single().text)

        // Only one fallback per turn: a stand-in that fails too ends the turn.
        val stuck = LimitedAdapter("go", helper = "small", exhausted = setOf("big", "small"))
        val other = agent(stuck)
        other.followup("hi")
        advanceUntilIdle()
        assertEquals(listOf("big", "small"), stuck.calls.map { it.config.model })
        assertEquals(TurnEndReason.ERROR, other.all<SessionEvent.TurnEnd>().single().reason)
    }

    @Test
    fun otherFailuresAndNoTargetFailAsBefore() = runTest {
        val go = LimitedAdapter("go", exhausted = setOf("big"))
        val agent = agent(go) // no helper, no on-device model
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(TurnEndReason.ERROR, agent.all<SessionEvent.TurnEnd>().single().reason)
        assertEquals(1, go.calls.size)
    }

    @Test
    fun compactionSummariesUseTheHelper() = runTest {
        val go = LimitedAdapter("go", helper = "small")
        val agent = agent(go)
        agent.followup("first")
        advanceUntilIdle()
        agent.followup("second")
        advanceUntilIdle()

        assertTrue(agent.ctx.require(Compactor.Key).compact(agent, "manual"))
        val summaryCall = go.calls.last()
        assertEquals("small", summaryCall.config.model)
        assertEquals("s1", summaryCall.sessionId)
        assertEquals("from small", agent.all<SessionEvent.UserMessage>().last { it.kind == UserMessageKind.SUMMARY }.text)
    }

    private companion object {
        fun reply(text: String) = listOf(StreamChunk.TextDelta(text), StreamChunk.Finish("stop"))
    }
}
