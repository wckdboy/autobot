package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.runtime.WebFetchException
import dev.wckdboy.autobot.agent.runtime.WebFetcher
import dev.wckdboy.autobot.agent.runtime.WebResponse
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatWire
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ModelLists
import dev.wckdboy.autobot.providers.remote.ModelsApi
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.ProviderSessions
import dev.wckdboy.autobot.providers.remote.QuotaErrors
import dev.wckdboy.autobot.shared.invincible.PrivateWork
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * The host side of networking for apps that bring their own HTTP stack (iOS: URLSession behind
 * the kill switch, route resolver and audit log). The host only moves bytes; request encoding
 * and SSE decoding stay in the shared codec.
 */

/** A running host operation. */
fun interface Cancellable {
    fun cancel()
}

/**
 * One HTTP request for the host to send. [route] is the provider's routing override
 * (`INHERIT`, `DIRECT`, `TOR`, `SOCKS5`); [tag] labels it in the audit log. The host must not
 * follow redirects and reads at most [maxBytes] of the body. [trace] is the agent turn the request
 * is made for (set by [traced]); the host records it in the audit log for privacy receipts.
 */
class HttpRequestSpec(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val tag: String,
    val route: String = "INHERIT",
    val maxBytes: Long = Long.MAX_VALUE,
    val trace: TurnTrace? = null,
) {
    /**
     * Made under [PrivateWork] (set by [traced]): a paired node's transport sends it with
     * `invincible-private: 1` ([dev.wckdboy.autobot.shared.invincible.NodeHttpTransport]); every
     * other transport ignores it.
     */
    var markedPrivate: Boolean = false
        private set

    /**
     * This request tagged with the calling coroutine's [TurnTrace] (unchanged outside a turn), and
     * [markedPrivate] when that coroutine runs under [PrivateWork].
     */
    suspend fun traced(): HttpRequestSpec {
        val current = TurnTrace.current()
        val private = markedPrivate || PrivateWork.active()
        if (current == null && private == markedPrivate) return this
        return HttpRequestSpec(method, url, headers, body, tag, route, maxBytes, current ?: trace).also { it.markedPrivate = private }
    }
}

/**
 * Callbacks for one request, called serially. [onResponse] comes first with the status and
 * headers (lower-case names); for a non-2xx status it carries up to 16 KiB of the body and no
 * lines follow. A 2xx body is delivered line by line (without line terminators) and ends with
 * [onComplete]. [onFailure] replaces all of these when no response arrived; [blocked] means the
 * network policy refused the request before any I/O.
 */
interface HttpSink {
    fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?)
    fun onLine(line: String)
    fun onComplete()
    fun onFailure(blocked: Boolean, message: String)

    /**
     * Like [onFailure] (no response, not blocked), for a failure trying again would not fix: a
     * paired node that is down, silent or cut the answer off ([dev.wckdboy.autobot.shared.invincible.NodeHttpTransport]).
     * A chat shows it once instead of retrying.
     */
    fun onFinalFailure(message: String) = onFailure(blocked = false, message = message)
}

interface HttpTransport {
    fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable
}

/**
 * A remote chat provider over a host [HttpTransport] (the iOS counterpart of the Android
 * `OpenAICompatibleProvider`, with the same codecs): OpenAI chat completions or Anthropic
 * Messages, by the [preset]'s wire for the requested model. Usage-limit refusals are told from
 * throttling by [QuotaErrors].
 */
class TransportChatProvider(
    baseUrl: String,
    private val apiKey: String?,
    private val transport: HttpTransport,
    private val tag: String,
    private val route: String = "INHERIT",
    private val preset: ProviderPreset = ProviderPresets.forKind(ProviderKind.OPENAI_COMPATIBLE),
) : ChatProvider {
    private val base: String? = baseUrl.trim().trimEnd('/').takeIf { BASE.matches(it) }

    private fun headers(wire: ChatWire, accept: String, request: ChatRequest? = null): Map<String, String> = buildMap {
        put("Accept", accept)
        put("Content-Type", "application/json; charset=utf-8")
        putAll(wire.headers(apiKey))
        request?.let { r -> ProviderSessions.header(preset, r)?.let { (k, v) -> put(k, v) } }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val wire = preset.wire(request.model)
        val body = when {
            base == null -> null.also { trySend(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid base URL")) }
            else -> try {
                wire.encode(request)
            } catch (e: IllegalArgumentException) {
                null.also { trySend(ChatEvent.Error(ErrorKind.BAD_REQUEST, "Invalid tool schema: ${e.message}")) }
            }
        }
        if (body == null) {
            close()
            awaitClose()
            return@callbackFlow
        }
        val decoder = wire.decoder()
        var failed = false
        val call = transport.send(
            HttpRequestSpec("POST", "$base/${wire.chatPath}", headers(wire, "text/event-stream", request), body, tag, route).traced(),
            object : HttpSink {
                override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                    if (status in 200..299) return
                    failed = true
                    val text = errorBody.orEmpty()
                    trySend(QuotaErrors.refine(wire.httpError(status, text), status, text, headers, ProviderSessions.name(preset)))
                    close()
                }

                override fun onLine(line: String) {
                    if (failed || decoder.done) return
                    for (event in decoder.feed(line)) {
                        trySend(event)
                        if (event is ChatEvent.Error) {
                            failed = true
                            close()
                            return
                        }
                    }
                }

                override fun onComplete() {
                    if (!failed) trySend(ChatEvent.Done)
                    close()
                }

                override fun onFailure(blocked: Boolean, message: String) {
                    trySend(ChatEvent.Error(if (blocked) ErrorKind.BLOCKED else ErrorKind.NETWORK, message))
                    close()
                }

                override fun onFinalFailure(message: String) {
                    trySend(ChatEvent.Error(ErrorKind.NETWORK, message, retryable = false))
                    close()
                }
            },
        )
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    override suspend fun testConnection(): ConnectionTestResult =
        ConnectionTestResult.of(listModels(), hasEndpoint = preset.modelsApi != ModelsApi.NONE)

    override suspend fun listModels(): ModelListResult {
        val base = base ?: return ModelListResult.Failure(ErrorKind.BAD_REQUEST, "Invalid base URL")
        val request = ModelLists.request(preset, base, apiKey) ?: return ModelLists.documented(preset)
        val response = fetchAll(transport, HttpRequestSpec("GET", request.url, request.headers, null, tag, route, MAX_MODELS_BYTES))
        return when {
            response.blocked -> ModelListResult.Failure(ErrorKind.BLOCKED, response.failure ?: "Blocked by network policy")
            response.failure != null -> ModelListResult.Failure(ErrorKind.NETWORK, response.failure)
            else -> ModelLists.result(preset, response.status, response.body)
        }
    }

    private companion object {
        val BASE = Regex("(?i)https?://[^\\s/?#]+[^\\s?#]*")
        const val MAX_MODELS_BYTES = 8L * 1024 * 1024
    }
}

/** A whole (small) response collected from a [HttpTransport]. */
internal class CollectedResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val failure: String? = null,
    val blocked: Boolean = false,
)

internal suspend fun fetchAll(transport: HttpTransport, spec: HttpRequestSpec): CollectedResponse {
    val request = spec.traced()
    return suspendCancellableCoroutine { cont ->
        val sink = object : HttpSink {
            private var status = 0
            private var headers = emptyMap<String, String>()
            private val body = StringBuilder()
            private var done = false

            private fun finish(response: CollectedResponse) {
                if (done) return
                done = true
                cont.resume(response)
            }

            override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                this.status = status
                this.headers = headers
                if (status !in 200..299) finish(CollectedResponse(status, headers, errorBody.orEmpty()))
            }

            override fun onLine(line: String) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line)
            }

            override fun onComplete() = finish(CollectedResponse(status, headers, body.toString()))

            override fun onFailure(blocked: Boolean, message: String) =
                finish(CollectedResponse(0, emptyMap(), "", failure = message, blocked = blocked))
        }
        val call = transport.send(request, sink)
        cont.invokeOnCancellation { call.cancel() }
    }
}

/** `web_fetch` over a host [HttpTransport]. */
class TransportWebFetcher(private val transport: HttpTransport) : WebFetcher {
    override suspend fun get(url: String, accept: String, maxBytes: Long): WebResponse {
        val response = fetchAll(transport, HttpRequestSpec("GET", url, mapOf("Accept" to accept), null, "agent:web_fetch", maxBytes = maxBytes))
        if (response.failure != null) throw WebFetchException(if (response.blocked) "BLOCKED" else "TRANSPORT", response.failure)
        val body = if (response.status in 200..299) response.body else ""
        return WebResponse(response.status, response.headers["location"], response.headers["content-type"], body)
    }
}
