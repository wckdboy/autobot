package dev.wckdboy.autobot.feature.imagine.gallery

import android.app.Application
import android.content.Intent
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.imagine.viewer.GalleryImages
import dev.wckdboy.autobot.feature.imagine.viewer.ViewerAction
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class GalleryUiState(
    val items: List<GalleryItem> = emptyList(),
    val total: Int = 0,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    /** The image the viewer is open on. */
    val open: GalleryItem? = null,
    /** The viewer's pages: the scope it was opened with (a chat's batch), else the filtered grid. */
    val viewerItems: List<GalleryItem> = emptyList(),
    /** Opened from elsewhere (chat, home): closing the viewer goes back there. */
    val returnOnClose: Boolean = false,
    val canUpscale: Boolean = false,
    /** What the viewer is doing, or how it went (saving, upscaling). */
    val busy: String? = null,
    val message: String? = null,
    val loaded: Boolean = false,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    private val gallery: GalleryRepository,
    val images: GalleryImages,
) : AndroidViewModel(application) {

    private data class Local(
        val favoritesOnly: Boolean = false,
        val openId: String? = null,
        val scope: List<String> = emptyList(),
        /** Unsaved incognito items of the scope (in memory, not in the table). */
        val unsaved: Map<String, GalleryItem> = emptyMap(),
        val returnOnClose: Boolean = false,
        val canUpscale: Boolean = false,
        val busy: String? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The search text; filtering follows it after a short pause (clearing applies at once). */
    private val query = MutableStateFlow("")
    /** Sized by memory, not count: a few large thumbnails must not pin hundreds of megabytes. */
    private val thumbs = object : LruCache<String, ImageBitmap>(THUMB_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    val uiState: StateFlow<GalleryUiState> = combine(gallery.observe(), local, query.debounce { if (it.isBlank()) 0L else QUERY_DEBOUNCE_MS }) { all, l, q ->
        val filtered = all.filter { item ->
            (!l.favoritesOnly || item.favorite) &&
                (q.isBlank() || item.prompt.contains(q, ignoreCase = true) || item.engine.contains(q, ignoreCase = true))
        }
        val byId = all.associateBy { it.id }
        val pages = viewerPages(l.scope, byId, l.unsaved, filtered, l.openId)
        GalleryUiState(
            items = filtered,
            total = all.size,
            favoritesOnly = l.favoritesOnly,
            query = q,
            open = l.openId?.let { byId[it] ?: l.unsaved[it] },
            viewerItems = pages,
            returnOnClose = l.returnOnClose,
            canUpscale = l.canUpscale,
            busy = l.busy,
            message = l.message,
            loaded = true,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    /** Decrypts and decodes a thumbnail (cached). */
    suspend fun thumbnail(id: String): ImageBitmap? {
        thumbs.get(id)?.let { return it }
        val bytes = runCatching { gallery.thumbnail(id) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { ImageCodec.decode(bytes, THUMB_SIDE)?.asImageBitmap() }?.also { thumbs.put(id, it) }
    }

    fun setFavoritesOnly(enabled: Boolean) = local.update { it.copy(favoritesOnly = enabled) }
    fun setQuery(text: String) {
        query.value = text
    }

    /** Opens the viewer on [id]; with a [scope] it swipes through those images only. */
    fun open(id: String, scope: List<String> = emptyList(), returnOnClose: Boolean = false) {
        local.update { it.copy(openId = id, scope = scope, returnOnClose = returnOnClose, busy = null) }
        viewModelScope.launch {
            val unsaved = (scope + id).distinct().filter(IncognitoImages::isUnsaved).mapNotNull { u -> gallery.get(u)?.let { u to it } }.toMap()
            val can = images.canUpscale()
            local.update { it.copy(unsaved = unsaved, canUpscale = can) }
        }
    }

    fun close() = local.update { it.copy(openId = null, scope = emptyList(), returnOnClose = false, busy = null) }

    /** In-place viewer actions; handoffs to the studio go through navigation. */
    fun act(action: ViewerAction, item: GalleryItem, launchIntent: (Intent) -> Unit) {
        // An unsaved incognito image is written nowhere except by Keep.
        if (IncognitoImages.isUnsaved(item.id) && action !in ViewerAction.UNSAVED) return
        viewModelScope.launch {
            when (action) {
                ViewerAction.KEEP -> {
                    val id = runCatching { gallery.keep(item.id) }.getOrNull()
                    local.update { it.copy(busy = if (id != null) "saved to the gallery" else "could not save") }
                }
                ViewerAction.FAVORITE -> images.toggleFavorite(item)
                ViewerAction.DELETE -> {
                    // The viewer stays open on the next image (the previous one at the end), or closes.
                    val pages = uiState.value.viewerItems
                    val at = pages.indexOfFirst { it.id == item.id }
                    val next = pages.getOrNull(at + 1) ?: pages.getOrNull(at - 1)
                    images.delete(item.id)
                    thumbs.remove(item.id)
                    local.update { it.copy(openId = next?.id, scope = it.scope - item.id, message = "Deleted") }
                }
                ViewerAction.SAVE -> {
                    local.update { it.copy(busy = "saving…") }
                    val ok = images.export(item)
                    local.update { it.copy(busy = if (ok) "saved to Pictures/Autobot" else "could not save") }
                }
                ViewerAction.SHARE -> images.shareIntent(item)?.let(launchIntent) ?: local.update { it.copy(busy = "could not share") }
                ViewerAction.UPSCALE -> upscale(item)
                else -> Unit
            }
        }
    }

    /** Upscales an image 4× on the NPU, stores it as a new item and shows it. */
    private suspend fun upscale(item: GalleryItem) {
        if (local.value.busy?.startsWith("upscaling") == true) return
        local.update { it.copy(busy = "upscaling 4× on the npu…") }
        val result = runCatching { images.upscale(item) }
        local.update {
            result.fold(
                // Shown at once: the viewer moves to the new image (in the grid's order it is first).
                { copy -> it.copy(openId = copy.id, scope = if (it.scope.isEmpty()) it.scope else listOf(copy.id) + it.scope, busy = "upscaled to ${copy.width}×${copy.height}") },
                { e -> it.copy(busy = "upscale failed: ${e.message}") },
            )
        }
    }

    fun consumeMessage() = local.update { it.copy(message = null) }

    companion object {
        private const val THUMB_CACHE_KB = 32 * 1024
        private const val QUERY_DEBOUNCE_MS = 150L

        /** Grid cells are small; a thumbnail is never decoded larger than this. */
        private const val THUMB_SIDE = 512

        /**
         * The viewer's pages: the [scope]'s images in its order (gallery rows, else unsaved ones),
         * or the [filtered] grid; an [openId] outside the grid (filtered out) is still shown.
         */
        internal fun viewerPages(
            scope: List<String>,
            byId: Map<String, GalleryItem>,
            unsaved: Map<String, GalleryItem>,
            filtered: List<GalleryItem>,
            openId: String?,
        ): List<GalleryItem> {
            if (scope.isNotEmpty()) return scope.mapNotNull { byId[it] ?: unsaved[it] }
            if (openId == null || filtered.any { it.id == openId }) return filtered
            return listOfNotNull(byId[openId] ?: unsaved[openId])
        }
    }
}
