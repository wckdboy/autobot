package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Multi-step, multi-turn tool use against a scripted provider. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiTurnToolUseTest {

    @Test
    fun sequentialToolCallsFeedEachStepAndTheTurnEndsOnText() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(
            replies = listOf(
                call("c1", "read", """{"text":"a"}"""),
                call("c2", "read", """{"text":"b"}"""),
                say("a then b"),
            ),
        )
        val rig = Rig(this, model, read)
        val agent = rig.open()
        agent.followup("read a then b")
        advanceUntilIdle()

        assertEquals(listOf("read:a", "read:b"), agent.results().map { it.content })
        assertEquals(3, agent.all<SessionEvent.StepStart>().size)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        // The second request sees the first result; the third sees both, in order.
        assertEquals(listOf("read:a"), model.calls[1].toolResults.map { it.text })
        assertEquals(listOf("read:a", "read:b"), model.calls[2].toolResults.map { it.text })
        assertEquals(listOf("c1", "c2"), model.calls[2].toolResults.map { it.toolCallId })
    }

    @Test
    fun parallelCallsRunTogetherButCommitInEmissionOrder() = runTest(timeout = 10.seconds) {
        // The first call finishes last: results must still be committed c1, c2, c3.
        val read = FakeTool("read") { args, _ ->
            delay(if (args.text() == "a") 300 else 10)
            ToolOutput("got ${args.text()}")
        }
        val model = FakeModel(
            replies = listOf(
                calls(Triple("c1", "read", """{"text":"a"}"""), Triple("c2", "read", """{"text":"b"}"""), Triple("c3", "read", """{"text":"c"}""")),
                say("done"),
            ),
        )
        val agent = Rig(this, model, read).open()
        agent.followup("go")
        advanceUntilIdle()

        assertEquals(3, read.maxConcurrent.get())
        assertEquals(listOf("c1", "c2", "c3"), agent.results().map { it.callId })
        assertEquals(listOf("got a", "got b", "got c"), model.calls[1].toolResults.map { it.text })
    }

    @Test
    fun maxParallelToolCallsBoundsABatch() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read") { _, _ ->
            delay(50)
            ToolOutput("ok")
        }
        val many = (1..7).map { Triple<String?, String, String>("c$it", "read", """{"text":"$it"}""") }.toTypedArray()
        val model = FakeModel(replies = listOf(calls(*many), say("done")))
        val rig = Rig(this, model, read)
        val agent = rig.open(config = dev.wckdboy.autobot.agent.core.loop.AgentConfig(dev.wckdboy.autobot.agent.core.llm.LlmCallConfig("fake", "m"), maxParallelToolCalls = 3))
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(3, read.maxConcurrent.get())
        assertEquals(7, agent.results().size)
    }

    @Test
    fun mixedBatchesKeepBarriersBetweenSafeRuns() = runTest(timeout = 10.seconds) {
        val order = mutableListOf<String>()
        val read = FakeTool("read") { args, _ ->
            order += "start ${args.text()}"
            delay(20)
            order += "end ${args.text()}"
            ToolOutput("r")
        }
        val write = FakeTool("write", ToolKind.EDIT, safe = false) { args, _ ->
            order += "write ${args.text()}"
            ToolOutput("w")
        }
        val model = FakeModel(
            replies = listOf(
                calls(
                    Triple("c1", "read", """{"text":"1"}"""),
                    Triple("c2", "read", """{"text":"2"}"""),
                    Triple("c3", "write", """{"text":"3"}"""),
                    Triple("c4", "read", """{"text":"4"}"""),
                ),
                say("ok"),
            ),
        )
        val agent = Rig(this, model, read, write).open()
        agent.followup("go")
        advanceUntilIdle()
        // 1 and 2 overlap; the write waits for both; 4 waits for the write.
        assertEquals(listOf("start 1", "start 2", "end 1", "end 2", "write 3", "start 4", "end 4"), order)
    }

    @Test
    fun malformedToolCallJsonIsAnErrorResultAndTheModelCanRecover() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read", parameters = schema { string("text", "text", required = true) })
        val model = FakeModel(
            replies = listOf(
                calls(
                    Triple("c1", "read", """{"text":"unterminated"""),
                    Triple("c2", "read", """["not","an","object"]"""),
                    Triple("c3", "read", """{"text":"x"} trailing"""),
                    Triple("c4", "read", "{}"),
                    Triple("c5", "read", ""),
                ),
                call("c6", "read", """{"text":"fixed"}"""),
                say("recovered"),
            ),
        )
        val agent = Rig(this, model, read).open()
        agent.followup("go")
        advanceUntilIdle()

        val results = agent.results()
        assertEquals(listOf("INVALID_ARGS", "INVALID_ARGS", "INVALID_ARGS", "INVALID_ARGS", "INVALID_ARGS", null), results.map { it.code })
        assertTrue(results[3].content, "missing required 'text'" in results[3].content)
        // The blank arguments were logged as "{}" (a valid history for strict providers).
        val logged = agent.all<SessionEvent.AssistantMessage>().first().toolCalls
        assertEquals("{}", logged[4].arguments)
        assertEquals(1, read.started.get())
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        // The model saw every error and its own malformed call (history stays paired).
        assertEquals(5, model.calls[1].toolResults.size)
    }

    @Test
    fun unknownAndDeniedByRestrictionToolsAreUnknownTool() = runTest(timeout = 10.seconds) {
        val hidden = FakeTool("hidden")
        val model = FakeModel(replies = listOf(calls(Triple("c1", "nope", "{}"), Triple("c2", "hidden", "{}"), Triple("c3", "", "{}")), say("ok")))
        val rig = Rig(this, model, hidden)
        val agent = rig.open(config = dev.wckdboy.autobot.agent.core.loop.AgentConfig(dev.wckdboy.autobot.agent.core.llm.LlmCallConfig("fake", "m"), restriction = dev.wckdboy.autobot.agent.core.tools.ToolRestriction(deny = setOf("hidden"))))
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(listOf("UNKNOWN_TOOL", "UNKNOWN_TOOL", "UNKNOWN_TOOL"), agent.results().map { it.code })
        assertEquals(0, hidden.started.get())
        assertFalse("a denied tool is not offered", model.calls[0].tools.any { it.name == "hidden" })
    }

    @Test
    fun aToolThatThrowsIsAnErrorResultNotATurnFailure() = runTest(timeout = 10.seconds) {
        val boom = FakeTool("boom") { _, _ -> throw IllegalStateException("disk on fire") }
        val npe = FakeTool("npe") { _, _ -> throw NullPointerException() }
        val model = FakeModel(replies = listOf(calls(Triple("c1", "boom", "{}"), Triple("c2", "npe", "{}")), say("sorry")))
        val agent = Rig(this, model, boom, npe).open()
        agent.followup("go")
        advanceUntilIdle()
        val (a, b) = agent.results()
        assertEquals("TOOL_ERROR", a.code)
        assertEquals("Error: disk on fire", a.content)
        assertEquals("TOOL_ERROR", b.code)
        assertEquals("Error: NullPointerException", b.content)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun aToolThatThrowsCancellationOnItsOwnIsAnErrorNotAnAbortedTurn() = runTest(timeout = 10.seconds) {
        // E.g. a tool awaiting a job that someone else cancelled: the turn itself is still active.
        val flaky = FakeTool("flaky") { _, _ ->
            coroutineScope {
                val inner = async { awaitCancellation() }
                inner.cancel(CancellationException("worker gone"))
                inner.await()
            }
        }
        val model = FakeModel(replies = listOf(call("c1", "flaky"), say("handled")))
        val agent = Rig(this, model, flaky).open()
        agent.followup("go")
        advanceUntilIdle()
        val result = agent.results().single()
        assertTrue(result.isError)
        assertEquals("TOOL_ERROR", result.code)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertEquals("handled", agent.all<SessionEvent.AssistantMessage>().last().text)
    }

    @Test
    fun aToolThatTimesOutIsATimeoutResultAndTheTurnGoesOn() = runTest(timeout = 10.seconds) {
        val slow = FakeTool("slow", timeoutMs = 5_000) { _, _ ->
            delay(60_000)
            ToolOutput("never")
        }
        val model = FakeModel(replies = listOf(call("c1", "slow"), say("it timed out")))
        val agent = Rig(this, model, slow).open()
        agent.followup("go")
        advanceUntilIdle()
        val result = agent.results().single()
        assertEquals("TIMEOUT", result.code)
        assertEquals("Error: timed out after 5000 ms", result.content)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
        assertEquals("virtual time only advanced to the timeout", 5_000L, currentTime)
        assertTrue(agent.runningTools.value.isEmpty())
    }

    @Test
    fun theDefaultToolTimeoutAppliesWhenAToolDeclaresNone() = runTest(timeout = 10.seconds) {
        val hung = FakeTool("hung") { _, _ -> awaitCancellation() }
        val model = FakeModel(replies = listOf(call("c1", "hung"), say("moving on")))
        val agent = Rig(this, model, hung).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("TIMEOUT", agent.results().single().code)
        assertEquals(dev.wckdboy.autobot.agent.core.tools.ToolExecutor.DEFAULT_TIMEOUT_MS, currentTime)
    }

    @Test
    fun hugeToolOutputIsTruncatedBeforeItIsLoggedOrSent() = runTest(timeout = 10.seconds) {
        val head = "H".repeat(4096)
        val tail = "T".repeat(1024)
        val big = FakeTool("big") { _, _ -> ToolOutput(head + "M".repeat(2_000_000) + tail) }
        val model = FakeModel(replies = listOf(call("c1", "big"), say("ok")))
        val agent = Rig(this, model, big).open()
        agent.followup("go")
        advanceUntilIdle()
        val content = agent.results().single().content
        assertTrue(content.length < 6_000)
        assertTrue(content.startsWith(head))
        assertTrue(content.endsWith(tail))
        assertTrue("2000000 characters omitted" in content)
        assertEquals(content, model.calls[1].toolResults.single().text)
    }

    @Test
    fun outputJustUnderTheThresholdIsKeptWhole() = runTest(timeout = 10.seconds) {
        val exact = FakeTool("exact") { _, _ -> ToolOutput("x".repeat(8192)) }
        val model = FakeModel(replies = listOf(call("c1", "exact"), say("ok")))
        val agent = Rig(this, model, exact).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(8192, agent.results().single().content.length)
    }

    @Test
    fun repeatedToolCallIdsInOneMessageAndAcrossTurnsAreMadeUnique() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(
            replies = listOf(
                calls(Triple("dup", "read", """{"text":"1"}"""), Triple("dup", "read", """{"text":"2"}""")),
                call("dup", "read", """{"text":"3"}"""),
                say("first"),
                call("dup", "read", """{"text":"4"}"""),
                say("second"),
            ),
        )
        val agent = Rig(this, model, read).open()
        agent.followup("one")
        advanceUntilIdle()
        agent.followup("two")
        advanceUntilIdle()

        val ids = agent.all<SessionEvent.AssistantMessage>().flatMap { it.toolCalls }.map { it.id }
        assertEquals(4, ids.size)
        assertEquals("every call id is unique in the session", ids.toSet().size, ids.size)
        assertEquals(ids, agent.results().map { it.callId })
        assertEquals(listOf("read:1", "read:2", "read:3", "read:4"), agent.results().map { it.content })
        // Each request pairs every call with exactly its own result.
        val last = model.calls.last().messages
        val callIds = last.filter { it.role == ModelRole.ASSISTANT }.flatMap { it.toolCalls }.map { it.id }
        assertEquals(callIds, last.filter { it.role == ModelRole.TOOL }.map { it.toolCallId })
        assertEquals(AgentStatus.IDLE, agent.status.value)
    }

    @Test
    fun callsWithoutIdsGetPositionalIds() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(replies = listOf(calls(Triple(null, "read", "{}"), Triple("", "read", "{}")), say("ok")))
        val agent = Rig(this, model, read).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(listOf("call_1_1_0", "call_1_1_1"), agent.results().map { it.callId })
    }

    @Test
    fun toolCallFragmentsArriveInterleavedAcrossIndices() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(
            replies = listOf(
                chunks(
                    StreamChunk.ToolCallDelta(1, "b", "read", """{"te"""),
                    StreamChunk.ToolCallDelta(0, "a", "read", """{"text":"""),
                    StreamChunk.ToolCallDelta(1, null, null, """xt":"B"}"""),
                    StreamChunk.ToolCallDelta(0, null, null, """"A"}"""),
                    StreamChunk.Finish("tool_calls"),
                ),
                say("ok"),
            ),
        )
        val agent = Rig(this, model, read).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(listOf("a" to "read:A", "b" to "read:B"), agent.results().map { it.callId to it.content })
    }

    @Test
    fun aConcludingToolEndsTheTurnWithoutAnotherRequest() = runTest(timeout = 10.seconds) {
        val done = FakeTool("finish") { _, _ -> ToolOutput("bye", concludesTurn = true) }
        val model = FakeModel(replies = listOf(call("c1", "finish")))
        val agent = Rig(this, model, done).open()
        agent.followup("go")
        advanceUntilIdle()
        assertEquals(1, model.calls.size)
        assertEquals(TurnEndReason.COMPLETED, agent.end().reason)
    }

    @Test
    fun aRunawayLoopStopsAtMaxSteps() = runTest(timeout = 10.seconds) {
        val read = FakeTool("read")
        val model = FakeModel(route = { request, n -> call("c$n", "read")(this, request) })
        val agent = Rig(this, model, read).open(
            config = dev.wckdboy.autobot.agent.core.loop.AgentConfig(dev.wckdboy.autobot.agent.core.llm.LlmCallConfig("fake", "m"), maxStepsPerTurn = 4),
        )
        agent.followup("loop")
        advanceUntilIdle()
        assertEquals(TurnEndReason.MAX_STEPS, agent.end().reason)
        assertEquals(4, model.calls.size)
        assertEquals(4, agent.results().size)
    }

    @Test
    fun followupsQueueAsSeparateTurnsAndSteerJoinsTheRunningOne() = runTest(timeout = 10.seconds) {
        val gate = CompletableDeferred<Unit>()
        val wait = FakeTool("wait") { _, _ ->
            gate.await()
            ToolOutput("waited")
        }
        val model = FakeModel(replies = listOf(call("c1", "wait"), say("first done"), say("second done")))
        val agent = Rig(this, model, wait).open()
        agent.followup("first")
        runCurrent()
        agent.steer("also this")
        agent.followup("second")
        gate.complete(Unit)
        advanceUntilIdle()

        val turns = agent.all<SessionEvent.TurnStart>().map { it.turn }
        assertEquals(listOf(1, 2), turns)
        // The steer was delivered at the step boundary of turn 1.
        assertTrue(model.calls[1].messages.any { it.role == ModelRole.USER && it.text == "also this" })
        assertFalse(model.calls[1].messages.any { it.text == "second" })
        assertEquals("second", model.calls[2].messages.last { it.role == ModelRole.USER }.text)
        assertEquals(listOf(TurnEndReason.COMPLETED, TurnEndReason.COMPLETED), agent.all<SessionEvent.TurnEnd>().map { it.reason })
    }

    @Test
    fun anAgentWithToolsDisabledSendsNoSchemasAndPlainChats() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(say("just text")))
        val agent = Rig(this, model, FakeTool("read")).open(
            config = dev.wckdboy.autobot.agent.core.loop.AgentConfig(dev.wckdboy.autobot.agent.core.llm.LlmCallConfig("fake", "m"), toolsEnabled = false),
        )
        agent.followup("hi")
        advanceUntilIdle()
        assertTrue(model.calls.single().tools.isEmpty())
    }
}
