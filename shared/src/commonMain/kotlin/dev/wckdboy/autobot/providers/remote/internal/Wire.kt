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
internal data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall,
    /** Provider state echoed back unchanged (Gemini's `{google:{thought_signature}}`). */
    @SerialName("extra_content") val extraContent: JsonObject? = null,
)

/**
 * A request message. [content] is a string, an array of parts (text + `image_url`) when images
 * are attached, or null for assistant messages that only carry [toolCalls] (by convention).
 */
@Serializable
internal data class WireMessage(
    val role: String,
    val content: JsonElement?,
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
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    // Thinking switches of other providers (see RemoteThinking); null fields are never encoded.
    val reasoning: JsonObject? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
    @SerialName("reasoning_budget_tokens") val reasoningBudgetTokens: Int? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    val thinking: JsonObject? = null,
)

@Serializable
internal data class WireFunctionDelta(val name: String? = null, val arguments: String? = null)

@Serializable
internal data class WireToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: WireFunctionDelta? = null,
    @SerialName("extra_content") val extraContent: JsonObject? = null,
)

@Serializable
internal data class WireDelta(
    /** A string, or (Mistral reasoning) a list of `text` / `thinking` chunks. */
    val content: JsonElement? = null,
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
    /** Groq reports streamed usage here. */
    @SerialName("x_groq") val xGroq: WireXGroq? = null,
)

@Serializable
internal data class WireXGroq(val usage: WireUsage? = null)

@Serializable
internal data class WireErrorEnvelope(val error: WireError? = null, val message: String? = null)

@Serializable
internal data class WireModelList(val data: List<JsonElement> = emptyList())
