package dev.wckdboy.autobot.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Application-level OkHttp interceptor that enforces [NetworkPolicy] *before* any DNS lookup or
 * socket is opened, and records every attempt in the [AuditLog]. The decision itself is the
 * shared [KillSwitch] (the iOS client uses the same one); refusals throw
 * [NetworkBlockedException].
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
        fun routeLabel(mode: NetworkMode): String = RouteResolver.label(mode)
    }
}

/** Per-request audit label, attach with `Request.Builder.tag(AuditTag::class.java, AuditTag("x"))`. */
data class AuditTag(val value: String)
