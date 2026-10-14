package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.LocalLlmCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Streamed output of one on-device chat, called serially. Exactly one of [onResult] / [onFailure] ends it. */
interface LocalSink {
    fun onContent(text: String)
    fun onReasoning(text: String)

    /** The engine's final result JSON (see [LocalLlmCodec.decodeResult]). */
    fun onResult(json: String)
    fun onFailure(message: String)
}

/**
 * The host's on-device llama.cpp engine (iOS: `AutobotEngines.LlamaEngine` on Metal). The host
 * resolves an installed model id to its files, context length and compute backend.
 */
interface LocalEngine {
    /** Starts one chat on [modelId]; null when that model is not installed and ready. */
    fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable?

    /** The vision projector of [modelId], if it has one. */
    fun mmproj(modelId: String): String?

    /** Installed, ready chat models (for the connection test). */
    fun readyModelCount(): Int
}

/** Chat on the device through a host [LocalEngine]: the iOS counterpart of `LocalChatProvider`. */
class LocalEngineChatProvider(private val engine: LocalEngine) : ChatProvider {

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val json = LocalLlmCodec.encode(request, engine.mmproj(request.model)).toString()
        val call = engine.chat(
            request.model,
            json,
            object : LocalSink {
                override fun onContent(text: String) {
                    trySend(ChatEvent.ContentDelta(text))
                }

                override fun onReasoning(text: String) {
                    trySend(ChatEvent.ReasoningDelta(text))
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
            trySend(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Model '${request.model}' is not installed on this device (Models tab)"))
            close()
        }
        awaitClose { call?.cancel() }
    }.buffer(Channel.UNLIMITED)

    override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success(engine.readyModelCount())
}
