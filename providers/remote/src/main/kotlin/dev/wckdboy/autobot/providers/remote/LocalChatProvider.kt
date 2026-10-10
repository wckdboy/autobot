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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

        internal fun encode(request: ChatRequest, mmproj: String? = null): JsonObject = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    request.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", m.role.name.lowercase())
                                if (m.images.isEmpty()) {
                                    put("content", m.content)
                                } else {
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(buildJsonObject { put("type", "text"); put("text", m.content) })
                                            m.images.forEach { path -> add(buildJsonObject { put("type", "image"); put("path", path) }) }
                                        },
                                    )
                                }
                                if (m.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            m.toolCalls.forEach { c ->
                                                add(
                                                    buildJsonObject {
                                                        put("id", c.id)
                                                        put("type", "function")
                                                        put("function", buildJsonObject {
                                                            put("name", c.name)
                                                            put("arguments", c.arguments)
                                                        })
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                                m.toolCallId?.let { put("tool_call_id", it) }
                            },
                        )
                    }
                },
            )
            if (request.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        request.tools.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", t.name)
                                        put("description", t.description)
                                        put("parameters", WireJsonParser.parse(t.parametersJson))
                                    })
                                },
                            )
                        }
                    },
                )
            }
            mmproj?.let { put("mmproj", it) }
            request.temperature?.let { put("temperature", it) }
            put("max_tokens", request.maxTokens ?: 2048)
        }

        internal fun decodeResult(json: String): List<ChatEvent> {
            val o = runCatching { WireJsonParser.parse(json) as JsonObject }.getOrNull()
                ?: return listOf(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed engine result"))
            (o["error"] as? JsonObject)?.let { err ->
                val code = (err["code"] as? JsonPrimitive)?.contentOrNull
                val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "engine error"
                return when (code) {
                    "CANCELLED" -> emptyList()
                    "CONTEXT_WINDOW_EXCEEDED" -> listOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, "context length exceeded: $msg"))
                    else -> listOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, msg))
                }
            }
            val events = mutableListOf<ChatEvent>()
            (o["tool_calls"] as? JsonArray)?.forEachIndexed { i, c ->
                val co = c as? JsonObject ?: return@forEachIndexed
                events += ChatEvent.ToolCallDelta(
                    i,
                    (co["id"] as? JsonPrimitive)?.contentOrNull,
                    (co["name"] as? JsonPrimitive)?.contentOrNull,
                    (co["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
            (o["usage"] as? JsonObject)?.let { u ->
                val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull
                val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull
                events += ChatEvent.Usage(prompt, completion, if (prompt != null && completion != null) prompt + completion else null)
            }
            (o["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { events += ChatEvent.Finish(it) }
            events += ChatEvent.Done
            return events
        }
    }
}

/** Parses JSON text with the provider module's lenient settings. */
internal object WireJsonParser {
    fun parse(text: String) = dev.wckdboy.autobot.providers.remote.internal.WireJson.parseToJsonElement(text)
}
