package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The network policy as it stands when a connection is opened. */
class NodePolicy(
    val global: NetworkMode,
    val socksFallback: NetworkMode.Socks5,
    val allowLoopbackWhenOffline: Boolean,
    val override: RouteOverride = RouteOverride.INHERIT,
)

/**
 * Opens connections to a node on the JVM (Android): [NodeEgress] decides first, then a direct
 * socket or a SOCKS5 one that hands the proxy the node's host name unresolved (remote DNS, so
 * lookups do not leak outside Tor). Every attempt, blocked ones included, goes in the [audit]
 * log, with the bytes each way once the connection closes.
 */
class JvmNodeConnector(
    private val policy: () -> NodePolicy,
    private val audit: AuditLog,
    private val tag: String = "node",
    private val connectTimeoutMillis: Int = 10_000,
) {
    /** Tries [addresses] in order; throws [NodeBlockedException] when policy refuses, else the last failure. */
    suspend fun open(addresses: List<String>): NodeLink = withContext(Dispatchers.IO) {
        var last: Exception = IOException("the node has no address")
        for (text in addresses) {
            val address = NodeAddress.parse(text) ?: continue
            val p = policy()
            val route = NodeEgress.plan(p.global, p.override, p.socksFallback, p.allowLoopbackWhenOffline, address.host)
            if (route is NodeRoute.Blocked) {
                audit.record("NOISE", address.host, "/", tag = tag, route = route.label, blocked = true, error = route.reason)
                throw NodeBlockedException(route.reason)
            }
            val socket = when (route) {
                is NodeRoute.Socks -> Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(route.proxyHost, route.proxyPort)))
                else -> Socket()
            }
            val target = if (route is NodeRoute.Socks) {
                InetSocketAddress.createUnresolved(address.host, address.port)
            } else {
                InetSocketAddress(address.host, address.port)
            }
            try {
                socket.connect(target, connectTimeoutMillis)
                socket.tcpNoDelay = true
            } catch (e: IOException) {
                socket.close()
                audit.record("NOISE", address.host, "/", tag = tag, route = route.label, blocked = false, error = e.javaClass.simpleName)
                last = e
                continue
            }
            val entry = audit.record("NOISE", address.host, "/", tag = tag, route = route.label, blocked = false)
            return@withContext CountingLink(SocketNodeLink(socket)) { sent, received ->
                audit.sent(entry.id, sent)
                audit.received(entry.id, received)
            }
        }
        throw last
    }
}

/** Counts bytes each way and reports them once, on close. */
private class CountingLink(private val inner: NodeLink, private val report: (sent: Long, received: Long) -> Unit) : NodeLink {
    private var sent = 0L
    private var received = 0L
    private var closed = false

    override suspend fun readExactly(count: Int): ByteArray = inner.readExactly(count).also { received += it.size }

    override suspend fun write(bytes: ByteArray) {
        inner.write(bytes)
        sent += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        inner.close()
        report(sent, received)
    }
}
