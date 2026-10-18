package dev.wckdboy.autobot.core.security.panic

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.wckdboy.autobot.shared.panic.PinCrypto
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The device-bound key that turns a stretched PIN into a verifier tag. */
interface PinMacKey {
    fun mac(data: ByteArray): ByteArray

    /** Deletes the key: every verifier made with it stops matching. */
    fun destroy()
}

/**
 * HMAC-SHA256 key in the Android Keystore (StrongBox when available), never exported. It is kept
 * apart from the data keys: a duress wipe destroys those but keeps this one, so the duress PIN's
 * verifier still opens the emptied app. A wipe to first run deletes it too.
 */
class KeystorePinMacKey : PinMacKey {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return runCatching { generate(strongBox = true) }.getOrElse {
            runCatching { keyStore.deleteEntry(ALIAS) }
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(key())
        doFinal(data)
    }

    @Synchronized
    override fun destroy() {
        runCatching { if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS) }
    }

    override fun toString(): String = "KeystorePinMacKey(<redacted>)"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "autobot.kek.pin.v1"
    }
}

/** [PinCrypto] on Android: PBKDF2WithHmacSHA256, then the Keystore HMAC. */
class DevicePinCrypto(private val key: PinMacKey) : PinCrypto {
    private val random = SecureRandom()

    override fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    override fun derive(pin: String, salt: String, iterations: Int): String {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, Base64.getDecoder().decode(salt), iterations, 256)
        val stretched = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
        return try {
            Base64.getEncoder().encodeToString(key.mac(stretched))
        } finally {
            stretched.fill(0)
        }
    }

    override fun toString(): String = "DevicePinCrypto(<redacted>)"
}
