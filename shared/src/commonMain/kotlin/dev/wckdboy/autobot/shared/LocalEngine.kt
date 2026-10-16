package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.LocalChatRouter
import dev.wckdboy.autobot.providers.remote.LocalLlmCodec
import dev.wckdboy.autobot.providers.remote.LocalModelSize
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemAvailability
import dev.wckdboy.autobot.providers.remote.SystemLlm
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.providers.remote.SystemPrompt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

/** Streamed output of one on-device chat, called serially. Exactly one of [onResult] / [onFailure] ends it. */
interface LocalSink {
    fun onContent(text: String)
    fun onReasoning(text: String)

    /** Reasoning progress from the engine, JSON `{tokens, done, stop?}` (AUTOBOT_LLAMA_DELTA_REASONING_STATUS). */
    fun onReasoningStatus(json: String)

    /** The engine's final result JSON (see [LocalLlmCodec.decodeResult]). */
    fun onResult(json: String)
    fun onFailure(message: String)
}

/**
 * The host's on-device engines: llama.cpp (iOS: `AutobotEngines.LlamaEngine` on Metal) and the
 * OS's own model (iOS 26+: Apple's Foundation Models). The host resolves an installed model id to
 * its files, context length and compute backend; which model runs is decided here
 * ([LocalChatRouter]).
 */
interface LocalEngine {
    /** Starts one chat on [modelId]; null when that model is not installed and ready. */
    fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable?

    /** The vision projector of [modelId], if it has one. */
    fun mmproj(modelId: String): String?

    /**
     * The chat template in [modelId]'s GGUF (`tokenizer.chat_template`, read from the file header
     * without loading the model), or null: says whether the model reasons ([ThinkingSupport]).
     */
    fun chatTemplate(modelId: String): String? = null

    /** Installed, ready chat models (for the connection test). */
    fun readyModelCount(): Int

    /** Whether [modelId] is installed and fits this device. */
    fun modelState(modelId: String): LocalModelState

    /** The OS's model ([SystemModels]); null when this OS has none. */
    fun systemModel(): SystemModelStatus?

    /**
     * Starts one answer from the OS's model to a [SystemPrompt] (JSON); null when it cannot run.
     * Ends with [LocalSink.onResult] in the [LocalLlmCodec] result shape (usage and finish reason,
     * or `{error:{code,message}}`).
     */
    fun systemChat(promptJson: String, sink: LocalSink): Cancellable?
}

/** Chat on the device through a host [LocalEngine]: the iOS counterpart of `LocalChatProvider`. */
class LocalEngineChatProvider(private val engine: LocalEngine) : ChatProvider {

    private val system = object : SystemLlm {
        override val id: String get() = engine.systemModel()?.id ?: SystemModels.APPLE

        override suspend fun status(): SystemModelStatus = engine.systemModel()
            ?: SystemModelStatus(SystemModels.APPLE, SystemModels.title(SystemModels.APPLE), SystemAvailability.UNAVAILABLE, "needs iOS 26 or newer")

        override fun chat(prompt: SystemPrompt): Flow<ChatEvent> = sinkFlow("${SystemModels.title(id)} is not available") { sink ->
            engine.systemChat(PromptJson.encodeToString(SystemPrompt.serializer(), prompt), sink)
        }
    }

    private val router = LocalChatRouter(
        modelState = { engine.modelState(it) },
        system = system,
        gguf = { request ->
            sinkFlow("Model '${request.model}' is not installed on this device (Models tab)") { sink ->
                engine.chat(request.model, LocalLlmCodec.encode(request, engine.mmproj(request.model), LocalModelSize.paramsB(request.model)).toString(), sink)
            }
        },
    )

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = router.stream(request)

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult.Success(engine.readyModelCount() + if (engine.systemModel()?.usable == true) 1 else 0)

    private companion object {
        val PromptJson = Json { encodeDefaults = true }

        /** One [LocalSink] call as a flow; [unavailable] is the error when [start] returns null. */
        fun sinkFlow(unavailable: String, start: (LocalSink) -> Cancellable?): Flow<ChatEvent> = callbackFlow {
            val call = start(
                object : LocalSink {
                    override fun onContent(text: String) {
                        trySend(ChatEvent.ContentDelta(text))
                    }

                    override fun onReasoning(text: String) {
                        trySend(ChatEvent.ReasoningDelta(text))
                    }

                    override fun onReasoningStatus(json: String) {
                        LocalLlmCodec.decodeStatus(json)?.let { trySend(it) }
                    }

                    override fun onResult(json: String) {
                        LocalLlmCodec.decodeResult(json).forEach { trySend(it) }
                        close()
                    }

                    override fun onFailure(message: String) {
                        trySend(ChatEvent.Error(ErrorKind.BAD_REQUEST, message))
                        close()
                    }
                },
            )
            if (call == null) {
                trySend(ChatEvent.Error(ErrorKind.BAD_REQUEST, unavailable))
                close()
            }
            awaitClose { call?.cancel() }
        }.buffer(Channel.UNLIMITED)
    }
}
