package dev.wckdboy.autobot.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.core.security.crypto.AesGcmEnvelope
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Images attached to chat messages. Stored AES-256-GCM sealed under `files/attachments/` with the
 * wrapped media key (the id is associated data). An engine that needs a plain file gets a
 * short-lived copy in the cache via [openPlain], which the caller deletes when done.
 */
@Singleton
class AttachmentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val plainDir: File get() = File(context.cacheDir, PLAIN_DIR).apply { mkdirs() }

    /** Seals [bytes] (an encoded image) and returns its id. */
    suspend fun put(bytes: ByteArray): String = withContext(io) {
        val id = UUID.randomUUID().toString()
        val sealed = AesGcmEnvelope.seal(keyManager.mediaKey(), bytes, aad(id))
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file(id))) {
            tmp.delete()
            throw IOException("Could not store attachment")
        }
        id
    }

    suspend fun bytes(id: String): ByteArray? = withContext(io) {
        val f = file(id)
        if (!f.isFile) return@withContext null
        try {
            AesGcmEnvelope.open(keyManager.mediaKey(), f.readBytes(), aad(id))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** Decrypts [id] into a private cache file for an engine to read; delete it afterwards. */
    suspend fun openPlain(id: String): File? {
        val bytes = bytes(id) ?: return null
        return withContext(io) { File(plainDir, "${UUID.randomUUID()}.img").apply { writeBytes(bytes) } }
    }

    suspend fun delete(id: String) = withContext(io) { file(id).delete() }

    /** Removes decrypted copies left behind by a crashed request. */
    suspend fun clearPlainCopies() = withContext(io) { plainDir.listFiles()?.forEach { it.delete() } }

    private fun file(id: String): File {
        require(id.matches(SAFE_ID)) { "Invalid attachment id" }
        return File(dir, id + EXT)
    }

    private fun aad(id: String) = "attachment:$id".encodeToByteArray()

    companion object {
        private const val DIR = "attachments"
        private const val PLAIN_DIR = "attachments-plain"
        private const val EXT = ".img"
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
