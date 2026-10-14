package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.llama.EngineDiedException
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.llama.LocalLlmEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * Chat on this phone through the llama.cpp engine (isolated `:llm` process, Binder IPC).
 * Nothing touches the network. [ChatRequest.model] is an installed model id; tool calling uses
 * the model's own chat template, so agent sessions work offline.
 */
class LocalChatProvider(
    private val library: ModelLibrary,
    private val engine: LocalLlm,
    private val compute: ComputeProfile,
    private val contextLength: Int = DEFAULT_CONTEXT,
) : ChatProvider {

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        val model = library.get(request.model)
        val path = model?.takeIf { it.isReady && it.engine == EngineKind.LLAMA }?.paths?.get(FileRole.MODEL)
        if (path == null) {
            emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Model '${request.model}' is not installed on this phone (Models tab)"))
            return@flow
        }
        val nCtx = model.manifest.recommended?.contextLength?.coerceAtMost(MAX_CONTEXT) ?: contextLength
        val backend = compute.resolve(model).id
        engine.chat(path, nCtx, backend, encode(request, model.paths[FileRole.MMPROJ]).toString()).collect { event ->
            when (event) {
                is LocalLlmEvent.Content -> emit(ChatEvent.ContentDelta(event.text))
                is LocalLlmEvent.Reasoning -> emit(ChatEvent.ReasoningDelta(event.text))
                is LocalLlmEvent.Status -> Unit
                is LocalLlmEvent.Result -> decodeResult(event.json).forEach { emit(it) }
            }
        }
    }.catch { e ->
        val message = if (e is EngineDiedException) {
            "The on-device engine stopped (likely out of memory). Try a smaller model or shorter context."
        } else {
            e.message ?: e.javaClass.simpleName
        }
        emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, message))
    }

    override suspend fun testConnection(): ConnectionTestResult = try {
        engine.systemInfo()
        ConnectionTestResult.Success(library.all().count { it.isReady && it.engine == EngineKind.LLAMA })
    } catch (e: Exception) {
        ConnectionTestResult.Failure(ErrorKind.SERVER, e.message ?: "Engine unavailable")
    }

    companion object {
        const val DEFAULT_CONTEXT = 8192
        private const val MAX_CONTEXT = 32768

        internal fun encode(request: ChatRequest, mmproj: String? = null): JsonObject = LocalLlmCodec.encode(request, mmproj)

        internal fun decodeResult(json: String): List<ChatEvent> = LocalLlmCodec.decodeResult(json)
    }
}
