package dev.wckdboy.autobot.core.network

/** Where one request goes: refused, straight out, or through a SOCKS5 proxy (remote DNS). */
sealed interface Route {
    data object Blocked : Route
    data object Direct : Route
    data class Socks(val host: String, val port: Int) : Route
}

/**
 * Pure routing logic, shared by both apps: given the global [NetworkMode] and a per-client
 * [RouteOverride], decides the effective egress route.
 *
 * Rules:
 * - [NetworkMode.Offline] always wins; the kill switch blocks the request regardless of overrides.
 * - [RouteOverride.INHERIT] uses the global mode.
 * - [RouteOverride.TOR] / [RouteOverride.SOCKS5] force a proxy even if the global mode is Direct.
 * - [RouteOverride.DIRECT] cannot *downgrade* a global proxy mode: if the user switched the whole
 *   app to Tor or SOCKS5, a provider configured as Direct still goes through that proxy.
 * - Loopback hosts never use a proxy (a SOCKS proxy cannot reach the device's own loopback).
 *
 * SOCKS routes carry the proxy's host name, and HTTP clients must hand the *target* host name to
 * the proxy (remote DNS) instead of resolving it locally, which would leak lookups outside Tor.
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

    /** Route for a non-loopback host under [mode]. */
    fun route(mode: NetworkMode): Route = when (mode) {
        NetworkMode.Offline -> Route.Blocked
        NetworkMode.Direct -> Route.Direct
        is NetworkMode.Tor -> Route.Socks(mode.host, mode.port)
        is NetworkMode.Socks5 -> Route.Socks(mode.host, mode.port)
    }

    /** Route for a request to [host] under [mode]. Loopback is always direct. */
    fun routeForHost(mode: NetworkMode, host: String): Route = if (Loopback.isLoopback(host)) Route.Direct else route(mode)

    /** Audit-log label of a route (`offline`, `direct`, `tor`, `socks5`). */
    fun label(mode: NetworkMode): String = when (mode) {
        NetworkMode.Offline -> "offline"
        NetworkMode.Direct -> "direct"
        is NetworkMode.Tor -> "tor"
        is NetworkMode.Socks5 -> "socks5"
    }
}

/**
 * The kill switch's decision for one request, made *before* any DNS lookup or socket. Both apps'
 * HTTP clients call this and refuse the request with the returned reason.
 *
 * A request is refused when:
 * - the global mode is [NetworkMode.Offline] and the host is not loopback (or loopback is not
 *   allowed while offline);
 * - it uses cleartext HTTP to a non-loopback host;
 * - the effective route changed since the client was built (e.g. Direct → Tor), because the
 *   client's proxy is fixed at build time and would silently use the old route.
 *
 * @param expectedRoute the effective mode the client was built for; `null` skips that check.
 * @return the reason to block, or `null` to let the request through.
 */
object KillSwitch {
    fun blockReason(
        global: NetworkMode,
        effective: NetworkMode,
        allowLoopbackWhenOffline: Boolean,
        host: String,
        https: Boolean,
        expectedRoute: NetworkMode? = null,
    ): String? {
        val loopback = Loopback.isLoopback(host)
        return when {
            global == NetworkMode.Offline && !(loopback && allowLoopbackWhenOffline) -> "Offline mode: network access is disabled"
            !https && !loopback -> "Cleartext HTTP is only allowed to loopback hosts"
            !loopback && expectedRoute != null && expectedRoute != effective ->
                "Network route changed; request dropped to avoid leaking outside the selected route"
            else -> null
        }
    }
}
