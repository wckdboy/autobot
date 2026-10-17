package dev.wckdboy.autobot.shared.panic

/** The result of one PIN attempt, with the record to persist. */
sealed interface UnlockOutcome {
    val record: LockRecord

    /** The real PIN: the counter is back to zero. */
    data class Unlocked(override val record: LockRecord) : UnlockOutcome

    /** The duress PIN: wipe quietly and open an empty app, exactly like [Unlocked] on screen. */
    data class Duress(override val record: LockRecord) : UnlockOutcome

    /**
     * A wrong PIN. [remaining] attempts before a wipe (null when the wipe is off), [warn] when
     * the wipe is close enough to say so, [waitMillis] before the next attempt.
     */
    data class Failed(override val record: LockRecord, val remaining: Int?, val warn: Boolean, val waitMillis: Long) : UnlockOutcome

    /** The last allowed attempt failed: wipe and return to first run. */
    data class Wipe(override val record: LockRecord) : UnlockOutcome
}

/** What the lock screen shows before an attempt (after a restart, or while waiting). */
data class LockStatus(val failures: Int, val remaining: Int?, val warn: Boolean, val waitMillis: Long)

/**
 * The PIN attempt counter, back-off and wipe threshold, shared by both apps.
 *
 * An attempt is journaled before the PIN is checked: [begin] counts it as failed and the platform
 * persists (and syncs) that record first, then checks the PIN and persists [resolve]'s record. A
 * kill between the two leaves the attempt counted, so force-quitting cannot reset the counter or
 * buy extra guesses. Biometric unlocks never touch the counter, and are only offered while it is
 * zero ([LockRecord.biometricsAllowed]): after a wrong PIN only the PIN opens the app. The duress
 * PIN works on the PIN pad only.
 */
object UnlockPolicy {
    /** The wipe thresholds Settings offers. */
    val WipeChoices: List<Int> = listOf(5, 10, 15)

    /** The lock screen warns when this many attempts (or fewer) are left before a wipe. */
    const val WARN_REMAINING = 2

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND

    /** Wait after the Nth failure in a row: none for the first three, then growing to 30 minutes. */
    private val Backoff = longArrayOf(0, 0, 0, 0, 30 * SECOND, MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE)

    fun backoffMillis(failures: Int): Long = Backoff[failures.coerceIn(0, Backoff.lastIndex)]

    /**
     * How long until the next attempt. The wait runs from the last attempt on the wall clock; a
     * clock set back before it restarts the full wait, so it cannot be skipped that way.
     */
    fun waitMillis(record: LockRecord, now: Long): Long {
        val backoff = backoffMillis(record.failures)
        if (backoff == 0L) return 0
        if (now < record.lastFailureAt) return backoff
        return (record.lastFailureAt + backoff - now).coerceAtLeast(0)
    }

    /** Attempts left before a wipe, or null when the wipe is off. */
    fun remaining(record: LockRecord): Int? =
        if (record.wipeAfter > 0) (record.wipeAfter - record.failures).coerceAtLeast(0) else null

    fun status(record: LockRecord, now: Long): LockStatus {
        val left = remaining(record)
        return LockStatus(record.failures, left, warns(left), waitMillis(record, now))
    }

    private fun warns(remaining: Int?): Boolean = remaining != null && remaining <= WARN_REMAINING

    /**
     * The record to persist before checking a PIN (the attempt counted as failed), or null while
     * the back-off still runs (the attempt is refused, not counted).
     */
    fun begin(record: LockRecord, now: Long): LockRecord? {
        if (record.pin == null || waitMillis(record, now) > 0) return null
        return record.copy(failures = record.failures + 1, lastFailureAt = now)
    }

    /** The outcome of the attempt [begun] by [begin], given which PIN it matched. */
    fun resolve(begun: LockRecord, match: PinMatch, now: Long): UnlockOutcome = when (match) {
        PinMatch.REAL -> UnlockOutcome.Unlocked(begun.copy(failures = 0, lastFailureAt = 0, quietStart = false))
        PinMatch.DURESS -> UnlockOutcome.Duress(begun.copy(failures = 0, lastFailureAt = 0))
        PinMatch.NONE -> {
            val left = remaining(begun)
            if (left != null && left <= 0) {
                UnlockOutcome.Wipe(begun)
            } else {
                UnlockOutcome.Failed(begun, left, warns(left), waitMillis(begun, now))
            }
        }
    }

    /** The record after Settings changes the wipe threshold (0 turns it off; anything else must be a [WipeChoices] value). */
    fun withWipeAfter(record: LockRecord, attempts: Int): LockRecord {
        require(attempts == 0 || attempts in WipeChoices) { "Unsupported wipe threshold" }
        require(attempts == 0 || record.pin != null) { "The wipe needs an app PIN" }
        return record.copy(wipeAfter = attempts)
    }
}
