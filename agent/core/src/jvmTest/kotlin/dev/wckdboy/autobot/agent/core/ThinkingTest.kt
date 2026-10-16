package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingPlan
import dev.wckdboy.autobot.agent.core.llm.ThinkingPolicy
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.chooseThinking
import dev.wckdboy.autobot.agent.core.loop.restoreThinking
import dev.wckdboy.autobot.agent.core.loop.thinking
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThinkingTest {

    // ---- budget computation ------------------------------------------------------------------

    @Test
    fun offIsOffForEveryModel() {
        for (size in listOf(0.8, 8.0, 70.0, null)) {
            assertEquals(ThinkingPlan.OFF, ThinkingPolicy.plan(ThinkingSetting.OFF, quickChat = false, paramsB = size, outputLimit = 4096))
        }
    }

    @Test
    fun autoOnATinyModelThinksOnlyInAgentMode() {
        // Quick chat on a sub-4B model: off (it would spend its whole answer reasoning).
        assertFalse(ThinkingPolicy.plan(null, quickChat = true, paramsB = 0.8, outputLimit = 4096).enabled)
        // Agent / code mode: min(1024, 25 % of the output).
        assertEquals(1024, ThinkingPolicy.plan(null, quickChat = false, paramsB = 0.8, outputLimit = 4096).budget)
        assertEquals(500, ThinkingPolicy.plan(ThinkingSetting.AUTO, quickChat = false, paramsB = 3.9, outputLimit = 2000).budget)
    }

    @Test
    fun autoScalesWithTheModel() {
        val out = 16_384
        val small = ThinkingPolicy.plan(null, false, 1.7, out).budget!!
        val medium = ThinkingPolicy.plan(null, false, 8.0, out).budget!!
        val large = ThinkingPolicy.plan(null, false, 32.0, out).budget!!
        assertEquals(listOf(1024, 2048, 4096), listOf(small, medium, large))
        // Unknown size counts as large; quick chat on bigger models thinks a little.
        assertEquals(large, ThinkingPolicy.plan(null, false, null, out).budget)
        assertEquals(1024, ThinkingPolicy.plan(null, true, 8.0, out).budget)
        assertEquals(2048, ThinkingPolicy.plan(null, true, 32.0, out).budget)
    }

    @Test
    fun onUsesTheChosenBudgetOrTheModelsDefault() {
        assertEquals(128, ThinkingPolicy.plan(ThinkingSetting(ThinkingMode.ON, 128), true, 0.8, 4096).budget)
        assertEquals(2048, ThinkingPolicy.plan(ThinkingSetting(ThinkingMode.ON), true, 0.8, 4096).budget)
        assertTrue(ThinkingPolicy.plan(ThinkingSetting(ThinkingMode.ON), true, 0.8, 4096).enabled)
    }

    @Test
    fun theAnswerAlwaysKeepsItsReserve() {
        for (out in listOf(256, 1000, 4096, 50_000)) {
            val plan = ThinkingPolicy.plan(ThinkingSetting(ThinkingMode.ON, 1_000_000), false, 70.0, out)
            assertTrue("output $out", plan.budget!! + plan.answerReserve <= out)
            assertTrue("output $out", plan.answerReserve >= minOf(64, out / 2))
        }
        assertEquals(1024, ThinkingPolicy.answerReserve(8192))
        assertEquals(64, ThinkingPolicy.answerReserve(200))
    }

    // ---- the chat's switch -------------------------------------------------------------------

    private class Echo : LlmAdapter {
        override val provider = "fake"
        val calls = mutableListOf<LlmCall>()
        var script: List<StreamChunk> = listOf(StreamChunk.TextDelta("ok"), StreamChunk.Finish("stop"))

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            script.forEach { emit(it) }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private suspend fun TestScope.open(adapter: Echo, store: SessionStore = InMemorySessionStore()): Agent {
        val harness = Harness()
        harness.llm.register(adapter)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        return harness.openAgent("s1", store, AgentConfig(LlmCallConfig("fake", "m")), scope, clock = { 0L })
    }

    @Test
    fun theChatsChoiceGoesWithEveryRequestAndSurvivesReopening() = runTest {
        val store = InMemorySessionStore()
        val adapter = Echo()
        val agent = open(adapter, store)
        assertEquals(ThinkingSetting.AUTO, agent.thinking)
        agent.chooseThinking(ThinkingSetting(ThinkingMode.ON, 512))
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(ThinkingSetting(ThinkingMode.ON, 512), adapter.calls.single().config.thinking)
        agent.dispose()

        // Reopened: the chat's own choice beats the model's default.
        val again = open(Echo(), store)
        again.restoreThinking(ThinkingSetting.OFF)
        assertEquals(ThinkingSetting(ThinkingMode.ON, 512), again.thinking)
        again.dispose()
    }

    @Test
    fun aChatThatNeverChoseFollowsItsModelsDefault() = runTest {
        val agent = open(Echo())
        agent.restoreThinking(ThinkingSetting.OFF)
        assertEquals(ThinkingSetting.OFF, agent.thinking)
        agent.restoreThinking(null)
        assertEquals(ThinkingSetting.AUTO, agent.thinking)
        assertTrue(agent.log.entries.value.none { it.event is SessionEvent.ThinkingChanged })
        agent.dispose()
    }

    @Test
    fun theSwitchIsNotModelVisibleAndRoundTrips() {
        val event = SessionEvent.ThinkingChanged(ThinkingMode.ON, 256)
        val json = SessionJson.encodeToString(SessionEvent.serializer(), event)
        assertTrue(json, "\"agent/thinking\"" in json)
        assertEquals(event, SessionJson.decodeFromString(SessionEvent.serializer(), json))
    }

    // ---- what the engine reports -------------------------------------------------------------

    @Test
    fun reasoningProgressIsLiveAndTheStopLandsInTheMessage() = runTest {
        val adapter = Echo()
        adapter.script = listOf(
            StreamChunk.ReasoningDelta("Let me think"),
            StreamChunk.ReasoningStatus(64),
            StreamChunk.ReasoningStatus(130, done = true, stop = "budget"),
            StreamChunk.TextDelta("17:25"),
            StreamChunk.Usage(TokenUsage(inputTokens = 10, outputTokens = 150)),
            StreamChunk.Finish("stop"),
        )
        val agent = open(adapter)
        agent.followup("when?")
        advanceUntilIdle()
        val message = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.AssistantMessage }.single()
        assertEquals("budget", message.reasoningStop)
        // The provider did not count reasoning tokens: the engine's count is kept.
        assertEquals(130, message.usage?.reasoningTokens)
        assertEquals("Let me think", message.reasoning)
        assertEquals("17:25", message.text)
        agent.dispose()
    }
}
