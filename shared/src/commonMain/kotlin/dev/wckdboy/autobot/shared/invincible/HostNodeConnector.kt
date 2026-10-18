package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A byte stream to a node, as a host without coroutines provides it (iOS: an `NWConnection`).
 * Each callback is called once. Errors are messages, never exceptions thrown into Kotlin.
 */
interface HostNodeLink {
    /** Exactly [count] bytes, or an error (the connection closed before them). */
    fun read(count: Int, done: (ByteArray?, String?) -> Unit)

    /** [done] with null once the bytes are handed to the network, else an error. */
    fun write(bytes: ByteArray, done: (String?) -> Unit)

    fun close()
}

/**
 * How a host opens node connections under its network policy: the kill switch and the
 * provider's [route] override (`INHERIT`, `DIRECT`, `TOR`, `SOCKS5`) decide ([NodeEgress.plan]),
 * Tor and SOCKS5 get the host name unresolved, and every attempt goes in the audit log, for the
 * agent turn [session]/[turn] when there is one ([turn] is -1 outside a turn).
 */
interface HostNodeConnector {
    /**
     * Opens a connection to the first reachable of [addresses] (`host:port`). [done] gets a link,
     * or `blocked = true` and the policy's reason, or the last failure.
     */
    fun open(addresses: List<String>, route: String, session: String?, turn: Int, done: (HostNodeLink?, Boolean, String?) -> Unit)
}

/** [host] as the connector the shared node code uses. */
fun nodeConnector(host: HostNodeConnector): suspend (List<String>, String) -> NodeLink = { addresses, route ->
    val trace = TurnTrace.current()
    val link = suspendCancellableCoroutine { cont ->
        host.open(addresses, route, trace?.sessionId, trace?.turn ?: -1) { link, blocked, message ->
            when {
                link != null -> if (cont.isActive) cont.resume(link) else link.close()
                blocked -> cont.resumeWithException(NodeBlockedException(message ?: "Blocked by network policy"))
                else -> cont.resumeWithException(NodeConnectException(message ?: "could not reach the node"))
            }
        }
    }
    HostLink(link)
}

/** A node could not be reached (none of its addresses answered). */
class NodeConnectException(message: String) : Exception(message)

private class HostLink(private val host: HostNodeLink) : NodeLink {
    override suspend fun readExactly(count: Int): ByteArray = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { host.close() }
        host.read(count) { bytes, error ->
            if (bytes != null && bytes.size == count) cont.resume(bytes) else cont.resumeWithException(NodeConnectException(error ?: "the node closed the connection"))
        }
    }

    override suspend fun write(bytes: ByteArray) = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { host.close() }
        host.write(bytes) { error -> if (error == null) cont.resume(Unit) else cont.resumeWithException(NodeConnectException(error)) }
    }

    override fun close() = host.close()
}
