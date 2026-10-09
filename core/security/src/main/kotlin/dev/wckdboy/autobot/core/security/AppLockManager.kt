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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
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
        if (!isLockActive()) {
            _state.value = LockState.Unlocked
        } else if (firstConfiguration) {
            _state.value = LockState.Locked
        }
        _ready.value = true
    }

    /** `true` if the lock is enabled and the device can actually authenticate the user. */
    fun isLockActive(): Boolean = enabled && canAuthenticate()

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
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    backgroundedAt = null
                    _state.value = LockState.Unlocked
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let(::setSubtitle) }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (timeoutMillis == 0L) lock()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (SystemClock.elapsedRealtime() - since >= timeoutMillis) lock()
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    }
}
