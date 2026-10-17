package dev.wckdboy.autobot.feature.chat.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Pieces of an answer spoken in order as they arrive while it still streams: [add] them, [close]
 * when the answer is complete, and [run] speaks until both. The next piece is handed to the
 * speaker's [Speaker.prepare] while one plays, so a neural voice synthesizes ahead. [onPiece] is
 * called as each piece starts, [onResume] before a piece that had to wait for the answer to go on
 * (the speaker was quiet meanwhile).
 */
internal class SpeechQueue(private val onResume: () -> Unit = {}, private val onPiece: (index: Int) -> Unit = {}) {
    private val pending = ArrayDeque<String>()
    private var closed = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun add(pieces: List<String>) {
        if (pieces.isEmpty()) return
        synchronized(pending) { pending.addAll(pieces) }
        wake.trySend(Unit)
    }

    /** No more pieces will come. */
    fun close() {
        synchronized(pending) { closed = true }
        wake.trySend(Unit)
    }

    /** Speaks every piece with [speaker] until [close]d and empty. Cancel to stop. */
    suspend fun run(speaker: Speaker) {
        var index = 0
        var waited = false
        while (true) {
            val (next, after, done) = synchronized(pending) { Triple(pending.removeFirstOrNull(), pending.firstOrNull(), closed) }
            if (next == null) {
                if (done) return
                wake.receive()
                waited = true
                continue
            }
            currentCoroutineContext().ensureActive()
            after?.let(speaker::prepare)
            if (waited && index > 0) onResume()
            waited = false
            onPiece(index++)
            speaker.speak(next)
        }
    }
}
