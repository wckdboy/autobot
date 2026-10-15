package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.turnTrace
import dev.wckdboy.autobot.core.security.Secret
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Streaming client for a remote chat endpoint over OkHttp, in the dialect of its [preset] for the
 * requested model: any OpenAI-compatible `/chat/completions` endpoint (Server-Sent Events,
 * `stream=true`) or Anthropic's native `/messages`. Encoding, stream decoding, model lists and
 * error mapping live in the shared [ChatWire] / [ModelLists] / [QuotaErrors] (the iOS app runs
 * the same code); end of stream → [ChatEvent.Done].
 *
 * HTTP and transport failures are mapped to [ChatEvent.Error]; kill-switch refusals become
 * [ErrorKind.BLOCKED]. Only the wire's auth headers, `Accept`, `Content-Type` and, where the
 * provider requires one, an opaque session id ([ProviderSessions]) are sent; the shared client
 * replaces the User-Agent and strips identifying headers. The key goes to the provider's own
 * origin only.
 *
 * @param baseUrl e.g. `https://api.deepseek.com` or `http://127.0.0.1:11434/v1`.
 */
open class OpenAICompatibleProvider(
    baseUrl: String,
    private val apiKey: Secret?,
    private val client: OkHttpClient,
    private val preset: ProviderPreset = ProviderPresets.forKind(ProviderKind.OPENAI_COMPATIBLE),
) : ChatProvider {

    private val baseText: String = baseUrl.trim().trimEnd('/')
    private val base: HttpUrl? = baseText.toHttpUrlOrNull()

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        val wire = preset.wire(request.model)
        val url = endpoint(wire.chatPath)
        if (url == null) {
            send(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid base URL"))
            return@channelFlow
        }
        val body = try {
            wire.encode(request)
        } catch (e: IllegalArgumentException) {
            send(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid tool schema: ${e.message}"))
            return@channelFlow
        }
        val httpRequest = Request.Builder().url(url)
            .apply { wire.headers(apiKey?.takeUnless { it.isBlank }?.reveal()).forEach { (k, v) -> header(k, v) } }
            .apply { ProviderSessions.header(preset, request)?.let { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .turnTrace()
            .build()
        val call = client.newCall(httpRequest)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val text = errorBody(response)
                        send(QuotaErrors.refine(wire.httpError(response.code, text), response.code, text, response.header("Retry-After"), ProviderSessions.name(preset)))
                    } else {
                        readStream(wire, response) { send(it) }
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

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult.of(listModels(), hasEndpoint = preset.modelsApi != ModelsApi.NONE)

    override suspend fun listModels(): ModelListResult = withContext(Dispatchers.IO) {
        if (base == null) return@withContext ModelListResult.Failure(ErrorKind.BAD_REQUEST, "Invalid base URL")
        val spec = ModelLists.request(preset, baseText, apiKey?.takeUnless { it.isBlank }?.reveal())
            ?: return@withContext ModelLists.documented(preset)
        val url = spec.url.toHttpUrlOrNull() ?: return@withContext ModelListResult.Failure(ErrorKind.BAD_REQUEST, "Invalid models URL")
        val request = Request.Builder().url(url).apply { spec.headers.forEach { (k, v) -> header(k, v) } }.get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) response.body.source().let { it.request(MAX_MODELS_BYTES); it.buffer.readUtf8() } else errorBody(response)
                ModelLists.result(preset, response.code, body)
            }
        } catch (e: NetworkBlockedException) {
            ModelListResult.Failure(ErrorKind.BLOCKED, e.message ?: "Blocked by network policy")
        } catch (e: IOException) {
            ModelListResult.Failure(ErrorKind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun endpoint(path: String): HttpUrl? = base?.newBuilder()?.addPathSegments(path)?.build()

    private suspend fun readStream(wire: ChatWire, response: Response, emit: suspend (ChatEvent) -> Unit) {
        val source = response.body.source()
        val decoder = wire.decoder()
        while (!decoder.done) {
            val line = source.readUtf8Line() ?: break
            for (event in decoder.feed(line)) {
                emit(event)
                if (event is ChatEvent.Error) return
            }
        }
        emit(ChatEvent.Done)
    }

    companion object {
        /** Serializes [request] to the OpenAI wire format. Throws on a tool schema that is not a JSON object. */
        internal fun encodeRequest(request: ChatRequest, includeUsage: Boolean): String = OpenAiCodec.encodeRequest(request, includeUsage)

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Model lists are small; OpenRouter's (the largest) is well under 1 MiB. */
        private const val MAX_MODELS_BYTES = 8L * 1024 * 1024

        private fun errorBody(response: Response): String = runCatching { response.peekBody(16_384).string() }.getOrDefault("")

        internal fun httpError(response: Response): ChatEvent.Error = OpenAiCodec.httpError(response.code, errorBody(response))
    }
}
