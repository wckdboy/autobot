package dev.wckdboy.autobot.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Application-level OkHttp interceptor that enforces [NetworkPolicy] *before* any DNS lookup or
 * socket is opened, and records every attempt in the [AuditLog].
 *
 * A request is refused with [NetworkBlockedException] when:
 * - the global mode is [NetworkMode.Offline] and the host is not loopback (or loopback is not
 *   allowed while offline);
 * - it uses cleartext HTTP to a non-loopback host;
 * - the effective route changed since the client was built (e.g. Direct → Tor). The client's
 *   proxy is fixed at build time, so continuing would silently use the old route.
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

        val blockReason: String? = when {
            global == NetworkMode.Offline && !(loopback && policy.allowLoopbackWhenOffline.value) ->
                "Offline mode: network access is disabled"
            !request.url.isHttps && !loopback ->
                "Cleartext HTTP is only allowed to loopback hosts"
            !loopback && expectedRoute != null && expectedRoute != effective ->
                "Network route changed; request dropped to avoid leaking outside the selected route"
            else -> null
        }

        val routeLabel = if (loopback) "loopback" else routeLabel(effective)
        if (blockReason != null) {
            audit(request, requestTag, routeLabel, blocked = true, error = blockReason)
            throw NetworkBlockedException(blockReason)
        }

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            audit(request, requestTag, routeLabel, blocked = false, error = e.javaClass.simpleName)
            throw e
        }
        audit(
            request,
            requestTag,
            routeLabel,
            blocked = false,
            status = response.code,
            bytesReceived = response.body.contentLength(),
        )
        return response
    }

    private fun audit(
        request: Request,
        tag: String?,
        route: String,
        blocked: Boolean,
        status: Int? = null,
        bytesReceived: Long? = null,
        error: String? = null,
    ) {
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
        )
    }

    companion object {
        fun routeLabel(mode: NetworkMode): String = when (mode) {
            NetworkMode.Offline -> "offline"
            NetworkMode.Direct -> "direct"
            is NetworkMode.Tor -> "tor"
            is NetworkMode.Socks5 -> "socks5"
        }
    }
}

/** Per-request audit label, attach with `Request.Builder.tag(AuditTag::class.java, AuditTag("x"))`. */
data class AuditTag(val value: String)
