package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.Fit
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.llama.EngineDiedException
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.llama.LocalLlmEvent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chat on this phone through the llama.cpp engine (isolated `:llm` process, Binder IPC).
 * Nothing touches the network. [ChatRequest.model] is an installed model id; tool calling uses
 * the model's own chat template, so agent sessions work offline.
 *
 * When the requested model is missing, too big or fails to load, the OS's own model ([system],
 * only in builds that include one) answers instead; [LocalChatRouter] decides.
 */
class LocalChatProvider(
    private val library: ModelLibrary,
    private val engine: LocalLlm,
    private val compute: ComputeProfile,
    private val system: SystemLlm? = null,
    private val fit: (InstalledModel) -> Fit = { Fit.GOOD },
    private val contextLength: Int = DEFAULT_CONTEXT,
) : ChatProvider {

    private val router = LocalChatRouter(
        modelState = { id -> modelState(library.get(id), fit) },
        system = system,
        gguf = ::streamGguf,
    )

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = router.stream(request)

    private fun streamGguf(request: ChatRequest): Flow<ChatEvent> = flow {
        val model = library.get(request.model)
        val path = model?.takeIf { it.isLocalChat }?.paths?.get(FileRole.MODEL)
        if (path == null) {
            emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Model '${request.model}' is not installed on this phone (Models tab)"))
            return@flow
        }
        if (!File(path).isFile || model.paths[FileRole.MMPROJ]?.let { !File(it).isFile } == true) {
            emit(
                ChatEvent.Error(
                    ErrorKind.BAD_REQUEST,
                    "The files of ${model.title} are missing (deleted outside the app). Download it again in the Models tab.",
                ),
            )
            return@flow
        }
        val backend = compute.resolve(model).id
        engine.chat(path, contextOf(model), backend, encode(request, model.paths[FileRole.MMPROJ]).toString()).collect { event ->
            when (event) {
                is LocalLlmEvent.Content -> emit(ChatEvent.ContentDelta(event.text))
                is LocalLlmEvent.Reasoning -> emit(ChatEvent.ReasoningDelta(event.text))
                is LocalLlmEvent.Status -> Unit
                is LocalLlmEvent.Result -> {
                    // Where the model actually loaded (an "auto" or failed accelerator falls back): the route tags show it.
                    backendOf(event.json)?.let { compute.noteRan(model.id, it) }
                    decodeResult(event.json).forEach { emit(it) }
                }
            }
        }
    }.catch { e ->
        if (e is EngineDiedException) {
            emit(
                ChatEvent.Error(
                    ErrorKind.BAD_REQUEST,
                    "The on-device engine stopped (likely out of memory). Try a smaller model or shorter context.",
                    code = "ENGINE_DIED",
                ),
            )
        } else {
            emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Same context length as [streamGguf] uses: a different one would load the model again. */
    private fun contextOf(model: InstalledModel): Int =
        model.manifest.recommended?.contextLength?.coerceAtMost(MAX_CONTEXT) ?: contextLength

    /** Loads [model] in the engine ahead of the first message, when it is installed and fits. */
    override suspend fun prepare(model: String) {
        val installed = library.get(model) ?: return
        if (modelState(installed, fit) != LocalModelState.READY) return
        val path = installed.paths[FileRole.MODEL]?.takeIf { File(it).isFile } ?: return
        try {
            engine.prepare(path, contextOf(installed), compute.resolve(installed).id)
        } catch (_: android.os.RemoteException) {
            // The engine is not up: the first message starts it and loads the model as before.
        }
    }

    override suspend fun testConnection(): ConnectionTestResult {
        val systemReady = system?.let { runCatching { it.status() }.getOrNull()?.usable } == true
        return try {
            engine.systemInfo()
            ConnectionTestResult.Success(library.all().count { it.isLocalChat } + if (systemReady) 1 else 0)
        } catch (e: Exception) {
            if (systemReady) ConnectionTestResult.Success(1) else ConnectionTestResult.Failure(ErrorKind.SERVER, e.message ?: "Engine unavailable")
        }
    }

    companion object {
        const val DEFAULT_CONTEXT = 8192
        private const val MAX_CONTEXT = 32768

        internal fun encode(request: ChatRequest, mmproj: String? = null): JsonObject = LocalLlmCodec.encode(request, mmproj)

        internal fun decodeResult(json: String): List<ChatEvent> = LocalLlmCodec.decodeResult(json)

        /** The engine's `usage.backend` ("cpu", "gpu", "npu") in a result, or null. */
        internal fun backendOf(json: String): Backend? = runCatching {
            Json.parseToJsonElement(json).jsonObject["usage"]?.jsonObject?.get("backend")?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.let(Backend::of)?.takeIf { it != Backend.AUTO }

        /** Whether an installed model can run here, by the shared fit rules. */
        internal fun modelState(model: InstalledModel?, fit: (InstalledModel) -> Fit): LocalModelState = when {
            model == null || !model.isLocalChat || model.paths[FileRole.MODEL] == null -> LocalModelState.NOT_INSTALLED
            fit(model) == Fit.TOO_BIG -> LocalModelState.TOO_BIG
            else -> LocalModelState.READY
        }
    }
}
