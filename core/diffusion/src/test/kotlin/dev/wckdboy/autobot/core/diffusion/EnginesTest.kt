package dev.wckdboy.autobot.core.diffusion

import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.SdApiEngine
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkPolicy
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnginesTest {

    private lateinit var server: MockWebServer
    private lateinit var clients: HttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        clients = HttpClientFactory(NetworkPolicy(), AuditLog(capacity = 50, clock = { 0L })) // Offline; loopback allowed
    }

    @After
    fun tearDown() = server.close()

    private fun url() = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private val png1 = byteArrayOf(1, 2, 3)
    private val png2 = byteArrayOf(4, 5, 6)

    @Test
    fun sdApiTxt2ImgSendsLoraTagsAndReturnsSeededImages() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"images":["${b64(png1)}","${b64(png2)}"],"info":"{\"all_seeds\":[11,12],\"width\":512,\"height\":768}"}""",
            ).build(),
        )
        val engine = SdApiEngine(url(), { clients.create("t") }, progressIntervalMs = 60_000)
        val request = DiffusionRequest(prompt = "a cat", loras = listOf(LoraRef("film", 0.8f)), batchCount = 2, seed = 11, height = 768)

        val results = engine.generate(request).toList().filterIsInstance<DiffusionEvent.Result>()

        assertEquals(listOf(11L, 12L), results.map { it.seed })
        assertArrayEquals(png2, results[1].image)
        assertEquals(768, results[0].height)
        val recorded = server.takeRequest()
        assertEquals("/sdapi/v1/txt2img", recorded.url.encodedPath)
        val sent = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("a cat <lora:film:0.80>", sent["prompt"]!!.jsonPrimitive.content)
        assertEquals("2", sent["n_iter"]!!.jsonPrimitive.content)
    }

    @Test
    fun sdApiInpaintSendsMaskAndInitImage() {
        val engine = SdApiEngine(url(), { clients.create("t") })
        val body = engine.requestBody(
            DiffusionRequest(mode = DiffusionMode.INPAINT, prompt = "x", denoiseStrength = 0.5f, inpaintOnlyMasked = false),
            DiffusionInputs(initImage = png1, mask = png2),
        )
        assertEquals(b64(png2), body["mask"]!!.jsonPrimitive.content)
        assertEquals(b64(png1), (body["init_images"] as kotlinx.serialization.json.JsonArray)[0].jsonPrimitive.content)
        assertEquals("0.5", body["denoising_strength"]!!.jsonPrimitive.content)
        assertEquals("false", body["inpaint_full_res"]!!.jsonPrimitive.content)
    }

    @Test
    fun inpaintWithoutMaskFailsBeforeAnyRequest() = runBlocking {
        val engine = SdApiEngine(url(), { clients.create("t") })
        val error = runCatching { engine.generate(DiffusionRequest(mode = DiffusionMode.INPAINT), DiffusionInputs(png1)).toList() }
            .exceptionOrNull()
        assertTrue(error is DiffusionException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sdApiCatalogListsModelsLorasAndSamplers() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""[{"title":"sd15.safetensors [abc]","model_name":"sd15"}]""").build())
        server.enqueue(MockResponse.Builder().body("""{"sd_model_checkpoint":"sd15.safetensors [abc]"}""").build())
        server.enqueue(MockResponse.Builder().body("""[{"name":"film","alias":"film"}]""").build())
        server.enqueue(MockResponse.Builder().body("""[{"name":"Euler a"},{"name":"DPM++ 2M"}]""").build())
        server.enqueue(MockResponse.Builder().body("""[{"name":"karras","label":"Karras"}]""").build())
        val catalog = SdApiEngine(url(), { clients.create("t") }).catalog()
        assertEquals(listOf("sd15.safetensors [abc]"), catalog.models)
        assertEquals("sd15.safetensors [abc]", catalog.currentModel)
        assertEquals(listOf("film"), catalog.loras)
        assertEquals(listOf("Euler a", "DPM++ 2M"), catalog.samplers)
        assertEquals(listOf("Karras"), catalog.schedulers)
    }

    @Test
    fun sseEngineStreamsProgressThenResult() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").body(
                "event: progress\ndata: {\"step\":1,\"total_steps\":4}\n\n" +
                    "event: progress\ndata: {\"step\":2,\"total_steps\":4,\"image\":\"${b64(png1)}\"}\n\n" +
                    "event: complete\ndata: {\"image\":\"${b64(png2)}\",\"seed\":99,\"width\":512,\"height\":512,\"generation_time_ms\":1234}\n\n",
            ).build(),
        )
        val engine = LocalSseEngine(url(), { clients.create("t") })
        val events = engine.generate(DiffusionRequest(prompt = "a <lora:x:1> cat", sampler = "Euler a", scheduler = "Karras")).toList()

        val progress = events.filterIsInstance<DiffusionEvent.Progress>()
        assertEquals(listOf(1, 2), progress.map { it.step })
        assertArrayEquals(png1, progress[1].preview)
        val result = events.filterIsInstance<DiffusionEvent.Result>().single()
        assertEquals(99L, result.seed)
        assertEquals(1234L, result.durationMs)

        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("a cat", sent["prompt"]!!.jsonPrimitive.content)
        assertEquals("euler_a_karras", sent["scheduler"]!!.jsonPrimitive.content)
        assertFalse("random seed is omitted", sent.containsKey("seed"))
    }

    @Test
    fun sseErrorEventFailsTheFlow() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream")
                .body("event: error\ndata: {\"message\":\"model not loaded\"}\n\n").build(),
        )
        val error = runCatching { LocalSseEngine(url(), { clients.create("t") }).generate(DiffusionRequest(prompt = "x")).toList() }
            .exceptionOrNull()
        assertEquals("model not loaded", error?.message)
    }

    @Test
    fun remoteBackendIsBlockedOfflineWithoutTraffic() = runBlocking {
        val engine = SdApiEngine("https://example.com", { clients.create("t") })
        val error = runCatching { engine.catalog() }.exceptionOrNull() as DiffusionException
        assertEquals(DiffusionException.Code.BLOCKED, error.code)
    }

    @Test
    fun promptHelpers() {
        assertEquals("a <lora:x:0.50>", PromptSyntax.withLoraTags("a", listOf(LoraRef("x", 0.5f))))
        assertEquals("a <lora:x:1>", PromptSyntax.withLoraTags("a <lora:x:1>", listOf(LoraRef("x", 0.5f))))
        assertEquals("a b", PromptSyntax.stripLoraTags("a <lora:x:0.5> b"))
        assertEquals(512, PromptSyntax.snap(509))
        assertEquals("dpm_karras", LocalSseEngine.schedulerId("DPM++ 2M", "Karras"))
        assertEquals("lcm", LocalSseEngine.schedulerId("LCM", null))
    }
}
