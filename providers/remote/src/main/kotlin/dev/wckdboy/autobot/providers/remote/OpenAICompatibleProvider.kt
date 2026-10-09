package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.providers.remote.internal.WireChatRequest
import dev.wckdboy.autobot.providers.remote.internal.WireChunk
import dev.wckdboy.autobot.providers.remote.internal.WireErrorEnvelope
import dev.wckdboy.autobot.providers.remote.internal.WireFunctionCall
import dev.wckdboy.autobot.providers.remote.internal.WireFunctionDef
import dev.wckdboy.autobot.providers.remote.internal.WireJson
import dev.wckdboy.autobot.providers.remote.internal.WireMessage
import dev.wckdboy.autobot.providers.remote.internal.WireModelList
import dev.wckdboy.autobot.providers.remote.internal.WireStreamOptions
import dev.wckdboy.autobot.providers.remote.internal.WireTool
import dev.wckdboy.autobot.providers.remote.internal.WireToolCall
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Streaming client for any OpenAI-compatible `/chat/completions` endpoint using Server-Sent
 * Events (`stream=true`).
 *
 * - `delta.content` → [ChatEvent.ContentDelta]
 * - `delta.reasoning_content` (DeepSeek) or `delta.reasoning` (OpenRouter, Ollama) →
 *   [ChatEvent.ReasoningDelta]
 * - `delta.tool_calls[]` → [ChatEvent.ToolCallDelta] (fragments, keyed by `index`)
 * - `finish_reason` → [ChatEvent.Finish]
 * - `usage` (requested with `stream_options.include_usage`) → [ChatEvent.Usage]
 * - `data: [DONE]` or end of stream → [ChatEvent.Done]
 *
 * HTTP and transport failures are mapped to [ChatEvent.Error]; kill-switch refusals become
 * [ErrorKind.BLOCKED]. Only `Authorization`, `Accept` and `Content-Type` headers are sent; the
 * shared client replaces the User-Agent and strips identifying headers.
 *
 * @param baseUrl e.g. `https://api.deepseek.com` or `http://127.0.0.1:11434/v1`.
 * @param includeUsage whether to request a final usage chunk; disable for servers that reject it.
 */
open class OpenAICompatibleProvider(
    baseUrl: String,
    private val apiKey: Secret?,
    private val client: OkHttpClient,
    private val includeUsage: Boolean = true,
) : ChatProvider {

    private val base: HttpUrl? = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        val url = endpoint("chat/completions")
        if (url == null) {
            send(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid base URL"))
            return@channelFlow
        }
        val body = try {
            encodeRequest(request, includeUsage)
        } catch (e: IllegalArgumentException) {
            send(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid tool schema: ${e.message}"))
            return@channelFlow
        }
        val httpRequest = authorized(Request.Builder().url(url))
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
        val call = client.newCall(httpRequest)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        send(httpError(response))
                    } else {
                        readStream(response) { send(it) }
                    }
                }
            } catch (e: NetworkBlockedException) {
                send(ChatEvent.Error(ErrorKind.BLOCKED, e.message ?: "Blocked by network policy"))
            } catch (e: IOException) {
                if (!call.isCanceled() && isActive) {
                    send(ChatEvent.Error(ErrorKind.NETWORK, e.message ?: e.javaClass.simpleName))
                }
            }
            channel.close()
        }
        awaitClose { call.cancel() }
    }

    override suspend fun testConnection(): ConnectionTestResult = withContext(Dispatchers.IO) {
        val url = endpoint("models") ?: return@withContext ConnectionTestResult.Failure(
            ErrorKind.BAD_REQUEST,
            "Invalid base URL",
        )
        val request = authorized(Request.Builder().url(url)).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = httpError(response)
                    return@use ConnectionTestResult.Failure(error.kind, error.message)
                }
                val count = runCatching {
                    WireJson.decodeFromString(WireModelList.serializer(), response.body.string()).data.size
                }.getOrNull()
                ConnectionTestResult.Success(count)
            }
        } catch (e: NetworkBlockedException) {
            ConnectionTestResult.Failure(ErrorKind.BLOCKED, e.message ?: "Blocked by network policy")
        } catch (e: IOException) {
            ConnectionTestResult.Failure(ErrorKind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun endpoint(path: String): HttpUrl? = base?.newBuilder()?.addPathSegments(path)?.build()

    private fun authorized(builder: Request.Builder): Request.Builder {
        val key = apiKey?.takeUnless { it.isBlank }
        if (key != null) builder.header("Authorization", "Bearer ${key.reveal()}")
        return builder
    }

    private suspend fun readStream(response: Response, emit: suspend (ChatEvent) -> Unit) {
        val source = response.body.source()
        var finished = false
        while (!finished) {
            val line = source.readUtf8Line() ?: break
            if (line.isEmpty() || line.startsWith(":")) continue // SSE keep-alive / comment
            if (!line.startsWith("data:")) continue // ignore `event:`, `id:`, `retry:`
            val data = line.removePrefix("data:").trim()
            if (data == DONE) {
                finished = true
                continue
            }
            val chunk = try {
                WireJson.decodeFromString(WireChunk.serializer(), data)
            } catch (_: SerializationException) {
                emit(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed stream chunk"))
                return
            } catch (_: IllegalArgumentException) {
                emit(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed stream chunk"))
                return
            }
            chunk.error?.let { error ->
                emit(ChatEvent.Error(ErrorKind.SERVER, error.message ?: "Provider reported an error"))
                return
            }
            for (choice in chunk.choices) {
                val delta = choice.delta
                if (delta != null) {
                    val reasoning = delta.reasoningContent ?: delta.reasoning
                    if (!reasoning.isNullOrEmpty()) emit(ChatEvent.ReasoningDelta(reasoning))
                    if (!delta.content.isNullOrEmpty()) emit(ChatEvent.ContentDelta(delta.content))
                    delta.toolCalls?.forEach { call ->
                        emit(ChatEvent.ToolCallDelta(call.index, call.id, call.function?.name, call.function?.arguments.orEmpty()))
                    }
                }
                choice.finishReason?.let { emit(ChatEvent.Finish(it)) }
            }
            chunk.usage?.let { emit(ChatEvent.Usage(it.promptTokens, it.completionTokens, it.totalTokens)) }
        }
        emit(ChatEvent.Done)
    }

    companion object {
        /** Serializes [request] to the OpenAI wire format. Throws on a tool schema that is not a JSON object. */
        internal fun encodeRequest(request: ChatRequest, includeUsage: Boolean): String = WireJson.encodeToString(
            WireChatRequest.serializer(),
            WireChatRequest(
                model = request.model,
                messages = request.messages.map { it.toWire(request.echoReasoning) },
                stream = true,
                streamOptions = if (includeUsage) WireStreamOptions() else null,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                tools = request.tools.takeIf { it.isNotEmpty() }?.map { spec ->
                    val schema = runCatching { WireJson.parseToJsonElement(spec.parametersJson) }.getOrNull() as? JsonObject
                        ?: throw IllegalArgumentException("${spec.name}: parameters must be a JSON object")
                    WireTool(function = WireFunctionDef(spec.name, spec.description, schema))
                },
            ),
        )

        private fun ChatMessage.toWire(echoReasoning: Boolean): WireMessage = WireMessage(
            role = role.name.lowercase(),
            content = if (role == ChatRole.ASSISTANT && toolCalls.isNotEmpty() && content.isEmpty()) null else content,
            toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
                WireToolCall(it.id, function = WireFunctionCall(it.name, it.arguments))
            },
            toolCallId = toolCallId,
            reasoningContent = if (echoReasoning && role == ChatRole.ASSISTANT) reasoning else null,
        )

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val DONE = "[DONE]"
        private const val MAX_ERROR_MESSAGE = 300

        internal fun httpError(response: Response): ChatEvent.Error {
            val code = response.code
            val raw = runCatching { response.peekBody(16_384).string() }.getOrDefault("")
            val parsed = runCatching { WireJson.decodeFromString(WireErrorEnvelope.serializer(), raw) }.getOrNull()
            val detail = (parsed?.error?.message ?: parsed?.message)
                ?.take(MAX_ERROR_MESSAGE)
                ?.takeIf { it.isNotBlank() }
            val kind = when (code) {
                400, 404, 422 -> ErrorKind.BAD_REQUEST
                401, 403 -> ErrorKind.UNAUTHORIZED
                402 -> ErrorKind.QUOTA
                429 -> ErrorKind.RATE_LIMITED
                in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.HTTP
            }
            val fallback = when (kind) {
                ErrorKind.UNAUTHORIZED -> "Authentication failed — check the API key"
                ErrorKind.QUOTA -> "Insufficient balance or quota"
                ErrorKind.RATE_LIMITED -> "Rate limited — try again later"
                ErrorKind.SERVER -> "Provider server error"
                ErrorKind.BAD_REQUEST -> "Request rejected by provider"
                else -> "HTTP error"
            }
            return ChatEvent.Error(kind, "HTTP $code: ${detail ?: fallback}", httpCode = code)
        }
    }
}
