package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.studio.comfy.ComfyBackend
import dev.wckdboy.autobot.shared.studio.comfy.ComfyClient
import dev.wckdboy.autobot.shared.studio.comfy.ComfyEngine
import dev.wckdboy.autobot.shared.studio.comfy.ComfyException
import dev.wckdboy.autobot.shared.studio.comfy.ComfyFile
import dev.wckdboy.autobot.shared.studio.comfy.ComfyRun
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComfyClientTest {
    private val backend = ComfyBackend("pc", "Studio PC", "http://127.0.0.1:8188/", route = "DIRECT", authHeader = "Authorization" to "Bearer t")
    private val graph = Json.parseToJsonElement(fixture("sd15_txt2img_api.json")).jsonObject

    private fun server(): FakeComfy = FakeComfy().apply {
        json["POST /prompt"] = { 200 to fixtureObject("misc_responses.json", "prompt_ok").toString() }
        json["GET /history/PROMPT"] = { 200 to fixture("history_success.json") }
        json["GET /queue"] = { 200 to fixtureObject("misc_responses.json", "queue").toString() }
        json["POST /interrupt"] = { 200 to "" }
        json["POST /queue"] = { 200 to "" }
        files["ComfyUI_00042_.png"] = byteArrayOf(1)
        files["ComfyUI_00043_.png"] = byteArrayOf(2)
    }

    private fun client(server: FakeComfy, sockets: Boolean = true) =
        ComfyClient(backend, server, server, if (sockets) server else null, clientId = "autobot-test", socketOpenTimeoutMs = 1_000)

    private fun preview(): ByteArray = ByteBuffer.allocate(10).putInt(1).putInt(1).put(byteArrayOf(7, 7)).array()

    @Test
    fun runFollowsTheSocketThenReadsHistory() = runTest {
        val server = server()
        server.socketScript = { l ->
            l.onOpen()
            fixture("ws_run.jsonl").lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                l.onText(line)
                if (i == 7) l.onBinary(preview())
            }
        }
        val events = CopyOnWriteArrayList<EngineEvent>()
        val run = client(server).execute(graph) { events += it }
        assertEquals("PROMPT", run.promptId)
        assertEquals(listOf("9", "20", "41", "19"), run.outputs.keys.toList())
        val progress = events.filterIsInstance<EngineEvent.Progress>().map { it.value to it.max }
        assertEquals(listOf(1 to 20, 10 to 20, 20 to 20), progress)
        val statuses = events.filterIsInstance<EngineEvent.Status>().map { it.text }
        assertTrue(statuses.toString(), "Running on Studio PC" in statuses && "KSampler" in statuses && "Save Image" in statuses)
        assertTrue("Reusing 3 cached steps" in statuses)
        assertEquals("image/jpeg", events.filterIsInstance<EngineEvent.Preview>().single().mimeType)

        val socket = server.sockets.single()
        assertEquals("ws://127.0.0.1:8188/ws?clientId=autobot-test", socket.url)
        assertEquals("DIRECT", socket.route)
        assertEquals(ComfyClient.TAG, socket.tag)
        assertEquals("Bearer t", socket.headers["Authorization"])
        val submit = server.requests.first { it.url.endsWith("/prompt") }
        assertEquals("DIRECT", submit.route)
        assertEquals(ComfyClient.TAG, submit.tag)
        val body = Json.parseToJsonElement(submit.body!!).jsonObject
        assertEquals("autobot-test", body["client_id"]!!.jsonPrimitive.content)
        assertEquals(graph, body["prompt"])
        assertFalse("no polling of /queue while the socket reports", server.paths().any { it == "GET /queue" })
    }

    @Test
    fun withoutASocketItPollsHistoryAndReportsTheQueue() = runTest {
        val server = server()
        var polls = 0
        server.json["GET /history/PROMPT"] = { if (++polls < 3) 200 to "{}" else 200 to fixture("history_success.json") }
        server.json["GET /queue"] = {
            200 to """{"queue_running":[[1,"other",{},{},[]]],"queue_pending":[[2,"PROMPT",{},{},[]]]}"""
        }
        val events = CopyOnWriteArrayList<EngineEvent>()
        val result = async { client(server).execute(graph) { events += it } }
        runCurrent()
        advanceTimeBy(5_000)
        val run = result.await()
        assertEquals(3, polls)
        assertEquals(4, run.outputs.size)
        assertEquals("one status per change", listOf("Waiting in Studio PC's queue (1 ahead)"), events.filterIsInstance<EngineEvent.Status>().map { it.text })
        assertTrue("the failed socket was tried first", server.sockets.size == 1)
    }

    @Test
    fun aSocketThatDropsMidRunFallsBackToPolling() = runTest {
        val server = server()
        server.socketScript = { l ->
            l.onOpen()
            l.onText("""{"type":"execution_start","data":{"prompt_id":"PROMPT"}}""")
            l.onFailure(false, "connection reset")
        }
        val run = client(server).execute(graph)
        assertEquals(4, run.outputs.size)
        assertTrue(server.paths().contains("GET /history/PROMPT"))
    }

    @Test
    fun executionErrorsAndRefusalsAreExplained() = runTest {
        val server = server()
        server.json["GET /history/PROMPT"] = { 200 to fixture("history_error.json") }
        try {
            client(server, sockets = false).execute(graph)
            fail()
        } catch (e: ComfyException) {
            assertTrue(e.message!!, e.message!!.startsWith("KSampler: Allocation on device"))
        }
        server.json["POST /prompt"] = { 400 to fixtureObject("misc_responses.json", "prompt_refused").toString() }
        try {
            client(server, sockets = false).execute(graph)
            fail()
        } catch (e: ComfyException) {
            assertTrue(e.message!!, e.message!!.startsWith("Studio PC refused the workflow: Prompt outputs failed validation"))
        }
        val socketError = server()
        socketError.socketScript = { l ->
            l.onOpen()
            l.onText("""{"type":"execution_error","data":{"prompt_id":"PROMPT","node_id":"3","node_type":"KSampler","exception_message":"CUDA error","exception_type":"RuntimeError"}}""")
        }
        try {
            client(socketError).execute(graph)
            fail()
        } catch (e: ComfyException) {
            assertEquals("KSampler: CUDA error", e.message)
        }
    }

    @Test
    fun blockedRequestsSaySo() = runTest {
        val server = server().apply { blocked = true }
        try {
            client(server, sockets = false).systemStats()
            fail()
        } catch (e: ComfyException) {
            assertTrue(e.blocked)
            assertTrue(e.message!!.startsWith("Blocked by network policy"))
        }
    }

    @Test
    fun cancellingDequeuesAndInterrupts() = runTest {
        val server = server()
        server.json["GET /history/PROMPT"] = { 200 to "{}" }
        val run = async { client(server, sockets = false).execute(graph) }
        runCurrent()
        advanceTimeBy(2_500)
        run.cancel()
        runCurrent()
        val tail = server.requests.filter { it.method == "POST" }.map { it.url.substringAfter("8188") to it.body }
        assertTrue(tail.toString(), tail.contains("/queue" to """{"delete":["PROMPT"]}"""))
        assertTrue(tail.toString(), tail.contains("/interrupt" to """{"prompt_id":"PROMPT"}"""))
    }

    @Test
    fun restEndpoints() = runTest {
        val server = server()
        server.json["GET /object_info"] = { 200 to fixture("object_info.json") }
        server.json["GET /system_stats"] = { 200 to fixtureObject("misc_responses.json", "system_stats").toString() }
        val c = client(server, sockets = false)
        assertTrue(c.objectInfo()["KSampler"] != null)
        assertEquals("0.3.66", c.systemStats().comfyVersion)
        assertEquals(listOf("pending-a", "pending-b"), c.queue().pending)
        assertArrayEquals(byteArrayOf(1), c.view(ComfyFile("ComfyUI_00042_.png")))
        val uploaded = c.upload("in.png", "image/png", byteArrayOf(5))
        assertEquals(ComfyFile("input.png", "autobot", "input"), uploaded)
        val upload = server.requests.last()
        assertTrue(upload.headers["Content-Type"]!!.startsWith("multipart/form-data; boundary=autobot-"))
        assertTrue(server.bodies.last()!!.size > 100)
        try {
            c.view(ComfyFile("missing.png"))
            fail()
        } catch (e: ComfyException) {
            assertEquals("Studio PC answered HTTP 404 for missing.png", e.message)
        }
    }

    @Test
    fun engineUploadsInputsAppliesValuesAndDownloadsSavedOutputs() = runTest {
        val server = server()
        server.files["ComfyUI_00001_.webp"] = byteArrayOf(3)
        server.files["ComfyUI_00001_.flac"] = byteArrayOf(4)
        val workflow = WorkflowImport.create("Img2img", fixture("sd15_txt2img_api.json"), id = "w-1").let { w ->
            // Add a LoadImage node and expose it, like an img2img graph would.
            val g = w.graph + ("10" to Json.parseToJsonElement("""{"class_type":"LoadImage","inputs":{"image":"example.png"}}"""))
            WorkflowImport.create("Img2img", kotlinx.serialization.json.JsonObject(g).toString(), id = "w-1")
        }
        val engine = ComfyEngine(
            backend,
            client(server, sockets = false),
            workflows = { if (it == "w-1") workflow else null },
            assets = { AssetBytes("photo.png", "image/png", byteArrayOf(9, 9)) },
        )
        val job = StudioJob(
            "j-1", "p-1",
            Recipe(EngineKinds.COMFYUI, "pc", params = mapOf("prompt" to ParamValue.of("a fox"), "steps" to ParamValue.of(12), "bogus" to ParamValue.of(1)), inputs = listOf(AssetRef("a-1")), workflowId = "w-1", seed = 77),
            createdAt = 0,
        )
        val outputs = engine.run(job) { }
        assertEquals(listOf(byteArrayOf(1).toList(), byteArrayOf(2).toList()), outputs.map { it.bytes.toList() })
        assertEquals("image/png", outputs.first().mimeType)
        val prompt = Json.parseToJsonElement(server.requests.first { it.url.endsWith("/prompt") }.body!!).jsonObject["prompt"]!!.jsonObject
        fun input(node: String, name: String) = prompt[node]!!.jsonObject["inputs"]!!.jsonObject[name]!!.jsonPrimitive
        assertEquals("a fox", input("6", "text").content)
        assertEquals(12L, input("3", "steps").long)
        assertEquals(77L, input("3", "seed").long)
        assertEquals("autobot/input.png", input("10", "image").content)
        assertTrue(server.paths().any { it.startsWith("POST /upload/image") })
    }

    @Test
    fun engineFailsClearlyWithoutWorkflow() = runTest {
        val engine = ComfyEngine(backend, client(server(), sockets = false), workflows = { null }, assets = { null })
        try {
            engine.run(StudioJob("j", "p", Recipe(EngineKinds.COMFYUI, "pc", workflowId = "w-x"), createdAt = 0)) {}
            fail()
        } catch (e: StudioEngineException) {
            assertEquals("The workflow is no longer saved", e.message)
        }
        assertEquals("pc", engine.capabilities.backendId)
        assertEquals("backend:pc", engine.capabilities.slot)
    }

    @Test
    fun outputSelectionPrefersSavedFilesAndMarksAnimation() {
        val run = ComfyRun(
            "P",
            linkedMapOf(
                "20" to listOf(ComfyFile("t.png", "", "temp")),
                "9" to listOf(ComfyFile("a.png"), ComfyFile("b.png")),
            ),
            emptySet(),
        )
        assertEquals(listOf("a.png", "b.png"), ComfyEngine.select(run, listOf("9", "20")).map { it.second.filename })
        val previewOnly = ComfyRun("P", linkedMapOf("20" to listOf(ComfyFile("t.png", "", "temp"))), emptySet())
        assertEquals(listOf("t.png"), ComfyEngine.select(previewOnly, listOf("20")).map { it.second.filename })
        assertEquals("unknown output nodes fall back to everything", listOf("t.png"), ComfyEngine.select(previewOnly, listOf("99")).map { it.second.filename })
    }
}
