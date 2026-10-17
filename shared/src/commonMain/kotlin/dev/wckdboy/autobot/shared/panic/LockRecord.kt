package dev.wckdboy.autobot.shared.panic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A PIN, stored as a verifier only: `tag = HMAC(device PIN key, PBKDF2-SHA256(pin, salt, iterations))`,
 * base64. The device PIN key is non-exportable (Android Keystore, iOS Keychain this-device-only),
 * so a copied verifier cannot be brute-forced off the phone. The PIN's length is not stored.
 */
@Serializable
data class PinVerifier(val salt: String, val tag: String, val iterations: Int = PinVault.ITERATIONS)

/**
 * Everything the lock screen needs before the app's encrypted data opens, persisted by the
 * platform in one small file next to (never inside) the app's data, written atomically and
 * synced before a PIN is checked.
 *
 * - [pin]: the app PIN. While set, the lock screen asks for it and the device passcode no longer
 *   opens the app (biometrics may, see [biometrics]).
 * - [duress]: a second PIN that unlocks into a silently wiped, empty app ([WipeLanding.QUIET]).
 * - [wipeAfter]: wipe after this many failed PIN attempts in a row (0 = off; [UnlockPolicy.WipeChoices]).
 * - [wipeModels]: a failed-unlock or duress wipe also deletes downloaded models (kept otherwise:
 *   large and not private).
 * - [failures] / [lastFailureAt]: wrong PINs in a row and when the last attempt began
 *   (epoch ms). An attempt counts as failed *before* the PIN is checked ([UnlockPolicy.begin]), so
 *   killing the app mid-check cannot erase it.
 * - [quietStart]: a duress wipe just finished; the next start opens unlocked, past the welcome.
 */
@Serializable
data class LockRecord(
    val pin: PinVerifier? = null,
    val duress: PinVerifier? = null,
    val wipeAfter: Int = 0,
    val wipeModels: Boolean = false,
    val biometrics: Boolean = true,
    val failures: Int = 0,
    val lastFailureAt: Long = 0,
    val quietStart: Boolean = false,
) {
    val hasPin: Boolean get() = pin != null

    /** Biometrics may open the app: a PIN is set, the user allows it, and no wrong PIN was entered since the last unlock. */
    val biometricsAllowed: Boolean get() = pin != null && biometrics && failures == 0

    /** The record with the PIN and every feature that depends on it removed. */
    fun withoutPin(): LockRecord = LockRecord(biometrics = biometrics)

    override fun toString(): String =
        "LockRecord(pin=${pin != null}, wipeAfter=$wipeAfter, wipeModels=$wipeModels, biometrics=$biometrics, failures=$failures)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(record: LockRecord): String = json.encodeToString(serializer(), record)

        /** A missing or unreadable record is an empty one (no PIN): the lock falls back to the device credential. */
        fun decode(text: String?): LockRecord =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: LockRecord()
    }
}
