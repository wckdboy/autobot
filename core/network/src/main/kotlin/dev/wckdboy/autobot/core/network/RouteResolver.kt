package dev.wckdboy.autobot.core.network

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Pure routing logic: given the global [NetworkMode] and a per-client [RouteOverride], decides
 * the effective egress route and the corresponding [Proxy].
 *
 * Rules:
 * - [NetworkMode.Offline] always wins; the kill switch blocks the request regardless of overrides.
 * - [RouteOverride.INHERIT] uses the global mode.
 * - [RouteOverride.TOR] / [RouteOverride.SOCKS5] force a proxy even if the global mode is Direct.
 * - [RouteOverride.DIRECT] cannot *downgrade* a global proxy mode: if the user switched the whole
 *   app to Tor or SOCKS5, a provider configured as Direct still goes through that proxy.
 * - Loopback hosts never use a proxy (a SOCKS proxy cannot reach the device's own loopback).
 *
 * Proxies are created with unresolved addresses so OkHttp/Java hand the *hostname* to the SOCKS
 * server (remote DNS) instead of resolving it locally, which would leak lookups outside Tor.
 */
object RouteResolver {

    fun effectiveMode(
        global: NetworkMode,
        override: RouteOverride,
        socksFallback: NetworkMode.Socks5,
    ): NetworkMode = when (global) {
        NetworkMode.Offline -> NetworkMode.Offline
        else -> when (override) {
            RouteOverride.INHERIT -> global
            RouteOverride.DIRECT -> global
            RouteOverride.TOR -> global as? NetworkMode.Tor ?: NetworkMode.Tor()
            RouteOverride.SOCKS5 -> global as? NetworkMode.Socks5 ?: socksFallback
        }
    }

    /** Proxy for a non-loopback host under [mode]. `null` means blocked (offline). */
    fun proxyFor(mode: NetworkMode): Proxy? = when (mode) {
        NetworkMode.Offline -> null
        NetworkMode.Direct -> Proxy.NO_PROXY
        is NetworkMode.Tor -> socks(mode.host, mode.port)
        is NetworkMode.Socks5 -> socks(mode.host, mode.port)
    }

    /** Proxy to use for a request to [host] under [mode]. Loopback is always direct. */
    fun proxyForHost(mode: NetworkMode, host: String): Proxy? =
        if (Loopback.isLoopback(host)) Proxy.NO_PROXY else proxyFor(mode)

    private fun socks(host: String, port: Int) =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
}
