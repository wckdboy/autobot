package dev.wckdboy.autobot.providers.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Anthropic Messages codec and the OpenAI-codec additions (Gemini signatures, Mistral chunks, Groq usage). */
class WireTest {

    private fun data(json: String) = "data: $json"
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    // ---- Anthropic -----------------------------------------------------------------------------

    @Test
    fun anthropicRequestHoistsSystemMergesToolResultsAndMarksCacheBreakpoints() {
        val request = ChatRequest(
            model = "claude-opus-5-5",
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, "Be brief."),
                ChatMessage(ChatRole.USER, "Read two files"),
                ChatMessage(
                    ChatRole.ASSISTANT, "",
                    toolCalls = listOf(ToolCall("toolu_1", "read", """{"path":"a"}"""), ToolCall("toolu_2", "read", "not json")),
                ),
                ChatMessage(ChatRole.TOOL, "A", toolCallId = "toolu_1"),
                ChatMessage(ChatRole.TOOL, "B", toolCallId = "toolu_2"),
                ChatMessage(ChatRole.SYSTEM, "Steer: shorter."),
            ),
            temperature = 1.7,
            tools = listOf(ToolSpec("read", "Read a file", """{"type":"object"}"""), ToolSpec("write", "Write", """{"type":"object"}""")),
        )
        val body = obj(AnthropicCodec.encodeRequest(request))
        assertEquals(16_384, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("1.0", body["temperature"]!!.jsonPrimitive.content)
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("Be brief.", system["text"]!!.jsonPrimitive.content)
        assertTrue("cache_control" in system)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val assistant = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("a", assistant[0]["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), assistant[1]["input"])
        val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "tool_result", "text"), results.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("[system] Steer: shorter.", results[2]["text"]!!.jsonPrimitive.content)
        assertTrue("cache_control" in results.last())
        val tools = body["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals("read", tools[0]["name"]!!.jsonPrimitive.content)
        assertTrue("input_schema" in tools[0])
        assertFalse("cache_control" in tools[0])
        assertTrue("cache_control" in tools[1])
    }

    @Test
    fun anthropicImagesAreBase64BlocksBeforeTheText() {
        val fs = FakeFileSystem()
        fs.write("/img.png".toPath()) { write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) }
        val body = obj(AnthropicCodec.encodeRequest(ChatRequest("claude-3-5-haiku", listOf(ChatMessage(ChatRole.USER, "what?", images = listOf("/img.png")))), fs))
        assertEquals(8_192, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("image", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", content[0]["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("text", content[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicStreamDecodesTextToolsUsageAndStop() {
        val decoder = AnthropicSseDecoder()
        val events = listOf(
            "event: message_start",
            data("""{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-opus-5-5","usage":{"input_tokens":25,"cache_read_input_tokens":100,"output_tokens":1}}}"""),
            data("""{"type":"ping"}"""),
            data("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            data("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""),
            data("""{"type":"content_block_stop","index":0}"""),
            data("""{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_9","name":"get_weather","input":{}}}"""),
            data("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":""}}"""),
            data("""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Oslo\"}"}}"""),
            data("""{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":15}}"""),
            data("""{"type":"message_stop"}"""),
            data("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ignored"}}"""),
        ).flatMap(decoder::feed)
        assertEquals(
            listOf(
                ChatEvent.ContentDelta("Hello"),
                ChatEvent.ToolCallDelta(0, "toolu_9", "get_weather", ""),
                ChatEvent.ToolCallDelta(0, null, null, ""),
                ChatEvent.ToolCallDelta(0, null, null, """{"city":"Oslo"}"""),
                ChatEvent.Finish("tool_calls"),
                ChatEvent.Usage(125, 15, 140),
            ),
            events,
        )
        assertTrue(decoder.done)
    }

    @Test
    fun anthropicThinkingBlocksRideTheFirstToolCallAndAreReplayedFirst() {
        val decoder = AnthropicSseDecoder()
        val events = listOf(
            data("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}"""),
            data("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need weather."}}"""),
            data("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"EqQBsig"}}"""),
            data("""{"type":"content_block_stop","index":0}"""),
            data("""{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}"""),
            data("""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"w","input":{}}}"""),
            data("""{"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"toolu_2","name":"w","input":{}}}"""),
        ).flatMap(decoder::feed)
        assertEquals(ChatEvent.ReasoningDelta("Need weather."), events[0])
        val first = (events[1] as ChatEvent.ToolCallDelta).id!!
        val second = (events[2] as ChatEvent.ToolCallDelta).id!!
        assertEquals("toolu_1", ToolCallIds.bare(first))
        assertEquals("toolu_2", second)

        // The agent echoes the ids back on the assistant message and on the tool results.
        val body = obj(
            AnthropicCodec.encodeRequest(
                ChatRequest(
                    "claude-opus-5-5",
                    listOf(
                        ChatMessage(ChatRole.USER, "weather?"),
                        ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall(first, "w", "{}"), ToolCall(second, "w", "{}"))),
                        ChatMessage(ChatRole.TOOL, "sunny", toolCallId = first),
                    ),
                ),
            ),
        )
        val assistant = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "redacted_thinking", "tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("Need weather.", assistant[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("EqQBsig", assistant[0]["signature"]!!.jsonPrimitive.content)
        assertEquals("opaque", assistant[1]["data"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", assistant[2]["id"]!!.jsonPrimitive.content)
        val result = body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("toolu_1", result["tool_use_id"]!!.jsonPrimitive.content)

        // Another provider never sees the state.
        val openAi = OpenAiCodec.encodeRequest(
            ChatRequest("m", listOf(ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall(first, "w", "{}"))), ChatMessage(ChatRole.TOOL, "x", toolCallId = first))),
            includeUsage = false,
        )
        assertFalse(openAi.contains("~~"))
        assertFalse(openAi.contains("signature"))
    }

    @Test
    fun anthropicErrorEventsAndMalformedChunks() {
        val error = AnthropicSseDecoder().feed(data("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")).single()
        assertEquals(ChatEvent.Error(ErrorKind.SERVER, "Overloaded"), error)
        val rate = AnthropicSseDecoder().feed(data("""{"type":"error","error":{"type":"rate_limit_error","message":"slow"}}""")).single() as ChatEvent.Error
        assertEquals(ErrorKind.RATE_LIMITED, rate.kind)
        assertEquals(ErrorKind.PROTOCOL, (AnthropicSseDecoder().feed("data: {oops").single() as ChatEvent.Error).kind)
        assertEquals("length", AnthropicCodec.finishReason("max_tokens"))
        assertEquals("stop", AnthropicCodec.finishReason("end_turn"))
        assertEquals(4_096, AnthropicCodec.defaultMaxTokens("claude-3-haiku-20240307"))
        assertEquals(16_384, AnthropicCodec.defaultMaxTokens("claude-3-7-sonnet-latest"))
    }

    @Test
    fun anthropicWireHeadersAndPath() {
        assertEquals("messages", AnthropicWire.chatPath)
        assertEquals(mapOf("x-api-key" to "k", "anthropic-version" to "2023-06-01"), AnthropicWire.headers("k"))
    }

    // ---- OpenAI-compatible additions ----------------------------------------------------------

    @Test
    fun geminiThoughtSignaturesAreKeptAndEchoedOnlyToGemini() {
        val events = SseDecoder().feed(
            data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"},"extra_content":{"google":{"thought_signature":"SIG_A"}}}]}}]}"""),
        )
        val id = (events.single() as ChatEvent.ToolCallDelta).id!!
        assertEquals("call_1", ToolCallIds.bare(id))
        val request = ChatRequest(
            "gemini-3.8-flash",
            listOf(ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall(id, "f", "{}"))), ChatMessage(ChatRole.TOOL, "r", toolCallId = id)),
        )
        val gemini = obj(ProviderPresets.byId("gemini")!!.wire().encode(request))
        val call = gemini["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("SIG_A", call["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content)
        assertEquals("call_1", gemini["messages"]!!.jsonArray[1].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        val other = ProviderPresets.byId("groq")!!.wire().encode(request)
        assertFalse(other.contains("extra_content"))
        assertFalse(other.contains("SIG_A"))
    }

    @Test
    fun plainIdsAreUntouched() {
        assertEquals("call_x", ToolCallIds.bare("call_x"))
        assertNull(ToolCallIds.state("call_x", ToolCallIds.GEMINI_EXTRA))
        assertNull(ToolCallIds.state("call_x~~!!notbase64", ToolCallIds.GEMINI_EXTRA))
    }

    @Test
    fun mistralChunkListsSplitThinkingFromText() {
        val decoder = SseDecoder()
        val events = listOf(
            data("""{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"Hmm"}]}]}}]}"""),
            data("""{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[]},{"type":"text","text":"Hi"}]}}]}"""),
            data("""{"choices":[{"delta":{"content":" there"}}]}"""),
        ).flatMap(decoder::feed)
        assertEquals(listOf(ChatEvent.ReasoningDelta("Hmm"), ChatEvent.ContentDelta("Hi"), ChatEvent.ContentDelta(" there")), events)
    }

    @Test
    fun groqUsageFallbackAndReasoningField() {
        val events = SseDecoder().feed(data("""{"choices":[],"x_groq":{"usage":{"prompt_tokens":5,"completion_tokens":6,"total_tokens":11}}}"""))
        assertEquals(listOf(ChatEvent.Usage(5, 6, 11)), events)
        assertEquals(listOf(ChatEvent.ReasoningDelta("r")), SseDecoder().feed(data("""{"choices":[{"delta":{"reasoning":"r"}}]}""")))
    }

    @Test
    fun connectionSummaryNamesTheFirstModels() {
        val ok = ConnectionTestResult.Success(42, listOf("a", "b", "c", "d").map { RemoteModel(it) })
        assertEquals("Connected · 42 models · a, b, c…", ok.summary())
        assertEquals("Connected · 1 model · a", ConnectionTestResult.Success(1, listOf(RemoteModel("a"))).summary())
        assertTrue(ConnectionTestResult.Success(null).summary().startsWith("No models endpoint"))
        val documented = ConnectionTestResult.of(ModelLists.documented(ProviderPresets.byId("zai")!!), hasEndpoint = false) as ConnectionTestResult.Success
        assertNull(documented.modelCount)
        assertEquals("glm-5.3", documented.models.first().id)
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}
