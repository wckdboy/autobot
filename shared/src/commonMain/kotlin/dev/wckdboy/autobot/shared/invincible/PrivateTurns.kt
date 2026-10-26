package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Agent turns whose requests to a paired node must stay on that node: the node gets them with
 * `invincible-private: 1`, and its router never sends them on to a cloud model (Invincible's
 * spec/routing.md). The "phone first" cascade marks the turns it hands to a node.
 */
class PrivateTurns(private val max: Int = 256) {
    private val lock = Mutex()
    private val turns = LinkedHashSet<TurnTrace>()

    suspend fun mark(turn: TurnTrace) = lock.withLock {
        turns += turn
        while (turns.size > max) turns.remove(turns.first())
    }

    suspend fun clear(turn: TurnTrace) = lock.withLock { turns -= turn }

    suspend fun isPrivate(turn: TurnTrace): Boolean = lock.withLock { turn in turns }
}
