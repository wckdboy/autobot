package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.network.NetworkBlockedException
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
 * Streaming client for any OpenAI-compatible `/chat/completions` endpoint using Server-Sent
 * Events (`stream=true`). The wire format and stream decoding live in the shared [OpenAiCodec] /
 * [SseDecoder] (the iOS app uses the same code); `data: [DONE]` or end of stream →
 * [ChatEvent.Done].
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
                val count = OpenAiCodec.modelCount(response.body.string())
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
        val decoder = SseDecoder()
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

        internal fun httpError(response: Response): ChatEvent.Error =
            OpenAiCodec.httpError(response.code, runCatching { response.peekBody(16_384).string() }.getOrDefault(""))
    }
}
