package dev.wckdboy.autobot.providers.remote

import kotlinx.coroutines.flow.Flow

enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** A function call requested by the model. [arguments] is the raw JSON object text. */
data class ToolCall(val id: String, val name: String, val arguments: String)

/**
 * One conversation message.
 *
 * @param toolCalls calls requested by an [ChatRole.ASSISTANT] message.
 * @param toolCallId the call a [ChatRole.TOOL] message answers.
 * @param reasoning an assistant's reasoning trace. Only sent back when the request sets
 *   [ChatRequest.echoReasoning] (DeepSeek thinking mode inside a tool-call turn).
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String? = null,
    /** Plain image files to show the model (user messages only; vision models). */
    val images: List<String> = emptyList(),
)

/** A function the model may call. [parametersJson] is a JSON Schema object, as text. */
data class ToolSpec(val name: String, val description: String, val parametersJson: String)

/**
 * A provider-agnostic chat completion request. Contains only conversation content: no device,
 * user or installation identifiers are ever attached.
 *
 * @param echoReasoning send [ChatMessage.reasoning] back as `reasoning_content` on assistant
 *   messages. DeepSeek requires this for the assistant messages of the current tool-call turn.
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<ToolSpec> = emptyList(),
    val echoReasoning: Boolean = false,
)

/** Streaming output of [ChatProvider.streamChat]. */
sealed interface ChatEvent {
    data class ContentDelta(val text: String) : ChatEvent
    data class ReasoningDelta(val text: String) : ChatEvent

    /**
     * A fragment of a streamed tool call. Fragments sharing an [index] belong to one call:
     * [id] and [name] arrive first, [argumentsDelta] is appended across fragments.
     */
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String) : ChatEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?) : ChatEvent

    /** The choice finished: `stop`, `length`, `tool_calls`, `content_filter`, … */
    data class Finish(val reason: String) : ChatEvent
    data object Done : ChatEvent
    data class Error(val kind: ErrorKind, val message: String, val httpCode: Int? = null) : ChatEvent
}

enum class ErrorKind {
    /** Refused locally by the kill switch / network policy. */
    BLOCKED,
    UNAUTHORIZED,
    RATE_LIMITED,
    QUOTA,
    BAD_REQUEST,
    SERVER,
    NETWORK,
    PROTOCOL,
    HTTP,
}

/** Outcome of [ChatProvider.testConnection]. */
sealed interface ConnectionTestResult {
    data class Success(val modelCount: Int?) : ConnectionTestResult
    data class Failure(val kind: ErrorKind, val message: String) : ConnectionTestResult
}

/** A remote (or loopback) chat model endpoint. */
interface ChatProvider {
    /**
     * Streams a completion. The flow is cold; collecting starts the HTTP call and cancelling the
     * collector cancels it. Failures are emitted as [ChatEvent.Error] rather than thrown.
     */
    fun streamChat(request: ChatRequest): Flow<ChatEvent>

    /** Lightweight authenticated request (`GET /models`) to verify reachability and credentials. */
    suspend fun testConnection(): ConnectionTestResult
}
