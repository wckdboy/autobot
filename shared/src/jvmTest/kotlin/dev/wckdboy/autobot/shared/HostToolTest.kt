package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host-implemented tool runs through the shared agent loop and its meta reaches the transcript. */
class HostToolTest {

    private class ScriptedTransport(private val bodies: ArrayDeque<List<String>>) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            sink.onResponse(200, emptyMap(), null)
            bodies.removeFirst().forEach(sink::onLine)
            sink.onComplete()
            return Cancellable { }
        }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private object NoStore : SessionStoreBridge {
        override fun load(sessionId: String): List<String> = emptyList()
        override fun append(sessionId: String, entries: List<String>) = Unit
    }

    private class FakeImageTool : HostTool {
        val calls = CopyOnWriteArrayList<String>()
        override val name = "generate_image"
        override val description = "Generate images"
        override val parametersJson =
            """{"type":"object","properties":{"prompt":{"type":"string","description":"Positive prompt"}},"required":["prompt"],"additionalProperties":false}"""
        override val kind = "GENERATE"
        override val timeoutMs = 60_000L

        override fun describe(argsJson: String): String = Json.parseToJsonElement(argsJson).jsonObject["prompt"]!!.jsonPrimitive.content

        override fun execute(callId: String, argsJson: String, sink: HostToolSink): Cancellable {
            calls += argsJson
            sink.onResult("Generated 1 image(s) on This phone: gallery:g1", false, null, """{"gallery":["g1"]}""")
            return Cancellable { }
        }
    }

    @Test
    fun hostToolRunsInTheLoopAndItsMetaReachesTheProjection() = runBlocking {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    listOf(
                        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"generate_image","arguments":"{\"prompt\":\"a lighthouse\"}"}}]}}]}""",
                        """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                        "data: [DONE]",
                    ),
                    listOf(
                        """data: {"choices":[{"delta":{"content":"Here it is."}}]}""",
                        """data: {"choices":[{"finish_reason":"stop"}]}""",
                        "data: [DONE]",
                    ),
                ),
            ),
        )
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", null, "m").takeIf { id == "p" }
        }
        val dir = Files.createTempDirectory("hosttool").toFile()
        val bridge = AgentBridge(dir.path, providers, transport, NoLocal, NoStore, null, { null })
        val tool = FakeImageTool()
        bridge.registerTool(tool)

        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        bridge.send("s1", "Draw a lighthouse", emptyList())
        withTimeout(10_000) {
            while (states.lastOrNull()?.let { s -> !s.running && s.projection.items.any { it is SessionItem.Assistant } } != true) delay(20)
        }
        handle.cancel()

        assertEquals(listOf("""{"prompt":"a lighthouse"}"""), tool.calls)
        val card = states.last().projection.items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals("generate_image", card.name)
        assertEquals("a lighthouse", card.summary)
        assertEquals(ToolState.OK, card.state)
        assertEquals(listOf("g1"), card.galleryIds)
        // The model saw the tool in its request and the host's text as the result.
        assertTrue(transport.sent[0].body!!.contains("\"generate_image\""))
        assertTrue(transport.sent[1].body!!.contains("gallery:g1"))
    }
}
