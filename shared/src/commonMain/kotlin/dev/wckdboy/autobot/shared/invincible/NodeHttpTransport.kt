package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The platform refused to open a connection to the node (offline mode, kill switch). */
class NodeBlockedException(message: String) : Exception(message)

/**
 * An [HttpTransport] to a paired Invincible node: each request opens an encrypted session
 * ([InvincibleNode.session]) and speaks HTTP/1.1 inside it, so the shared [TransportChatProvider]
 * talks to "My node" exactly as to any OpenAI-compatible server. The host part of request URLs
 * is ignored; the node is the one whose key is pinned.
 *
 * [connect] opens the byte stream: the platform resolves the node's addresses, applies the kill
 * switch and the route (direct, Tor, SOCKS5), and throws [NodeBlockedException] when policy
 * refuses.
 */
class NodeHttpTransport(
    private val crypto: NodeCrypto,
    private val nodeKey: ByteArray,
    private val device: NodeKeyPair,
    private val connect: suspend () -> NodeLink,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Told the node's request id (`invincible-request-id`) of each answer made for an agent turn. */
    private val onRequestId: (suspend (TurnTrace, String) -> Unit)? = null,
    /** Turns that must not leave the node: their requests carry `invincible-private: 1`. */
    private val privateTurns: PrivateTurns? = null,
    /** The node's name and addresses, for the words a failure is shown in ([NodeErrors]). */
    private val node: String = "the node",
    private val addresses: List<String> = emptyList(),
    private val handshakeMillis: Long = NodeErrors.HANDSHAKE_MILLIS,
) : HttpTransport {

    override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
        var link: NodeLink? = null
        // The request's turn travels with it, so the connection is audited for that turn and
        // shows up in its privacy receipt.
        val job = scope.launch(request.trace ?: EmptyCoroutineContext) {
            try {
                val opened = PairedNode.open(addresses) { connect() }.also { link = it }
                val channel = try {
                    InvincibleNode.session(crypto, opened, nodeKey, device, handshakeMillis)
                } catch (e: NodeRefusedException) {
                    // This device was unpaired there: a refusal, not a network error (not retried).
                    sink.onResponse(403, emptyMap(), NodeErrors.unpairedBody(node))
                    return@launch
                }
                val private = request.trace?.let { privateTurns?.isPrivate(it) } == true
                channel.send(encode(request, private))
                var requestId: String? = null
                Http1Reader(channel).deliver(request.maxBytes, object : HttpSink by sink {
                    override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                        requestId = headers["invincible-request-id"]
                        sink.onResponse(status, headers, errorBody)
                    }
                })
                val trace = request.trace
                val id = requestId
                if (trace != null && id != null) onRequestId?.invoke(trace, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeBlockedException) {
                sink.onFailure(blocked = true, message = e.message ?: "Blocked by network policy")
            } catch (e: Exception) {
                sink.onFailure(blocked = false, message = failure(e))
            } finally {
                link?.close()
            }
        }
        return Cancellable {
            job.cancel()
            link?.close()
        }
    }

    /** [e] in words: the node unreachable, this phone offline, no handshake in time, the answer cut off. */
    private fun failure(e: Exception): String = when (e) {
        is NodeConnectException -> if (NodeErrors.isNoNetwork(e.message)) NodeErrors.NO_NETWORK else NodeErrors.unreachable(node, addresses)
        is NodeTimeoutException -> NodeErrors.timeout(node)
        is NodeDroppedException -> NodeErrors.dropped(node)
        else -> "$node: ${e.message ?: e::class.simpleName}"
    }

    companion object {
        /**
         * The request as HTTP/1.1, one exchange per session (`Connection: close`); [private] adds
         * `invincible-private: 1` (the node keeps it on its own models).
         */
        fun encode(request: HttpRequestSpec, private: Boolean = false): ByteArray {
            val target = request.url.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
            val body = request.body?.encodeToByteArray() ?: ByteArray(0)
            val head = buildString {
                append(request.method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("host: invincible\r\n")
                if (private) append("invincible-private: 1\r\n")
                for ((name, value) in request.headers) {
                    if (name.equals("host", true) || name.equals("connection", true) || name.equals("content-length", true)) continue
                    if (name.equals("invincible-private", true)) continue
                    require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value) { "header with a line break" }
                    append(name).append(": ").append(value).append("\r\n")
                }
                if (request.body != null) append("content-length: ").append(body.size).append("\r\n")
                append("connection: close\r\n\r\n")
            }
            return head.encodeToByteArray() + body
        }
    }
}

/** Reads one HTTP/1.1 response from a [NodeChannel] and hands it to an [HttpSink] as [HttpTransport] promises. */
internal class Http1Reader(private val channel: NodeChannel) {
    private var buffer = ByteArray(0)
    private var at = 0
    private var ended = false

    private suspend fun fill(): Boolean {
        if (ended) return false
        val chunk = try {
            channel.receive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ended = true
            return false
        }
        buffer = buffer.copyOfRange(at, buffer.size) + chunk
        at = 0
        return true
    }

    /** One line without its `\r\n` (or `\n`); null at the end of the stream. */
    private suspend fun line(limit: Int = 64 * 1024): String? {
        while (true) {
            val nl = (at until buffer.size).firstOrNull { buffer[it] == '\n'.code.toByte() }
            if (nl != null) {
                val end = if (nl > at && buffer[nl - 1] == '\r'.code.toByte()) nl - 1 else nl
                return buffer.decodeToString(at, end).also { at = nl + 1 }
            }
            check(buffer.size - at <= limit) { "line too long" }
            if (!fill()) return if (at < buffer.size) buffer.decodeToString(at, buffer.size).also { at = buffer.size } else null
        }
    }

    private suspend fun bytes(count: Int): ByteArray {
        while (buffer.size - at < count) if (!fill()) throw NodeDroppedException("the node closed the connection mid-answer")
        return buffer.copyOfRange(at, at + count).also { at += count }
    }

    /** The rest of the stream, as it comes. */
    private suspend fun rest(each: suspend (ByteArray) -> Unit) {
        if (at < buffer.size) each(buffer.copyOfRange(at, buffer.size)).also { at = buffer.size }
        while (fill()) {
            each(buffer.copyOfRange(at, buffer.size))
            at = buffer.size
        }
    }

    suspend fun deliver(maxBytes: Long, sink: HttpSink) {
        val first = line() ?: throw NodeDroppedException("the node closed the connection before answering")
        val status = first.split(' ').getOrNull(1)?.toIntOrNull() ?: error("no HTTP status from the node")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = line() ?: throw NodeDroppedException("headers cut off")
            if (h.isEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0) headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
        }
        val body = Body(this, headers)
        if (status !in 200..299) {
            val error = StringBuilder()
            body.forEach { if (error.length < ERROR_BODY) error.append(it.decodeToString()) }
            sink.onResponse(status, headers, error.take(ERROR_BODY).toString())
            return
        }
        sink.onResponse(status, headers, null)
        var total = 0L
        val pending = StringBuilder()
        body.forEach { chunk ->
            total += chunk.size
            check(total <= maxBytes) { "answer larger than $maxBytes bytes" }
            pending.append(chunk.decodeToString())
            while (true) {
                val nl = pending.indexOf('\n')
                if (nl < 0) break
                sink.onLine(pending.substring(0, nl).removeSuffix("\r"))
                pending.deleteRange(0, nl + 1)
            }
        }
        if (pending.isNotEmpty()) sink.onLine(pending.toString().removeSuffix("\r"))
        sink.onComplete()
    }

    /** The body framing: chunked, a content length, or to the end of the stream. */
    private class Body(private val reader: Http1Reader, private val headers: Map<String, String>) {
        suspend fun forEach(each: suspend (ByteArray) -> Unit) {
            val length = headers["content-length"]?.toLongOrNull()
            when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> while (true) {
                    // The stream ending where a chunk should start: the connection dropped, not a bad answer.
                    val line = reader.line() ?: throw NodeDroppedException("the answer was cut off")
                    val size = line.substringBefore(';').trim().toIntOrNull(16) ?: error("bad chunk size")
                    if (size == 0) {
                        while (reader.line()?.isNotEmpty() == true) {
                            // trailers: skipped
                        }
                        return
                    }
                    each(reader.bytes(size))
                    reader.line() // the CRLF after the chunk
                }
                length != null -> if (length > 0) each(reader.bytes(length.toInt()))
                else -> reader.rest(each)
            }
        }
    }

    private companion object {
        const val ERROR_BODY = 16 * 1024
    }
}
