package dev.wckdboy.autobot.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.core.security.crypto.AesGcmEnvelope
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Generated images. Metadata lives in the encrypted database; pixels are AES-256-GCM sealed
 * files under `files/gallery/` using the wrapped media key, with the item id as associated data
 * so files cannot be swapped between rows. Nothing is written to shared storage unless the user
 * explicitly exports an image.
 */
@Singleton
class GalleryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseHolder,
    private val keyManager: KeyManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun observe(): Flow<List<GalleryItem>> = flow { emitAll(database.get().galleryDao().observeAll()) }.flowOn(io)

    suspend fun get(id: String): GalleryItem? = withContext(io) { database.get().galleryDao().get(id) }

    fun newId(): String = UUID.randomUUID().toString()

    /** Stores [image] (full size) and [thumbnail] encrypted, then the row. */
    suspend fun save(item: GalleryItem, image: ByteArray, thumbnail: ByteArray) = withContext(io) {
        write(file(item.id, IMAGE), item.id + IMAGE, image)
        write(file(item.id, THUMB), item.id + THUMB, thumbnail)
        database.get().galleryDao().upsert(item)
    }

    suspend fun image(id: String): ByteArray? = withContext(io) { read(file(id, IMAGE), id + IMAGE) }

    suspend fun thumbnail(id: String): ByteArray? = withContext(io) { read(file(id, THUMB), id + THUMB) }

    suspend fun setFavorite(id: String, favorite: Boolean) = withContext(io) { database.get().galleryDao().setFavorite(id, favorite) }

    suspend fun delete(id: String) = withContext(io) {
        database.get().galleryDao().delete(id)
        file(id, IMAGE).delete()
        file(id, THUMB).delete()
    }

    private fun file(id: String, suffix: String): File {
        require(id.matches(SAFE_ID)) { "Invalid gallery id" }
        return File(dir, id + suffix)
    }

    private fun write(file: File, aad: String, bytes: ByteArray) {
        val sealed = AesGcmEnvelope.seal(keyManager.mediaKey(), bytes, aad.encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("Could not store ${file.name}")
        }
    }

    private fun read(file: File, aad: String): ByteArray? {
        if (!file.isFile) return null
        return try {
            AesGcmEnvelope.open(keyManager.mediaKey(), file.readBytes(), aad.encodeToByteArray())
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        private const val DIR = "gallery"
        private const val IMAGE = ".img"
        private const val THUMB = ".thumb"
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
