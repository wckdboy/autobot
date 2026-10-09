package dev.wckdboy.autobot.core.network

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * Builds OkHttp clients that honour the [NetworkPolicy].
 *
 * Every client:
 * - runs [KillSwitchInterceptor] first (blocks before DNS/socket I/O, writes the [AuditLog]) and
 *   then [HeaderScrubInterceptor];
 * - only negotiates `RESTRICTED_TLS` / `MODERN_TLS`, plus `CLEARTEXT` which the kill switch limits
 *   to loopback hosts;
 * - has no HTTP cache and no cookie jar;
 * - uses timeouts suited to token streaming (long read timeout, no call timeout);
 * - never follows redirects (they would bypass the kill switch) and does not retry silently.
 *
 * The proxy is resolved from the effective route **at build time** and is fixed for the client's
 * lifetime. Build a fresh client per logical operation (e.g. per chat completion); if the route
 * changes while an old client is still used, the kill switch rejects its requests. Connection
 * pools are kept separate per route so a connection opened directly can never be reused once
 * Tor is selected.
 */
@Singleton
class HttpClientFactory @Inject constructor(
    private val policy: NetworkPolicy,
    private val auditLog: AuditLog,
) {
    private val pools = ConcurrentHashMap<String, ConnectionPool>()

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            // Redirects bypass application interceptors, so they are disabled entirely.
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Creates a client for [tag] (shown in the audit log) with the given routing [override].
     */
    fun create(tag: String, override: RouteOverride = RouteOverride.INHERIT): OkHttpClient {
        val effective = RouteResolver.effectiveMode(policy.mode.value, override, policy.socksFallback.value)
        val proxy = RouteResolver.proxyFor(effective) ?: Proxy.NO_PROXY
        val routeKey = KillSwitchInterceptor.routeLabel(effective) + "|" + proxy.address()
        val builder = base.newBuilder()
            .proxySelector(LoopbackAwareProxySelector(proxy))
            .connectionPool(pools.getOrPut(routeKey) { ConnectionPool() })
            .addInterceptor(KillSwitchInterceptor(policy, auditLog, tag, override, expectedRoute = effective))
            .addInterceptor(HeaderScrubInterceptor())
        if (proxy.type() == Proxy.Type.SOCKS) {
            // Tor circuits are slow to build.
            builder.connectTimeout(PROXY_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return builder.build()
    }

    /** Closes idle connections on every route, e.g. after the network mode changes. */
    fun evictAll() {
        pools.values.forEach(ConnectionPool::evictAll)
    }

    companion object {
        const val CONNECT_TIMEOUT_SECONDS = 20L
        const val PROXY_CONNECT_TIMEOUT_SECONDS = 60L
        const val READ_TIMEOUT_SECONDS = 180L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Returns [proxy] for every host except loopback, which is always reached directly.
 */
internal class LoopbackAwareProxySelector(private val proxy: Proxy) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> =
        if (Loopback.isLoopback(uri?.host)) listOf(Proxy.NO_PROXY) else listOf(proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
