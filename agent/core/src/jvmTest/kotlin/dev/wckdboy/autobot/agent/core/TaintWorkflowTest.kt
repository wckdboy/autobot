package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Private data flowing towards the network, end to end through steps, subagents and sinks. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaintWorkflowTest {

    private companion object {
        const val NOTE = "Patient file for Jonas Weber, insurance number K482913377, born 1984, phone 0176 5550 1234."
    }

    private fun memory() = FakeTool("memory_search", privateSource = PrivateSource("memory", names = true)) { _, _ -> ToolOutput(NOTE) }
    private fun web() = FakeTool("web_fetch", ToolKind.FETCH)
    private fun arg(text: String) = buildJsonObject { put("text", text) }.toString()

    private class Asked(harness: Harness, private val answer: ApprovalOutcome) {
        val requests = mutableListOf<ApprovalRequest>()

        init {
            harness.root.intercept(Hooks.ApprovalRequest) { request, _ ->
                requests += request
                answer
            }
        }
    }

    @Test
    fun privateDataInAFetchAsksEvenUnderFullAccessAndApprovalLetsItThrough() = runTest(timeout = 10.seconds) {
        val web = web()
        val model = FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "web_fetch", arg("https://lookup.example/?id=K482913377")), say("ok")))
        val rig = Rig(this, model, memory(), web)
        val asked = Asked(rig.harness, ApprovalOutcome.ALLOWED_ONCE)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("look the patient up")
        advanceUntilIdle()
        val request = asked.requests.single()
        assertEquals("c2", request.callId)
        assertTrue(request.privateData.toString(), "K482913377" in request.privateData)
        assertTrue(request.reason!!.startsWith("Would send private data: memory"))
        assertEquals(1, web.started.get())
    }

    @Test
    fun deniedTaintedFetchNeverRuns() = runTest(timeout = 10.seconds) {
        val web = web()
        val model = FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "web_fetch", arg("call 0176 5550 1234 please")), say("ok")))
        val rig = Rig(this, model, memory(), web)
        Asked(rig.harness, ApprovalOutcome.REJECTED)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("go")
        advanceUntilIdle()
        assertEquals("USER_REJECTED", agent.results().last().code)
        assertEquals(0, web.started.get())
    }

    @Test
    fun cleanDataUnderFullAccessDoesNotAsk() = runTest(timeout = 10.seconds) {
        val web = web()
        val model = FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "web_fetch", arg("https://weather.example/berlin?days=3")), say("ok")))
        val rig = Rig(this, model, memory(), web)
        val asked = Asked(rig.harness, ApprovalOutcome.REJECTED)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("weather")
        advanceUntilIdle()
        assertTrue(asked.requests.isEmpty())
        assertEquals(1, web.started.get())
    }

    @Test
    fun withNeverPolicyTaintedFetchIsDeniedWithTheReason() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "web_fetch", arg("Jonas Weber")), say("ok")))
        val rig = Rig(this, model, memory(), web())
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS, policy = ApprovalPolicy.NEVER)
        agent.followup("go")
        advanceUntilIdle()
        val result = agent.results().last()
        assertEquals("PERMISSION_DENIED", result.code)
        assertTrue(result.content, "Would send private data" in result.content)
    }

    @Test
    fun taintAskingNeverOverridesADenialFurtherIn() = runTest(timeout = 10.seconds) {
        // A gate that refuses the call (a disabled MCP server, a host policy) must win: the user is
        // not offered to approve a call that could never be allowed.
        val web = web()
        val model = FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "web_fetch", arg("K482913377")), say("ok")))
        val rig = Rig(this, model, memory(), web)
        rig.harness.root.intercept(Hooks.ToolPreExecute, priority = 200) { input, next ->
            if (input.tool.name == "web_fetch") PreToolDecision.Deny("the server is disabled") else next(input)
        }
        val asked = Asked(rig.harness, ApprovalOutcome.ALLOWED_ONCE)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("go")
        advanceUntilIdle()
        assertTrue("nobody was asked", asked.requests.isEmpty())
        assertEquals("PERMISSION_DENIED", agent.results().last().code)
        assertEquals(0, web.started.get())
    }

    @Test
    fun taintSurvivesThroughASubagentThatReadsAndFetches() = runTest(timeout = 10.seconds) {
        val web = web()
        val model = FakeModel(route = { request, _ ->
            when {
                request.isSubagent && request.toolResults.isEmpty() -> call("k1", "memory_search")(this, request)
                request.isSubagent && request.toolResults.size == 1 -> call("k2", "web_fetch", arg("https://x.example/?q=Jonas+Weber"))(this, request)
                request.isSubagent -> say("done")(this, request)
                request.toolResults.isEmpty() -> chunks(
                    StreamChunk.ToolCallDelta(0, "t1", "task", buildJsonObject { put("description", "lookup"); put("prompt", "Look up the patient.") }.toString()),
                    StreamChunk.Finish("tool_calls"),
                )(this, request)
                request.toolResults.size == 1 -> call("c2", "web_fetch", arg("weber jonas"))(this, request)
                else -> say("ok")(this, request)
            }
        })
        val rig = Rig(this, model, memory(), web)
        val asked = Asked(rig.harness, ApprovalOutcome.REJECTED)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("go")
        advanceUntilIdle()
        // The subagent's own fetch asks on the parent's task card...
        assertEquals("t1", asked.requests[0].callId)
        assertTrue(asked.requests[0].privateData.containsAll(listOf("Jonas", "Weber")))
        // ...and what it read taints the parent's later fetch too.
        assertEquals("c2", asked.requests[1].callId)
        assertEquals(0, web.started.get())
    }

    /** A Studio-like tool: GENERATE, but it sends its values to a remote backend for one workflow. */
    private class RemoteRender : ToolDefinition {
        override val name = "render"
        override val description = "render"
        override val kind = ToolKind.GENERATE
        override val parameters = schema {
            string("workflow", "w")
            string("prompt", "p")
        }
        var ran = 0

        override suspend fun reachesNetwork(args: JsonObject) = args.string("workflow") == "remote"

        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            ran++
            return ToolOutput("rendered")
        }
    }

    @Test
    fun aToolThatReachesTheNetworkPerCallIsCheckedLikeAFetch() = runTest(timeout = 10.seconds) {
        val render = RemoteRender()
        val model = FakeModel(
            replies = listOf(
                call("c1", "memory_search"),
                call("c2", "render", """{"workflow":"local","prompt":"portrait of Jonas Weber"}"""),
                call("c3", "render", """{"workflow":"remote","prompt":"portrait of Jonas Weber"}"""),
                say("ok"),
            ),
        )
        val rig = Rig(this, model, memory(), render)
        val asked = Asked(rig.harness, ApprovalOutcome.REJECTED)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("draw the patient")
        advanceUntilIdle()
        assertEquals(listOf("c3"), asked.requests.map { it.callId })
        assertEquals(1, render.ran)
        assertEquals(listOf(null, null, "USER_REJECTED"), agent.results().map { it.code })
    }

    @Test
    fun allowForSessionOnATaintPromptCoversThoseFragmentsAcrossTurns() = runTest(timeout = 10.seconds) {
        val web = web()
        val model = FakeModel(
            replies = listOf(
                call("c1", "memory_search"),
                call("c2", "web_fetch", arg("K482913377")),
                say("ok"),
                call("c3", "web_fetch", arg("K482913377 again")),
                say("ok"),
                call("c4", "web_fetch", arg("0176 5550 1234")),
                say("ok"),
            ),
        )
        val rig = Rig(this, model, memory(), web)
        val asked = Asked(rig.harness, ApprovalOutcome.ALLOWED_FOR_SESSION)
        val agent = rig.open(permission = PermissionPreset.FULL_ACCESS)
        agent.followup("one")
        advanceUntilIdle()
        agent.followup("two")
        advanceUntilIdle()
        agent.followup("three")
        advanceUntilIdle()
        // c3 repeats the allowed fragment; c4 carries a new one and asks again.
        assertEquals(listOf("c2", "c4"), asked.requests.map { it.callId })
        assertEquals(3, web.started.get())
    }
}
