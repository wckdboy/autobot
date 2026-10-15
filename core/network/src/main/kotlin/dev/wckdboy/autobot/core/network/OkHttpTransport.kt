package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.shared.BinaryHttpTransport
import dev.wckdboy.autobot.shared.BinarySink
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.WebSocketConnection
import dev.wckdboy.autobot.shared.WebSocketListener
import dev.wckdboy.autobot.shared.WebSocketSpec
import dev.wckdboy.autobot.shared.WebSocketTransport
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okio.Buffer
import okio.ByteString

/**
 * The shared [HttpTransport] (and its binary and WebSocket siblings) over a policy-bound OkHttp
 * client from [HttpClientFactory]: kill switch, route (the request's override), header scrub and
 * audit log apply, and redirects are never followed. Bytes only — request building and parsing
 * stay in the shared code.
 */
class OkHttpTransport(private val clients: HttpClientFactory) : HttpTransport, BinaryHttpTransport, WebSocketTransport {

    private fun overrideOf(route: String) = RouteOverride.entries.firstOrNull { it.name == route } ?: RouteOverride.INHERIT

    override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
        val override = overrideOf(request.route)
        val http = try {
            Request.Builder().url(request.url)
                .method(request.method, request.body?.toByteArray()?.toRequestBody())
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                .turnTrace(request.trace)
                .build()
        } catch (e: IllegalArgumentException) {
            sink.onFailure(blocked = false, message = "Invalid URL")
            return Cancellable { }
        }
        val call = clients.create(request.tag, override).newCall(http)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    val blocked = e is NetworkBlockedException
                    sink.onFailure(blocked, e.message ?: if (blocked) "Blocked by network policy" else "Network error")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val headers = r.headers.associate { (name, value) -> name.lowercase() to value }
                        if (!r.isSuccessful) {
                            sink.onResponse(r.code, headers, runCatching { r.peekBody(ERROR_BODY_LIMIT).string() }.getOrDefault(""))
                            return
                        }
                        sink.onResponse(r.code, headers, null)
                        try {
                            val source = r.body.source()
                            var read = 0L
                            while (read < request.maxBytes) {
                                val line = source.readUtf8Line() ?: break
                                read += line.length + 1
                                sink.onLine(line)
                            }
                            sink.onComplete()
                        } catch (e: IOException) {
                            if (!call.isCanceled()) sink.onFailure(blocked = false, message = e.message ?: "Network error")
                        }
                    }
                }
            },
        )
        return Cancellable { call.cancel() }
    }

    override fun exchange(request: HttpRequestSpec, body: ByteArray?, sink: BinarySink): Cancellable {
        val bytes = body ?: request.body?.toByteArray()
        val http = try {
            Request.Builder().url(request.url)
                .method(request.method, bytes?.toRequestBody())
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                .turnTrace(request.trace)
                .build()
        } catch (e: IllegalArgumentException) {
            sink.onFailure(blocked = false, message = "Invalid URL")
            return Cancellable { }
        }
        val call = clients.create(request.tag, overrideOf(request.route)).newCall(http)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (call.isCanceled()) return
                    val blocked = e is NetworkBlockedException
                    sink.onFailure(blocked, e.message ?: if (blocked) "Blocked by network policy" else "Network error")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        val headers = r.headers.associate { (name, value) -> name.lowercase() to value }
                        if (!r.isSuccessful) {
                            sink.onResponse(r.code, headers, runCatching { r.peekBody(ERROR_BODY_LIMIT).bytes() }.getOrDefault(ByteArray(0)))
                            return
                        }
                        try {
                            val source = r.body.source()
                            val buffer = Buffer()
                            while (source.read(buffer, 64L * 1024) != -1L) {
                                if (buffer.size > request.maxBytes) {
                                    sink.onFailure(blocked = false, message = "Response larger than ${request.maxBytes} bytes")
                                    return
                                }
                            }
                            sink.onResponse(r.code, headers, buffer.readByteArray())
                        } catch (e: IOException) {
                            if (!call.isCanceled()) sink.onFailure(blocked = false, message = e.message ?: "Network error")
                        }
                    }
                }
            },
        )
        return Cancellable { call.cancel() }
    }

    override fun connect(spec: WebSocketSpec, listener: WebSocketListener): WebSocketConnection {
        val entry = AuditEntryRef()
        val http = try {
            Request.Builder().url(spec.url)
                .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
                .turnTrace(spec.trace)
                .tag(AuditEntryRef::class.java, entry)
                .build()
        } catch (e: IllegalArgumentException) {
            listener.onFailure(blocked = false, message = "Invalid URL")
            return ClosedConnection
        }
        val received = AtomicLong()
        val reported = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        // The message bytes go into the upgrade's audit entry once the socket ends.
        fun report() {
            if (reported.compareAndSet(false, true)) entry.id?.let { clients.auditLog.received(it, received.get()) }
        }
        val client = clients.create(spec.tag, overrideOf(spec.route)).newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build()
        val socket = client.newWebSocket(
            http,
            object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!cancelled.get()) listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val size = text.toByteArray().size.toLong()
                    if (tooBig(webSocket, size)) return
                    if (!cancelled.get()) listener.onText(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (tooBig(webSocket, bytes.size.toLong())) return
                    if (!cancelled.get()) listener.onBinary(bytes.toByteArray())
                }

                private fun tooBig(webSocket: WebSocket, size: Long): Boolean {
                    received.addAndGet(size)
                    if (size <= spec.maxMessageBytes) return false
                    webSocket.close(MESSAGE_TOO_BIG, "Message too big")
                    return true
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    report()
                    if (!cancelled.get()) listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    report()
                    if (cancelled.get()) return
                    val blocked = t is NetworkBlockedException
                    val message = t.message ?: if (blocked) "Blocked by network policy" else response?.let { "WebSocket upgrade refused (HTTP ${it.code})" } ?: "Network error"
                    listener.onFailure(blocked, message)
                }
            },
        )
        return object : WebSocketConnection {
            override fun sendText(text: String): Boolean = socket.send(text)

            override fun close(code: Int, reason: String) {
                socket.close(code, reason)
            }

            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    socket.cancel()
                    report()
                }
            }
        }
    }

    private object ClosedConnection : WebSocketConnection {
        override fun sendText(text: String) = false
        override fun close(code: Int, reason: String) = Unit
        override fun cancel() = Unit
    }

    private companion object {
        const val ERROR_BODY_LIMIT = 16L * 1024
        const val PING_SECONDS = 30L
        const val NORMAL_CLOSURE = 1000
        const val MESSAGE_TOO_BIG = 1009
    }
}
