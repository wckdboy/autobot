package dev.wckdboy.autobot.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.core.security.crypto.AesGcmEnvelope
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    private val unsaved: UnsavedImages,
) {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun observe(): Flow<List<GalleryItem>> = flow { emitAll(database.get().galleryDao().observeAll()) }.flowOn(io)

    /** The [limit] newest images (LIMIT in SQL). */
    fun observeRecent(limit: Int): Flow<List<GalleryItem>> = flow { emitAll(database.get().galleryDao().observeRecent(limit)) }.flowOn(io)

    suspend fun get(id: String): GalleryItem? =
        if (IncognitoImages.isUnsaved(id)) unsaved.get(id)?.item else withContext(io) { database.get().galleryDao().get(id) }

    fun newId(): String = UUID.randomUUID().toString()

    /** Stores [image] (full size) and [thumbnail] encrypted, then the row. */
    suspend fun save(item: GalleryItem, image: ByteArray, thumbnail: ByteArray) = withContext(io) {
        write(file(item.id, IMAGE), item.id + IMAGE, image)
        write(file(item.id, THUMB), item.id + THUMB, thumbnail)
        database.get().galleryDao().upsert(item)
    }

    suspend fun image(id: String): ByteArray? =
        if (IncognitoImages.isUnsaved(id)) unsaved.get(id)?.image else withContext(io) { read(file(id, IMAGE), id + IMAGE) }

    suspend fun thumbnail(id: String): ByteArray? =
        if (IncognitoImages.isUnsaved(id)) unsaved.get(id)?.thumbnail else withContext(io) { read(file(id, THUMB), id + THUMB) }

    /**
     * Saves an unsaved incognito image ([UnsavedImages]) into the gallery: the user tapped Save.
     * Returns its gallery id (the same one when tapped again), or null when the image is gone.
     */
    /** Unsaved incognito images the user saved, by unsaved id → gallery id. */
    val keptIncognito: StateFlow<Map<String, String>> get() = unsaved.saved

    suspend fun keep(id: String): String? {
        if (!IncognitoImages.isUnsaved(id)) return id
        unsaved.saved.value[id]?.let { return it }
        val entry = unsaved.get(id) ?: return null
        val galleryId = newId()
        save(entry.item.copy(id = galleryId, createdAt = System.currentTimeMillis()), entry.image.copyOf(), entry.thumbnail.copyOf())
        unsaved.markSaved(id, galleryId)
        return galleryId
    }

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
        } catch (_: Exception) { // wrong key, truncated or unreadable file (require() in the envelope, IO)
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
