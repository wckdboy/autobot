package dev.wckdboy.autobot.core.security

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A secret value (e.g. an API key) that refuses to print itself.
 *
 * [toString] is always redacted so the value cannot leak through string templates, logs,
 * crash messages or data-class `toString` of containing objects. Use [reveal] only at the
 * point where the raw value is actually required (e.g. building an `Authorization` header).
 */
class Secret(private val value: String) {
    /** Returns the raw secret. Never log the result. */
    fun reveal(): String = value

    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = REDACTED
    override fun equals(other: Any?): Boolean = other is Secret && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        const val REDACTED = "Secret(██████)"
    }
}

/**
 * Encrypted storage for API keys and other credentials.
 *
 * Each secret lives in its own app-private file `files/secrets/<ref>.bin`, encrypted with the
 * Keystore secrets KEK (AES-256-GCM). The reference string is bound as associated data so blobs
 * cannot be swapped between references. Values are never logged, and this class has no
 * listing API that would return secrets in bulk.
 */
@Singleton
class SecretStore internal constructor(
    private val directory: File,
    private val keyManager: KeyManager,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        keyManager: KeyManager,
    ) : this(File(context.filesDir, DIR), keyManager, Dispatchers.IO)

    /** Generates a new opaque reference for a secret. */
    fun newRef(): String = UUID.randomUUID().toString()

    /** Encrypts and stores [secret] under [ref], replacing any previous value. */
    suspend fun put(ref: String, secret: Secret): Unit = withContext(io) {
        val file = atomicFile(ref)
        directory.mkdirs()
        val plaintext = secret.reveal().encodeToByteArray()
        try {
            val sealed = keyManager.seal(KeyManager.ALIAS_SECRETS, plaintext, aad(ref))
            val out = file.startWrite()
            try {
                out.write(sealed)
                file.finishWrite(out)
            } catch (t: Throwable) {
                file.failWrite(out)
                throw t
            }
        } finally {
            plaintext.fill(0)
        }
    }

    /** Returns the secret stored under [ref], or `null` if absent or undecryptable. */
    suspend fun get(ref: String): Secret? = withContext(io) {
        val file = atomicFile(ref)
        if (!file.baseFile.exists()) return@withContext null
        runCatching {
            val plaintext = keyManager.open(KeyManager.ALIAS_SECRETS, file.readFully(), aad(ref))
            try {
                Secret(plaintext.decodeToString())
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    /** Whether a secret exists under [ref]. */
    suspend fun contains(ref: String): Boolean = withContext(io) { atomicFile(ref).baseFile.exists() }

    /** Deletes the secret stored under [ref]. */
    suspend fun delete(ref: String): Unit = withContext(io) { atomicFile(ref).delete() }

    /** Deletes every stored secret file. */
    fun deleteAll() {
        directory.deleteRecursively()
    }

    private fun atomicFile(ref: String): AtomicFile {
        require(REF_PATTERN.matches(ref)) { "Invalid secret reference" }
        return AtomicFile(File(directory, "$ref.bin"))
    }

    private fun aad(ref: String) = "autobot:secret:$ref".encodeToByteArray()

    override fun toString(): String = "SecretStore(<redacted>)"

    companion object {
        private const val DIR = "secrets"
        private val REF_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
