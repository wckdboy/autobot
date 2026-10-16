package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.agent.core.ApprovalRequest
import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Private data the model puts into `run_workflow` params for a remote backend asks first. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunWorkflowTaintTest {

    private class Memory : ToolDefinition {
        override val name = "memory_search"
        override val description = "notes"
        override val kind = ToolKind.READ
        override val parameters = schema { }
        override val privateSource = PrivateSource("memory", names = true)
        override suspend fun execute(args: JsonObject, run: ToolRunContext) = ToolOutput("The user's daughter is called Malin Svensson.")
    }

    private class Runner : StudioRunner {
        val runs = mutableListOf<String>()
        override suspend fun workflows() = listOf(
            WorkflowSummary("w1", "PC Portrait", "", AssetType.IMAGE, listOf(WorkflowParam("prompt", "Prompt", ParamType.STRING, listOf(InputTarget("1", "text")))), "Studio PC"),
            WorkflowSummary("w2", "Phone Portrait", "", AssetType.IMAGE, listOf(WorkflowParam("prompt", "Prompt", ParamType.STRING, listOf(InputTarget("1", "text")))), null),
        )
        override suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean): WorkflowRunResult {
            runs += workflowId
            return WorkflowRunResult(listOf("a-000000000001"), AssetType.IMAGE)
        }
    }

    private class Script(private val replies: List<List<StreamChunk>>) : LlmAdapter {
        override val provider = "fake"
        private var n = 0
        override fun stream(call: LlmCall): Flow<StreamChunk> = flow { replies[n++].forEach { emit(it) } }
        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private fun call(id: String, name: String, args: String) = listOf(StreamChunk.ToolCallDelta(0, id, name, args), StreamChunk.Finish("tool_calls"))

    @Test
    fun privateDataInARemoteWorkflowsParamsAsksEvenUnderFullAccess() = runTest(timeout = 10.seconds) {
        val runner = Runner()
        val harness = Harness()
        harness.llm.register(
            Script(
                listOf(
                    call("c1", "memory_search", "{}"),
                    call("c2", "run_workflow", """{"workflow":"Phone Portrait","params":{"prompt":"Malin Svensson as a knight"}}"""),
                    call("c3", "run_workflow", """{"workflow":"PC Portrait","params":{"prompt":"Malin Svensson as a knight"}}"""),
                    call("c4", "run_workflow", """{"workflow":"PC Portrait","params":{"prompt":"a knight on a horse"}}"""),
                    listOf(StreamChunk.TextDelta("done"), StreamChunk.Finish("stop")),
                ),
            ),
        )
        harness.tools.register(Memory())
        harness.root.plugin(studioPlugin(runner))
        val asked = mutableListOf<ApprovalRequest>()
        harness.root.intercept(Hooks.ApprovalRequest) { request, _ ->
            asked += request
            ApprovalOutcome.REJECTED
        }
        val agent = harness.openAgent(
            "s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("fake", "m")),
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), PermissionPreset.FULL_ACCESS,
        )
        agent.followup("make a portrait")
        advanceUntilIdle()

        val request = asked.single()
        assertEquals("c3", request.callId)
        assertTrue(request.privateData.toString(), request.privateData.containsAll(listOf("Malin", "Svensson")))
        assertEquals("on-device and clean runs went ahead", listOf("w2", "w1"), runner.runs)
        val codes = agent.log.entries.value.mapNotNull { (it.event as? SessionEvent.ToolResult)?.code }
        assertEquals(listOf("USER_REJECTED"), codes)
    }
}
