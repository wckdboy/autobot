package dev.wckdboy.autobot.providers.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatProviderTest {

    @Test
    fun encodesOpenAiShapeWithToolsAndToolMessages() {
        val json = LocalChatProvider.encode(
            ChatRequest(
                model = "catalog:qwen",
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "sys"),
                    ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "read", """{"file_path":"a"}"""))),
                    ChatMessage(ChatRole.TOOL, "ok", toolCallId = "c1"),
                ),
                tools = listOf(ToolSpec("read", "Read", """{"type":"object","properties":{}}""")),
            ),
        )
        val messages = json["messages"] as JsonArray
        assertEquals("assistant", (messages[1] as JsonObject)["role"]!!.jsonPrimitive.content)
        val call = ((messages[1] as JsonObject)["tool_calls"] as JsonArray)[0] as JsonObject
        assertEquals("read", (call["function"] as JsonObject)["name"]!!.jsonPrimitive.content)
        assertEquals("c1", (messages[2] as JsonObject)["tool_call_id"]!!.jsonPrimitive.content)
        val tool = (json["tools"] as JsonArray)[0] as JsonObject
        assertTrue((tool["function"] as JsonObject)["parameters"] is JsonObject)
        assertEquals("2048", json["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun decodesToolCallsUsageAndFinish() {
        val events = LocalChatProvider.decodeResult(
            """{"content":"","reasoning":"","tool_calls":[{"id":"local_1","name":"read","arguments":"{\"file_path\":\"a\"}"}],
               "finish_reason":"tool_calls","usage":{"prompt_tokens":100,"cached_tokens":80,"completion_tokens":12}}""",
        )
        assertEquals(ChatEvent.ToolCallDelta(0, "local_1", "read", """{"file_path":"a"}"""), events[0])
        assertEquals(ChatEvent.Usage(100, 12, 112), events[1])
        assertEquals(ChatEvent.Finish("tool_calls"), events[2])
        assertEquals(ChatEvent.Done, events.last())
    }

    @Test
    fun imagesBecomePartsAndMmprojIsSent() {
        val json = LocalChatProvider.encode(
            ChatRequest(model = "m", messages = listOf(ChatMessage(ChatRole.USER, "what is this?", images = listOf("/cache/a.img")))),
            mmproj = "/models/mmproj.gguf",
        )
        val parts = ((json["messages"] as JsonArray)[0] as JsonObject)["content"] as JsonArray
        assertEquals("text", (parts[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("/cache/a.img", (parts[1] as JsonObject)["path"]!!.jsonPrimitive.content)
        assertEquals("/models/mmproj.gguf", json["mmproj"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsEngineErrors() {
        val overflow = LocalChatProvider.decodeResult("""{"error":{"code":"CONTEXT_WINDOW_EXCEEDED","message":"prompt has 9000 tokens"}}""").single()
        assertTrue((overflow as ChatEvent.Error).message.contains("context length"))
        assertTrue(LocalChatProvider.decodeResult("""{"error":{"code":"CANCELLED","message":"cancelled"}}""").isEmpty())
    }
}
