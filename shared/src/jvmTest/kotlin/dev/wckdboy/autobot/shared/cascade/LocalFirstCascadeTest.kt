package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.TurnTrace
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
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.session.CASCADE_ESCALATION
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.ReceiptCascade
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.shared.invincible.PrivateTurns
import dev.wckdboy.autobot.shared.redaction.RedactionGateway
import dev.wckdboy.autobot.shared.session.Receipts
import dev.wckdboy.autobot.shared.session.NoticeKind
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

    private class Setup(val agent: Agent, val cascade: LocalFirstCascade, val phone: Scripted, val cloud: Scripted, val harness: Harness)

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
        /** The user's own node as a provider of its own ("node"), so the chat can switch between it and "cloud". */
        myNode: Scripted? = null,
        /** While true, telling whether a provider is the user's node fails (a broken provider store). */
        ownedFails: () -> Boolean = { false },
    ): Setup {
        val harness = Harness()
        harness.llm.register(phone)
        harness.llm.register(cloud)
        myNode?.let { harness.llm.register(it) }
        var now = 0L
        val cascade = LocalFirstCascade(
            defaultPolicy = { policy },
            onDevice = { if (phoneInstalled) LlmCallConfig("phone", "qwen3-1.7b") else null },
            cloud = { it == "cloud" && !node },
            owned = {
                if (ownedFails()) error("provider store unavailable")
                (it == "cloud" && node) || (it == "node" && myNode != null)
            },
            privateTurns = privateTurns,
            selfCheckTimeoutMs = selfCheckTimeoutMs,
            millis = { now.also { now += 120 } },
        )
        if (redact) harness.root.plugin(RedactionGateway(cloud = { it == "cloud" }, enabled = { true }).plugin)
        harness.root.plugin(cascade.plugin)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val agent = harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "deepseek-chat"), toolsEnabled = tools), scope, clock = { 0L })
        return Setup(agent, cascade, phone, cloud, harness)
    }

    private inline fun <reified T : SessionEvent> Agent.all(): List<T> = log.entries.value.map { it.event }.filterIsInstance<T>()
    private fun Agent.cascades(): List<ReceiptCascade?> = all<SessionEvent.TurnReceipt>().map { it.cascade }
    private fun Agent.projection() = projectSession(log.entries.value, emptySet(), emptySet()) { _, _ -> "" }

    /** The user picks another model for the chat (AgentHost / AgentBridge `setRoute`): no event, only the config. */
    private fun Agent.switchTo(provider: String, model: String) = updateConfig { it.copy(llm = it.llm.copy(provider = provider, model = model)) }

    /** A compaction summary request (the compactor's instruction last). */
    private fun LlmCall.isSummary() = messages.lastOrNull()?.text == Compactor.SUMMARY_INSTRUCTION

    private fun Agent.notices() = projection().items.filterIsInstance<SessionItem.Notice>()

    /**
     * A node chat under [policy] whose phone answer gets an offer ("Sounds good." to a question that
     * needs fresh information: the judge is unsure), so "Ask my node" is there.
     */
    private suspend fun TestScope.nodeOffer(policy: CascadePolicy, private: PrivateTurns? = null, node: Scripted = Scripted("node"), cloud: Scripted = Scripted("cloud"), ownedFails: () -> Boolean = { false }, longAnswer: Boolean = false): Setup {
        val s = setup(policy = policy, privateTurns = private, myNode = node, cloud = cloud, ownedFails = ownedFails)
        s.agent.switchTo("node", "auto")
        s.phone.answer = { if (longAnswer) "Sounds good. ".repeat(40) else "Sounds good." }
        s.phone.grade = { "3" }
        s.agent.followup("What's the bitcoin price right now?")
        advanceUntilIdle()
        val offered = s.agent.cascades().single()!!
        assertEquals("OFFERED", offered.action)
        assertEquals("node", offered.target)
        return s
    }

    /** Whether each request [adapter] gets is marked private ([PrivateTurns]). */
    private fun Scripted.recordPrivate(private: PrivateTurns): MutableList<Boolean> {
        val seen = mutableListOf<Boolean>()
        onCall = { seen += private.isPrivate(TurnTrace.current()!!) }
        return seen
    }

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
            // Sensitive: to the cloud "Auto" would only offer; the node gets it as private, off cloud models.
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

    /** What [CascadePolicy.NEVER]'s node line says: on a node it is "Auto" without the self-check. */
    @Test
    fun onAPairedNodeNeverIsAutoWithoutTheSelfCheck() = runTest {
        val actions = mutableMapOf<CascadePolicy, String>()
        for (policy in listOf(CascadePolicy.AUTO, CascadePolicy.NEVER)) {
            val s = setup(policy = policy, node = true)
            // No rule objects to this answer; only the self-check finds it weak.
            s.phone.answer = { "Mount Everest is 8,848 m tall and in the Alps." }
            s.phone.grade = { "2" }
            s.agent.followup("Where is Mount Everest?")
            advanceUntilIdle()
            val local = s.agent.cascades().first()!!
            assertEquals("node", local.target)
            if (policy == CascadePolicy.AUTO) {
                assertEquals(1, s.phone.selfChecks.size)
                assertEquals("self-check", local.judge)
                assertEquals(1, s.cloud.chats.size)
            } else {
                assertTrue(s.phone.selfChecks.isEmpty())
                assertEquals("heuristic", local.judge)
                assertTrue(s.cloud.calls.isEmpty())
            }
            actions[policy] = local.action
        }
        assertEquals(mapOf(CascadePolicy.AUTO to "ESCALATED", CascadePolicy.NEVER to "KEPT"), actions)
    }

    /**
     * An offer stays on screen when the user switches the chat's model, and "Ask my node" then sends
     * the prompt to the node, marked private: where a follow-up goes is the chat's model when it
     * starts, not where the phone's answer was judged.
     */
    @Test
    fun anOfferMadeOnACloudChatGoesToTheNodeMarkedPrivateAfterASwitch() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.ASK, privateTurns = private, myNode = node)
        val toNode = node.recordPrivate(private)
        s.phone.answer = { "I don't know." }
        s.agent.followup("Who wrote the novel Stiller?")
        advanceUntilIdle()
        val offered = s.agent.cascades().single()!!
        assertEquals("OFFERED", offered.action)
        assertNull("offered on the cloud chat", offered.target)

        s.agent.switchTo("node", "auto")
        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertEquals("the node got it as private", listOf(true), toNode)
        val follow = s.agent.cascades().last()!!
        assertEquals("asked", follow.trigger)
        assertEquals("node", follow.target)
        val projection = s.agent.projection()
        assertEquals("phone → my node · you asked", projection.items.filterIsInstance<SessionItem.Notice>().single().text)
        val receipt = (projection.items.last() as SessionItem.Assistant).receipt!!
        assertTrue(receipt.cascade!!.summary, receipt.cascade!!.summary.startsWith("Sent to your node because you asked, and kept there."))
        assertEquals("my node", receipt.cascade!!.followUpTarget)
    }

    /** The other way: an offer made on a node chat, then a switch to a cloud model, goes to the cloud as a cloud follow-up, or not at all under "Never". */
    @Test
    fun anOfferMadeOnANodeChatGoesToTheCloudAsSuchAfterASwitch() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.ASK, privateTurns = private, myNode = node)
        s.agent.switchTo("node", "auto")
        val toCloud = s.cloud.recordPrivate(private)
        s.phone.answer = { "I don't know." }
        s.agent.followup("Who wrote the novel Stiller?")
        advanceUntilIdle()
        assertEquals("node", s.agent.cascades().single()!!.target)

        s.agent.switchTo("cloud", "deepseek-chat")
        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        assertTrue(node.calls.isEmpty())
        assertEquals(listOf(false), toCloud)
        val follow = s.agent.cascades().last()!!
        assertEquals("asked", follow.trigger)
        assertNull(follow.target)
        val projection = s.agent.projection()
        assertEquals("phone → cloud · you asked", projection.items.filterIsInstance<SessionItem.Notice>().single().text)
        val receipt = (projection.items.last() as SessionItem.Assistant).receipt!!
        assertTrue(receipt.cascade!!.summary, receipt.cascade!!.summary.startsWith("Sent to the cloud because you asked."))
        assertEquals("cloud", receipt.cascade!!.followUpTarget)

        // "Never" offers on a node chat (the judge unsure); after a switch to a cloud model nothing goes there.
        val never = setup(policy = CascadePolicy.NEVER, myNode = Scripted("node"))
        never.agent.switchTo("node", "auto")
        never.phone.answer = { "Sounds good." }
        never.agent.followup("What's the bitcoin price right now?")
        advanceUntilIdle()
        assertEquals("OFFERED", never.agent.cascades().single()!!.action)
        never.agent.switchTo("cloud", "deepseek-chat")
        assertFalse(never.cascade.escalate(never.agent))
        advanceUntilIdle()
        assertTrue(never.cloud.calls.isEmpty())
        assertFalse(CascadePolicy.NEVER.allowsAsk(node = false))
        assertTrue(CascadePolicy.NEVER.allowsAsk(node = true))
        assertTrue(CascadePolicy.ASK.allowsAsk(node = false))
    }

    /**
     * "Never" sends a follow-up nowhere but the user's node, whatever happened since it was queued.
     * Here the chat switches to a cloud model while the node writes the follow-up's compaction
     * summary (marked private): the follow-up is not sent, and the notices say so and why.
     */
    @Test
    fun underNeverAFollowUpWhoseChatLeavesTheNodeDuringCompactionIsNotSent() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node", window = 400)
        val s = nodeOffer(CascadePolicy.NEVER, private, node, longAnswer = true)
        val seen = mutableListOf<Pair<Boolean, Boolean>>()
        node.onCall = { call ->
            seen += call.isSummary() to private.isPrivate(TurnTrace.current()!!)
            if (call.isSummary()) s.agent.switchTo("cloud", "deepseek-chat")
        }

        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        assertTrue("nothing went to the cloud", s.cloud.calls.isEmpty())
        assertEquals("only the summary went to the node, marked private", listOf(true to true), seen)
        val end = s.agent.all<SessionEvent.TurnEnd>().last()
        assertEquals(TurnEndReason.BLOCKED, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.detail)
        val follow = s.agent.cascades().last()!!
        assertEquals(listOf("cloud", "KEPT", "asked"), listOf(follow.role, follow.action, follow.trigger))
        assertNull(follow.target)
        assertEquals("not sent", CascadeLabels.line(follow))
        val notices = s.agent.notices()
        assertEquals("follow-up not sent · you asked", notices.first().text)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER to NoticeKind.ERROR, notices.last().let { it.text to it.kind })
        assertNull(LocalFirstCascade.pendingOffer(s.agent.log.entries.value))
        assertFalse("cleared once the turn ended", private.isPrivate(TurnTrace("s1", 2)))
    }

    /** A switch before the follow-up starts: refused ahead of compaction, so not even the summary goes to the cloud. */
    @Test
    fun underNeverAFollowUpWhoseChatLeftTheNodeBeforeItStartsSendsNothing() = runTest {
        val node = Scripted("node", window = 400)
        val s = nodeOffer(CascadePolicy.NEVER, node = node, cloud = Scripted("cloud", window = 400), longAnswer = true)
        assertTrue(s.cascade.escalate(s.agent))
        s.agent.switchTo("cloud", "deepseek-chat")
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertTrue(node.calls.isEmpty())
        assertTrue("no compaction either", s.agent.all<SessionEvent.CompactionStart>().isEmpty())
        val end = s.agent.all<SessionEvent.TurnEnd>().last()
        assertEquals(TurnEndReason.BLOCKED, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.detail)
        assertEquals(listOf("follow-up not sent · you asked", CascadeRoutes.NOT_SENT_NEVER), s.agent.notices().map { it.text })
    }

    /** A switch after the follow-up started, before its request: the request is stopped there, nothing goes. */
    @Test
    fun underNeverAFollowUpsRequestIsStoppedWhenTheChatLeavesTheNodeAfterItStarted() = runTest {
        val node = Scripted("node")
        val s = nodeOffer(CascadePolicy.NEVER, node = node)
        // Runs after the cascade's own pre-step (lower priority): the user switches right then.
        s.harness.root.intercept(Hooks.PreStep, priority = -1) { input, next ->
            if (input.agent.turnInput.any { it.contextId == CASCADE_ESCALATION }) s.agent.switchTo("cloud", "deepseek-chat")
            next(input)
        }
        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertTrue(node.calls.isEmpty())
        assertTrue("stopped before the request was even prepared", s.agent.all<SessionEvent.RequestContext>().none { it.provider == "cloud" })
        val end = s.agent.all<SessionEvent.TurnEnd>().last()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.detail)
        assertEquals("KEPT", s.agent.cascades().last()!!.action)
        assertEquals(listOf("follow-up not sent · you asked", CascadeRoutes.NOT_SENT_NEVER), s.agent.notices().map { it.text })
    }

    /** The notice above a follow-up names where it went once it started, not where the chat's model was when "Ask" was tapped. */
    @Test
    fun theFollowUpsNoticeSaysWhereItWent() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.ASK, privateTurns = private, myNode = node)
        val toNode = node.recordPrivate(private)
        s.phone.answer = { "I don't know." }
        s.agent.followup("Who wrote the novel Stiller?")
        advanceUntilIdle()
        assertTrue("asked on the cloud chat", s.cascade.escalate(s.agent))
        s.agent.switchTo("node", "auto")
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertEquals(listOf(true), toNode)
        assertEquals("phone → my node · you asked", s.agent.notices().single().text)
    }

    /**
     * Every request "phone first" sends the user's node goes marked private, a compaction summary
     * included (it runs before the turn's first step): on the phone's route and on the node's own.
     * A plain node chat without "phone first" is left as it was: nothing marked.
     */
    @Test
    fun aCompactionSummaryPhoneFirstSendsTheNodeGoesMarkedPrivate() = runTest {
        for ((policy, phoneInstalled) in listOf(CascadePolicy.NEVER to false, CascadePolicy.AUTO to true, CascadePolicy.OFF to true)) {
            val private = PrivateTurns()
            val node = Scripted("node", window = 400)
            val s = setup(policy = policy, phoneInstalled = phoneInstalled, privateTurns = private, myNode = node)
            s.agent.switchTo("node", "auto")
            val seen = node.recordPrivate(private)
            val long = (1..60).joinToString(" ") { "Paris has a district numbered $it." }
            s.phone.answer = { long }
            node.answer = { if (it.isSummary()) "The user asks about capitals." else long }
            s.agent.followup("What is the capital of France?")
            advanceUntilIdle()
            s.agent.followup("And of Italy?")
            advanceUntilIdle()

            assertTrue(policy.name, s.agent.all<SessionEvent.CompactionStart>().isNotEmpty())
            assertTrue(policy.name, node.calls.any { it.isSummary() })
            if (policy == CascadePolicy.OFF) {
                assertTrue("$policy: $seen", seen.none { it })
            } else {
                assertTrue("$policy: $seen", seen.isNotEmpty() && seen.all { it })
            }
        }
    }

    /** A switch to the node in the middle of a turn: the summary an overflow then sends the node goes marked private too. */
    @Test
    fun aSummaryAfterASwitchToTheNodeMidTurnGoesMarkedPrivate() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.AUTO, privateTurns = private, myNode = node)
        val seen = node.recordPrivate(private)
        s.phone.answer = { "The capital of France is Paris, on the Seine." }
        s.agent.followup("What is the capital of France?")
        advanceUntilIdle()
        // The phone's request overflows just after the user picked the node: the compactor's summary goes there.
        var overflow = true
        s.phone.onCall = { call ->
            if (overflow && with(s.phone) { !call.isSelfCheck() }) {
                overflow = false
                s.agent.switchTo("node", "auto")
                throw LlmException(LlmFailure(LlmErrorCode.CONTEXT_WINDOW_EXCEEDED, "too long"))
            }
        }
        s.agent.followup("And of Italy?")
        advanceUntilIdle()

        assertTrue(node.calls.single().isSummary())
        assertEquals(listOf(true), seen)
        assertEquals(listOf("KEPT", "KEPT"), s.agent.cascades().map { it!!.action })
    }

    /** Privacy fails closed: when whether the chat's model is the user's node cannot be told, the follow-up goes marked private, and "Never" sends it nowhere. */
    @Test
    fun whenTheNodeCannotBeToldAFollowUpGoesMarkedPrivateOrUnderNeverNotAtAll() = runTest {
        val private = PrivateTurns()
        val node = Scripted("node")
        var fails = false
        val s = nodeOffer(CascadePolicy.ASK, private, node, ownedFails = { fails })
        val toNode = node.recordPrivate(private)
        assertTrue(s.cascade.escalate(s.agent))
        fails = true
        advanceUntilIdle()
        assertEquals(listOf(true), toNode)
        // Its words claim the node only where that is known: here they say cloud, the stricter claim.
        assertNull(s.agent.cascades().last()!!.target)
        assertEquals("phone → cloud · you asked", s.agent.notices().single().text)

        var neverFails = false
        val neverNode = Scripted("node")
        val never = nodeOffer(CascadePolicy.NEVER, node = neverNode, ownedFails = { neverFails })
        assertTrue(never.cascade.escalate(never.agent))
        neverFails = true
        advanceUntilIdle()
        assertTrue(neverNode.calls.isEmpty())
        assertEquals(CascadeRoutes.NOT_SENT_UNKNOWN, never.agent.all<SessionEvent.TurnEnd>().last().detail)
        // Nor does "Ask" go while it cannot be told.
        var askFails = false
        val again = nodeOffer(CascadePolicy.NEVER, ownedFails = { askFails })
        askFails = true
        assertFalse(again.cascade.escalate(again.agent))
    }

    /**
     * One rule for "Ask": [LocalFirstCascade.escalate] refuses exactly where [LocalFirstCascade.canAsk]
     * (the offer row on both apps) says no: to the node always, to a cloud model unless "Never", and
     * never to "This phone", which has answered already.
     */
    @Test
    fun askWorksExactlyWhereTheOfferShows() = runTest {
        for (provider in listOf("node", "cloud", "phone")) {
            for (policy in listOf(CascadePolicy.NEVER, CascadePolicy.ASK)) {
                val s = nodeOffer(policy)
                s.agent.switchTo(provider, "m")
                val canAsk = s.cascade.canAsk(s.agent.config.value, owned = provider == "node", cloudRoute = provider == "cloud")
                assertEquals("$policy on $provider", canAsk, s.cascade.escalate(s.agent))
                assertEquals("$policy on $provider", provider == "node" || (provider == "cloud" && policy != CascadePolicy.NEVER), canAsk)
                advanceUntilIdle()
                assertEquals("$policy on $provider: the phone answered once", 1, s.phone.chats.size)
            }
        }
    }

    /**
     * A switch after the follow-up passed its first check but before its compaction summary is made
     * (here between the cascade's pre-step and compaction's): the summary is refused where it is
     * made, nothing reaches the cloud model, and the receipt does not list that model.
     */
    @Test
    fun underNeverASwitchJustBeforeTheFollowUpsSummaryStopsTheSummaryToo() = runTest {
        val node = Scripted("node", window = 400)
        val cloud = Scripted("cloud", window = 400)
        val s = nodeOffer(CascadePolicy.NEVER, node = node, cloud = cloud, longAnswer = true)
        // After the cascade's first pre-step (priority 100), before compaction's (priority 0).
        s.harness.root.intercept(Hooks.PreStep, priority = 50) { input, next ->
            if (input.agent.turnInput.any { it.contextId == CASCADE_ESCALATION }) s.agent.switchTo("cloud", "deepseek-chat")
            next(input)
        }
        assertTrue(s.cascade.escalate(s.agent))
        advanceUntilIdle()

        assertTrue("nothing reached the cloud model", cloud.calls.isEmpty())
        assertTrue(node.calls.isEmpty())
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, s.agent.all<SessionEvent.CompactionEnd>().single().error)
        val end = s.agent.all<SessionEvent.TurnEnd>().last()
        assertEquals(TurnEndReason.BLOCKED, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.detail)
        val receipt = s.agent.all<SessionEvent.TurnReceipt>().last()
        assertEquals("KEPT", receipt.cascade!!.action)
        assertTrue("the refused summary's model is not listed: ${receipt.models}", receipt.models.isEmpty())
        assertEquals(
            listOf("follow-up not sent · you asked", "compaction failed: ${CascadeRoutes.NOT_SENT_NEVER}", CascadeRoutes.NOT_SENT_NEVER),
            s.agent.notices().map { it.text },
        )
    }

    /** "Never" on a node chat without an on-device model: the node answers directly, and a switch to a cloud model before that request stops it. */
    @Test
    fun underNeverATurnTheNodeTakesDirectlyGoesNowhereElseAfterASwitch() = runTest {
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.NEVER, phoneInstalled = false, myNode = node)
        s.agent.switchTo("node", "auto")
        // After the cascade routed the turn to the node, before its request.
        s.harness.root.intercept(Hooks.PreStep, priority = -1) { input, next ->
            s.agent.switchTo("cloud", "deepseek-chat")
            next(input)
        }
        s.agent.followup("hello")
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertTrue(node.calls.isEmpty())
        val end = s.agent.all<SessionEvent.TurnEnd>().single()
        assertEquals(TurnEndReason.ERROR, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.detail)
        val receipt = s.agent.all<SessionEvent.TurnReceipt>().single()
        assertNull("no \"answered directly\": nothing was sent", receipt.cascade)
        assertTrue(receipt.models.isEmpty())
        assertTrue("stopped before the request was even prepared", s.agent.all<SessionEvent.RequestContext>().isEmpty())
        assertEquals(listOf(CascadeRoutes.NOT_SENT_NEVER), s.agent.notices().map { it.text })
    }

    /** "Never" on a cloud chat: a compaction summary would send the chat to the cloud model, so it is refused; the phone still answers. */
    @Test
    fun underNeverACompactionSummaryDoesNotGoToTheCloudModel() = runTest {
        val cloud = Scripted("cloud", window = 400)
        val s = setup(policy = CascadePolicy.NEVER, cloud = cloud)
        s.phone.answer = { "Paris has twenty districts. ".repeat(30) }
        s.agent.followup("What is the capital of France?")
        advanceUntilIdle()
        s.agent.followup("And of Italy?")
        advanceUntilIdle()

        assertTrue("nothing went to the cloud model", cloud.calls.isEmpty())
        assertEquals(2, s.phone.chats.size)
        val ends = s.agent.all<SessionEvent.CompactionEnd>()
        assertTrue(ends.isNotEmpty())
        assertTrue(ends.joinToString { "${it.error}" }, ends.all { it.error == CascadeRoutes.NOT_SENT_NEVER })
        assertTrue(s.agent.all<SessionEvent.TurnReceipt>().flatMap { it.models }.none { it.provider == "cloud" })
        assertEquals(listOf("KEPT", "KEPT"), s.agent.cascades().map { it!!.action })
    }

    /**
     * "Auto" on a node chat sends a weak answer's prompt to the node on its own, a personal one
     * included. If the chat switches to a cloud model as that follow-up starts, it goes there only
     * where "Auto" would send it to a cloud model on its own: a low-risk prompt does, a personal one
     * is not sent.
     */
    @Test
    fun anAutoFollowUpForTheNodeGoesToACloudModelOnlyWhereAutoWouldSendItThere() = runTest {
        for ((prompt, answer, sent) in listOf(
            Triple("What dose of sertraline should I take?", "I don't know.", false),
            Triple("Explain the Monty Hall problem", "I'm sorry, but I can't help with that.", true),
        )) {
            val node = Scripted("node")
            val s = setup(policy = CascadePolicy.AUTO, myNode = node)
            s.agent.switchTo("node", "auto")
            s.phone.answer = { answer }
            // The user picks a cloud model just as the follow-up starts.
            s.harness.root.intercept(Hooks.PreStep, priority = 200) { input, next ->
                if (input.agent.turnInput.any { it.contextId == CASCADE_ESCALATION }) s.agent.switchTo("cloud", "deepseek-chat")
                next(input)
            }
            s.agent.followup(prompt)
            advanceUntilIdle()

            val (local, follow) = s.agent.cascades()
            assertEquals(prompt, listOf("ESCALATED", "node"), listOf(local!!.action, local.target))
            assertTrue(prompt, node.calls.isEmpty())
            assertEquals(prompt, if (sent) 1 else 0, s.cloud.chats.size)
            assertEquals(prompt, if (sent) "ESCALATED" else "KEPT", follow!!.action)
            assertNull(prompt, follow.target)
            val notices = s.agent.notices().map { it.text }
            if (sent) {
                assertEquals(listOf("phone → cloud · auto · it declined"), notices)
            } else {
                assertEquals(listOf("follow-up not sent · auto · it said it doesn't know", CascadeRoutes.NOT_SENT_AUTO), notices)
            }
        }
    }

    /**
     * A follow-up whose chat moves to "This phone" before it starts is not sent: the phone has
     * answered already, and no word says it went to the cloud.
     */
    @Test
    fun aFollowUpWhoseChatMovedToThisPhoneIsNotSent() = runTest {
        val s = nodeOffer(CascadePolicy.ASK)
        assertTrue(s.cascade.escalate(s.agent))
        s.agent.switchTo("phone", "qwen3-1.7b")
        advanceUntilIdle()

        assertEquals("the phone answered once", 1, s.phone.chats.size)
        val end = s.agent.all<SessionEvent.TurnEnd>().last()
        assertEquals(TurnEndReason.BLOCKED, end.reason)
        assertEquals(CascadeRoutes.NOT_SENT_LOCAL, end.detail)
        val follow = s.agent.cascades().last()!!
        assertEquals(listOf("KEPT", null), listOf(follow.action, follow.target))
        assertEquals(listOf("follow-up not sent · you asked", CascadeRoutes.NOT_SENT_LOCAL), s.agent.notices().map { it.text })
    }

    /** A switch to "This phone" while the phone answers: there is nothing further to ask, so the answer is kept and nothing queued. */
    @Test
    fun aSwitchToThisPhoneWhileThePhoneAnswersKeepsTheAnswer() = runTest {
        val s = setup(policy = CascadePolicy.AUTO)
        s.phone.answer = { "I'm sorry, but I can't help with that." }
        s.phone.onCall = { s.agent.switchTo("phone", "qwen3-1.7b") }
        s.agent.followup("Explain the Monty Hall problem")
        advanceUntilIdle()

        assertTrue(s.cloud.calls.isEmpty())
        assertEquals(1, s.phone.chats.size)
        assertEquals(1, s.agent.all<SessionEvent.TurnStart>().size)
        assertEquals("KEPT", s.agent.cascades().single()!!.action)
        assertNull(LocalFirstCascade.pendingOffer(s.agent.log.entries.value))
    }

    /** A switch while the phone answers: the judged answer follows the rules, and goes to the target, of the chat's model by then. */
    @Test
    fun aSwitchWhileThePhoneAnswersAppliesTheNewModelsRules() = runTest {
        // Cloud to node under "Auto": a weak answer to a sensitive prompt goes to the node on its own, private.
        val private = PrivateTurns()
        val node = Scripted("node")
        val s = setup(policy = CascadePolicy.AUTO, privateTurns = private, myNode = node)
        val toNode = node.recordPrivate(private)
        s.phone.answer = { "I don't know." }
        s.phone.onCall = { s.agent.switchTo("node", "auto") }
        s.agent.followup("What dose of sertraline should I take?")
        advanceUntilIdle()
        assertTrue(s.cloud.calls.isEmpty())
        assertEquals(listOf(true), toNode)
        val (local, follow) = s.agent.cascades()
        assertEquals("ESCALATED", local!!.action)
        assertEquals("node", local.target)
        assertTrue(CascadeLabels.summary(local), CascadeLabels.summary(local).endsWith("so the prompt went to your node, which keeps it on its own models."))
        assertEquals("node", follow!!.target)
        assertEquals("phone → my node · auto · it said it doesn't know", s.agent.projection().items.filterIsInstance<SessionItem.Notice>().single().text)

        // Node to cloud: "Never" keeps the weak answer, and "Auto" only offers for a sensitive prompt.
        for ((policy, action) in listOf(CascadePolicy.NEVER to "KEPT", CascadePolicy.AUTO to "OFFERED")) {
            val n = Scripted("node")
            val t = setup(policy = policy, myNode = n)
            t.agent.switchTo("node", "auto")
            t.phone.answer = { "I don't know." }
            t.phone.onCall = { t.agent.switchTo("cloud", "deepseek-chat") }
            t.agent.followup("What dose of sertraline should I take?")
            advanceUntilIdle()
            assertTrue(policy.name, t.cloud.calls.isEmpty())
            assertTrue(policy.name, n.calls.isEmpty())
            val c = t.agent.cascades().single()!!
            assertEquals(policy.name, action, c.action)
            assertNull(policy.name, c.target)
        }
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
        assertEquals(LocalFirstCascade.NONE, s.cascade.chipState(s.agent.config.value, cloudRoute = true, owned = false))
        assertEquals(LocalFirstCascade.NONE, s.cascade.chipState(s.agent.config.value.copy(toolsEnabled = false), cloudRoute = false, owned = false))
        assertEquals("AUTO", s.cascade.chipState(s.agent.config.value.copy(toolsEnabled = false), cloudRoute = true, owned = false))
        // The user's own node is not a cloud route, and a plain chat on it has the chip (as on Android).
        assertEquals("AUTO", s.cascade.chipState(s.agent.config.value.copy(toolsEnabled = false), cloudRoute = false, owned = true))
        assertEquals(LocalFirstCascade.NONE, s.cascade.chipState(s.agent.config.value, cloudRoute = false, owned = true))
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
