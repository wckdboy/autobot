package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.shared.HttpRequestSpec
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Agent turns whose requests to a paired node must never run on a cloud model: the node gets them
 * with `invincible-private: 1`, and its router never sends them on to one (Invincible's
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

/**
 * Work outside the turns [PrivateTurns] marks whose requests to a paired node must never run on a
 * cloud model either: a manual `/compact` of a chat "phone first" works in, or worked in
 * (`LocalFirstCascade.compact`). A request made under it is [HttpRequestSpec.markedPrivate]
 * ([HttpRequestSpec.traced]), so the node gets it with `invincible-private: 1`.
 */
class PrivateWork : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrivateWork> {
        /** Whether the calling coroutine runs under one. */
        suspend fun active(): Boolean = currentCoroutineContext()[Key] != null
    }
}

/**
 * Whether [request] goes to a paired node with `invincible-private: 1`: it was made under
 * [PrivateWork], or for a turn [turns] marks.
 */
suspend fun privateRequest(request: HttpRequestSpec, turns: PrivateTurns?): Boolean =
    request.markedPrivate || request.trace?.let { turns?.isPrivate(it) } == true
