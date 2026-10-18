package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.core.network.KillSwitch
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.network.RouteResolver

/** How a connection to a node goes out, decided before any DNS lookup or socket. */
sealed interface NodeRoute {
    /** The network policy refuses it; [reason] is the kill switch's. */
    data class Blocked(val reason: String) : NodeRoute

    /** A node on this device (tests, a node on the same machine): never through a proxy. */
    data object Loopback : NodeRoute

    data object Direct : NodeRoute

    /** Through a SOCKS5 proxy (Tor's or another), handing it the node's host name: remote DNS. */
    data class Socks(val proxyHost: String, val proxyPort: Int) : NodeRoute

    /** The audit log's route label. */
    val label: String
        get() = when (this) {
            is Blocked -> "blocked"
            Loopback -> "loopback"
            Direct -> "direct"
            is Socks -> "socks"
        }
}

/**
 * The network policy for node connections: the same kill switch and route resolution as every
 * HTTP request ([KillSwitch], [RouteResolver]), so offline mode blocks a node and Tor / SOCKS5
 * carry it. One difference: the kill switch's "cleartext only to loopback" rule exists to keep
 * traffic encrypted and authenticated, and a Noise channel to a pinned key is both, so it counts
 * as secure the way HTTPS does.
 */
object NodeEgress {
    fun plan(
        global: NetworkMode,
        override: RouteOverride,
        socksFallback: NetworkMode.Socks5,
        allowLoopbackWhenOffline: Boolean,
        host: String,
    ): NodeRoute {
        val effective = RouteResolver.effectiveMode(global, override, socksFallback)
        KillSwitch.blockReason(global, effective, allowLoopbackWhenOffline, host, https = true)?.let { return NodeRoute.Blocked(it) }
        if (Loopback.isLoopback(host)) return NodeRoute.Loopback
        return when (effective) {
            NetworkMode.Offline -> NodeRoute.Blocked("Offline mode: network access is disabled")
            NetworkMode.Direct -> NodeRoute.Direct
            is NetworkMode.Tor -> NodeRoute.Socks(effective.host, effective.port)
            is NetworkMode.Socks5 -> NodeRoute.Socks(effective.host, effective.port)
        }
    }
}

/** A node address from a pairing code: `host:port`, `[v6]:port`. */
data class NodeAddress(val host: String, val port: Int) {
    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(text: String): NodeAddress? {
            val t = text.trim()
            val (host, port) = if (t.startsWith('[')) {
                val end = t.indexOf(']')
                if (end < 0 || t.getOrNull(end + 1) != ':') return null
                t.substring(1, end) to t.substring(end + 2)
            } else {
                val colon = t.lastIndexOf(':')
                if (colon <= 0) return null
                t.substring(0, colon) to t.substring(colon + 1)
            }
            val p = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return if (host.isBlank()) null else NodeAddress(host, p)
        }
    }
}
