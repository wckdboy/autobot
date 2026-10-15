package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The shared [HttpTransport] over a policy-bound OkHttp client from [HttpClientFactory]: kill
 * switch, route (the request's override), header scrub and audit log apply, and redirects are
 * never followed. Bytes only — request building and parsing stay in the shared code.
 */
class OkHttpTransport(private val clients: HttpClientFactory) : HttpTransport {

    override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
        val override = RouteOverride.entries.firstOrNull { it.name == request.route } ?: RouteOverride.INHERIT
        val http = try {
            Request.Builder().url(request.url)
                .method(request.method, request.body?.toByteArray()?.toRequestBody())
                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
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

    private companion object {
        const val ERROR_BODY_LIMIT = 16L * 1024
    }
}
