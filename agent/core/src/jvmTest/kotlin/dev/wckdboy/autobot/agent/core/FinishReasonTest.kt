package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** How each way a model answer can end becomes a turn outcome. */
@OptIn(ExperimentalCoroutinesApi::class)
class FinishReasonTest {

    private suspend fun kotlinx.coroutines.test.TestScope.turn(vararg replies: Reply): Pair<dev.wckdboy.autobot.agent.core.loop.Agent, FakeModel> {
        val model = FakeModel(replies = replies.toList())
        val agent = Rig(this, model, FakeTool("read")).open()
        agent.followup("hi")
        advanceUntilIdle()
        return agent to model
    }

    @Test
    fun errorFinishWithTextKeepsTheTextAndFailsTheTurn() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(chunks(StreamChunk.TextDelta("partial"), StreamChunk.Finish("error")))
        assertEquals("partial", agent.all<SessionEvent.AssistantMessage>().single().text)
        val end = agent.end()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertTrue(end.detail!!, end.detail!!.startsWith("PROTOCOL: the model stopped mid-answer"))
        assertEquals("not retried", 1, model.calls.size)
    }

    @Test
    fun contentFilterFinishFailsTheTurnWithItsOwnReason() = runTest(timeout = 10.seconds) {
        val (agent, _) = turn(chunks(StreamChunk.TextDelta("I can"), StreamChunk.Finish("content_filter")))
        val end = agent.end()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertEquals("PROTOCOL: the provider's content filter cut the answer off", end.detail)
    }

    @Test
    fun anEngineStopBeforeAnyTokenIsNotRetriedAsAnEmptyAnswer() = runTest(timeout = 10.seconds) {
        // On-device out of memory before the first token: retrying five times only repeats it.
        val (agent, model) = turn(chunks(StreamChunk.Finish("error")))
        assertEquals(1, model.calls.size)
        assertTrue(agent.all<SessionEvent.LlmRetry>().isEmpty())
        assertTrue(agent.end().detail!!, agent.end().detail!!.contains("stopped mid-answer"))
    }

    @Test
    fun aFilteredAnswerWithNoContentSaysFilteredNotEmpty() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(chunks(StreamChunk.Finish("content_filter")))
        assertEquals(1, model.calls.size)
        assertEquals("PROTOCOL: the provider's content filter cut the answer off", agent.end().detail)
    }

    @Test
    fun lengthFinishEndsTheTurnAsMaxTokensAndKeepsTheText() = runTest(timeout = 10.seconds) {
        val (agent, _) = turn(say("a long answer that got cut", finish = "length"))
        assertEquals(TurnEndReason.MAX_TOKENS, agent.end().reason)
        assertEquals("a long answer that got cut", agent.all<SessionEvent.AssistantMessage>().single().text)
    }

    @Test
    fun lengthFinishWithNothingStreamedIsMaxTokensNotFiveRetries() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(chunks(StreamChunk.Usage(TokenUsage(outputTokens = 0)), StreamChunk.Finish("length")))
        assertEquals(1, model.calls.size)
        assertEquals(TurnEndReason.MAX_TOKENS, agent.end().reason)
    }

    @Test
    fun anEmptyResponseIsRetriedWithBackoffThenExplained() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(*Array(6) { chunks(StreamChunk.Finish("stop")) })
        assertEquals(6, model.calls.size)
        assertEquals(listOf(1, 2, 3, 4, 5), agent.all<SessionEvent.LlmRetry>().map { it.attempt })
        val end = agent.end()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertEquals("EMPTY_RESPONSE: The model returned an empty response", end.detail)
        assertEquals(1, agent.all<SessionEvent.AssistantAttempt>().size)
        // Backoff 500 ms doubling, ±10 %: about 15.5 s in all, on virtual time.
        assertTrue(currentTime.toString(), currentTime in 13_000..18_000)
    }

    @Test
    fun whitespaceOnlyCountsAsEmptyAndARetryCanSucceed() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(say("   \n "), say("real answer"))
        assertEquals(2, model.calls.size)
        assertEquals(listOf("real answer"), agent.all<SessionEvent.AssistantMessage>().map { it.text })
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun reasoningOnlyIsAnAnswerNotAnEmptyResponse() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(chunks(StreamChunk.ReasoningDelta("thought about it"), StreamChunk.Finish("stop")))
        assertEquals(1, model.calls.size)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun killSwitchIsBlockedAndNeverRetried() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(fail(LlmErrorCode.BLOCKED, "network is off"))
        assertEquals(TurnEndReason.BLOCKED, agent.end().reason)
        assertEquals("BLOCKED: network is off", agent.end().detail)
        assertEquals(1, model.calls.size)
    }

    @Test
    fun aRejectingPreStepHookBlocksTheTurn() = runTest(timeout = 10.seconds) {
        val model = FakeModel()
        val rig = Rig(this, model)
        rig.harness.root.intercept(Hooks.PreStep) { _, _ -> PreStepDecision.Reject("daily budget used up") }
        val agent = rig.open()
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(TurnEndReason.BLOCKED, agent.end().reason)
        assertEquals("daily budget used up", agent.end().detail)
        assertTrue(model.calls.isEmpty())
    }

    @Test
    fun aProviderErrorMidStreamIsRetriedAndOnlyTheGoodAttemptIsKept() = runTest(timeout = 10.seconds) {
        val broken: Reply = {
            emit(StreamChunk.TextDelta("Half of the fir"))
            throw LlmException(LlmFailure(LlmErrorCode.TRANSPORT, "connection reset"))
        }
        val (agent, model) = turn(broken, say("The full answer."))
        assertEquals(2, model.calls.size)
        assertEquals(listOf("The full answer."), agent.all<SessionEvent.AssistantMessage>().map { it.text })
        assertEquals("TRANSPORT", agent.all<SessionEvent.LlmRetry>().single().code)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun aProviderErrorMidStreamThatPersistsFailsTheTurnAndDropsThePartialText() = runTest(timeout = 10.seconds) {
        // Intended: a failed attempt is logged as an attempt (code + message), not as an answer.
        val broken: Reply = {
            emit(StreamChunk.TextDelta("Half"))
            throw LlmException(LlmFailure(LlmErrorCode.TRANSPORT, "connection reset"))
        }
        val (agent, model) = turn(*Array(6) { broken })
        assertEquals(6, model.calls.size)
        assertTrue(agent.all<SessionEvent.AssistantMessage>().isEmpty())
        val attempt = agent.all<SessionEvent.AssistantAttempt>().single()
        assertEquals("TRANSPORT" to "connection reset", attempt.code to attempt.message)
        assertEquals("TRANSPORT: connection reset", agent.end().detail)
        assertEquals(null, agent.live.value)
    }

    @Test
    fun aNonProviderExceptionMidStreamFailsTheTurnWithoutRetries() = runTest(timeout = 10.seconds) {
        val broken: Reply = {
            emit(StreamChunk.TextDelta("x"))
            throw IOException("socket closed")
        }
        val (agent, model) = turn(broken)
        assertEquals(1, model.calls.size)
        assertEquals(TurnEndReason.ERROR, agent.end().reason)
        assertEquals("socket closed", agent.end().detail)
    }

    @Test
    fun aNonRetryableFailureStopsAtOnce() = runTest(timeout = 10.seconds) {
        val (agent, model) = turn(fail(LlmErrorCode.AUTH, "bad key"))
        assertEquals(1, model.calls.size)
        assertEquals("AUTH: bad key", agent.end().detail)
    }

    @Test
    fun anUnknownProviderEndsTheTurnWithAnError() = runTest(timeout = 10.seconds) {
        val rig = Rig(this, FakeModel())
        val agent = rig.open(config = dev.wckdboy.autobot.agent.core.loop.AgentConfig(dev.wckdboy.autobot.agent.core.llm.LlmCallConfig("gone", "m")))
        agent.followup("hi")
        advanceUntilIdle()
        assertEquals(TurnEndReason.ERROR, agent.end().reason)
        assertTrue(agent.end().detail!!.contains("no longer exists"))
    }
}
