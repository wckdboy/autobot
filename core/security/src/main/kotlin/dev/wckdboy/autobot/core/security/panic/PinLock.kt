package dev.wckdboy.autobot.core.security.panic

import dev.wckdboy.autobot.shared.panic.LockRecord
import dev.wckdboy.autobot.shared.panic.LockStatus
import dev.wckdboy.autobot.shared.panic.PinCrypto
import dev.wckdboy.autobot.shared.panic.PinRules
import dev.wckdboy.autobot.shared.panic.PinVault
import dev.wckdboy.autobot.shared.panic.UnlockOutcome
import dev.wckdboy.autobot.shared.panic.UnlockPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The result of a PIN typed on the lock screen. */
sealed interface PinAttempt {
    /** The back-off still runs: nothing was checked or counted. */
    data class Refused(val waitMillis: Long) : PinAttempt

    data class Done(val outcome: UnlockOutcome) : PinAttempt
}

/**
 * The app PIN, the duress PIN and the failed-attempt counter ([LockRecord] in [PinLockStore]).
 *
 * Every lock-screen attempt is persisted as failed before the PIN is checked
 * ([UnlockPolicy.begin]) and resolved after, so force-quitting cannot reset the counter. Nothing
 * here logs, and no exception message carries a PIN or a verifier.
 */
class PinLock(
    private val store: PinLockStore,
    crypto: PinCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    private val vault = PinVault(crypto)
    private val mutex = Mutex()
    private val _record = MutableStateFlow(store.read())

    /** The current record (also before the app's data opens). */
    val record: StateFlow<LockRecord> = _record.asStateFlow()

    val hasPin: Boolean get() = _record.value.hasPin

    fun status(): LockStatus = UnlockPolicy.status(_record.value, clock())

    /** Re-reads the file (after a wipe rewrote it). */
    fun reload() {
        _record.value = store.read()
    }

    /** One lock-screen attempt: counted, checked, resolved and persisted. */
    suspend fun attempt(pin: String): PinAttempt = mutex.withLock {
        val now = clock()
        val current = _record.value
        val begun = UnlockPolicy.begin(current, now) ?: return PinAttempt.Refused(UnlockPolicy.waitMillis(current, now))
        // An attempt that cannot be recorded is not allowed: an unrecorded guess is a free guess.
        if (runCatching { save(begun) }.isFailure) return PinAttempt.Refused(0)
        val match = withContext(compute) { vault.match(pin, begun) }
        val outcome = UnlockPolicy.resolve(begun, match, clock())
        // A wipe outcome is persisted by the wipe's own journal; the counter stays as begun.
        if (outcome !is UnlockOutcome.Wipe) save(outcome.record)
        PinAttempt.Done(outcome)
    }

    /** Checks the app PIN without counting (Settings, inside the unlocked app). */
    suspend fun verify(pin: String): Boolean {
        val real = _record.value.pin ?: return false
        return withContext(compute) { vault.matches(pin, real) }
    }

    /** Sets the first PIN (no PIN yet). */
    suspend fun setPin(pin: String, confirm: String): String? = mutex.withLock {
        if (hasPin) return "A PIN is already set"
        PinRules.confirmProblem(pin, confirm)?.let { return it }
        val verifier = withContext(compute) { vault.create(pin) }
        save(_record.value.copy(pin = verifier, failures = 0, lastFailureAt = 0))
        null
    }

    /** Replaces the PIN; the duress PIN must still differ from the new one. */
    suspend fun changePin(current: String, pin: String, confirm: String): String? = mutex.withLock {
        if (!verify(current)) return WRONG_PIN
        PinRules.confirmProblem(pin, confirm)?.let { return it }
        val duress = _record.value.duress
        if (duress != null && withContext(compute) { vault.matches(pin, duress) }) return "Your PIN must differ from the duress PIN"
        save(_record.value.copy(pin = withContext(compute) { vault.create(pin) }))
        null
    }

    /** Removes the PIN, and with it the duress PIN and the wipe threshold. */
    suspend fun removePin(current: String): String? = mutex.withLock {
        if (!verify(current)) return WRONG_PIN
        save(_record.value.withoutPin())
        null
    }

    suspend fun setDuress(current: String, duress: String, confirm: String): String? = mutex.withLock {
        if (!verify(current)) return WRONG_PIN
        PinRules.confirmProblem(duress, confirm)?.let { return it }
        val real = _record.value.pin ?: return WRONG_PIN
        PinRules.duressProblem(duress, withContext(compute) { vault.matches(duress, real) })?.let { return it }
        save(_record.value.copy(duress = withContext(compute) { vault.create(duress) }))
        null
    }

    suspend fun clearDuress(): Unit = mutex.withLock { save(_record.value.copy(duress = null)) }

    /** Turning the wipe on needs the PIN; turning it off does not. */
    suspend fun setWipeAfter(attempts: Int, current: String?): String? = mutex.withLock {
        if (attempts > 0 && (current == null || !verify(current))) return WRONG_PIN
        save(UnlockPolicy.withWipeAfter(_record.value, attempts))
        null
    }

    suspend fun setWipeModels(enabled: Boolean): Unit = mutex.withLock { save(_record.value.copy(wipeModels = enabled)) }

    suspend fun setBiometrics(enabled: Boolean): Unit = mutex.withLock { save(_record.value.copy(biometrics = enabled)) }

    /** True once after a duress wipe: this start opens unlocked, past the welcome. */
    suspend fun consumeQuietStart(): Boolean = mutex.withLock {
        val r = _record.value
        if (!r.quietStart) return false
        save(r.copy(quietStart = false))
        true
    }

    private suspend fun save(record: LockRecord) {
        withContext(io) { store.write(record) }
        _record.value = record
    }

    override fun toString(): String = "PinLock(<redacted>)"

    companion object {
        const val WRONG_PIN = "Wrong PIN"
    }
}
