package dev.wckdboy.autobot.providers.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Requests to and results from the on-device llama.cpp engine (`autobot_llama` core, used by the
 * Android `:llm` process and the iOS engine alike): OpenAI-shaped messages and tools in, a result
 * object with tool calls, usage and finish reason out.
 */
object LocalLlmCodec {
    fun encode(request: ChatRequest, mmproj: String? = null): JsonObject = buildJsonObject {
        put(
            "messages",
            buildJsonArray {
                request.messages.forEach { m ->
                    add(
                        buildJsonObject {
                            put("role", m.role.name.lowercase())
                            if (m.images.isEmpty()) {
                                put("content", m.content)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "text"); put("text", m.content) })
                                        m.images.forEach { path -> add(buildJsonObject { put("type", "image"); put("path", path) }) }
                                    },
                                )
                            }
                            if (m.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        m.toolCalls.forEach { c ->
                                            add(
                                                buildJsonObject {
                                                    put("id", c.id)
                                                    put("type", "function")
                                                    put("function", buildJsonObject {
                                                        put("name", c.name)
                                                        put("arguments", c.arguments)
                                                    })
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                            m.toolCallId?.let { put("tool_call_id", it) }
                        },
                    )
                }
            },
        )
        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { t ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", OpenAiCodec.parseJson(t.parametersJson))
                                })
                            },
                        )
                    }
                },
            )
        }
        mmproj?.let { put("mmproj", it) }
        request.temperature?.let { put("temperature", it) }
        put("max_tokens", request.maxTokens ?: 2048)
    }

    fun decodeResult(json: String): List<ChatEvent> {
        val o = runCatching { OpenAiCodec.parseJson(json) as JsonObject }.getOrNull()
            ?: return listOf(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed engine result"))
        (o["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.contentOrNull
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "engine error"
            return when (code) {
                "CANCELLED" -> emptyList()
                "CONTEXT_WINDOW_EXCEEDED" -> listOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, "context length exceeded: $msg", code = code))
                else -> listOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, msg, code = code))
            }
        }
        val events = mutableListOf<ChatEvent>()
        (o["tool_calls"] as? JsonArray)?.forEachIndexed { i, c ->
            val co = c as? JsonObject ?: return@forEachIndexed
            events += ChatEvent.ToolCallDelta(
                i,
                (co["id"] as? JsonPrimitive)?.contentOrNull,
                (co["name"] as? JsonPrimitive)?.contentOrNull,
                (co["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        (o["usage"] as? JsonObject)?.let { u ->
            val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull
            val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull
            events += ChatEvent.Usage(prompt, completion, if (prompt != null && completion != null) prompt + completion else null)
        }
        (o["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { events += ChatEvent.Finish(it) }
        events += ChatEvent.Done
        return events
    }
}
