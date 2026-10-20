package dev.wckdboy.autobot.shared.invincible

import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The node refused the handshake: a pairing code it would not take, or a device it no longer knows. */
class NodeRefusedException(message: String) : Exception(message)

/** The node took the connection but did not finish the handshake in time. */
class NodeTimeoutException(message: String) : Exception(message)

/** The connection ended before the node's whole answer arrived. */
class NodeDroppedException(message: String) : Exception(message)

/**
 * What went wrong talking to a node, in words both apps show: what happened and what to do, never
 * a socket error. The same on Android and iOS.
 */
object NodeErrors {
    /** How long a node has to finish a handshake (it gives each one 10 s itself). */
    const val HANDSHAKE_MILLIS = 15_000L

    /** The `error.type` of the answer a transport makes up when the node no longer knows this device. */
    const val UNPAIRED = "device_unpaired"

    const val CODE_REFUSED = "The node didn't take this code. A code works once, for 10 minutes: run `invincible pair` for a new one."

    const val NO_NETWORK = "This phone has no network. Connect to Wi-Fi or turn off airplane mode, then try again."

    fun unreachable(node: String, addresses: List<String>): String {
        val where = when (addresses.size) {
            0 -> ""
            1 -> " at ${addresses[0]}"
            else -> " at ${addresses.dropLast(1).joinToString(", ")} or ${addresses.last()}"
        }
        return "Can't reach $node$where. Check that Invincible is running there and this phone is on its network (or Tailscale)."
    }

    fun timeout(node: String): String = "$node didn't answer within ${HANDSHAKE_MILLIS / 1000} s. Check that Invincible is running there."

    fun dropped(node: String): String = "The connection to $node dropped before the answer."

    fun unpaired(node: String): String = "$node doesn't know this phone any more: it was unpaired there. Pair it again in Remote."

    fun noAgent(node: String): String = "$node has no Autobot Agent yet. Update Invincible there (`invincible update`), then run `autobot serve`."

    /** Why pairing with the node in a code at [addresses] failed. */
    fun pairing(error: Throwable, addresses: List<String>): String = when (error) {
        is NodeBlockedException -> "Blocked by the network settings: ${error.message}"
        is NodeRefusedException -> CODE_REFUSED
        is NodeTimeoutException -> timeout("The node")
        is NodeConnectException -> if (isNoNetwork(error.message)) NO_NETWORK else unreachable("the node", addresses)
        else -> "Pairing failed: ${error.message ?: error::class.simpleName}"
    }

    /** A connect failure that means this device has no network at all (rather than the node being down). */
    fun isNoNetwork(message: String?): Boolean {
        val m = message ?: return false
        return NO_NETWORK_SIGNS.any { m.contains(it, ignoreCase = true) }
    }

    /** An answer a transport made up for a device the node no longer knows (spec/pairing.md, Session). */
    fun unpairedBody(node: String): String =
        """{"error":{"message":"${unpaired(node).replace("\"", "\\\"")}","type":"$UNPAIRED"}}"""

    /** Whether a provider error's [message] is [unpaired]'s (so it is shown as it is, not as a rejected API key). */
    fun isUnpaired(message: String): Boolean = UNPAIRED_SIGN in message

    private const val UNPAIRED_SIGN = "doesn't know this phone any more"

    /** Whether [message] is one of these sentences already (a caller adds no "Couldn't reach…" of its own). */
    fun isWorded(message: String): Boolean =
        message.startsWith("Can't reach ") || message == NO_NETWORK || message.startsWith("The connection to ") ||
            " didn't answer within " in message || isUnpaired(message)

    // Android (ENETUNREACH), iOS / Network.framework (ENETDOWN, "The Internet connection appears to be offline").
    private val NO_NETWORK_SIGNS = listOf("ENETUNREACH", "Network is unreachable", "ENETDOWN", "Network is down", "appears to be offline")
}

/**
 * Runs a handshake on [link] within [millis]; past it the link is closed (that unblocks a read
 * blocked in a socket, which cancellation alone does not) and [NodeTimeoutException] is thrown.
 * Any other failure of the exchange means the node closed it: [NodeRefusedException].
 */
internal suspend fun <T> NodeLink.handshake(millis: Long, block: suspend () -> T): T = coroutineScope {
    val late = AtomicBoolean(false)
    val timer = launch {
        delay(millis)
        late.store(true)
        close()
    }
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (late.load()) throw NodeTimeoutException("no handshake within ${millis / 1000} s")
        throw NodeRefusedException(e.message ?: "the node closed the connection")
    } finally {
        timer.cancel()
    }
}
