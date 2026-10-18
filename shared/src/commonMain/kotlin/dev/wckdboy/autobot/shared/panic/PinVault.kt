package dev.wckdboy.autobot.shared.panic

/**
 * The platform half of a PIN verifier. Android: PBKDF2WithHmacSHA256 and an HMAC key in the
 * Keystore. iOS: CommonCrypto PBKDF2 and an HMAC key in the Keychain. Strings only, so Swift
 * implements it without byte-array bridging.
 */
interface PinCrypto {
    /** 16 fresh random bytes, base64. */
    fun newSalt(): String

    /** HMAC-SHA256(device PIN key, PBKDF2-HMAC-SHA256([pin], [salt], [iterations], 32 bytes)), base64. */
    fun derive(pin: String, salt: String, iterations: Int): String
}

/** Which PIN an attempt matched. */
enum class PinMatch { REAL, DURESS, NONE }

/** Builds and checks [PinVerifier]s. */
class PinVault(private val crypto: PinCrypto) {

    fun create(pin: String): PinVerifier {
        val salt = crypto.newSalt()
        return PinVerifier(salt, crypto.derive(pin, salt, ITERATIONS), ITERATIONS)
    }

    fun matches(pin: String, verifier: PinVerifier): Boolean =
        constantTimeEquals(crypto.derive(pin, verifier.salt, verifier.iterations), verifier.tag)

    /**
     * Checks [pin] against the real and the duress PIN. Both derivations always run (against a
     * stand-in that never matches when there is no duress PIN) and are compared in constant
     * time, so how long a check takes does not say which PIN, if any, was entered.
     */
    fun match(pin: String, record: LockRecord): PinMatch {
        val real = record.pin ?: return PinMatch.NONE
        val duress = record.duress ?: PinVerifier(crypto.newSalt(), "", real.iterations)
        val isReal = constantTimeEquals(crypto.derive(pin, real.salt, real.iterations), real.tag)
        val isDuress = constantTimeEquals(crypto.derive(pin, duress.salt, duress.iterations), duress.tag)
        return when {
            isReal -> PinMatch.REAL
            isDuress -> PinMatch.DURESS
            else -> PinMatch.NONE
        }
    }

    companion object {
        /** PBKDF2 rounds: ~100-250 ms per derivation on a current phone; the device key does the rest. */
        const val ITERATIONS = 120_000

        /** Equal strings, compared without an early exit. */
        fun constantTimeEquals(a: String, b: String): Boolean {
            var diff = a.length xor b.length
            val n = maxOf(a.length, b.length)
            for (i in 0 until n) {
                val x = if (i < a.length) a[i].code else 0
                val y = if (i < b.length) b[i].code else 0
                diff = diff or (x xor y)
            }
            return diff == 0
        }
    }
}

/** What a new PIN must look like. */
object PinRules {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    /** Why [pin] cannot be an app PIN, or null if it can. */
    fun problem(pin: String): String? = when {
        pin.any { it !in '0'..'9' } -> "Use digits only"
        pin.length < MIN_LENGTH || pin.length > MAX_LENGTH -> "Use $MIN_LENGTH to $MAX_LENGTH digits"
        pin.toSet().size == 1 -> "Avoid one repeated digit"
        else -> null
    }

    /** Why [candidate] cannot be the duress PIN ([matchesReal]: it opens the app normally). */
    fun duressProblem(candidate: String, matchesReal: Boolean): String? =
        problem(candidate) ?: if (matchesReal) "The duress PIN must differ from your PIN" else null

    /** Why a new PIN and its confirmation cannot be saved, or null. */
    fun confirmProblem(pin: String, confirm: String): String? =
        problem(pin) ?: if (pin != confirm) "The PINs do not match" else null
}
