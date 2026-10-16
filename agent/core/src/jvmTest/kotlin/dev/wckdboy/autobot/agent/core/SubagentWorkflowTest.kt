package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Subagents (`task`): depth, approvals, failures and cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentWorkflowTest {

    private fun task(id: String, prompt: String, index: Int = 0) =
        StreamChunk.ToolCallDelta(index, id, "task", buildJsonObject { put("description", "job $id"); put("prompt", prompt) }.toString())

    private fun delegate(vararg ids: String): Reply = {
        ids.forEachIndexed { i, id -> emit(task(id, "Do job $id.", i)) }
        emit(StreamChunk.Finish("tool_calls"))
    }

    @Test
    fun aSubagentCannotSpawnSubagents() = runTest(timeout = 10.seconds) {
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent && request.toolResults.isEmpty() -> delegate("nested")(this, request)
                request.isSubagent -> say("nested result: ${request.toolResults.single().text}")(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("parent done")(this, request)
            }
        })
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()

        val subagentRequest = model.calls.first { it.isSubagent }
        assertFalse("task is not offered to a subagent", subagentRequest.tools.any { it.name == "task" })
        val report = agent.results().single()
        assertTrue(report.content, report.content.contains("unknown tool 'task'"))
        assertEquals(listOf(Triple("task", "job nested", "error")), TaskTool.steps(report.meta))
    }

    @Test
    fun theTaskToolRefusesToRunInsideASubagentSession() = runTest(timeout = 10.seconds) {
        val rig = Rig(this, FakeModel())
        val child = rig.open(TaskTool.childId("s1", "t1"))
        val run = object : ToolRunContext {
            override val callId = "x"
            override val sessionId = child.id
            override val agent = child
            override fun deferContext(text: String) = Unit
            override suspend fun log(event: SessionEvent) = Unit
            override suspend fun askUser(question: UserQuestion): List<String>? = null
        }
        val out = TaskTool.execute(buildJsonObject { put("description", "d"); put("prompt", "p") }, run)
        assertEquals("PERMISSION_DENIED", out.code)
        child.dispose()
    }

    @Test
    fun subagentApprovalsSurfaceOnTheParentCardAndParallelTasksAskSeparately() = runTest(timeout = 10.seconds) {
        val fetch = FakeTool("fetch", ToolKind.FETCH)
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent && request.toolResults.isEmpty() -> call("k1", "fetch", """{"text":"site"}""")(this, request)
                request.isSubagent -> say("report: ${request.toolResults.single().text}")(this, request)
                request.toolResults.isEmpty() -> delegate("t1", "t2")(this, request)
                else -> say("both done")(this, request)
            }
        })
        val rig = Rig(this, model, fetch)
        val pending = LinkedHashMap<String, CompletableDeferred<ApprovalOutcome>>()
        val asked = mutableListOf<ApprovalRequest>()
        rig.harness.root.intercept(Hooks.ApprovalRequest) { request, _ ->
            asked += request
            CompletableDeferred<ApprovalOutcome>().also { pending[request.callId] = it }.await()
        }
        val agent = rig.open()
        agent.followup("go")
        runCurrent()
        // Each subagent's call surfaces on its own task card of the parent session.
        assertEquals(setOf("t1", "t2"), pending.keys)
        assertTrue(asked.all { it.agent === agent && it.toolName == "fetch" })
        pending.getValue("t2").complete(ApprovalOutcome.REJECTED)
        pending.getValue("t1").complete(ApprovalOutcome.ALLOWED_ONCE)
        advanceUntilIdle()

        val byCall = agent.results().associateBy { it.callId }
        assertEquals("report: fetch:site", byCall.getValue("t1").content)
        assertEquals(listOf(Triple("fetch", "text=site", "error")), TaskTool.steps(byCall.getValue("t2").meta))
        assertEquals(1, fetch.started.get())
    }

    @Test
    fun aSubagentsModelFailureIsAnErrorResultAndTheParentCarriesOn() = runTest(timeout = 10.seconds) {
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent -> fail(LlmErrorCode.AUTH, "bad key")(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("the subagent failed: ${request.toolResults.single().text}")(this, request)
            }
        })
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()
        val result = agent.results().single()
        assertTrue(result.isError)
        assertEquals("SUBAGENT_ERROR", result.code)
        assertEquals("The subagent stopped (error: AUTH: bad key).", result.content)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertEquals("only the parent's driver is left", 1, rig.active())
    }

    @Test
    fun aSubagentWithoutAFinalReportIsAnError() = runTest(timeout = 10.seconds) {
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent -> chunks(StreamChunk.ReasoningDelta("hmm"), StreamChunk.Finish("stop"))(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("ok")(this, request)
            }
        })
        val agent = Rig(this, model).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("EMPTY_REPORT", agent.results().single().code)
    }

    @Test
    fun aRunawaySubagentStopsAtItsStepLimitAndKeepsItsLastMessage() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(route = { request, n ->
            when {
                request.isSubagent -> chunks(StreamChunk.TextDelta("still looking"), StreamChunk.ToolCallDelta(0, "k$n", "read", "{}"), StreamChunk.Finish("tool_calls"))(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("ok")(this, request)
            }
        })
        val agent = Rig(this, model, read).open()
        agent.followup("go")
        advanceUntilIdle()
        val result = agent.results().single()
        assertEquals("SUBAGENT_MAX_STEPS", result.code)
        assertTrue(result.content.endsWith("Last message:\nstill looking"))
        assertEquals(24, read.started.get())
    }

    @Test
    fun cancellingTheParentCancelsTheSubagentsToolAndRequests() = runTest(timeout = 10.seconds) {
        var cleanedUp = false
        val slow = FakeTool("slow") { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cleanedUp = true
            }
        }
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent -> call("k1", "slow")(this, request)
                else -> delegate("t1")(this, request)
            }
        })
        val rig = Rig(this, model, slow)
        val agent = rig.open()
        agent.followup("go")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, slow.started.get())
        agent.cancel()
        advanceUntilIdle()

        assertTrue(cleanedUp)
        assertEquals(TurnEndReason.ABORTED, agent.end().reason)
        assertEquals("TOOL_OUTCOME_UNKNOWN", agent.results().single().code)
        assertEquals(1, rig.active())
        val requests = model.calls.size
        advanceTimeBy(60_000)
        assertEquals(requests, model.calls.size)
    }

    @Test
    fun cancellingTheParentWithdrawsTheSubagentsPendingApproval() = runTest(timeout = 10.seconds) {
        val fetch = FakeTool("fetch", ToolKind.FETCH)
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent -> call("k1", "fetch")(this, request)
                else -> delegate("t1")(this, request)
            }
        })
        val rig = Rig(this, model, fetch)
        var waiting = 0
        rig.harness.root.intercept(Hooks.ApprovalRequest) { _, _ ->
            waiting++
            try {
                awaitCancellation()
            } finally {
                waiting--
            }
        }
        val agent = rig.open()
        agent.followup("go")
        runCurrent()
        assertEquals(1, waiting)
        agent.cancel()
        advanceUntilIdle()
        assertEquals("the prompt was withdrawn", 0, waiting)
        assertEquals(0, fetch.started.get())
        assertEquals(1, rig.active())
    }

    @Test
    fun aHungSubagentTimesOutAfterTheTaskTimeout() = runTest(timeout = 10.seconds) {
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent -> hang(StreamChunk.TextDelta("..."))(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("gave up")(this, request)
            }
        })
        val rig = Rig(this, model)
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("TIMEOUT", agent.results().single().code)
        assertEquals(30.minutes.inWholeMilliseconds, testScheduler.currentTime)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertEquals(1, rig.active())
    }

    @Test
    fun theParentReceiptListsTheModelTheSubagentRanOn() = runTest(timeout = 10.seconds) {
        val model = FakeModel(helper = "helper-model", route = { request, _ ->
            when {
                request.isSubagent -> say("found it")(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("done")(this, request)
            }
        })
        val agent = Rig(this, model).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("helper-model", model.calls.single { it.isSubagent }.config.model)
        val receipt = agent.all<SessionEvent.TurnReceipt>().single()
        assertEquals(listOf("main-model", "helper-model"), receipt.models.map { it.model })
        assertEquals(listOf("task"), receipt.tools)
    }

    @Test
    fun aSubagentsQuestionsReachTheParentsAnswerer() = runTest(timeout = 10.seconds) {
        val ask = FakeTool("ask", ToolKind.INTERACT) { _, run -> ToolOutput("answer=" + run.askUser(UserQuestion("Which?", listOf("a", "b"))).orEmpty().joinToString()) }
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent && request.toolResults.isEmpty() -> call("k1", "ask")(this, request)
                request.isSubagent -> say(request.toolResults.single().text)(this, request)
                request.toolResults.isEmpty() -> delegate("t1")(this, request)
                else -> say("ok")(this, request)
            }
        })
        val rig = Rig(this, model, ask)
        val questions = mutableListOf<QuestionRequest>()
        rig.harness.root.intercept(Hooks.UserQuestion) { request, _ ->
            questions += request
            listOf("b")
        }
        val agent = rig.open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("t1", questions.single().callId)
        assertTrue(questions.single().agent === agent)
        assertEquals("answer=b", agent.results().single().content)
    }
}
