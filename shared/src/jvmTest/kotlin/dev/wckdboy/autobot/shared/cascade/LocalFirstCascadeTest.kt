package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.Harness
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
import dev.wckdboy.autobot.agent.core.loop.chooseCascade
import dev.wckdboy.autobot.agent.core.session.CASCADE_ESCALATION
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.ReceiptCascade
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.shared.redaction.RedactionGateway
import dev.wckdboy.autobot.shared.session.Receipts
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.projectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cascade end to end on the agent loop: the phone answers, the judge decides, the cloud follows up. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstCascadeTest {

    /** A scripted provider: [answer] for chat requests, [grade] for the judge's self-check. */
    private class Scripted(
        override val provider: String,
        var answer: (LlmCall) -> String = { "answer from ${it.config.model}" },
        var grade: () -> String = { "5" },
        var fail: Boolean = false,
        var hangSelfCheck: Boolean = false,
        private val window: Int = 32_768,
        /** Runs inside each call, in its coroutine (to look at the turn it is made for). */
        var onCall: suspend (LlmCall) -> Unit = {},
    ) : LlmAdapter {
        val calls = mutableListOf<LlmCall>()
        val chats get() = calls.filterNot { it.isSelfCheck() }
        val selfChecks get() = calls.filter { it.isSelfCheck() }

        fun LlmCall.isSelfCheck() = messages.firstOrNull()?.text == CascadeJudge.SELF_CHECK_SYSTEM

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            onCall(call)
            if (call.isSelfCheck()) {
                if (hangSelfCheck) awaitCancellation()
                emit(StreamChunk.TextDelta(grade()))
            } else {
                if (fail) throw LlmException(LlmFailure(LlmErrorCode.PROTOCOL, "engine crashed"))
                emit(StreamChunk.TextDelta(answer(call)))
            }
            emit(StreamChunk.Finish("stop"))
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = window, supportsTools = false)
    }

    private class Setup(val agent: Agent, val cascade: LocalFirstCascade, val phone: Scripted, val cloud: Scripted)

    private suspend fun TestScope.setup(
        policy: CascadePolicy = CascadePolicy.ASK,
        phoneInstalled: Boolean = true,
        tools: Boolean = false,
        phone: Scripted = Scripted("phone"),
        cloud: Scripted = Scripted("cloud"),
        redact: Boolean = false,
        selfCheckTimeoutMs: Long = CascadeJudge.SELF_CHECK_TIMEOUT_MS,
        /** The chat's model ("cloud" here) is the user's own paired node. */
        node: Boolean = false,
        privateTurns: dev.wckdboy.autobot.shared.invincible.PrivateTurns? = null,
    ): Setup {
        val harness = Harness()
        harness.llm.register(phone)
        harness.llm.register(cloud)
        var now = 0L
        val cascade = LocalFirstCascade(
            defaultPolicy = { policy },
            onDevice = { if (phoneInstalled) LlmCallConfig("phone", "qwen3-1.7b") else null },
            cloud = { it == "cloud" && !node },
            owned = { it == "cloud" && node },
            privateTurns = privateTurns,
            selfCheckTimeoutMs = selfCheckTimeoutMs,
            millis = { now.also { now += 120 } },
        )
        if (redact) harness.root.plugin(RedactionGateway(cloud = { it == "cloud" }, enabled = { true }).plugin)
        harness.root.plugin(cascade.plugin)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val agent = harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "deepseek-chat"), toolsEnabled = tools), scope, clock = { 0L })
        return Setup(agent, cascade, phone, cloud)
    }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = log.entries.value.map { it.event }.filterIsInstance<T>()
    private fun Agent.cascades(): List<ReceiptCascade?> = all<SessionEvent.TurnReceipt>().map { it.cascade }

    @Test
    fun offByDefaultNothingChanges() = runTest {
        val s = setup(policy = CascadePolicy.OFF)
        s.agent.followup("hello")
        advanceUntilIdle()
        assertEquals(1, s.cloud.chats.size)
        assertTrue(s.phone.calls.isEmpty())
        assertEquals(listOf<ReceiptCascade?>(null), s.agent.cascades())
    }

    @Test
    fun aGoodPhoneAnswerStaysOnThePhone() = runTest {
        val s = setup(policy = CascadePolicy.AUTO)
        s.phone.answer = { "The capital of France is Paris." }
        s.agent.followup("What is the capital of France?")
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertEquals(1, s.phone.chats.size)
        assertEquals(1, s.phone.selfChecks.size)
        val answer = s.agent.all<SessionEvent.AssistantMessage>().single()
        assertEquals("qwen3-1.7b", answer.model)
        val c = s.agent.cascades().single()!!
        assertEquals(ReceiptCascade("local", "AUTO", "GOOD", emptyList(), "KEPT", emptyList(), "self-check", 120), c)
        // The receipt line: on the phone, judged ok.
        assertTrue(Receipts.summarize(s.agent.all<SessionEvent.TurnReceipt>().single()).line.endsWith("judge ok"))
        // The self-check is short, Think off, and never goes to the cloud.
        val check = s.phone.selfChecks.single()
        assertEquals(CascadeJudge.SELF_CHECK_MAX_TOKENS, check.config.maxTokens)
        assertEquals("OFF", check.config.thinking?.mode?.name)
    }

    @Test
    fun askOffersTheCloudAndTheFollowUpAnswersThePromptAfresh() = runTest {
        val s = setup(policy = CascadePolicy.ASK)
        s.phone.answer = { "I don't know who wrote it." }
        s.cloud.answer = { "Max Frisch, in 1954." }
        s.agent.followup("Who wrote the novel Stiller?")
        advanceUntilIdle()

        assertTrue("nothing goes to the cloud unasked", s.cloud.calls.isEmpty())
        assertTrue("a hard signal needs no self-check", s.phone.selfChecks.isEmpty())
        val local = s.agent.cascades().single()!!
        assertEquals("OFFERED", local.action)
        assertEquals("WEAK", local.verdict)
        assertEquals(listOf(CascadeJudge.DONT_KNOW_CODE), local.reasons)
        assertEquals("heuristic", local.judge)
        assertNotNull(LocalFirstCascade.pendingOffer(s.agent.log.entries.value))

        val projection = projectSession(s.agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }
        assertEquals("The phone's answer looks weak: it said it doesn't know.", projection.cascadeOffer?.text)

        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        val call = s.cloud.chats.single()
        assertEquals("deepseek-chat", call.config.model)
        // The cloud sees the user's prompt last, not the phone's answer or the marker.
        assertEquals("Who wrote the novel Stiller?", call.messages.last { it.role == ModelRole.USER }.text)
        assertFalse(call.messages.any { it.role == ModelRole.ASSISTANT })
        assertFalse(call.messages.any { "asked" == it.text || "I don't know" in it.text })

        val cloud = s.agent.cascades().last()!!
        assertEquals("cloud", cloud.role)
        assertEquals("ESCALATED", cloud.action)
        assertEquals("asked", cloud.trigger)
        assertEquals(listOf(CascadeJudge.DONT_KNOW_CODE), cloud.reasons)
        assertNull(LocalFirstCascade.pendingOffer(s.agent.log.entries.value))
        assertFalse("one follow-up per offer", s.cascade.escalate(s.agent))

        // Both answers stay on screen, the follow-up under a notice; the offer is gone.
        val after = projectSession(s.agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }
        assertNull(after.cascadeOffer)
        val rows = after.items.map {
            when (it) {
                is SessionItem.User -> "user"
                is SessionItem.Assistant -> "assistant:${it.model}"
                is SessionItem.Notice -> "notice:${it.text}"
                is SessionItem.Tool -> "tool"
            }
        }
        assertEquals(listOf("user", "assistant:qwen3-1.7b", "notice:phone → cloud · you asked", "assistant:deepseek-chat"), rows)
        val receipt = (after.items.last() as SessionItem.Assistant).receipt!!
        assertTrue(receipt.line, receipt.line.endsWith("cascaded"))
        assertTrue(receipt.cascade!!.summary.startsWith("Sent to the cloud because you asked."))

        // The next prompt starts on the phone again, with the cloud's answer in its history.
        s.phone.answer = { "You're welcome." }
        s.agent.followup("thanks")
        advanceUntilIdle()
        val next = s.phone.chats.last()
        assertEquals(listOf("Max Frisch, in 1954."), next.messages.filter { it.role == ModelRole.ASSISTANT }.map { it.text })
    }

    @Test
    fun autoEscalatesAWeakAnswerToALowRiskPromptOnItsOwn() = runTest {
        val s = setup(policy = CascadePolicy.AUTO)
        s.phone.answer = { "I'm sorry, but I can't help with that." }
        s.agent.followup("Explain the Monty Hall problem")
        advanceUntilIdle()

        assertEquals(1, s.cloud.chats.size)
        val (local, cloud) = s.agent.cascades()
        assertEquals("ESCALATED", local!!.action)
        assertEquals(listOf(CascadeJudge.REFUSAL), local.reasons)
        assertEquals("auto", cloud!!.trigger)
        assertEquals(2, s.agent.all<SessionEvent.TurnEnd>().count { it.reason == TurnEndReason.COMPLETED })
        val notice = projectSession(s.agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }.items.filterIsInstance<SessionItem.Notice>().single()
        assertEquals("phone → cloud · auto · it declined", notice.text)
    }

    @Test
    fun autoNeverSendsASensitivePromptWithoutAsking() = runTest {
        for (prompt in listOf("Write to lena.vogel@posteo.de about Friday", "What dose of sertraline should I take?", "How do I get out of debt?")) {
            val s = setup(policy = CascadePolicy.AUTO)
            s.phone.answer = { "I don't know." }
            s.agent.followup(prompt)
            advanceUntilIdle()
            assertTrue(prompt, s.cloud.calls.isEmpty())
            val c = s.agent.cascades().single()!!
            assertEquals(prompt, "OFFERED", c.action)
            assertTrue(prompt, c.risk.isNotEmpty())
            assertTrue(Receipts.summarize(s.agent.all<SessionEvent.TurnReceipt>().single()).cascade!!.summary.contains("Not sent on its own"))
        }
        // An attached image counts too.
        val s = setup(policy = CascadePolicy.AUTO)
        s.phone.answer = { "I don't know." }
        s.agent.followup("What is this?", images = listOf("img-1"))
        advanceUntilIdle()
        assertTrue(s.cloud.calls.isEmpty())
        assertEquals(listOf(CascadeRisk.ATTACHMENT), s.agent.cascades().single()!!.risk)
    }

    /** Option (a): phone first, then the user's own node, where the follow-up stays (private). */
    @Test
    fun onAPairedNodeWeakAnswersGoToTheNodeAndStayThere() = runTest {
        for (policy in listOf(CascadePolicy.AUTO, CascadePolicy.NEVER)) {
            val private = dev.wckdboy.autobot.shared.invincible.PrivateTurns()
            val s = setup(policy = policy, node = true, privateTurns = private)
            val seen = mutableListOf<Boolean>()
            s.cloud.onCall = { seen += private.isPrivate(dev.wckdboy.autobot.agent.core.TurnTrace.current()!!) }
            s.phone.answer = { "I don't know." }
            // Sensitive: to the cloud "Auto" would only offer; the node is hardware the user owns.
            s.agent.followup("What dose of sertraline should I take?")
            advanceUntilIdle()

            assertEquals(policy.name, 1, s.cloud.chats.size)
            assertEquals("the node got it as private", listOf(true), seen)
            assertFalse("cleared once the turn ended", private.isPrivate(dev.wckdboy.autobot.agent.core.TurnTrace("s1", 2)))
            val (local, follow) = s.agent.cascades()
            assertEquals("ESCALATED", local!!.action)
            assertEquals("node", local.target)
            assertEquals("node", follow!!.target)
            val projection = projectSession(s.agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }
            assertEquals("phone → my node · auto · it said it doesn't know", projection.items.filterIsInstance<SessionItem.Notice>().single().text)
            val receipt = (projection.items.last() as SessionItem.Assistant).receipt!!
            assertTrue(receipt.cascade!!.summary, receipt.cascade!!.summary.startsWith("Sent to your node on its own, and kept there"))
            assertEquals("my node", receipt.cascade!!.followUpTarget)
        }
        // "Ask" offers the node; a good answer stays on the phone.
        val ask = setup(policy = CascadePolicy.ASK, node = true)
        ask.phone.answer = { "I don't know." }
        ask.agent.followup("Who wrote Stiller?")
        advanceUntilIdle()
        assertTrue(ask.cloud.calls.isEmpty())
        val offered = ask.agent.cascades().single()!!
        assertEquals("OFFERED", offered.action)
        assertTrue(CascadeLabels.summary(offered).endsWith("so you can ask your node."))
    }

    @Test
    fun onAPairedNodeWithoutAnOnDeviceModelTheNodeAnswersEvenUnderNever() = runTest {
        val private = dev.wckdboy.autobot.shared.invincible.PrivateTurns()
        val s = setup(policy = CascadePolicy.NEVER, phoneInstalled = false, node = true, privateTurns = private)
        val seen = mutableListOf<Boolean>()
        s.cloud.onCall = { seen += private.isPrivate(dev.wckdboy.autobot.agent.core.TurnTrace.current()!!) }
        s.agent.followup("hello")
        advanceUntilIdle()
        assertEquals(listOf(true), seen)
        val c = s.agent.cascades().single()!!
        assertEquals("DIRECT", c.action)
        assertEquals("node", c.target)
        assertTrue(CascadeLabels.summary(c).startsWith("Your node answered directly, and kept it there"))
    }

    @Test
    fun theEscalatedRequestGoesThroughRedaction() = runTest {
        val s = setup(policy = CascadePolicy.ASK, redact = true)
        s.phone.answer = { "I can't help with that." }
        s.agent.followup("Write a short note to anna.berg@posteo.de saying I'm late")
        advanceUntilIdle()
        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        val sent = s.cloud.chats.single().messages.joinToString("\n") { it.text }
        assertFalse(sent, "anna.berg@posteo.de" in sent)
        assertTrue(sent, "[EMAIL_1]" in sent)
        // The phone saw the real prompt (nothing left the device).
        assertTrue(s.phone.chats.single().messages.any { "anna.berg@posteo.de" in it.text })
        val receipt = s.agent.all<SessionEvent.TurnReceipt>().last()
        assertEquals(mapOf("EMAIL" to 1), receipt.redacted)
        assertEquals("cloud", receipt.cascade!!.role)
    }

    @Test
    fun theSelfCheckDecidesWhenNoRuleDoes() = runTest {
        val s = setup(policy = CascadePolicy.AUTO)
        s.phone.answer = { "Mount Everest is 8,848 m tall and in the Alps." }
        s.phone.grade = { "2" }
        s.agent.followup("Where is Mount Everest?")
        advanceUntilIdle()
        val local = s.agent.cascades().first()!!
        assertEquals("WEAK", local.verdict)
        assertEquals(listOf(CascadeJudge.SELF_CHECK), local.reasons)
        assertEquals("self-check", local.judge)
        assertEquals(1, s.cloud.chats.size)

        // Unsure: Auto offers instead.
        val t = setup(policy = CascadePolicy.AUTO)
        t.phone.grade = { "3" }
        t.agent.followup("Summarise the plot of Hamlet")
        advanceUntilIdle()
        assertEquals("UNSURE", t.agent.cascades().single()!!.verdict)
        assertEquals("OFFERED", t.agent.cascades().single()!!.action)
        assertTrue(t.cloud.calls.isEmpty())
    }

    @Test
    fun aHangingSelfCheckFallsBackToTheRules() = runTest {
        val phone = Scripted("phone", hangSelfCheck = true)
        val s = setup(policy = CascadePolicy.AUTO, phone = phone, selfCheckTimeoutMs = 2_000)
        s.agent.followup("Tell me a joke about cats")
        advanceUntilIdle()
        val c = s.agent.cascades().single()!!
        assertEquals("heuristic", c.judge)
        assertEquals("GOOD", c.verdict)
        assertTrue(s.cloud.calls.isEmpty())
    }

    @Test
    fun whenThePhoneFailsAskOffersAndAutoEscalates() = runTest {
        val ask = setup(policy = CascadePolicy.ASK, phone = Scripted("phone", fail = true))
        ask.agent.followup("Explain monads")
        advanceUntilIdle()
        val offered = ask.agent.cascades().single()!!
        assertEquals("OFFERED", offered.action)
        assertEquals(listOf(CascadeJudge.FAILED), offered.reasons)
        assertTrue(ask.cloud.calls.isEmpty())
        assertNotNull(projectSession(ask.agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }.cascadeOffer)

        val auto = setup(policy = CascadePolicy.AUTO, phone = Scripted("phone", fail = true))
        auto.agent.followup("Explain monads")
        advanceUntilIdle()
        assertEquals("ESCALATED", auto.agent.cascades().first()!!.action)
        assertEquals(1, auto.cloud.chats.size)
    }

    @Test
    fun withoutAnOnDeviceModelTheCloudAnswersOrNeverRefuses() = runTest {
        val s = setup(policy = CascadePolicy.ASK, phoneInstalled = false)
        s.agent.followup("hello")
        advanceUntilIdle()
        assertEquals(1, s.cloud.chats.size)
        val c = s.agent.cascades().single()!!
        assertEquals("DIRECT", c.action)
        assertEquals(listOf(CascadeRoutes.NO_LOCAL_MODEL), c.reasons)
        assertEquals("phone skipped", Receipts.summarize(s.agent.all<SessionEvent.TurnReceipt>().single()).line.substringAfterLast(" · "))

        val never = setup(policy = CascadePolicy.NEVER, phoneInstalled = false)
        never.agent.followup("hello")
        advanceUntilIdle()
        assertTrue(never.cloud.calls.isEmpty())
        val end = never.agent.all<SessionEvent.TurnEnd>().single()
        assertEquals(TurnEndReason.BLOCKED, end.reason)
        assertEquals(CascadeRoutes.BLOCKED_MESSAGE, end.detail)
    }

    @Test
    fun neverKeepsEvenAWeakAnswerAndSkipsTheSelfCheck() = runTest {
        val s = setup(policy = CascadePolicy.NEVER)
        s.phone.answer = { "Sounds good." }
        s.agent.followup("What's the bitcoin price right now?")
        advanceUntilIdle()
        assertTrue(s.cloud.calls.isEmpty())
        assertTrue(s.phone.selfChecks.isEmpty())
        val c = s.agent.cascades().single()!!
        assertEquals("KEPT", c.action)
        assertEquals("UNSURE", c.verdict)
        assertNull(LocalFirstCascade.pendingOffer(s.agent.log.entries.value))
    }

    @Test
    fun aChatTooLongForThePhoneGoesStraightToTheCloud() = runTest {
        val s = setup(policy = CascadePolicy.AUTO, phone = Scripted("phone", window = 64))
        s.agent.followup("x".repeat(400))
        advanceUntilIdle()
        assertTrue(s.phone.calls.isEmpty())
        assertEquals(listOf(CascadeRoutes.TOO_LONG), s.agent.cascades().single()!!.reasons)
    }

    @Test
    fun agentRunsAndThePhonesOwnChatsAreLeftAlone() = runTest {
        val s = setup(policy = CascadePolicy.AUTO, tools = true)
        s.agent.followup("hello")
        advanceUntilIdle()
        assertEquals(1, s.cloud.chats.size)
        assertTrue(s.phone.calls.isEmpty())
        assertNull(s.agent.cascades().single())
        assertEquals(LocalFirstCascade.NONE, s.cascade.chipState(s.agent.config.value, cloudRoute = true))
        assertEquals(LocalFirstCascade.NONE, s.cascade.chipState(s.agent.config.value.copy(toolsEnabled = false), cloudRoute = false))
        assertEquals("AUTO", s.cascade.chipState(s.agent.config.value.copy(toolsEnabled = false), cloudRoute = true))
    }

    @Test
    fun aChatsOwnChoiceWinsAndIsRemembered() = runTest {
        val s = setup(policy = CascadePolicy.OFF)
        s.agent.chooseCascade(s.cascade.override(CascadePolicy.ASK))
        assertEquals("ASK", s.agent.all<SessionEvent.CascadeChanged>().single().policy)
        s.phone.answer = { "I don't know." }
        s.agent.followup("hi there")
        advanceUntilIdle()
        assertEquals("OFFERED", s.agent.cascades().single()!!.action)
        // Back to what Settings says: the chat follows Settings again.
        s.agent.chooseCascade(s.cascade.override(CascadePolicy.OFF))
        assertNull(s.agent.all<SessionEvent.CascadeChanged>().last().policy)
        assertEquals(CascadePolicy.OFF, s.cascade.policyOf(s.agent))
    }

    @Test
    fun stoppingThePhonesAnswerNeitherJudgesNorEscalates() = runTest {
        val phone = object : LlmAdapter {
            override val provider = "phone"
            override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
                emit(StreamChunk.TextDelta("Once upon"))
                awaitCancellation()
            }
        }
        val harness = Harness()
        harness.llm.register(phone)
        val cloud = Scripted("cloud")
        harness.llm.register(cloud)
        val cascade = LocalFirstCascade({ CascadePolicy.AUTO }, { LlmCallConfig("phone", "m") }, { it == "cloud" })
        harness.root.plugin(cascade.plugin)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val agent = harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "c"), toolsEnabled = false), scope, clock = { 0L })
        agent.followup("Tell me a story")
        advanceUntilIdle()
        agent.cancel()
        advanceUntilIdle()
        assertEquals(TurnEndReason.ABORTED, agent.all<SessionEvent.TurnEnd>().single().reason)
        assertNull(agent.all<SessionEvent.TurnReceipt>().single().cascade)
        assertTrue(cloud.calls.isEmpty())
        assertFalse(agent.all<SessionEvent.UserMessage>().any { it.contextId == CASCADE_ESCALATION })
    }
}
