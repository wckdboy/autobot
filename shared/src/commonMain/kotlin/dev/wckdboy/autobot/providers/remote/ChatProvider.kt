package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
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
 * @param sessionKey an opaque per-conversation key (a hash, never the local id), sent only to
 *   providers that require one for routing and prompt caching (`ProviderPreset.sessionHeader`).
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val tools: List<ToolSpec> = emptyList(),
    val echoReasoning: Boolean = false,
    val sessionKey: String? = null,
    /**
     * The chat's Think switch (null: not set, the default applies). Each wire maps it to the
     * provider's own control, and only for models that have one ([RemoteThinking], [LocalLlmCodec]).
     */
    val thinking: ThinkingSetting? = null,
) {
    /** A plain chat: no tools offered (the agent's tools are off, or the model has none). */
    val quickChat: Boolean get() = tools.isEmpty()
}

/** Streaming output of [ChatProvider.streamChat]. */
sealed interface ChatEvent {
    data class ContentDelta(val text: String) : ChatEvent
    data class ReasoningDelta(val text: String) : ChatEvent

    /**
     * A fragment of a streamed tool call. Fragments sharing an [index] belong to one call:
     * [id] and [name] arrive first, [argumentsDelta] is appended across fragments.
     */
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String) : ChatEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?, val totalTokens: Int?, val reasoningTokens: Int? = null) : ChatEvent

    /** Reasoning progress counted by the engine (on-device): see [dev.wckdboy.autobot.agent.core.llm.StreamChunk.ReasoningStatus]. */
    data class ReasoningStatus(val tokens: Int, val done: Boolean = false, val stop: String? = null) : ChatEvent

    /** The choice finished: `stop`, `length`, `tool_calls`, `content_filter`, … */
    data class Finish(val reason: String) : ChatEvent
    data object Done : ChatEvent

    /**
     * @param code the on-device engine's error code (`LOAD_FAILED`, `CONTEXT_WINDOW_EXCEEDED`, …),
     *   so a local route can tell a model that never loaded from one that failed mid-answer.
     * @param retryAfterMs when the provider said to come back (`Retry-After`, or a usage
     *   limit's reset time, see [QuotaErrors]).
     */
    data class Error(
        val kind: ErrorKind,
        val message: String,
        val httpCode: Int? = null,
        val code: String? = null,
        val retryAfterMs: Long? = null,
    ) : ChatEvent

    /** Another model than the requested one is answering (on-device fallback); [fallback] says why. */
    data class ServedBy(val model: String, val fallback: String? = null) : ChatEvent
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

/**
 * Outcome of [ChatProvider.testConnection]. [Success.models] are the chat models found (empty
 * when the provider has no models endpoint: then [Success.modelCount] is null and nothing was
 * verified beyond the provider's documented list).
 */
sealed interface ConnectionTestResult {
    data class Success(val modelCount: Int?, val models: List<RemoteModel> = emptyList()) : ConnectionTestResult {
        /** `Connected · 42 models · gpt-6-luna, gpt-6-sol, gpt-6.1-sol…` */
        fun summary(): String = when (modelCount) {
            null -> "No models endpoint — the key is checked on the first message"
            else -> buildString {
                append("Connected · ").append(modelCount).append(if (modelCount == 1) " model" else " models")
                val first = models.take(PREVIEW).joinToString(", ") { it.id }
                if (first.isNotEmpty()) append(" · ").append(first)
                if (models.size > PREVIEW) append("…")
            }
        }

        private companion object {
            const val PREVIEW = 3
        }
    }

    data class Failure(val kind: ErrorKind, val message: String) : ConnectionTestResult

    companion object {
        /** A model list as a connection test (the models endpoint is the test). */
        fun of(result: ModelListResult, hasEndpoint: Boolean = true): ConnectionTestResult = when (result) {
            is ModelListResult.Success -> Success(if (hasEndpoint) result.models.size else null, result.models)
            is ModelListResult.Failure -> Failure(result.kind, result.message)
        }
    }
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

    /** The provider's chat models (see [ModelLists]); kill switch, route and audit log apply. */
    suspend fun listModels(): ModelListResult = ModelListResult.Failure(ErrorKind.BAD_REQUEST, "No model list")

    /**
     * A chat with [model] was opened: an on-device provider may load it now, so the first message
     * does not wait for the load. Best effort and quick to return; remote providers do nothing.
     */
    suspend fun prepare(model: String) = Unit
}
