package dev.wckdboy.autobot.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.security.crypto.AesGcmEnvelope
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all hardware-backed key material of the app.
 *
 * - Key-encryption keys (KEKs) are AES-256-GCM keys inside the Android Keystore. They are created
 *   in StrongBox when the device has one and silently fall back to the TEE otherwise.
 * - The SQLCipher database passphrase is 32 random bytes generated on first use, wrapped with the
 *   database KEK and stored in an app-private file (`files/keys/db_passphrase.bin`). The raw
 *   passphrase never touches disk and is never logged.
 *
 * Instances never expose key bytes of Keystore keys (they are non-exportable).
 */
@Singleton
class KeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val random = SecureRandom()
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    /** Directory holding wrapped (encrypted) key blobs. */
    val keyDirectory: File get() = File(context.filesDir, KEY_DIR)

    /** All Keystore aliases this app may create; used by panic wipe. */
    val aliases: List<String> = listOf(ALIAS_DATABASE, ALIAS_SECRETS, ALIAS_MEDIA)

    @Volatile
    private var mediaKeyCache: SecretKey? = null

    /** Whether the given alias is backed by StrongBox; `null` if the key does not exist yet. */
    @Volatile
    var lastKeyStrongBoxBacked: Boolean? = null
        private set

    /** Returns the Keystore key for [alias], generating it on first use. */
    @Synchronized
    fun getOrCreateKey(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generateKey(alias, strongBox = true).also { lastKeyStrongBoxBacked = true }
        } catch (_: StrongBoxUnavailableException) {
            generateKey(alias, strongBox = false).also { lastKeyStrongBoxBacked = false }
        }
    }

    /** Encrypts [plaintext] with the KEK identified by [alias]. */
    fun seal(alias: String, plaintext: ByteArray, aad: ByteArray? = null): ByteArray =
        AesGcmEnvelope.seal(getOrCreateKey(alias), plaintext, aad)

    /** Decrypts an envelope produced by [seal]. */
    fun open(alias: String, envelope: ByteArray, aad: ByteArray? = null): ByteArray =
        AesGcmEnvelope.open(getOrCreateKey(alias), envelope, aad)

    /**
     * Returns the SQLCipher passphrase in SQLCipher raw-key form (`x'<64 hex chars>'`), which
     * skips the PBKDF2 derivation because the key is already uniformly random.
     *
     * The returned array is a fresh copy; callers should zero it once handed to SQLCipher.
     */
    @Synchronized
    fun databasePassphrase(): ByteArray {
        val file = AtomicFile(File(keyDirectory, DB_PASSPHRASE_FILE))
        val raw: ByteArray = if (file.baseFile.exists()) {
            open(ALIAS_DATABASE, file.readFully(), DB_PASSPHRASE_AAD)
        } else {
            val generated = ByteArray(DB_PASSPHRASE_BYTES).also(random::nextBytes)
            val wrapped = seal(ALIAS_DATABASE, generated, DB_PASSPHRASE_AAD)
            keyDirectory.mkdirs()
            val out = file.startWrite()
            try {
                out.write(wrapped)
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
            generated
        }
        return try {
            rawKeyLiteral(raw)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Software AES-256 key for bulk media (generated images, init images, masks).
     *
     * Large blobs are too slow to push through the Keystore (especially StrongBox), so a random
     * data key is generated once, wrapped with the [ALIAS_MEDIA] KEK and kept in
     * `files/keys/media_key.bin`. The unwrapped key lives only in process memory.
     */
    @Synchronized
    fun mediaKey(): SecretKey {
        mediaKeyCache?.let { return it }
        val file = AtomicFile(File(keyDirectory, MEDIA_KEY_FILE))
        val raw: ByteArray = if (file.baseFile.exists()) {
            open(ALIAS_MEDIA, file.readFully(), MEDIA_KEY_AAD)
        } else {
            val generated = ByteArray(MEDIA_KEY_BYTES).also(random::nextBytes)
            val wrapped = seal(ALIAS_MEDIA, generated, MEDIA_KEY_AAD)
            keyDirectory.mkdirs()
            val out = file.startWrite()
            try {
                out.write(wrapped)
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
            generated
        }
        return try {
            SecretKeySpec(raw, "AES").also { mediaKeyCache = it }
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Deletes every Keystore alias and every wrapped key file. After this, previously encrypted
     * data (database, secrets, media) is cryptographically unrecoverable.
     */
    @Synchronized
    fun destroyAllKeys() {
        mediaKeyCache = null
        aliases.forEach { alias ->
            runCatching { if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias) }
        }
        keyDirectory.deleteRecursively()
        lastKeyStrongBoxBacked = null
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    override fun toString(): String = "KeyManager(<redacted>)"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_DATABASE = "autobot.kek.database.v1"
        const val ALIAS_SECRETS = "autobot.kek.secrets.v1"
        const val ALIAS_MEDIA = "autobot.kek.media.v1"
        private const val MEDIA_KEY_FILE = "media_key.bin"
        private const val MEDIA_KEY_BYTES = 32
        private val MEDIA_KEY_AAD = "autobot:media-key:v1".encodeToByteArray()
        private const val KEY_DIR = "keys"
        private const val DB_PASSPHRASE_FILE = "db_passphrase.bin"
        private const val DB_PASSPHRASE_BYTES = 32
        private val DB_PASSPHRASE_AAD = "autobot:db-passphrase:v1".encodeToByteArray()

        /** Formats [key] as an SQLCipher raw key literal: `x'0011…ff'`. */
        internal fun rawKeyLiteral(key: ByteArray): ByteArray {
            val hex = "0123456789abcdef"
            val out = ByteArray(3 + key.size * 2)
            out[0] = 'x'.code.toByte()
            out[1] = '\''.code.toByte()
            key.forEachIndexed { i, b ->
                val v = b.toInt() and 0xFF
                out[2 + i * 2] = hex[v ushr 4].code.toByte()
                out[3 + i * 2] = hex[v and 0x0F].code.toByte()
            }
            out[out.size - 1] = '\''.code.toByte()
            return out
        }
    }
}
