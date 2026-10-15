package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.monotonicNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive

/** A regular expression ran past its time budget (catastrophic backtracking, most likely). */
class RegexTimeoutException : Exception("the pattern took too long to match")

/**
 * [chars] for a regex that the model (or a fetched page) controls: every few thousand reads it
 * checks [job] and the [deadlineNanos] (on [monotonicNanos]'s clock), so a pattern that
 * backtracks catastrophically stops with the turn or a [RegexTimeoutException] instead of
 * pinning a core until the app is killed.
 */
class InterruptibleChars(
    private val chars: CharSequence,
    private val job: Job?,
    private val deadlineNanos: Long,
) : CharSequence {
    private var reads = 0

    override val length: Int get() = chars.length

    override fun get(index: Int): Char {
        if (++reads and CHECK_EVERY == 0) {
            job?.ensureActive()
            if (monotonicNanos() > deadlineNanos) throw RegexTimeoutException()
        }
        return chars[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        InterruptibleChars(chars.subSequence(startIndex, endIndex), job, deadlineNanos)

    override fun toString(): String = chars.toString()

    companion object {
        private const val CHECK_EVERY = 0xFFF

        /** A deadline [millis] from now. */
        fun deadline(millis: Long): Long = monotonicNanos() + millis * 1_000_000
    }
}
