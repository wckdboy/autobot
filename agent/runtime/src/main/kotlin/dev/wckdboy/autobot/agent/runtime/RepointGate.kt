package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.providers.remote.RouteTarget

/**
 * Whether a provider list change can move any chat (`ChatRoutes.repoint`), so the chats are only
 * scanned then: chats move when their provider disappears, or when the first provider arrives for
 * chats that had none. Renaming a provider or changing its default model moves nobody.
 */
internal object RepointGate {
    fun needsScan(previous: List<RouteTarget>?, current: List<RouteTarget>): Boolean {
        if (current.isEmpty()) return false
        if (previous.isNullOrEmpty()) return true
        val now = current.mapTo(HashSet()) { it.id }
        return previous.any { it.id !in now }
    }
}
