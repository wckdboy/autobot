package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.core.tools.validateArgs
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunWorkflowToolTest {
    private val sd15 = WorkflowImport.create("Portrait SD1.5", fixture("sd15_txt2img_api.json"), id = "w-sd15", backendId = "pc")
    private val audio = WorkflowImport.create("Stable Audio", fixture("stable_audio_api.json"), id = "w-audio", backendId = "pc")

    private class FakeRunner(val list: List<WorkflowSummary>) : StudioRunner {
        val calls = ArrayList<Triple<String, Map<String, ParamValue>, Boolean>>()
        var result: (String) -> WorkflowRunResult = { WorkflowRunResult(listOf("a-1", "a-2"), AssetType.IMAGE, 42, "Studio PC") }
        override suspend fun workflows() = list
        override suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean): WorkflowRunResult {
            calls += Triple(workflowId, values, randomizeSeed)
            return result(workflowId)
        }
    }

    private object Run : ToolRunContext {
        override val callId = "c1"
        override val sessionId = "s1"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun summary(w: StudioWorkflow) = WorkflowSummary(w.id, w.name, w.description, w.output, w.params, "Studio PC")
    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun isAGenerateToolWithAValidSchema() {
        val tool = RunWorkflowTool(FakeRunner(emptyList()))
        assertEquals("run_workflow", tool.name)
        assertEquals(ToolKind.GENERATE, tool.kind)
        assertNull(validateArgs(tool.parameters, args("""{"workflow":"x","params":{"prompt":"a","steps":3}}""")))
        assertEquals("missing required 'workflow'", validateArgs(tool.parameters, args("""{"params":{}}""")))
        assertEquals("unknown property 'extra'", validateArgs(tool.parameters, args("""{"workflow":"x","extra":1}""")))
        assertEquals(
            "Portrait SD1.5 · \"a fox in the snow\" · steps=30 seed=7",
            tool.describeCall(args("""{"workflow":"Portrait SD1.5","params":{"prompt":"a fox in the snow","steps":30,"seed":7}}""")),
        )
    }

    @Test
    fun runsByNameWithTypedValuesAndRandomSeed() = runTest {
        val runner = FakeRunner(listOf(summary(sd15), summary(audio)))
        val out = RunWorkflowTool(runner).execute(args("""{"workflow":"portrait sd1.5","params":{"prompt":"a fox","steps":"30","cfg":6,"sampler":"euler"}}"""), Run)
        assertFalse(out.content, out.isError)
        val (id, values, randomize) = runner.calls.single()
        assertEquals("w-sd15", id)
        assertEquals(ParamValue.of("a fox"), values["prompt"])
        assertEquals(ParamValue.of(30), values["steps"])
        assertEquals(ParamValue.of(6.0), values["cfg"])
        assertTrue("no seed given → random", randomize)
        assertEquals("Made 2 images with \"Portrait SD1.5\" on Studio PC (seed 42). They are in the Studio library; the user can see them below.", out.content)
        assertEquals(listOf("a-1", "a-2"), out.meta!!["gallery"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("image", out.meta!!["studio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aGivenSeedIsKept() = runTest {
        val runner = FakeRunner(listOf(summary(sd15)))
        RunWorkflowTool(runner).execute(args("""{"workflow":"Portrait","params":{"seed":7}}"""), Run)
        assertFalse(runner.calls.single().third)
        assertEquals(ParamValue.of(7), runner.calls.single().second["seed"])
    }

    @Test
    fun withoutParamsItDescribesTheWorkflow() = runTest {
        val runner = FakeRunner(listOf(summary(audio)))
        val out = RunWorkflowTool(runner).execute(args("""{"workflow":"Stable Audio"}"""), Run)
        assertTrue(runner.calls.isEmpty())
        assertTrue(out.content, out.content.startsWith("\"Stable Audio\" makes audio and runs on Studio PC. Parameters: prompt (string) default heaven church"))
        assertTrue(out.content, out.content.contains("seconds (float, 1–600) default 47.6"))
    }

    @Test
    fun unknownNamesAndParamsAreExplained() = runTest {
        val tool = RunWorkflowTool(FakeRunner(listOf(summary(sd15), summary(audio))))
        val unknown = tool.execute(args("""{"workflow":"Flux"}"""), Run)
        assertEquals("NOT_FOUND", unknown.code)
        assertEquals("Error: No workflow named \"Flux\". Saved workflows: \"Portrait SD1.5\", \"Stable Audio\"", unknown.content)
        val badParam = tool.execute(args("""{"workflow":"Stable Audio","params":{"fps":24}}"""), Run)
        assertEquals("INVALID_ARGS", badParam.code)
        assertTrue(badParam.content.startsWith("Error: \"Stable Audio\" has no parameter \"fps\""))
        val badType = tool.execute(args("""{"workflow":"Stable Audio","params":{"steps":"lots"}}"""), Run)
        assertEquals("Error: steps expects int, got lots", badType.content)
        val none = RunWorkflowTool(FakeRunner(emptyList())).execute(args("""{"workflow":"x"}"""), Run)
        assertTrue(none.content.contains("no saved Studio workflows"))
    }

    @Test
    fun runnerErrorsBecomeToolErrors() = runTest {
        val runner = FakeRunner(listOf(summary(sd15))).apply { result = { WorkflowRunResult(emptyList(), AssetType.IMAGE, error = "Can't reach Studio PC") } }
        val out = RunWorkflowTool(runner).execute(args("""{"workflow":"Portrait SD1.5","params":{}}"""), Run)
        assertTrue(out.isError)
        assertEquals("Error: Can't reach Studio PC", out.content)
    }

    @Test
    fun queueRunnerRunsTheWorkflowAsAComfyJob() = runTest {
        val store = InMemoryStudioStore()
        store.putWorkflow(audio)
        val engine = FakeEngine(EngineKinds.COMFYUI, backendId = "pc", output = AssetType.AUDIO)
        val sink = MemorySink(store)
        val queue = JobQueue(backgroundScope, EngineRegistry().apply { register(engine) }, sink)
        val runner = QueueStudioRunner(store, queue, { QueueStudioRunner.agentProject(store, now = 1) }, { if (it == "pc") "Studio PC" else null }, Random(3))
        assertEquals(listOf("Stable Audio"), runner.workflows().map { it.name })
        val out = RunWorkflowTool(runner).execute(args("""{"workflow":"Stable Audio","params":{"prompt":"rain","seconds":10}}"""), Run)
        assertFalse(out.content, out.isError)
        val job = engine.ran.single()
        assertEquals("w-audio", job.recipe.workflowId)
        assertEquals(1, job.recipe.workflowVersion)
        assertEquals(AssetType.AUDIO, job.recipe.output)
        assertTrue(job.recipe.seed!! in 0..Seeds.MAX)
        assertEquals(ParamValue.of(job.recipe.seed!!), job.recipe.params["seed"])
        assertEquals(ParamValue.of(10.0), job.recipe.params["seconds"])
        assertEquals("Agent", store.projects().single().name)
        assertTrue(out.content.startsWith("Made 1 audio clip with \"Stable Audio\" on Studio PC (seed "))
        assertNull("audio is not put in the image gallery", out.meta!!["gallery"])
    }

    @Test
    fun queueRunnerRejectsBadValuesBeforeQueueing() = runTest {
        val store = InMemoryStudioStore()
        store.putWorkflow(sd15)
        val engine = FakeEngine(EngineKinds.COMFYUI, backendId = "pc")
        val queue = JobQueue(backgroundScope, EngineRegistry().apply { register(engine) }, MemorySink(store))
        val runner = QueueStudioRunner(store, queue, { "p" })
        val result = runCatching { runner.run("w-sd15", mapOf("sampler" to ParamValue.of("nope")), false) }
        assertTrue(result.exceptionOrNull() is WorkflowException)
        assertTrue(queue.jobs.value.isEmpty())
        val tool = RunWorkflowTool(runner).execute(args("""{"workflow":"Portrait SD1.5","params":{"sampler":"nope"}}"""), Run)
        assertTrue(tool.content, tool.content.startsWith("Error: Sampler \"nope\" isn't available"))
    }

    @Test
    fun valuesFromJsonByType() {
        val p = { type: ParamType -> WorkflowParam("x", "X", type, emptyList(), role = "image") }
        assertEquals(ParamValue.of(3), WorkflowValues.fromJson(p(ParamType.INT), Json.parseToJsonElement("3.0")))
        assertEquals(ParamValue.of(true), WorkflowValues.fromJson(p(ParamType.BOOL), Json.parseToJsonElement("true")))
        assertEquals(ParamValue.AssetValue(AssetRef("a-0123456789ab", "image")), WorkflowValues.fromJson(p(ParamType.IMAGE), Json.parseToJsonElement("\"a-0123456789ab\"")))
        assertEquals(ParamValue.of("server.png"), WorkflowValues.fromJson(p(ParamType.IMAGE), Json.parseToJsonElement("\"server.png\"")))
        assertTrue(runCatching { WorkflowValues.fromJson(p(ParamType.INT), JsonObject(emptyMap())) }.exceptionOrNull() is WorkflowException)
    }
}
