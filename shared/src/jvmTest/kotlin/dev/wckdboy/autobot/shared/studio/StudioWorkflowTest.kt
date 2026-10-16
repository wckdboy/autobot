package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.studio.comfy.ComfyBackend
import dev.wckdboy.autobot.shared.studio.comfy.ComfyClient
import dev.wckdboy.autobot.shared.studio.comfy.ComfyEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The Studio side of agent workflows: queue order, cancellation and a misbehaving ComfyUI. */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioWorkflowTest {
    private val backend = ComfyBackend("pc", "Studio PC", "http://127.0.0.1:8188/")
    private val sd15 = WorkflowImport.create("Portrait", fixture("sd15_txt2img_api.json"), id = "w-sd15", backendId = "pc")

    private fun server(): FakeComfy = FakeComfy().apply {
        json["POST /prompt"] = { 200 to fixtureObject("misc_responses.json", "prompt_ok").toString() }
        json["GET /history/PROMPT"] = { 200 to fixture("history_success.json") }
        json["GET /queue"] = { 200 to fixtureObject("misc_responses.json", "queue").toString() }
        json["POST /interrupt"] = { 200 to "" }
        json["POST /queue"] = { 200 to "" }
        files["ComfyUI_00042_.png"] = byteArrayOf(1)
        files["ComfyUI_00043_.png"] = byteArrayOf(2)
    }

    private val scopes = java.util.concurrent.CopyOnWriteArrayList<kotlinx.coroutines.Job>()

    /** A scope for the queue on the test scheduler, cancelled when the test ends. */
    private fun TestScope.queueScope(): kotlinx.coroutines.CoroutineScope {
        val job = kotlinx.coroutines.Job().also { scopes += it }
        return kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + job)
    }

    /**
     * [runTest] that cancels the queues' scopes before it drains the scheduler: a job that polls
     * forever (the bug these tests guard against) would otherwise keep the drain spinning.
     */
    private fun studioTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 10.seconds) {
        try {
            body()
        } finally {
            scopes.forEach { it.cancel() }
        }
    }

    /**
     * Runs 30 virtual minutes, not "until idle": a job that polls forever (the bug these tests
     * guard against) would otherwise spin virtual time without end and hang the build.
     */
    private fun TestScope.settle() {
        advanceTimeBy(30 * 60_000)
        runCurrent()
    }

    private class Comfy(val queue: JobQueue, val sink: MemorySink)

    /** A queue with one ComfyUI engine over [http] (JSON), [server] (files) and optional sockets. */
    private fun TestScope.comfy(server: FakeComfy, http: HttpTransport = server, sockets: Boolean = false): Comfy {
        val client = ComfyClient(backend, http, server, if (sockets) server else null, clientId = "t", socketOpenTimeoutMs = 1_000)
        val engine = ComfyEngine(backend, client, workflows = { if (it == "w-sd15") sd15 else null }, assets = { null })
        val sink = MemorySink()
        var n = 0
        return Comfy(JobQueue(queueScope(), EngineRegistry().apply { register(engine) }, sink, clock = { currentTime }, ids = { "j-${++n}" }), sink)
    }

    private fun recipe() = Recipe(EngineKinds.COMFYUI, "pc", params = mapOf("prompt" to ParamValue.of("a fox")), workflowId = "w-sd15", seed = 1)

    // -----------------------------------------------------------------------------------------
    // Queue order and cancellation

    @Test
    fun jobsStartInSubmissionOrderPerSlotAndSlotsDoNotWaitOnEachOther() = studioTest {
        val gate = CompletableDeferred<Unit>()
        val local = FakeEngine(gate = gate)
        val pc = FakeEngine(EngineKinds.COMFYUI, backendId = "pc")
        var n = 0
        val queue = JobQueue(queueScope(), EngineRegistry().apply { register(local); register(pc) }, MemorySink(), slotLimits = mapOf("backend:pc" to 2), clock = { 0 }, ids = { "j-${++n}" })
        val l1 = queue.submit("p", Recipe(EngineKinds.DIFFUSION))
        val l2 = queue.submit("p", Recipe(EngineKinds.DIFFUSION))
        val r = (1..3).map { queue.submit("p", Recipe(EngineKinds.COMFYUI, "pc")) }
        val l3 = queue.submit("p", Recipe(EngineKinds.DIFFUSION))
        runCurrent()
        assertEquals(JobState.RUNNING, queue.job(l1.id)!!.state)
        assertEquals(JobState.QUEUED, queue.job(l2.id)!!.state)
        assertTrue("the remote slot ran while the local one was busy", r.all { queue.job(it.id)!!.state == JobState.DONE })
        gate.complete(Unit)
        settle()
        assertEquals(listOf(l1.id, l2.id, l3.id), local.ran.map { it.id })
        assertEquals(r.map { it.id }, pc.ran.map { it.id })
        assertTrue(queue.jobs.value.all { it.state == JobState.DONE })
    }

    @Test
    fun cancellingAQueuedJobLeavesTheRunningOneAndTheRestInOrder() = studioTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        var n = 0
        val queue = JobQueue(queueScope(), EngineRegistry().apply { register(engine) }, MemorySink(), clock = { 0 }, ids = { "j-${++n}" })
        val (a, b, c) = (1..3).map { queue.submit("p", Recipe(EngineKinds.DIFFUSION)) }
        runCurrent()
        assertTrue(queue.cancel(b.id))
        assertFalse("twice", queue.cancel(b.id))
        assertEquals(JobState.RUNNING, queue.job(a.id)!!.state)
        gate.complete(Unit)
        settle()
        assertEquals(listOf(a.id, c.id), engine.ran.map { it.id })
        assertEquals(JobState.CANCELLED, queue.await(b.id)!!.state)
        assertFalse("unknown", queue.cancel("nope"))
    }

    @Test
    fun cancelAllStopsRunningAndQueuedJobs() = studioTest {
        val engine = FakeEngine(gate = CompletableDeferred())
        val queue = JobQueue(queueScope(), EngineRegistry().apply { register(engine) }, MemorySink(), clock = { 0 })
        repeat(3) { queue.submit("p", Recipe(EngineKinds.DIFFUSION)) }
        runCurrent()
        queue.cancelAll()
        runCurrent()
        assertTrue(queue.jobs.value.all { it.state == JobState.CANCELLED })
        assertEquals(1, engine.ran.size)
        assertEquals(0, engine.running)
    }

    @Test
    fun anEnginesOwnTimeoutIsAFailureNotAUserCancel() = studioTest {
        val engine = object : StudioEngine {
            override val capabilities = EngineCapabilities(EngineKinds.DIFFUSION, null, "phone", setOf(AssetType.IMAGE), onDevice = true)
            override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> =
                withTimeout(1_000) {
                    delay(5_000)
                    emptyList()
                }
        }
        val queue = JobQueue(queueScope(), EngineRegistry().apply { register(engine) }, MemorySink(), clock = { 0 })
        val job = queue.submit("p", Recipe(EngineKinds.DIFFUSION))
        val next = queue.submit("p", Recipe(EngineKinds.DIFFUSION))
        advanceTimeBy(1_500)
        runCurrent()
        val done = queue.job(job.id)!!
        assertEquals(JobState.FAILED, done.state)
        assertTrue(done.error!!, done.error!!.contains("Timed out"))
        assertEquals("the slot moved on", JobState.RUNNING, queue.job(next.id)!!.state)
    }

    // -----------------------------------------------------------------------------------------
    // ComfyUI misbehaving

    @Test
    fun aComfySocketThatDropsMidJobFallsBackToPollingAndTheJobFinishes() = studioTest {
        val server = server()
        server.socketScript = { l ->
            l.onOpen()
            l.onText("""{"type":"execution_start","data":{"prompt_id":"PROMPT"}}""")
            l.onText("""{"type":"progress","data":{"value":3,"max":20,"prompt_id":"PROMPT"}}""")
            l.onFailure(false, "connection reset by peer")
        }
        val c = comfy(server, sockets = true)
        val job = c.queue.submit("p", recipe())
        settle()
        val done = c.queue.job(job.id)!!
        assertEquals(done.error, JobState.DONE, done.state)
        assertEquals(2, done.outputs.size)
        assertTrue(server.paths().contains("GET /history/PROMPT"))
    }

    @Test
    fun aSilentButOpenSocketDoesNotHangTheJob() = studioTest {
        val server = server()
        // Opens, says the prompt started, then never says anything again (a proxy eating frames).
        server.socketScript = { l ->
            l.onOpen()
            l.onText("""{"type":"execution_start","data":{"prompt_id":"PROMPT"}}""")
        }
        val c = comfy(server, sockets = true)
        val job = c.queue.submit("p", recipe())
        settle()
        assertEquals(JobState.DONE, c.queue.job(job.id)!!.state)
        assertTrue("it checked the queue after the silence", server.paths().contains("GET /queue"))
    }

    @Test
    fun aServerThatNeverAnswersFailsTheJobInsteadOfHanging() = studioTest {
        val server = server()
        val cancelled = AtomicInteger()
        val silent = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable = Cancellable { cancelled.incrementAndGet() }
        }
        val c = comfy(server, http = silent)
        val job = c.queue.submit("p", recipe())
        val other = c.queue.submit("p", recipe())
        advanceTimeBy(61_000)
        runCurrent()
        val done = c.queue.job(job.id)!!
        assertEquals(JobState.FAILED, done.state)
        assertTrue(done.error!!, done.error!!.contains("didn't answer /prompt"))
        assertTrue("the hung request was dropped", cancelled.get() >= 1)
        assertEquals("the next job got the slot", JobState.RUNNING, c.queue.job(other.id)!!.state)
    }

    @Test
    fun aJobTheServerLostFailsAfterAFewPolls() = studioTest {
        val server = server()
        server.json["GET /history/PROMPT"] = { 200 to "{}" }
        server.json["GET /queue"] = { 200 to """{"queue_running":[],"queue_pending":[]}""" }
        val c = comfy(server)
        val job = c.queue.submit("p", recipe())
        settle()
        val done = c.queue.job(job.id)!!
        assertEquals(JobState.FAILED, done.state)
        assertTrue(done.error!!, done.error!!.contains("no longer has this job"))
        assertTrue("failed within seconds", done.finishedAt!! < 10_000)
    }

    @Test
    fun aJobStillQueuedOnTheServerIsWaitedFor() = studioTest {
        val server = server()
        var polls = 0
        server.json["GET /history/PROMPT"] = { if (++polls < 20) 200 to "{}" else 200 to fixture("history_success.json") }
        server.json["GET /queue"] = { 200 to """{"queue_running":[[1,"other",{},{},[]]],"queue_pending":[[2,"PROMPT",{},{},[]]]}""" }
        val c = comfy(server)
        val job = c.queue.submit("p", recipe())
        settle()
        assertEquals(JobState.DONE, c.queue.job(job.id)!!.state)
        assertEquals(20, polls)
    }

    @Test
    fun cancellingARunningComfyJobInterruptsAndDequeuesOnTheServer() = studioTest {
        val server = server()
        server.json["GET /history/PROMPT"] = { 200 to "{}" }
        server.json["GET /queue"] = { 200 to """{"queue_running":[[1,"PROMPT",{},{},[]]],"queue_pending":[]}""" }
        val c = comfy(server)
        val job = c.queue.submit("p", recipe())
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(JobState.RUNNING, c.queue.job(job.id)!!.state)
        c.queue.cancel(job.id)
        settle()
        assertEquals(JobState.CANCELLED, c.queue.job(job.id)!!.state)
        assertTrue(server.paths().contains("POST /interrupt"))
        assertTrue(server.paths().contains("POST /queue"))
    }

    // -----------------------------------------------------------------------------------------
    // run_workflow and the queue

    @Test
    fun aCancelledAgentCallCancelsTheJobItQueued() = studioTest {
        val store = InMemoryStudioStore()
        store.putWorkflow(sd15)
        val engine = FakeEngine(EngineKinds.COMFYUI, backendId = "pc", gate = CompletableDeferred())
        val queue = JobQueue(queueScope(), EngineRegistry().apply { register(engine) }, MemorySink(store), clock = { 0 })
        val runner = QueueStudioRunner(store, queue, { "p" })
        val call = launch { runner.run("w-sd15", mapOf("prompt" to ParamValue.of("x")), randomizeSeed = true) }
        runCurrent()
        val job = queue.jobs.value.single()
        assertEquals(JobState.RUNNING, job.state)
        call.cancel()
        runCurrent()
        assertEquals(JobState.CANCELLED, queue.job(job.id)!!.state)
        assertEquals(0, engine.running)
    }

    @Test
    fun runWorkflowReachesTheNetworkOnlyForRemoteWorkflows() = studioTest {
        val remote = WorkflowSummary("w1", "Remote", "", AssetType.IMAGE, emptyList(), "Studio PC")
        val local = WorkflowSummary("w2", "Local", "", AssetType.IMAGE, emptyList(), null)
        val runner = object : StudioRunner {
            override suspend fun workflows() = listOf(remote, local)
            override suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean) = error("unused")
        }
        val tool = RunWorkflowTool(runner)
        fun args(name: String) = kotlinx.serialization.json.buildJsonObject { put("workflow", kotlinx.serialization.json.JsonPrimitive(name)) }
        assertTrue(tool.reachesNetwork(args("Remote")))
        assertTrue("case-insensitive like the tool itself", tool.reachesNetwork(args("remote")))
        assertFalse(tool.reachesNetwork(args("Local")))
        assertFalse("an unknown name fails before anything is sent", tool.reachesNetwork(args("nope")))
        val broken = RunWorkflowTool(object : StudioRunner {
            override suspend fun workflows(): List<WorkflowSummary> = throw IllegalStateException("db locked")
            override suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean) = error("unused")
        })
        assertTrue("unknown: assume it does", broken.reachesNetwork(args("Remote")))
    }

    // -----------------------------------------------------------------------------------------
    // Workflow input detection on odd graphs

    private fun detect(json: String) = WorkflowDetector.detect(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)

    @Test
    fun emptyAndJunkGraphsDetectNothingWithoutCrashing() {
        for (g in listOf(
            "{}",
            """{"1": 5, "2": "x", "3": null, "4": []}""",
            """{"1": {"inputs": {"seed": 3}}}""",
            """{"1": {"class_type": null, "inputs": null}}""",
            """{"1": {"class_type": "KSampler", "inputs": {"positive": ["9", 0], "negative": ["9", "zero"], "seed": null}}}""",
        )) {
            val d = detect(g)
            assertTrue(g, d.params.none { it.role == "prompt" })
            assertEquals(g, AssetType.IMAGE, d.output)
        }
    }

    @Test
    fun cyclicConditioningIsCutOffAndFallsBackToTheFirstTextEncoder() {
        val d = detect(
            """{
              "1": {"class_type": "KSampler", "inputs": {"positive": ["2", 0], "negative": ["3", 0], "seed": 5, "steps": 20}},
              "2": {"class_type": "ConditioningCombine", "inputs": {"conditioning_1": ["3", 0]}},
              "3": {"class_type": "ConditioningCombine", "inputs": {"conditioning_1": ["2", 0]}},
              "4": {"class_type": "CLIPTextEncode", "inputs": {"text": "a cat"}}
            }""",
        )
        assertEquals(listOf(InputTarget("4", "text")), d.params.single { it.role == "prompt" }.targets)
        assertTrue(d.params.none { it.role == "negative" })
        assertNotNull(d.params.single { it.role == "seed" })
    }

    @Test
    fun aNullTitleIsNotShownAsTheWordNull() {
        val d = detect("""{"1": {"class_type": "LoadImage", "inputs": {"image": "in.png"}, "_meta": {"title": null}}}""")
        assertEquals("Input image", d.params.single().label)
    }

    @Test
    fun promptFedByAPrimitiveStringNodeAndAStringSeedAreDetected() {
        val d = detect(
            """{
              "1": {"class_type": "KSampler", "inputs": {"positive": ["2", 0], "negative": ["2", 0], "seed": "123", "steps": 30}},
              "2": {"class_type": "CLIPTextEncode", "inputs": {"text": ["5", 0]}},
              "5": {"class_type": "PrimitiveString", "inputs": {"value": "a lighthouse"}}
            }""",
        )
        assertEquals(listOf(InputTarget("5", "value")), d.params.single { it.role == "prompt" }.targets)
        assertTrue("the same encoder is not also the negative", d.params.none { it.role == "negative" })
        assertEquals(ParamValue.IntValue(123), d.params.single { it.role == "seed" }.default)
    }

    @Test
    fun randomGraphsNeverCrashTheDetectorAndParamsAreWellFormed() {
        val classes = listOf(
            "KSampler", "KSamplerAdvanced", "CLIPTextEncode", "CLIPTextEncodeSDXL", "LoadImage", "LoadAudio", "CheckpointLoaderSimple",
            "LoraLoader", "EmptyLatentImage", "SaveImage", "SaveAudio", "VHS_VideoCombine", "PrimitiveString", "ConditioningCombine",
            "RandomNoise", "BasicScheduler", "ConditioningStableAudio", "Unknown", "",
        )
        val inputs = listOf(
            "positive", "negative", "conditioning", "conditioning_1", "text", "text_g", "seed", "noise_seed", "steps", "cfg", "width",
            "height", "batch_size", "image", "audio", "ckpt_name", "lora_name", "strength_model", "fps", "frame_rate", "seconds_total",
            "value", "denoise", "sampler_name", "scheduler", "length",
        )
        val random = kotlin.random.Random(20261016)
        repeat(500) { round ->
            val n = random.nextInt(0, 12)
            val ids = (1..n).map { if (random.nextInt(10) == 0) "$it:${random.nextInt(3)}" else "$it" }
            val graph = kotlinx.serialization.json.buildJsonObject {
                for (id in ids) {
                    put(id, kotlinx.serialization.json.buildJsonObject {
                        if (random.nextInt(12) != 0) put("class_type", kotlinx.serialization.json.JsonPrimitive(classes.random(random)))
                        put("inputs", kotlinx.serialization.json.buildJsonObject {
                            repeat(random.nextInt(0, 6)) {
                                val value: kotlinx.serialization.json.JsonElement = when (random.nextInt(6)) {
                                    0 -> kotlinx.serialization.json.JsonPrimitive(random.nextLong(-5, 1L shl 40))
                                    1 -> kotlinx.serialization.json.JsonPrimitive("text ${random.nextInt(100)}")
                                    2 -> kotlinx.serialization.json.JsonPrimitive(random.nextDouble())
                                    3 -> kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive((ids.randomOrNull(random) ?: "99")), kotlinx.serialization.json.JsonPrimitive(random.nextInt(3))))
                                    4 -> kotlinx.serialization.json.JsonNull
                                    else -> kotlinx.serialization.json.JsonPrimitive(random.nextBoolean())
                                }
                                put(inputs.random(random), value)
                            }
                        })
                    })
                }
            }
            val d = try {
                WorkflowDetector.detect(graph)
            } catch (t: Throwable) {
                throw AssertionError("round $round crashed on $graph", t)
            }
            assertEquals("unique names in round $round", d.params.size, d.params.map { it.name }.toSet().size)
            for (p in d.params) {
                assertTrue("round $round: ${p.name} has targets", p.targets.isNotEmpty())
                assertTrue("round $round: ${p.name} targets existing nodes", p.targets.all { it.nodeId in graph })
            }
            val all = d.params.flatMap { it.targets }
            assertEquals("round $round: no input is set by two params", all.size, all.toSet().size)
            assertTrue(d.outputNodes.all { it in graph })
        }
    }
}
