package dev.wckdboy.autobot.core.security

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.security.panic.PinAttempt
import dev.wckdboy.autobot.core.security.panic.PinLock
import dev.wckdboy.autobot.shared.panic.LockStatus
import dev.wckdboy.autobot.shared.panic.UnlockOutcome
import dev.wckdboy.autobot.shared.panic.WipeLanding
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the lock screen shows after a PIN. */
sealed interface PinResult {
    data object Unlocked : PinResult

    /** A wrong PIN, or an attempt refused while the back-off runs (nothing counted then). */
    data class Wrong(val status: LockStatus, val refused: Boolean = false) : PinResult
}

/** Whether the UI is currently allowed to show protected content. */
sealed interface LockState {
    data object Locked : LockState
    data object Unlocked : LockState
}

/**
 * App lock gate.
 *
 * - Starts [LockState.Locked]. [configure] is called once persisted settings are loaded; if the
 *   lock is disabled (or the device has no secure credential) the app unlocks immediately.
 * - Observes [ProcessLifecycleOwner]: when the whole app returns to the foreground after being in
 *   the background for at least [timeoutMillis] it re-locks. A timeout of `0` locks on every
 *   background transition.
 * - [authenticate] shows a [BiometricPrompt] that accepts Class 3 biometrics or the device
 *   PIN/pattern/password ([BIOMETRIC_STRONG] or [DEVICE_CREDENTIAL]).
 *
 * The background timestamp uses [SystemClock.elapsedRealtime] so changing the wall clock cannot
 * be used to bypass the lock.
 *
 * With an app PIN ([PinLock]) the lock screen asks for the PIN instead and the device credential
 * no longer opens the app. Biometrics ([BIOMETRIC_STRONG] only) may, while no wrong PIN was
 * entered since the last unlock; they never count as attempts and never trigger the duress PIN,
 * which works on the PIN pad only. Reaching the wipe threshold or entering the duress PIN hands
 * over to [PanicWipe.wipeAndRestart]; for the duress PIN the screen behaves as for the real one.
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pinLock: PinLock,
    private val panicWipe: Lazy<PanicWipe>,
) : DefaultLifecycleObserver {

    private val _state = MutableStateFlow<LockState>(LockState.Locked)

    /** Current lock state; UI shows the lock screen while [LockState.Locked]. */
    val state: StateFlow<LockState> = _state.asStateFlow()

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        private set

    private val _ready = MutableStateFlow(false)

    /** `false` until [configure] has been called once; the UI shows nothing until then. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var backgroundedAt: Long? = null

    private var installed = false

    /** Registers with [ProcessLifecycleOwner]. Call once from `Application.onCreate`. */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Applies persisted lock preferences. */
    fun configure(enabled: Boolean, timeoutMillis: Long) {
        this.enabled = enabled
        this.timeoutMillis = timeoutMillis.coerceAtLeast(0)
        val firstConfiguration = !_ready.value
        if (firstConfiguration && _quietStart.value) {
            // Locked until the first frame prints the lock screen away (finishQuietStart).
            _state.value = LockState.Locked
        } else if (!isLockActive()) {
            _state.value = LockState.Unlocked
        } else if (firstConfiguration) {
            _state.value = LockState.Locked
        }
        _ready.value = true
    }

    /**
     * `true` while an app PIN is set (a PIN always locks: the wipe and the duress PIN depend on
     * it), or the lock is enabled and the device can authenticate the user.
     */
    fun isLockActive(): Boolean = pinLock.hasPin || (enabled && canAuthenticate())

    /** The lock screen shows the PIN pad. */
    val usesPin: Boolean get() = pinLock.hasPin

    /** Biometrics may open the app now (PIN mode: allowed in Settings and no wrong PIN since the last unlock). */
    fun biometricsOffered(): Boolean =
        if (usesPin) pinLock.record.value.biometricsAllowed && canUseBiometrics() else true

    private fun canUseBiometrics(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Counter, warning and back-off for the lock screen. */
    fun pinStatus(): LockStatus = pinLock.status()

    private val _quietStart = MutableStateFlow(false)

    /**
     * This start follows a duress wipe: the lock screen shows for a moment and prints away as if
     * the PIN had just been accepted ([finishQuietStart]).
     */
    val quietStart: StateFlow<Boolean> = _quietStart.asStateFlow()

    /** Marks this start as the end of a duress unlock (call before [configure]). */
    fun beginQuietStart() {
        _quietStart.value = true
    }

    fun finishQuietStart() {
        if (!_quietStart.value) return
        _quietStart.value = false
        unlocked()
    }

    /**
     * Checks a PIN typed on the lock screen. The real PIN unlocks; the duress PIN and the last
     * allowed wrong PIN start a wipe that restarts the app (this call then never returns), the
     * duress one with no visible difference from the real PIN until the restart.
     */
    suspend fun submitPin(pin: String): PinResult = when (val attempt = pinLock.attempt(pin)) {
        is PinAttempt.Refused -> PinResult.Wrong(pinLock.status(), refused = true)
        is PinAttempt.Done -> when (val outcome = attempt.outcome) {
            is UnlockOutcome.Unlocked -> {
                unlocked()
                PinResult.Unlocked
            }
            is UnlockOutcome.Duress -> panicWipe.get().wipeAndRestart(WipeLanding.QUIET, carriedPin = outcome.record.duress)
            is UnlockOutcome.Wipe -> panicWipe.get().wipeAndRestart(WipeLanding.FIRST_RUN)
            is UnlockOutcome.Failed -> PinResult.Wrong(pinLock.status())
        }
    }

    private fun unlocked() {
        backgroundedAt = null
        _state.value = LockState.Unlocked
    }

    /** Whether a Class 3 biometric or a device credential is enrolled. */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Locks immediately (e.g. from a "lock now" action). */
    fun lock() {
        if (isLockActive()) _state.value = LockState.Locked
    }

    /**
     * Prompts the user. On success the state becomes [LockState.Unlocked]; failures and
     * cancellations leave it [LockState.Locked] and report through [onError].
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        onError: (CharSequence) -> Unit = {},
    ) {
        if (!isLockActive()) {
            _state.value = LockState.Unlocked
            return
        }
        if (usesPin && !biometricsOffered()) return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // A wrong PIN typed while the prompt was up withdraws biometrics.
                    if (usesPin && !pinLock.record.value.biometricsAllowed) return
                    unlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // With a PIN, closing the prompt just leaves the PIN pad: nothing to report.
                    if (usesPin && errorCode in DISMISSED) return
                    onError(errString)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let(::setSubtitle) }
            .apply {
                // With an app PIN the device credential must not open the app: biometrics only, the PIN pad is the fallback.
                if (usesPin) setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText(USE_PIN) else setAllowedAuthenticators(AUTHENTICATORS)
            }
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    /** When the app last opened another app's screen for a result (see [expectReturn]). */
    @Volatile
    private var pickerOpenedAt: Long? = null

    @Volatile
    private var inPicker = false

    /**
     * The app is opening a system screen for a result (photo picker, file picker). Coming back
     * from it soon does not re-lock even with a timeout of 0: the lock would tear down the
     * screen waiting for the result and lose it.
     */
    fun expectReturn() {
        pickerOpenedAt = SystemClock.elapsedRealtime()
    }

    override fun onStop(owner: LifecycleOwner) {
        val now = SystemClock.elapsedRealtime()
        backgroundedAt = now
        inPicker = pickerOpenedAt?.let { now - it < PICKER_START_MILLIS } == true
        pickerOpenedAt = null
        if (timeoutMillis == 0L && !inPicker) lock()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        val picker = inPicker
        inPicker = false
        if (shouldLock(SystemClock.elapsedRealtime() - since, timeoutMillis, picker)) lock()
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

        private const val USE_PIN = "Use PIN"

        private val DISMISSED = setOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_CANCELED,
        )
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L

        /** How soon after [expectReturn] the app must leave the foreground for it to count as a picker trip. */
        private const val PICKER_START_MILLIS = 5_000L

        /** With a timeout of 0, a picker trip longer than this still locks. */
        const val PICKER_GRACE_MILLIS = 2 * 60_000L

        /**
         * Whether coming back after [awayMillis] locks: the timeout, except that "lock at once" (0)
         * allows a short trip to a system picker. A set timeout applies to picker trips as well.
         */
        fun shouldLock(awayMillis: Long, timeoutMillis: Long, fromPicker: Boolean): Boolean =
            awayMillis >= if (fromPicker && timeoutMillis == 0L) PICKER_GRACE_MILLIS else timeoutMillis
    }
}
