package dev.wckdboy.autobot.feature.imagine.gallery

import android.app.Application
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.diffusion.Upscaler
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
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
    val open: GalleryItem? = null,
    val openImage: ImageBitmap? = null,
    val message: String? = null,
    val loaded: Boolean = false,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    private val gallery: GalleryRepository,
    private val upscaler: Upscaler,
) : AndroidViewModel(application) {

    private data class Local(
        val favoritesOnly: Boolean = false,
        val openId: String? = null,
        val openImage: ImageBitmap? = null,
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
        GalleryUiState(
            items = filtered,
            total = all.size,
            favoritesOnly = l.favoritesOnly,
            query = q,
            open = all.firstOrNull { it.id == l.openId },
            openImage = l.openImage,
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

    fun open(id: String) {
        local.update { it.copy(openId = id, openImage = null) }
        viewModelScope.launch {
            val image = runCatching { gallery.image(id) }.getOrNull()?.let { withContext(Dispatchers.Default) { ImageCodec.decode(it, 2048)?.asImageBitmap() } }
            local.update { if (it.openId == id) it.copy(openImage = image) else it }
        }
    }

    fun close() = local.update { it.copy(openId = null, openImage = null) }

    fun toggleFavorite(item: GalleryItem) {
        viewModelScope.launch { gallery.setFavorite(item.id, !item.favorite) }
    }

    fun delete(item: GalleryItem) {
        viewModelScope.launch {
            gallery.delete(item.id)
            thumbs.remove(item.id)
            local.update { it.copy(openId = null, openImage = null, message = "Deleted") }
        }
    }

    /** Upscales an image 4× on the NPU and stores the result as a new gallery item. */
    fun upscale(item: GalleryItem) {
        if (local.value.message?.startsWith("Upscaling") == true) return
        viewModelScope.launch {
            local.update { it.copy(message = "Upscaling 4× on the NPU…") }
            val result = runCatching {
                val bytes = gallery.image(item.id) ?: error("image unavailable")
                val started = System.currentTimeMillis()
                val png = upscaler.upscale(bytes)
                // Never the 4× PNG itself as the thumbnail: decoding it in the grid would take a 64 MB bitmap.
                val thumb = withContext(Dispatchers.Default) { ImageCodec.thumbnail(png) } ?: gallery.thumbnail(item.id) ?: error("cannot make a thumbnail")
                val copy = item.copy(
                    id = gallery.newId(),
                    createdAt = System.currentTimeMillis(),
                    width = item.width * 4,
                    height = item.height * 4,
                    engine = item.engine + " · 4× npu",
                    durationMs = System.currentTimeMillis() - started,
                    favorite = false,
                )
                gallery.save(copy, png, thumb)
                copy
            }
            local.update {
                result.fold(
                    { copy -> it.copy(openId = copy.id, openImage = null, message = "Upscaled to ${copy.width}×${copy.height}") },
                    { e -> it.copy(message = "Upscale failed: ${e.message}") },
                )
            }
            result.getOrNull()?.let { open(it.id) }
        }
    }

    /**
     * Writes a decrypted copy to `Pictures/Autobot` via MediaStore (no storage permission). This
     * deliberately leaves the encrypted vault, so the UI asks first.
     */
    fun export(item: GalleryItem) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                // Storage can refuse the new entry (full, no Pictures volume): a message, not a crash.
                runCatching { exportNow(item) }.getOrDefault(false)
            }
            local.update { it.copy(message = if (ok) "Exported to Pictures/Autobot" else "Export failed") }
        }
    }

    private suspend fun exportNow(item: GalleryItem): Boolean {
        val bytes = gallery.image(item.id) ?: return false
        val png = bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "autobot_${item.createdAt}_${item.seed}.${if (png) "png" else "jpg"}")
            put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Autobot")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }.onFailure { runCatching { resolver.delete(uri, null, null) } }.isSuccess
    }

    /** A1111-style "parameters" text, for pasting into other tools. */
    fun parametersText(item: GalleryItem): String = buildString {
        append(item.prompt)
        if (item.negativePrompt.isNotBlank()) append("\nNegative prompt: ").append(item.negativePrompt)
        append("\nSteps: ").append(item.steps)
        append(", Sampler: ").append(item.sampler)
        append(", CFG scale: ").append(item.cfgScale)
        append(", Seed: ").append(item.seed)
        append(", Size: ").append(item.width).append('x').append(item.height)
        item.model?.let { append(", Model: ").append(it) }
    }

    fun consumeMessage() = local.update { it.copy(message = null) }

    private companion object {
        const val THUMB_CACHE_KB = 32 * 1024
        const val QUERY_DEBOUNCE_MS = 150L

        /** Grid cells are small; a thumbnail is never decoded larger than this. */
        const val THUMB_SIDE = 512
    }
}
