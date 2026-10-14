package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.SseDecoder
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeTest {

    /** Replays canned SSE bodies, one per request, and records what was sent. */
    private class FakeTransport(private val bodies: ArrayDeque<List<String>>, private val status: Int = 200) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            if (status != 200) {
                sink.onResponse(status, emptyMap(), """{"error":{"message":"bad key"}}""")
                return Cancellable { }
            }
            sink.onResponse(200, mapOf("content-type" to "text/event-stream"), null)
            bodies.removeFirst().forEach(sink::onLine)
            sink.onComplete()
            return Cancellable { }
        }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
    }

    private class MemoryStore : SessionStoreBridge {
        val lines = HashMap<String, MutableList<String>>()
        override fun load(sessionId: String): List<String> = synchronized(lines) { lines[sessionId].orEmpty().toList() }
        override fun append(sessionId: String, entries: List<String>) {
            synchronized(lines) { lines.getOrPut(sessionId) { mutableListOf() }.addAll(entries) }
        }
    }

    private fun data(json: String) = "data: $json"

    @Test
    fun sseDecoderHandlesDeltasToolCallsUsageAndDone() {
        val decoder = SseDecoder()
        val events = listOf(
            ": keep-alive",
            data("""{"choices":[{"delta":{"reasoning_content":"hm"}}]}"""),
            data("""{"choices":[{"delta":{"content":"Hi"}}]}"""),
            data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{\"fi"}}]}}]}"""),
            data("""{"choices":[{"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""),
            "data: [DONE]",
            data("""{"choices":[{"delta":{"content":"ignored"}}]}"""),
        ).flatMap(decoder::feed)
        assertEquals(
            listOf(
                ChatEvent.ReasoningDelta("hm"),
                ChatEvent.ContentDelta("Hi"),
                ChatEvent.ToolCallDelta(0, "c1", "read", "{\"fi"),
                ChatEvent.Finish("tool_calls"),
                ChatEvent.Usage(3, 4, 7),
            ),
            events,
        )
        assertTrue(decoder.done)
    }

    @Test
    fun transportProviderMapsHttpErrorsAndBlocking() = runBlocking {
        val request = ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "hi")))
        val unauthorized = TransportChatProvider("https://api.example.com/v1", "k", FakeTransport(ArrayDeque(), status = 401), "t")
            .streamChat(request).toList()
        assertEquals(ErrorKind.UNAUTHORIZED, (unauthorized.single() as ChatEvent.Error).kind)
        assertEquals("HTTP 401: bad key", (unauthorized.single() as ChatEvent.Error).message)

        val blocked = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
                sink.onFailure(blocked = true, message = "Offline mode")
                return Cancellable { }
            }
        }
        val events = TransportChatProvider("https://api.example.com", null, blocked, "t").streamChat(request).toList()
        assertEquals(ChatEvent.Error(ErrorKind.BLOCKED, "Offline mode"), events.single())
    }

    @Test
    fun bridgeRunsAToolCallingTurnAndPersistsTheLog() = runBlocking {
        val dir = Files.createTempDirectory("bridge").toFile()
        File(dir, "notes.md").writeText("hello from the workspace")
        val transport = FakeTransport(
            ArrayDeque(
                listOf(
                    listOf(
                        data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{\"file_path\":\"notes.md\"}"}}]}}]}"""),
                        data("""{"choices":[{"finish_reason":"tool_calls"}]}"""),
                        "data: [DONE]",
                    ),
                    listOf(
                        data("""{"choices":[{"delta":{"content":"It says hello."}}]}"""),
                        data("""{"choices":[{"finish_reason":"stop"}]}"""),
                        "data: [DONE]",
                    ),
                ),
            ),
        )
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", "secret", "m").takeIf { id == "p" }
        }
        val store = MemoryStore()
        val bridge = AgentBridge(dir.path, providers, transport, NoLocal, store, null)
        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) kotlinx.coroutines.delay(10) }
        bridge.send("s1", "What is in notes.md?", emptyList())

        withTimeout(10_000) {
            while (states.lastOrNull()?.let { s -> !s.running && s.projection.items.any { it is SessionItem.Assistant } } != true) {
                kotlinx.coroutines.delay(20)
            }
        }
        handle.cancel()

        val items = states.last().projection.items
        val tool = items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals("read", tool.name)
        assertEquals(ToolState.OK, tool.state)
        assertTrue(tool.output!!.contains("hello from the workspace"))
        assertEquals("It says hello.", items.filterIsInstance<SessionItem.Assistant>().last().text)

        // Two model calls, authenticated, with the tool result in the second.
        assertEquals(2, transport.sent.size)
        assertEquals("Bearer secret", transport.sent[0].headers["Authorization"])
        assertEquals("https://api.example.com/v1/chat/completions", transport.sent[0].url)
        assertTrue(transport.sent[1].body!!.contains("hello from the workspace"))
        // Every event went through the host store.
        assertTrue(store.load("s1").size > 5)

        bridge.answerApproval("none", ApprovalOutcome.REJECTED) // no-op for unknown call ids
    }
}
