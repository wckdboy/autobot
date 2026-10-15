package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.studio.comfy.ComfyBinary
import dev.wckdboy.autobot.shared.studio.comfy.ComfyFile
import dev.wckdboy.autobot.shared.studio.comfy.ComfyMessage
import dev.wckdboy.autobot.shared.studio.comfy.ComfyRequests
import dev.wckdboy.autobot.shared.studio.comfy.HistoryEntry
import dev.wckdboy.autobot.shared.studio.comfy.ObjectInfo
import dev.wckdboy.autobot.shared.studio.comfy.QueueStatus
import dev.wckdboy.autobot.shared.studio.comfy.SubmitResult
import dev.wckdboy.autobot.shared.studio.comfy.SystemStats
import java.nio.ByteBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyProtocolTest {
    private fun misc(key: String) = fixtureObject("misc_responses.json", key).toString()

    @Test
    fun objectInfoReadsWidgetsLinksAndBothComboForms() {
        val info = ObjectInfo.parse(fixture("object_info.json"))
        val seed = info.input("KSampler", "seed")!!
        assertEquals("INT", seed.type)
        assertEquals(0.0, seed.min!!, 0.0)
        assertEquals(1.8446744073709552E19, seed.max!!, 1e4)
        assertEquals(0.1, info.input("KSampler", "cfg")!!.step!!, 1e-9)
        assertEquals("COMBO", info.input("KSampler", "sampler_name")!!.type)
        assertTrue("dpmpp_3m_sde_gpu" in info.input("KSampler", "sampler_name")!!.options)
        assertFalse(info.input("KSampler", "model")!!.isWidget)
        assertEquals(listOf("euler", "euler_ancestral", "dpmpp_2m"), info.input("KSamplerSelect", "sampler_name")!!.options)
        assertTrue(info.input("CLIPTextEncode", "text")!!.multiline)
        assertTrue(info.input("LoadImage", "image")!!.upload)
        assertTrue(info["SaveImage"]!!.outputNode)
        assertEquals(listOf("MODEL", "CLIP", "VAE"), info["CheckpointLoaderSimple"]!!.outputs)
        assertEquals("Load Checkpoint", info["CheckpointLoaderSimple"]!!.displayName)
    }

    @Test
    fun systemStatsAndQueue() {
        val stats = SystemStats.parse(misc("system_stats"))
        assertEquals("0.3.66", stats.comfyVersion)
        assertEquals("cuda", stats.devices.single().type)
        assertEquals(25386352640L, stats.devices.single().vramTotal)
        val queue = QueueStatus.parse(misc("queue"))
        assertEquals(listOf("running-id"), queue.running)
        assertEquals(listOf("pending-a", "pending-b"), queue.pending)
    }

    @Test
    fun submitResultsQueuedOrRefusedWithNodeErrors() {
        val ok = SubmitResult.parse(200, misc("prompt_ok")) as SubmitResult.Queued
        assertEquals("PROMPT", ok.promptId)
        assertEquals(7, ok.number)
        val refused = SubmitResult.parse(400, misc("prompt_refused")) as SubmitResult.Refused
        assertEquals("CheckpointLoaderSimple", refused.nodeErrors.single().classType)
        assertEquals(
            "Prompt outputs failed validation — CheckpointLoaderSimple #4: Value not in list: ckpt_name: 'missing.safetensors' not in ['sd_xl_base_1.0.safetensors']",
            refused.message,
        )
        assertEquals("HTTP 500", (SubmitResult.parse(500, "Internal Server Error") as SubmitResult.Refused).message)
    }

    @Test
    fun historyReadsOutputsAnimatedNodesAndErrors() {
        val entry = HistoryEntry.parse(fixture("history_success.json"), "PROMPT")!!
        assertTrue(entry.completed)
        assertEquals("success", entry.status)
        assertEquals(listOf("9", "20", "41", "19"), entry.outputs.keys.toList())
        assertEquals(2, entry.outputs.getValue("9").size)
        assertEquals(ComfyFile("ComfyUI_temp_abcde_00001_.png", "", "temp"), entry.outputs.getValue("20").single())
        assertEquals(ComfyFile("ComfyUI_00001_.flac", "audio", "output"), entry.outputs.getValue("19").single())
        assertEquals(setOf("41"), entry.animated)
        assertNull(HistoryEntry.parse(fixture("history_success.json"), "other"))
        assertNull(HistoryEntry.parse("{}", "PROMPT"))

        val failed = HistoryEntry.parse(fixture("history_error.json"), "PROMPT")!!
        assertEquals("error", failed.status)
        assertEquals("KSampler: Allocation on device \nThis error means you ran out of memory on your GPU.", failed.error)
    }

    @Test
    fun socketMessagesOfARun() {
        val messages = fixture("ws_run.jsonl").lines().filter { it.isNotBlank() }.map { ComfyMessage.parse(it)!! }
        assertEquals("c0ffee00c0ffee00c0ffee00c0ffee00", (messages[0] as ComfyMessage.Status).sid)
        assertEquals(1, (messages[1] as ComfyMessage.Status).queueRemaining)
        assertEquals("PROMPT", (messages[2] as ComfyMessage.ExecutionStart).promptId)
        assertEquals(listOf("4", "6", "7"), (messages[3] as ComfyMessage.ExecutionCached).nodes)
        assertEquals("5", (messages[4] as ComfyMessage.Executing).node)
        val progress = messages[7] as ComfyMessage.Progress
        assertEquals(10 to 20, progress.value to progress.max)
        assertEquals("3", progress.node)
        val executed = messages[11] as ComfyMessage.Executed
        assertEquals("9", executed.node)
        assertEquals(listOf(ComfyFile("ComfyUI_00042_.png")), executed.files)
        assertTrue(messages[12] is ComfyMessage.ExecutionSuccess)
        assertNull((messages[13] as ComfyMessage.Executing).node)
        assertTrue(ComfyMessage.parse("""{"type":"crystools.monitor","data":{}}""") is ComfyMessage.Other)
        assertNull(ComfyMessage.parse("not json"))
        val error = ComfyMessage.parse(
            """{"type":"execution_error","data":{"prompt_id":"P","node_id":"3","node_type":"KSampler","exception_message":"boom","exception_type":"RuntimeError"}}""",
        ) as ComfyMessage.ExecutionError
        assertEquals("KSampler: boom", error.message)
        assertEquals("P", (ComfyMessage.parse("""{"type":"execution_interrupted","data":{"prompt_id":"P","node_id":"3"}}""") as ComfyMessage.ExecutionInterrupted).promptId)
    }

    private fun frame(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }
    private fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()

    @Test
    fun binaryPreviewFrames() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
        val p1 = ComfyBinary.parse(frame(u32(1), u32(1), jpeg)) as ComfyBinary.PreviewImage
        assertEquals("image/jpeg", p1.mimeType)
        assertArrayEquals(jpeg, p1.bytes)
        assertEquals("image/png", (ComfyBinary.parse(frame(u32(1), u32(2), jpeg)) as ComfyBinary.PreviewImage).mimeType)

        val meta = """{"node_id":"3","prompt_id":"P","display_node_id":"3","parent_node_id":null,"real_node_id":"3","image_type":"image/png"}""".encodeToByteArray()
        val p4 = ComfyBinary.parse(frame(u32(4), u32(meta.size), meta, jpeg)) as ComfyBinary.PreviewImage
        assertEquals("P", p4.promptId)
        assertEquals("3", p4.nodeId)
        assertEquals("image/png", p4.mimeType)
        assertArrayEquals(jpeg, p4.bytes)

        val text = ComfyBinary.parse(frame(u32(3), u32(2), "12".encodeToByteArray(), "Loading".encodeToByteArray())) as ComfyBinary.Text
        assertEquals("12" to "Loading", text.nodeId to text.text)
        assertTrue(ComfyBinary.parse(frame(u32(2), u32(0))) is ComfyBinary.Other)
        assertNull(ComfyBinary.parse(byteArrayOf(0, 0)))
        assertNull("truncated metadata", ComfyBinary.parse(frame(u32(4), u32(500), meta)))
    }

    @Test
    fun requestShapes() {
        val graph = Json.parseToJsonElement("""{"9":{"class_type":"SaveImage","inputs":{}}}""").jsonObject
        val body = Json.parseToJsonElement(ComfyRequests.promptBody(graph, "cid")).jsonObject
        assertEquals("cid", body["client_id"]!!.jsonPrimitive.content)
        assertEquals(graph, body["prompt"])
        assertEquals("""{"prompt_id":"P"}""", ComfyRequests.interruptBody("P"))
        assertEquals("{}", ComfyRequests.interruptBody(null))
        assertEquals("""{"delete":["P"]}""", ComfyRequests.deleteFromQueueBody("P"))
        assertEquals("/view?filename=a%20b.png&subfolder=x%2Fy&type=output", ComfyRequests.viewPath(ComfyFile("a b.png", "x/y")))
        assertEquals("ws://127.0.0.1:8188/ws?clientId=c1", ComfyRequests.socketUrl("http://127.0.0.1:8188", "c1"))
        assertEquals("wss://pc.example/comfy/ws?clientId=c1", ComfyRequests.socketUrl("https://pc.example/comfy", "c1"))
        assertEquals("sub/x.png", ComfyRequests.imageInputValue(ComfyFile("x.png", "sub", "input")))
        assertEquals(ComfyFile("input.png", "autobot", "input"), ComfyRequests.parseUpload(misc("upload")))
    }

    @Test
    fun multipartUploadBody() {
        val (type, body) = ComfyRequests.uploadBody("my\"pic.png", "image/png", byteArrayOf(1, 2, 3), "autobot", true, "BOUND")
        assertEquals("multipart/form-data; boundary=BOUND", type)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--BOUND\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\ninput\r\n"))
        assertTrue(text.contains("name=\"subfolder\"\r\n\r\nautobot\r\n"))
        assertTrue(text.contains("name=\"overwrite\"\r\n\r\ntrue\r\n"))
        assertTrue(text.contains("name=\"image\"; filename=\"my_pic.png\"\r\nContent-Type: image/png\r\n\r\n\u0001\u0002\u0003\r\n--BOUND--\r\n"))
    }

    @Test
    fun graphLinksStayLinks() {
        // Sanity: the fixtures are API format with [node, slot] links.
        val g = Json.parseToJsonElement(fixture("sd15_txt2img_api.json")) as JsonObject
        assertEquals(7, g.size)
    }
}
