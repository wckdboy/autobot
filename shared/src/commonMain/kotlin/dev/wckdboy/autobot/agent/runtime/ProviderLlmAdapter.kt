package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.providers.remote.ToolCall
import dev.wckdboy.autobot.providers.remote.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Decrypted image attachments for one model call. [openPlain] writes a short-lived plain copy and
 * returns its path; [discard] deletes it once the request is sent.
 */
interface ImageAttachments {
    suspend fun openPlain(id: String): String?
    fun discard(path: String)
}

/**
 * Bridges a configured [ChatProvider] (OpenAI-compatible SSE) to the agent's [LlmAdapter]
 * contract: one call is one attempt, failures are thrown as [LlmException].
 *
 * DeepSeek thinking mode requires the reasoning of the *current* tool-call turn to be sent back
 * (`reasoning_content`); older reasoning is never resent. Other providers never receive it.
 */
class ProviderLlmAdapter(
    override val provider: String,
    private val chat: ChatProvider,
    private val kind: ProviderKind,
    private val attachments: ImageAttachments? = null,
) : LlmAdapter {

    override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
        val echo = kind == ProviderKind.DEEPSEEK
        // Attachments are decrypted into short-lived files for this call only. Only the newest
        // [MAX_IMAGES] images are sent; older ones are noted in the text instead.
        val plain = mutableListOf<String>()
        try {
            val keep = call.messages.flatMap { it.images }.takeLast(MAX_IMAGES).toSet()
            val messages = call.messages.map { m ->
                if (m.images.isEmpty()) return@map m.toChat(echo)
                val files = m.images.filter { it in keep }.mapNotNull { id -> attachments?.openPlain(id)?.also { plain += it } }
                val dropped = m.images.size - files.size
                val note = if (dropped > 0) "\n[$dropped earlier image(s) not shown]" else ""
                m.toChat(echo).copy(content = m.text + note, images = files)
            }
            val request = ChatRequest(
                model = call.config.model,
                messages = messages,
                temperature = call.config.temperature,
                maxTokens = call.config.maxTokens,
                tools = call.tools.map { ToolSpec(it.name, it.description, it.parameters.toString()) },
                echoReasoning = echo,
            )
            chat.streamChat(request).collect { event ->
                when (event) {
                    is ChatEvent.ContentDelta -> emit(StreamChunk.TextDelta(event.text))
                    is ChatEvent.ReasoningDelta -> emit(StreamChunk.ReasoningDelta(event.text))
                    is ChatEvent.ToolCallDelta -> emit(StreamChunk.ToolCallDelta(event.index, event.id, event.name, event.argumentsDelta))
                    is ChatEvent.Usage -> emit(
                        StreamChunk.Usage(TokenUsage(event.promptTokens, event.completionTokens, event.totalTokens)),
                    )
                    is ChatEvent.Finish -> emit(StreamChunk.Finish(event.reason))
                    is ChatEvent.Error -> throw LlmException(event.toFailure())
                    is ChatEvent.ServedBy -> emit(StreamChunk.ServedBy(event.model, event.fallback))
                    ChatEvent.Done -> Unit
                }
            }
        } finally {
            plain.forEach { attachments?.discard(it) }
        }
    }

    override suspend fun resolveModel(model: String): ModelInfo = modelInfo(kind, model)

    private fun ModelMessage.toChat(echo: Boolean): ChatMessage = when (role) {
        ModelRole.SYSTEM -> ChatMessage(ChatRole.SYSTEM, text)
        ModelRole.USER -> ChatMessage(ChatRole.USER, text)
        ModelRole.TOOL -> ChatMessage(ChatRole.TOOL, text, toolCallId = toolCallId)
        ModelRole.ASSISTANT -> ChatMessage(
            ChatRole.ASSISTANT,
            text,
            toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) },
            reasoning = reasoning.takeIf { echo && inCurrentTurn },
        )
    }

    companion object {
        /** On-device context (KV cache memory is the limit on a phone). */
        const val LOCAL_CONTEXT = 8192

        /** Images sent per call; vision encoders are slow on a phone and cost context. */
        const val MAX_IMAGES = 4

        /** Best-effort context windows; unknown models get a conservative 64k. */
        fun modelInfo(kind: ProviderKind, model: String): ModelInfo {
            val m = model.lowercase()
            return when {
                kind == ProviderKind.LOCAL && SystemModels.isSystem(model) ->
                    ModelInfo(SystemModels.CONTEXT_TOKENS, defaultMaxTokens = SystemModels.MAX_OUTPUT_TOKENS, supportsTools = false)
                kind == ProviderKind.LOCAL -> ModelInfo(LOCAL_CONTEXT, defaultMaxTokens = 2048)
                "deepseek-v4" in m || "deepseek-flash" in m -> ModelInfo(1_000_000, supportsReasoning = true)
                m == "deepseek-reasoner" || "-r1" in m -> ModelInfo(128_000, supportsReasoning = true)
                m.startsWith("deepseek") || "/deepseek" in m -> ModelInfo(128_000)
                "qwen3" in m -> ModelInfo(128_000, supportsReasoning = true)
                kind == ProviderKind.OLLAMA -> ModelInfo(32_768)
                kind == ProviderKind.OPENROUTER -> ModelInfo(128_000)
                else -> ModelInfo(LlmAdapter.DEFAULT_CONTEXT_WINDOW)
            }
        }

        private val CONTEXT_OVERFLOW = Regex("context (length|window)|maximum context|too many tokens|prompt is too long", RegexOption.IGNORE_CASE)

        internal fun ChatEvent.Error.toFailure(): LlmFailure {
            val code = when (kind) {
                ErrorKind.BLOCKED -> LlmErrorCode.BLOCKED
                ErrorKind.UNAUTHORIZED -> LlmErrorCode.AUTH
                ErrorKind.RATE_LIMITED -> LlmErrorCode.RATE_LIMIT
                ErrorKind.QUOTA -> LlmErrorCode.QUOTA
                ErrorKind.BAD_REQUEST ->
                    if (CONTEXT_OVERFLOW.containsMatchIn(message)) LlmErrorCode.CONTEXT_WINDOW_EXCEEDED else LlmErrorCode.BAD_REQUEST
                ErrorKind.SERVER -> LlmErrorCode.SERVER
                ErrorKind.NETWORK -> LlmErrorCode.TRANSPORT
                ErrorKind.PROTOCOL -> LlmErrorCode.PROTOCOL
                ErrorKind.HTTP -> LlmErrorCode.UNKNOWN
            }
            return LlmFailure(code, message, httpCode)
        }
    }
}
