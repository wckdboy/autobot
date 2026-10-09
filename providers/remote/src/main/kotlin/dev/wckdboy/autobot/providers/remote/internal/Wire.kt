package dev.wckdboy.autobot.providers.remote.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

@Serializable
internal data class WireFunctionCall(val name: String, val arguments: String)

@Serializable
internal data class WireToolCall(val id: String, val type: String = "function", val function: WireFunctionCall)

/**
 * A request message. [content] is nullable because assistant messages that only carry
 * [toolCalls] are sent with `content: null` by convention.
 */
@Serializable
internal data class WireMessage(
    val role: String,
    val content: String?,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
internal data class WireFunctionDef(val name: String, val description: String, val parameters: JsonObject)

@Serializable
internal data class WireTool(val type: String = "function", val function: WireFunctionDef)

@Serializable
internal data class WireStreamOptions(@SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
internal data class WireChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: WireStreamOptions? = null,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<WireTool>? = null,
)

@Serializable
internal data class WireFunctionDelta(val name: String? = null, val arguments: String? = null)

@Serializable
internal data class WireToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: WireFunctionDelta? = null,
)

@Serializable
internal data class WireDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCallDelta>? = null,
)

@Serializable
internal data class WireChoice(
    val delta: WireDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
internal data class WireError(val message: String? = null, val type: String? = null, val code: JsonElement? = null)

@Serializable
internal data class WireChunk(
    val choices: List<WireChoice> = emptyList(),
    val usage: WireUsage? = null,
    val error: WireError? = null,
)

@Serializable
internal data class WireErrorEnvelope(val error: WireError? = null, val message: String? = null)

@Serializable
internal data class WireModelList(val data: List<JsonElement> = emptyList())
