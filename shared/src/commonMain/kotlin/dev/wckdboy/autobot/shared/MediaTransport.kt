package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.TurnTrace
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Two additions to the host transport for media backends (ComfyUI): whole binary bodies (image
 * uploads and downloads, which the line-based [HttpTransport] cannot carry) and WebSockets
 * (progress events and preview frames). Same rules as [HttpTransport]: the host applies the kill
 * switch, the request's route, the header scrub and the cleartext rules (`ws://` counts as
 * cleartext, `wss://` as TLS), never follows redirects, and writes an audit entry (the WebSocket
 * upgrade is one entry; the bytes of the messages received are added to it when the socket ends).
 */

/**
 * Callbacks for one [BinaryHttpTransport.exchange]; exactly one of them is called. [body] is the
 * whole body for a 2xx status (at most `maxBytes`, longer bodies fail) and up to 16 KiB of it
 * otherwise. [blocked] means the network policy refused the request before any I/O.
 */
interface BinarySink {
    fun onResponse(status: Int, headers: Map<String, String>, body: ByteArray)
    fun onFailure(blocked: Boolean, message: String)
}

interface BinaryHttpTransport {
    /**
     * Sends [request] with [body] as the raw request body (when non-null it replaces
     * `request.body`) and delivers the whole response body.
     */
    fun exchange(request: HttpRequestSpec, body: ByteArray?, sink: BinarySink): Cancellable
}

/**
 * One WebSocket for the host to open (`ws://` or `wss://`). [route], [tag] and [trace] as for
 * [HttpRequestSpec]; messages larger than [maxMessageBytes] close the socket.
 */
class WebSocketSpec(
    val url: String,
    val headers: Map<String, String>,
    val tag: String,
    val route: String = "INHERIT",
    val maxMessageBytes: Long = 32L * 1024 * 1024,
    val trace: TurnTrace? = null,
) {
    /** This socket tagged with the calling coroutine's [TurnTrace] (unchanged outside a turn). */
    suspend fun traced(): WebSocketSpec {
        val current = TurnTrace.current() ?: return this
        return WebSocketSpec(url, headers, tag, route, maxMessageBytes, current)
    }
}

/**
 * Callbacks for one socket, called serially: [onOpen] once the upgrade succeeded, then messages,
 * then exactly one of [onClosed] (either side closed it) or [onFailure] (it never opened, broke,
 * or [blocked] by the network policy). Nothing follows a [WebSocketConnection.cancel].
 */
interface WebSocketListener {
    fun onOpen()
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)
    fun onClosed(code: Int, reason: String)
    fun onFailure(blocked: Boolean, message: String)
}

/** An open (or opening) socket. [cancel] drops it at once without further callbacks. */
interface WebSocketConnection : Cancellable {
    /** Queues a text message; false when the socket is closed or closing. */
    fun sendText(text: String): Boolean

    /** Starts a clean close handshake. */
    fun close(code: Int, reason: String)
}

interface WebSocketTransport {
    fun connect(spec: WebSocketSpec, listener: WebSocketListener): WebSocketConnection
}

/** A whole binary response from a [BinaryHttpTransport]. */
class BinaryResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val failure: String? = null,
    val blocked: Boolean = false,
) {
    val isSuccessful: Boolean get() = failure == null && status in 200..299
}

/** Sends [spec] (traced to the calling turn) and suspends for the whole response. */
suspend fun BinaryHttpTransport.fetch(spec: HttpRequestSpec, body: ByteArray? = null): BinaryResponse {
    val request = spec.traced()
    return suspendCancellableCoroutine { cont ->
        val call = exchange(
            request,
            body,
            object : BinarySink {
                override fun onResponse(status: Int, headers: Map<String, String>, body: ByteArray) {
                    if (cont.isActive) cont.resume(BinaryResponse(status, headers, body))
                }

                override fun onFailure(blocked: Boolean, message: String) {
                    if (cont.isActive) cont.resume(BinaryResponse(0, emptyMap(), ByteArray(0), message, blocked))
                }
            },
        )
        cont.invokeOnCancellation { call.cancel() }
    }
}

/** What a socket delivers, as a flow item. */
sealed interface SocketEvent {
    data object Open : SocketEvent
    class Text(val text: String) : SocketEvent
    class Binary(val bytes: ByteArray) : SocketEvent
    class Closed(val code: Int, val reason: String) : SocketEvent
    class Failed(val blocked: Boolean, val message: String) : SocketEvent
}

/**
 * Opens [spec] (traced to the collecting turn) and emits its events; the flow ends after
 * [SocketEvent.Closed] or [SocketEvent.Failed], and cancelling the collector drops the socket.
 */
fun WebSocketTransport.events(spec: WebSocketSpec): Flow<SocketEvent> = callbackFlow {
    val connection = connect(
        spec.traced(),
        object : WebSocketListener {
            override fun onOpen() {
                trySend(SocketEvent.Open)
            }

            override fun onText(text: String) {
                trySend(SocketEvent.Text(text))
            }

            override fun onBinary(bytes: ByteArray) {
                trySend(SocketEvent.Binary(bytes))
            }

            override fun onClosed(code: Int, reason: String) {
                trySend(SocketEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(blocked: Boolean, message: String) {
                trySend(SocketEvent.Failed(blocked, message))
                close()
            }
        },
    )
    awaitClose { connection.cancel() }
}.buffer(Channel.UNLIMITED)
