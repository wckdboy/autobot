package dev.wckdboy.autobot.agent.core.llm

import dev.wckdboy.autobot.agent.core.ServiceKey
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

enum class ModelRole { SYSTEM, USER, ASSISTANT, TOOL }

/** A request-history message derived from the session log. */
data class ModelMessage(
    val role: ModelRole,
    val text: String,
    val reasoning: String? = null,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Assistant message after the latest user prompt (adapters may echo its reasoning). */
    val inCurrentTurn: Boolean = false,
)

/** Function schema sent to the model. */
data class ToolSchema(val name: String, val description: String, val parameters: JsonObject)

enum class ReasoningEffort { OFF, LOW, HIGH, MAX }

/** Which adapter and model a call goes to, plus sampling knobs. */
data class LlmCallConfig(
    val provider: String,
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: ReasoningEffort? = null,
)

data class LlmCall(
    val config: LlmCallConfig,
    val messages: List<ModelMessage>,
    val tools: List<ToolSchema>,
)

/** Normalized streaming output (DSH `StreamChunk`). Usage precedes finish; nothing follows finish. */
sealed interface StreamChunk {
    data class TextDelta(val text: String) : StreamChunk
    data class ReasoningDelta(val text: String) : StreamChunk

    /** Fragments with the same [index] form one call; [argumentsDelta] concatenates to raw JSON. */
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String) : StreamChunk
    data class Usage(val usage: TokenUsage) : StreamChunk
    data class Finish(val reason: String) : StreamChunk
}

enum class LlmErrorCode(val retryable: Boolean) {
    EMPTY_RESPONSE(true),
    RATE_LIMIT(true),
    SERVER(true),
    TIMEOUT(true),
    TRANSPORT(true),
    AUTH(false),
    QUOTA(false),
    BAD_REQUEST(false),
    CONTEXT_WINDOW_EXCEEDED(false),

    /** Refused by the local network policy (kill switch). Never retried. */
    BLOCKED(false),
    PROTOCOL(false),
    UNKNOWN(false),
}

data class LlmFailure(val code: LlmErrorCode, val message: String, val status: Int? = null, val retryAfterMs: Long? = null)

class LlmException(val failure: LlmFailure) : Exception(failure.message)

data class ModelInfo(
    val contextWindow: Int,
    val defaultMaxTokens: Int? = null,
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = false,
)

/**
 * One provider route. [stream] is a single attempt (no internal retries); failures are thrown
 * as [LlmException]. Collecting is the request; cancelling the collector cancels it.
 */
interface LlmAdapter {
    val provider: String
    fun stream(call: LlmCall): Flow<StreamChunk>
    suspend fun resolveModel(model: String): ModelInfo = ModelInfo(contextWindow = DEFAULT_CONTEXT_WINDOW)

    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 65_536
    }
}

/** `ctx.llm`: adapters by provider name. */
class LlmService {
    private val adapters = java.util.concurrent.ConcurrentHashMap<String, LlmAdapter>()
    private var resolver: (suspend (String) -> LlmAdapter?)? = null

    fun register(adapter: LlmAdapter): dev.wckdboy.autobot.agent.core.Disposable {
        adapters[adapter.provider] = adapter
        return dev.wckdboy.autobot.agent.core.Disposable { adapters.remove(adapter.provider, adapter) }
    }

    /** Fallback used for providers that are created on demand (e.g. from stored provider rows). */
    fun setResolver(resolve: suspend (String) -> LlmAdapter?): dev.wckdboy.autobot.agent.core.Disposable {
        resolver = resolve
        return dev.wckdboy.autobot.agent.core.Disposable { if (resolver === resolve) resolver = null }
    }

    suspend fun adapter(provider: String): LlmAdapter {
        if (provider.isBlank()) throw LlmException(LlmFailure(LlmErrorCode.BAD_REQUEST, "No model provider selected — add one under SYSTEM › Model providers"))
        return adapters[provider] ?: resolver?.invoke(provider)
            ?: throw LlmException(LlmFailure(LlmErrorCode.BAD_REQUEST, "Model provider '$provider' no longer exists"))
    }

    companion object {
        val Key = ServiceKey<LlmService>("llm")
    }
}
