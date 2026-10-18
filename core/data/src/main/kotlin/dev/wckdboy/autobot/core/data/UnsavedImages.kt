package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Images from incognito runs (an incognito chat's `generate_image`, the Image screen with
 * Incognito on): process memory only, under [IncognitoImages.ID_PREFIX] ids that
 * [GalleryRepository] resolves here. Nothing is written to disk, the gallery or the Studio until
 * the user taps Save ([GalleryRepository.keep]). Entries are dropped with their incognito chat,
 * when the Image screen's incognito results are replaced, by a panic wipe, and with the process;
 * their bytes are zeroed when dropped. At most [MAX] are held, oldest dropped first.
 */
@Singleton
class UnsavedImages @Inject constructor() {

    /** An unsaved image: [owner] is the incognito chat (session id) or [IMAGE_SCREEN]. */
    class Entry(val item: GalleryItem, val image: ByteArray, val thumbnail: ByteArray, val owner: String)

    private val entries = LinkedHashMap<String, Entry>()

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Unsaved ids the user saved, mapped to their new gallery ids. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    fun newId(): String = IncognitoImages.ID_PREFIX + UUID.randomUUID()

    @Synchronized
    fun put(item: GalleryItem, image: ByteArray, thumbnail: ByteArray, owner: String) {
        require(IncognitoImages.isUnsaved(item.id)) { "Unsaved images need an incognito id" }
        entries[item.id] = Entry(item, image, thumbnail, owner)
        while (entries.size > MAX) remove(entries.keys.first())
    }

    @Synchronized
    fun get(id: String): Entry? = entries[id]

    @Synchronized
    fun ids(): List<String> = entries.keys.toList()

    internal fun markSaved(id: String, galleryId: String) = _saved.update { it + (id to galleryId) }

    /** Drops every image of [owner] (and of its subagents, whose session ids start with it). */
    @Synchronized
    fun drop(owner: String) {
        entries.values.filter { it.owner.startsWith(owner) }.map { it.item.id }.forEach(::remove)
    }

    @Synchronized
    fun clear() {
        entries.keys.toList().forEach(::remove)
        _saved.value = emptyMap()
    }

    private fun remove(id: String) {
        entries.remove(id)?.let {
            it.image.fill(0)
            it.thumbnail.fill(0)
        }
    }

    companion object {
        /** The Image screen's owner tag. */
        const val IMAGE_SCREEN = "image-screen"

        /** Memory bound: a handful of batches. */
        const val MAX = 24
    }
}
