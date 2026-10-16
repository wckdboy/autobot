package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Approvals end to end: answers, routing by (session, call), cancellation and the presets. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalFlowTest {

    /** A UI stand-in: pending requests keyed by (sessionId, callId), answered explicitly. */
    private class Answerer(harness: Harness) {
        val pending = LinkedHashMap<Pair<String, String>, CompletableDeferred<ApprovalOutcome>>()
        val asked = mutableListOf<ApprovalRequest>()

        init {
            harness.root.intercept(Hooks.ApprovalRequest) { request, _ ->
                asked += request
                val answer = CompletableDeferred<ApprovalOutcome>()
                val key = request.agent.id to request.callId
                pending[key] = answer
                try {
                    answer.await()
                } finally {
                    pending.remove(key)
                }
            }
        }

        /** Answers (session, call); false when nothing is waiting for it (stale or unknown). */
        fun answer(sessionId: String, callId: String, outcome: ApprovalOutcome): Boolean =
            pending[sessionId to callId]?.complete(outcome) ?: false
    }

    @Test
    fun approveOnceRunsTheToolAndTheNextCallAsksAgain() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val model = FakeModel(replies = listOf(call("c1", "rm", """{"text":"a"}"""), call("c2", "rm", """{"text":"b"}"""), say("ok")))
        val rig = Rig(this, model, rm)
        val ui = Answerer(rig.harness)
        val agent = rig.open()
        agent.followup("clean up")
        runCurrent()
        assertTrue(ui.answer("s1", "c1", ApprovalOutcome.ALLOWED_ONCE))
        runCurrent()
        assertTrue("asks again for the next call", ui.answer("s1", "c2", ApprovalOutcome.ALLOWED_ONCE))
        advanceUntilIdle()
        assertEquals(listOf("rm:a", "rm:b"), agent.results().map { it.content })
        assertEquals(listOf(ApprovalOutcome.ALLOWED_ONCE, ApprovalOutcome.ALLOWED_ONCE), agent.all<SessionEvent.ApprovalDecided>().map { it.outcome })
        assertFalse("rm" in agent.sessionGrants)
    }

    @Test
    fun denyIsAnErrorResultTheModelSeesAndTheToolNeverRuns() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val model = FakeModel(replies = listOf(call("c1", "rm"), say("understood")))
        val rig = Rig(this, model, rm)
        val ui = Answerer(rig.harness)
        val agent = rig.open()
        agent.followup("delete it")
        runCurrent()
        ui.answer("s1", "c1", ApprovalOutcome.REJECTED)
        advanceUntilIdle()
        val result = agent.results().single()
        assertEquals("USER_REJECTED", result.code)
        assertEquals(0, rm.started.get())
        assertEquals(result.content, model.calls[1].toolResults.single().text)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertTrue("the receipt does not list a tool that never ran", agent.all<SessionEvent.TurnReceipt>().single().tools.isEmpty())
    }

    @Test
    fun approveAlwaysGrantsThatToolForTheSessionOnly() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val run = FakeTool("exec", ToolKind.EXECUTE)
        val model = FakeModel(
            replies = listOf(call("c1", "rm"), call("c2", "rm"), call("c3", "exec"), say("done"), call("c4", "rm"), say("again")),
        )
        val rig = Rig(this, model, rm, run)
        val ui = Answerer(rig.harness)
        val agent = rig.open()
        agent.followup("go")
        runCurrent()
        ui.answer("s1", "c1", ApprovalOutcome.ALLOWED_FOR_SESSION)
        runCurrent()
        assertFalse("c2 runs without asking", ("s1" to "c2") in ui.pending)
        assertTrue("another tool still asks", ("s1" to "c3") in ui.pending)
        ui.answer("s1", "c3", ApprovalOutcome.ALLOWED_ONCE)
        advanceUntilIdle()
        // The grant outlives the turn.
        agent.followup("more")
        advanceUntilIdle()
        assertEquals(listOf("c1", "c3"), ui.asked.map { it.callId })
        assertEquals(4, agent.results().count { !it.isError })
        assertTrue("rm" in agent.sessionGrants)
        assertFalse("exec" in agent.sessionGrants)
    }

    @Test
    fun parallelAsksAreAnsweredPerCallInAnyOrder() = runTest(timeout = 10.seconds) {
        val fetch = FakeTool("fetch", ToolKind.FETCH)
        val model = FakeModel(
            replies = listOf(
                calls(Triple("c1", "fetch", """{"text":"one"}"""), Triple("c2", "fetch", """{"text":"two"}"""), Triple("c3", "fetch", """{"text":"three"}""")),
                say("ok"),
            ),
        )
        val rig = Rig(this, model, fetch)
        val ui = Answerer(rig.harness)
        val agent = rig.open()
        agent.followup("go")
        runCurrent()
        assertEquals(setOf("c1", "c2", "c3"), ui.pending.keys.map { it.second }.toSet())
        ui.answer("s1", "c3", ApprovalOutcome.ALLOWED_ONCE)
        ui.answer("s1", "c1", ApprovalOutcome.REJECTED)
        runCurrent()
        assertEquals(listOf("c2"), ui.pending.keys.map { it.second })
        ui.answer("s1", "c2", ApprovalOutcome.ALLOWED_ONCE)
        advanceUntilIdle()
        assertEquals(listOf("USER_REJECTED", null, null), agent.results().map { it.code })
        assertEquals("in the order they were approved", listOf("three", "two"), fetch.runs.map { it.text() })
    }

    @Test
    fun answersAreMatchedOnSessionAndCallIdAcrossChats() = runTest(timeout = 10.seconds) {
        // Two chats each wait on a call with the same id: answering one must not answer the other.
        val fetch = FakeTool("fetch", ToolKind.FETCH)
        val model = FakeModel(route = { request, _ ->
            if (request.toolResults.isEmpty()) call("c1", "fetch", """{"text":"x"}""")(this, request) else say("after: ${request.toolResults.single().text}")(this, request)
        })
        val rig = Rig(this, model, fetch)
        val ui = Answerer(rig.harness)
        val a = rig.open("chat-a")
        val b = rig.open("chat-b")
        a.followup("go")
        b.followup("go")
        runCurrent()
        assertEquals(setOf("chat-a" to "c1", "chat-b" to "c1"), ui.pending.keys)
        ui.answer("chat-b", "c1", ApprovalOutcome.REJECTED)
        advanceUntilIdle()
        assertEquals("USER_REJECTED", b.results().single().code)
        assertTrue("chat a is still waiting", ("chat-a" to "c1") in ui.pending)
        assertTrue(a.results().isEmpty())
        ui.answer("chat-a", "c1", ApprovalOutcome.ALLOWED_ONCE)
        advanceUntilIdle()
        assertEquals("fetch:x", a.results().single().content)
    }

    @Test
    fun aStaleOrUnknownAnswerChangesNothing() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val model = FakeModel(replies = listOf(call("c1", "rm"), say("ok"), call("c2", "rm"), say("ok")))
        val rig = Rig(this, model, rm)
        val ui = Answerer(rig.harness)
        val agent = rig.open()
        agent.followup("one")
        runCurrent()
        assertFalse("unknown call", ui.answer("s1", "nope", ApprovalOutcome.ALLOWED_FOR_SESSION))
        assertFalse("other session", ui.answer("s2", "c1", ApprovalOutcome.ALLOWED_FOR_SESSION))
        ui.answer("s1", "c1", ApprovalOutcome.REJECTED)
        advanceUntilIdle()
        assertFalse("already answered", ui.answer("s1", "c1", ApprovalOutcome.ALLOWED_FOR_SESSION))
        assertFalse(agent.sessionGrants.contains("rm"))
        // The next call still asks: nothing leaked from the stale answers.
        agent.followup("two")
        runCurrent()
        assertTrue(("s1" to "c2") in ui.pending)
        ui.answer("s1", "c2", ApprovalOutcome.REJECTED)
        advanceUntilIdle()
        assertEquals(0, rm.started.get())
    }

    /** A store that suspends on every write, like the real (I/O) ones. */
    private class SuspendingStore : SessionStore {
        private val inner = InMemorySessionStore()
        override suspend fun load(sessionId: String): List<LogEntry> = inner.load(sessionId)
        override suspend fun append(sessionId: String, entries: List<LogEntry>) {
            yield()
            inner.append(sessionId, entries)
        }
    }

    @Test
    fun cancellingWhileAnApprovalIsPendingLogsItAndAnswersTheCallAsNotRun() = runTest(timeout = 10.seconds) {
        val fetch = FakeTool("fetch", ToolKind.FETCH)
        val model = FakeModel(replies = listOf(call("c1", "fetch", """{"text":"x"}"""), say("next turn")))
        val rig = Rig(this, model, fetch)
        val ui = Answerer(rig.harness)
        val agent = rig.open(store = SuspendingStore())
        agent.followup("go")
        advanceUntilIdle()
        assertTrue(("s1" to "c1") in ui.pending)

        agent.cancel()
        advanceUntilIdle()
        assertTrue("the prompt went away with the turn", ui.pending.isEmpty())
        assertEquals(ApprovalOutcome.CANCELLED, agent.all<SessionEvent.ApprovalDecided>().single().outcome)
        val result = agent.results().single()
        assertEquals("ABORTED_BEFORE_DISPATCH", result.code)
        assertEquals(0, fetch.started.get())
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertTrue("the tool did not run, so the receipt does not list it", agent.all<SessionEvent.TurnReceipt>().single().tools.isEmpty())
        assertFalse("a late answer finds nothing", ui.answer("s1", "c1", ApprovalOutcome.ALLOWED_ONCE))
        assertEquals("only the driver is left", 1, rig.active())

        agent.followup("again")
        advanceUntilIdle()
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun anAnswererThatThrowsFailsClosed() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val model = FakeModel(replies = listOf(call("c1", "rm"), say("ok")))
        val rig = Rig(this, model, rm)
        rig.harness.root.intercept(Hooks.ApprovalRequest) { _, _ -> error("UI crashed") }
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("APPROVAL_UNAVAILABLE", agent.results().single().code)
        assertEquals(0, rm.started.get())
    }

    @Test
    fun neverPolicyRejectsWithoutAskingAnyone() = runTest(timeout = 10.seconds) {
        val rm = FakeTool("rm", ToolKind.DELETE)
        val model = FakeModel(replies = listOf(call("c1", "rm"), say("ok")))
        val rig = Rig(this, model, rm)
        val ui = Answerer(rig.harness)
        val agent = rig.open(policy = ApprovalPolicy.NEVER)
        agent.followup("go")
        advanceUntilIdle()
        assertTrue(ui.asked.isEmpty())
        assertEquals("USER_REJECTED", agent.results().single().code)
    }

    @Test
    fun presetsDecideEveryKindAsDocumented() {
        val expected = mapOf(
            PermissionPreset.READ_ONLY to mapOf(
                ToolKind.READ to "allow", ToolKind.SEARCH to "allow", ToolKind.OTHER to "allow", ToolKind.INTERACT to "allow",
                ToolKind.EDIT to "deny", ToolKind.GENERATE to "deny", ToolKind.DELETE to "deny", ToolKind.EXECUTE to "deny", ToolKind.FETCH to "ask",
            ),
            PermissionPreset.WORKSPACE to mapOf(
                ToolKind.READ to "allow", ToolKind.SEARCH to "allow", ToolKind.OTHER to "allow", ToolKind.INTERACT to "allow",
                ToolKind.EDIT to "allow", ToolKind.GENERATE to "allow", ToolKind.DELETE to "ask", ToolKind.EXECUTE to "ask", ToolKind.FETCH to "ask",
            ),
            PermissionPreset.FULL_ACCESS to ToolKind.entries.associateWith { "allow" },
        )
        for ((preset, kinds) in expected) {
            for (kind in ToolKind.entries) {
                val decision = preset.decide(kind)
                val word = when (decision) {
                    PreToolDecision.Allow -> "allow"
                    is PreToolDecision.Ask -> "ask"
                    is PreToolDecision.Deny -> "deny"
                }
                assertEquals("$preset $kind", kinds.getValue(kind), word)
            }
            assertEquals(preset, PermissionPreset.fromLabel(preset.label))
        }
    }

    @Test
    fun presetsApplyEndToEndAndAChangeTakesEffectOnTheNextCall() = runTest(timeout = 10.seconds) {
        val edit = FakeTool("edit", ToolKind.EDIT)
        val model = FakeModel(replies = listOf(call("c1", "edit"), say("no"), call("c2", "edit"), say("yes")))
        val rig = Rig(this, model, edit)
        val ui = Answerer(rig.harness)
        val agent = rig.open(permission = PermissionPreset.READ_ONLY)
        agent.followup("edit")
        advanceUntilIdle()
        assertEquals("PERMISSION_DENIED", agent.results().single().code)
        assertTrue("a denial never asks", ui.asked.isEmpty())

        agent.setPermission(PermissionPreset.WORKSPACE)
        agent.followup("edit now")
        advanceUntilIdle()
        assertEquals(null, agent.results().last().code)
        assertEquals("workspace-write", agent.all<SessionEvent.PermissionPresetChanged>().single().preset)
    }

    @Test
    fun fullAccessNeverAsksForCleanCalls() = runTest(timeout = 10.seconds) {
        val tools = ToolKind.entries.map { FakeTool("t_${it.name.lowercase()}", it) }
        val model = FakeModel(replies = listOf(calls(*tools.mapIndexed { i, t -> Triple<String?, String, String>("c$i", t.name, """{"text":"plain"}""") }.toTypedArray()), say("ok")))
        val rig = Rig(this, model, *tools.toTypedArray())
        val ui = Answerer(rig.harness)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("go")
        advanceUntilIdle()
        assertTrue(ui.asked.isEmpty())
        assertTrue(agent.results().none { it.isError })
    }
}
