package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.security.Secret
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAICompatibleProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var policy: NetworkPolicy
    private lateinit var auditLog: AuditLog
    private lateinit var clients: HttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        policy = NetworkPolicy() // default Offline; loopback allowed
        auditLog = AuditLog(capacity = 50, clock = { 0L })
        clients = HttpClientFactory(policy, auditLog)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl(path: String = "/v1"): String =
        server.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun sse(vararg events: String): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/event-stream")
        .body(events.joinToString(separator = "") { "data: $it\n\n" })
        .build()

    private val request = ChatRequest(
        model = "deepseek-reasoner",
        messages = listOf(ChatMessage(ChatRole.USER, "Hi")),
    )

    private fun provider(key: Secret? = Secret("sk-test-123")) =
        OpenAICompatibleProvider(baseUrl(), key, clients.create("test"))

    @Test
    fun streamsReasoningAndContentDeltasUntilDone() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"role":"assistant","content":null,"reasoning_content":"Let me "}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"think."}}]}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}""",
                "[DONE]",
            ),
        )

        val events = provider().streamChat(request).toList()

        assertEquals(
            listOf(
                ChatEvent.ReasoningDelta("Let me "),
                ChatEvent.ReasoningDelta("think."),
                ChatEvent.ContentDelta("Hel"),
                ChatEvent.ContentDelta("lo"),
                ChatEvent.Finish("stop"),
                ChatEvent.Usage(3, 5, 8),
                ChatEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun openRouterStyleReasoningFieldIsSupported() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"reasoning":"hmm"}}]}""", "[DONE]"))
        val events = provider().streamChat(request).toList()
        assertEquals(listOf(ChatEvent.ReasoningDelta("hmm"), ChatEvent.Done), events)
    }

    @Test
    fun ignoresSseCommentsAndStopsAtDone() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    ": OPENROUTER PROCESSING\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\n" +
                        "data: [DONE]\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"ignored\"}}]}\n\n",
                )
                .build(),
        )
        val events = provider().streamChat(request).toList()
        assertEquals(listOf(ChatEvent.ContentDelta("A"), ChatEvent.Done), events)
    }

    @Test
    fun sendsStreamingRequestToChatCompletionsWithoutIdentifiers() = runTest {
        server.enqueue(sse("[DONE]"))
        provider().streamChat(request.copy(temperature = 0.5)).toList()

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test-123", recorded.headers["Authorization"])
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertEquals("Autobot", recorded.headers["User-Agent"])
        assertNull(recorded.headers["X-Device-Id"])
        assertNull(recorded.headers["Cookie"])
        val body = recorded.body!!.utf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"model\":\"deepseek-reasoner\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"include_usage\":true"))
        assertFalse(body.contains("user_id"))
    }

    @Test
    fun omitsAuthorizationWithoutKey() = runTest {
        server.enqueue(sse("[DONE]"))
        provider(key = null).streamChat(request).toList()
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun mapsUnauthorized() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"message":"Authentication Fails (no such user)","type":"authentication_error"}}""")
                .build(),
        )
        val event = provider().streamChat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.UNAUTHORIZED, event.kind)
        assertEquals(401, event.httpCode)
        assertTrue(event.message.contains("Authentication Fails"))
        assertFalse(event.message.contains("sk-test-123"))
    }

    @Test
    fun mapsRateLimitQuotaAndServerErrors() = runTest {
        val cases = listOf(429 to ErrorKind.RATE_LIMITED, 402 to ErrorKind.QUOTA, 503 to ErrorKind.SERVER, 400 to ErrorKind.BAD_REQUEST)
        cases.forEach { (code, _) -> server.enqueue(MockResponse.Builder().code(code).body("oops").build()) }
        cases.forEach { (code, kind) ->
            val event = provider().streamChat(request).toList().single() as ChatEvent.Error
            assertEquals(kind, event.kind)
            assertEquals(code, event.httpCode)
        }
    }

    @Test
    fun midStreamErrorObjectIsReported() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"x"}}]}""", """{"error":{"message":"overloaded"}}"""))
        val events = provider().streamChat(request).toList()
        assertEquals(ChatEvent.ContentDelta("x"), events[0])
        val error = events[1] as ChatEvent.Error
        assertEquals(ErrorKind.SERVER, error.kind)
        assertEquals("overloaded", error.message)
    }

    @Test
    fun malformedChunkIsProtocolError() = runTest {
        server.enqueue(sse("{not json"))
        val event = provider().streamChat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.PROTOCOL, event.kind)
    }

    @Test
    fun streamWithoutDoneStillCompletes() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"x"}}]}"""))
        val events = provider().streamChat(request).toList()
        assertEquals(listOf(ChatEvent.ContentDelta("x"), ChatEvent.Done), events)
    }

    @Test
    fun killSwitchProducesBlockedErrorAndNoRequest() = runTest {
        policy.setAllowLoopbackWhenOffline(false)
        val event = provider().streamChat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.BLOCKED, event.kind)
        assertEquals(0, server.requestCount)
        assertTrue(auditLog.entries.value.single().blocked)
    }

    @Test
    fun killSwitchBlocksRemoteDeepSeekWhileOffline() = runTest {
        val deepSeek = DeepSeekProvider(Secret("sk"), clients.create("deepseek"))
        val event = deepSeek.streamChat(request).toList().single() as ChatEvent.Error
        assertEquals(ErrorKind.BLOCKED, event.kind)
        assertEquals("api.deepseek.com", auditLog.entries.value.single().host)
    }

    @Test
    fun deepSeekUsesChatCompletionsAtRoot() = runTest {
        policy.setMode(NetworkMode.Direct)
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        val deepSeek = DeepSeekProvider(Secret("sk"), clients.create("deepseek"), baseUrl(path = "/"))
        val events = deepSeek.streamChat(request).toList()
        assertEquals(ChatEvent.ContentDelta("ok"), events.first())
        assertEquals("/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun testConnectionCountsModels() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[{"id":"a"},{"id":"b"}]}""").build())
        val result = provider().testConnection()
        assertEquals(ConnectionTestResult.Success(2), result)
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }

    @Test
    fun testConnectionRespectsKillSwitch() = runTest {
        policy.setAllowLoopbackWhenOffline(false)
        val result = provider().testConnection() as ConnectionTestResult.Failure
        assertEquals(ErrorKind.BLOCKED, result.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun streamsToolCallFragmentsAndFinishReason() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"fs_read","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.md\"}"}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            ),
        )

        val events = provider().streamChat(request).toList()

        assertEquals(
            listOf(
                ChatEvent.ToolCallDelta(0, "call_1", "fs_read", ""),
                ChatEvent.ToolCallDelta(0, null, null, "{\"path\":"),
                ChatEvent.ToolCallDelta(0, null, null, "\"a.md\"}"),
                ChatEvent.Finish("tool_calls"),
                ChatEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun encodesToolsToolCallsAndToolResults() {
        val body = OpenAICompatibleProvider.encodeRequest(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(
                    ChatMessage(ChatRole.USER, "read a.md"),
                    ChatMessage(
                        ChatRole.ASSISTANT,
                        "",
                        toolCalls = listOf(ToolCall("call_1", "fs_read", """{"path":"a.md"}""")),
                        reasoning = "need file",
                    ),
                    ChatMessage(ChatRole.TOOL, "hello", toolCallId = "call_1"),
                ),
                tools = listOf(ToolSpec("fs_read", "Read a file", """{"type":"object","properties":{"path":{"type":"string"}}}""")),
                echoReasoning = true,
            ),
            includeUsage = false,
        )

        assertTrue(body.contains(""""tools":[{"type":"function","function":{"name":"fs_read","description":"Read a file","parameters":{"type":"object""""))
        assertTrue(body.contains(""""role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"fs_read","arguments":"{\"path\":\"a.md\"}"}}],"reasoning_content":"need file""""))
        assertTrue(body.contains(""""role":"tool","content":"hello","tool_call_id":"call_1""""))
    }

    @Test
    fun omitsReasoningUnlessEchoRequested() {
        val body = OpenAICompatibleProvider.encodeRequest(
            ChatRequest("m", listOf(ChatMessage(ChatRole.ASSISTANT, "x", reasoning = "secret thoughts"))),
            includeUsage = false,
        )
        assertFalse(body.contains("reasoning_content"))
        assertFalse(body.contains("\"tools\""))
    }

    @Test
    fun rejectsNonObjectToolSchema() = runTest {
        val events = provider().streamChat(request.copy(tools = listOf(ToolSpec("x", "y", "[1]")))).toList()
        assertEquals(ErrorKind.BAD_REQUEST, (events.single() as ChatEvent.Error).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun presetsCoverEveryKind() {
        dev.wckdboy.autobot.core.data.model.ProviderKind.entries.forEach { ProviderPresets.forKind(it) }
        assertEquals("http://127.0.0.1:11434/v1", ProviderPresets.forKind(dev.wckdboy.autobot.core.data.model.ProviderKind.OLLAMA).baseUrl)
    }
}
