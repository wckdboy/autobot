package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.ProviderSessions
import dev.wckdboy.autobot.providers.remote.RemoteModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model lists and the Anthropic wire through a host transport, as iOS runs them. */
class TransportModelsTest {

    private class Recording(private val status: Int, private val lines: List<String>) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            if (status !in 200..299) {
                sink.onResponse(status, emptyMap(), lines.joinToString("\n"))
            } else {
                sink.onResponse(status, emptyMap(), null)
                lines.forEach(sink::onLine)
                sink.onComplete()
            }
            return Cancellable { }
        }
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/models/$name")).readText()

    @Test
    fun listsGeminiModelsFromTheNativeEndpointWithTheGoogleKeyHeader() = runBlocking {
        val transport = Recording(200, fixture("gemini_native_models.json").lines())
        val gemini = ProviderPresets.byId("gemini")!!
        val provider = TransportChatProvider(gemini.baseUrl, "g-key", transport, "Gemini", "TOR", gemini)
        val result = provider.testConnection() as ConnectionTestResult.Success
        assertEquals(4, result.modelCount)
        assertEquals("gemini-3.8-flash", result.models.first().id)
        val request = transport.sent.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", request.url)
        assertEquals("g-key", request.headers["x-goog-api-key"])
        assertNull(request.headers["Authorization"])
        assertEquals("TOR", request.route)
        assertEquals("Gemini", request.tag)
    }

    @Test
    fun aKeyIsNeverSentOffTheProvidersHost() = runBlocking {
        // A preset whose models path pointed elsewhere would still not get the key.
        val evil = ProviderPresets.byId("groq")!!.copy(modelsPath = "https://evil.example/models")
        val transport = Recording(200, listOf("""{"data":[]}"""))
        TransportChatProvider(evil.baseUrl, "secret", transport, "t", preset = evil).listModels()
        assertTrue(transport.sent.single().headers.values.none { "secret" in it })
    }

    @Test
    fun blockedAndHttpErrorsBecomeFailures() = runBlocking {
        val blocked = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
                sink.onFailure(blocked = true, message = "Offline mode")
                return Cancellable { }
            }
        }
        val groq = ProviderPresets.byId("groq")!!
        assertEquals(ModelListResult.Failure(ErrorKind.BLOCKED, "Offline mode"), TransportChatProvider(groq.baseUrl, "k", blocked, "t", preset = groq).listModels())
        val unauthorized = TransportChatProvider(groq.baseUrl, "k", Recording(401, listOf("""{"error":{"message":"Invalid API Key"}}""")), "t", preset = groq)
        assertEquals(ConnectionTestResult.Failure(ErrorKind.UNAUTHORIZED, "HTTP 401: Invalid API Key"), unauthorized.testConnection())
    }

    @Test
    fun presetsWithoutAModelsEndpointReturnTheDocumentedListWithoutARequest() = runBlocking {
        val transport = Recording(200, emptyList())
        val zai = ProviderPresets.byId("zai")!!
        val result = TransportChatProvider(zai.baseUrl, "k", transport, "t", preset = zai).testConnection() as ConnectionTestResult.Success
        assertNull(result.modelCount)
        assertEquals(zai.suggestedModels, result.models.map { it.id })
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun anthropicChatGoesToMessagesWithNativeHeaders() = runBlocking {
        val transport = Recording(
            200,
            listOf(
                "event: content_block_delta",
                """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
                """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
                """data: {"type":"message_stop"}""",
            ),
        )
        val anthropic = ProviderPresets.byId("anthropic")!!
        val events = TransportChatProvider(anthropic.baseUrl, "a-key", transport, "Anthropic", preset = anthropic)
            .streamChat(ChatRequest("claude-opus-5-5", listOf(ChatMessage(ChatRole.USER, "hi")))).toList()
        assertEquals(listOf(ChatEvent.ContentDelta("Hi"), ChatEvent.Finish("stop"), ChatEvent.Usage(null, 2, null), ChatEvent.Done), events)
        val request = transport.sent.single()
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("a-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun openCodeGoThroughTheAdapterSendsTheSessionHashAndPicksTheWirePerModel() = runBlocking {
        val go = ProviderPresets.byId(ProviderPresets.OPENCODE_GO)!!
        val transport = Recording(200, listOf("""data: {"type":"message_stop"}"""))
        val adapter = ProviderLlmAdapter(
            "go", TransportChatProvider(go.baseUrl, "k", transport, "OpenCode Go", preset = go), ProviderKind.OPENAI_COMPATIBLE,
            echoReasoning = go::echoesReasoning, helperModel = "glm-5.3-flash",
        )
        assertEquals("glm-5.3-flash", adapter.helperModel)
        val call = LlmCall(LlmCallConfig("go", "minimax-m3"), listOf(ModelMessage(ModelRole.USER, "hi")), emptyList(), sessionId = "s1")
        adapter.stream(call).toList()
        val request = transport.sent.single()
        assertEquals("https://opencode.ai/inference/go/v1/messages", request.url)
        assertEquals(ProviderSessions.key("s1"), request.headers["x-opencode-session"])
        assertEquals("k", request.headers["x-api-key"])
    }

    @Test
    fun aUsedUpLimitReachesTheAgentAsQuotaWithItsResetTime() = runBlocking {
        val limited = object : HttpTransport {
            override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
                sink.onResponse(429, mapOf("retry-after" to "1800"), """{"type":"error","error":{"type":"GoUsageLimitError","message":"Go usage limit exceeded"}}""")
                return Cancellable { }
            }
        }
        val go = ProviderPresets.byId(ProviderPresets.OPENCODE_GO)!!
        val adapter = ProviderLlmAdapter("go", TransportChatProvider(go.baseUrl, "k", limited, "t", preset = go), ProviderKind.OPENAI_COMPATIBLE)
        val failure = runCatching { adapter.stream(LlmCall(LlmCallConfig("go", "kimi-k2.6"), emptyList(), emptyList())).toList() }
            .exceptionOrNull() as LlmException
        assertEquals(LlmErrorCode.QUOTA, failure.failure.code)
        assertEquals(1_800_000L, failure.failure.retryAfterMs)
        assertEquals("OpenCode Go's usage limit is used up; it resets in ~30 min.", failure.failure.message)
    }

    @Test
    fun cachedContextWindowsReplaceGuesses() = runBlocking {
        val cache = ModelCache(0, listOf(RemoteModel("my-model", contextWindow = 40_960)))
        val adapter = ProviderLlmAdapter("p", TransportChatProvider("https://x.example", null, Recording(200, emptyList()), "t"), ProviderKind.OPENAI_COMPATIBLE, models = cache)
        assertEquals(40_960, adapter.resolveModel("my-model").contextWindow)
        assertEquals(65_536, adapter.resolveModel("unknown").contextWindow)
        assertEquals(1_000_000, ProviderLlmAdapter.modelInfo(ProviderKind.ANTHROPIC, "claude-opus-5-5").contextWindow)
        assertEquals(8_192, ProviderLlmAdapter.modelInfo(ProviderKind.LOCAL, "x", RemoteModel("x", contextWindow = 1_000_000)).contextWindow)
    }
}
