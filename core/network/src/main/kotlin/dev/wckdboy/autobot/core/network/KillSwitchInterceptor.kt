package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.agent.core.TurnTrace
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * Application-level OkHttp interceptor that enforces [NetworkPolicy] *before* any DNS lookup or
 * socket is opened, and records every attempt in the [AuditLog]. The decision itself is the
 * shared [KillSwitch] (the iOS client uses the same one); refusals throw
 * [NetworkBlockedException]. A request tagged with a [TurnTrace] is recorded for that agent turn
 * (privacy receipts), and the response body bytes actually read are added to its entry once the
 * body is exhausted or closed.
 *
 * @param expectedRoute the effective mode the owning client was built for; `null` skips the
 * route-change check (used by tests and loopback-only clients).
 */
class KillSwitchInterceptor(
    private val policy: NetworkPolicy,
    private val auditLog: AuditLog,
    private val tag: String? = null,
    private val override: RouteOverride = RouteOverride.INHERIT,
    private val expectedRoute: NetworkMode? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val loopback = Loopback.isLoopback(host)
        val global = policy.mode.value
        val effective = RouteResolver.effectiveMode(global, override, policy.socksFallback.value)
        val requestTag = request.tag(AuditTag::class.java)?.value ?: tag
        val trace = request.tag(TurnTrace::class.java)
        val blockReason = KillSwitch.blockReason(
            global,
            effective,
            policy.allowLoopbackWhenOffline.value,
            host,
            request.url.isHttps,
            expectedRoute,
        )

        val routeLabel = if (loopback) "loopback" else routeLabel(effective)
        if (blockReason != null) {
            audit(request, requestTag, routeLabel, trace, blocked = true, error = blockReason)
            throw NetworkBlockedException(blockReason)
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            audit(request, requestTag, routeLabel, trace, blocked = false, error = e.javaClass.simpleName)
            throw e
        }
        val entry = audit(
            request,
            requestTag,
            routeLabel,
            trace,
            blocked = false,
            status = response.code,
            bytesReceived = response.body.contentLength(),
        )
        // A body closed unread keeps its Content-Length (if any) as the figure.
        val counted = CountingBody(response.body) { read -> if (read > 0 || entry.bytesReceived == null) auditLog.received(entry.id, read) }
        return response.newBuilder().body(counted).build()
    }

    private fun audit(
        request: Request,
        tag: String?,
        route: String,
        trace: TurnTrace?,
        blocked: Boolean,
        status: Int? = null,
        bytesReceived: Long? = null,
        error: String? = null,
    ): AuditEntry =
        auditLog.record(
            method = request.method,
            host = request.url.host,
            path = request.url.encodedPath,
            bytesSent = runCatching { request.body?.contentLength() }.getOrNull(),
            bytesReceived = bytesReceived,
            status = status,
            tag = tag,
            route = route,
            blocked = blocked,
            error = error,
            session = trace?.sessionId,
            turn = trace?.turn,
        )

    companion object {
        fun routeLabel(mode: NetworkMode): String = RouteResolver.label(mode)
    }
}

/**
 * [body] counting the bytes read from the network; [done] gets the total once, at the end of the
 * body or when it is closed early.
 */
private class CountingBody(private val body: ResponseBody, private val done: (Long) -> Unit) : ResponseBody() {
    private val reported = AtomicBoolean(false)

    @Volatile
    private var count = 0L

    private val counted: BufferedSource by lazy {
        object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read == -1L) report() else count += read
                return read
            }

            override fun close() {
                report()
                super.close()
            }
        }.buffer()
    }

    private fun report() {
        if (reported.compareAndSet(false, true)) done(count)
    }

    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun source(): BufferedSource = counted

    override fun close() {
        report()
        body.close()
    }
}

/** Per-request audit label, attach with `Request.Builder.tag(AuditTag::class.java, AuditTag("x"))`. */
data class AuditTag(val value: String)
