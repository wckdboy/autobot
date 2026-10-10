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

@HiltViewModel
class GalleryViewModel @Inject constructor(
    application: Application,
    private val gallery: GalleryRepository,
    private val upscaler: Upscaler,
) : AndroidViewModel(application) {

    private data class Local(
        val favoritesOnly: Boolean = false,
        val query: String = "",
        val openId: String? = null,
        val openImage: ImageBitmap? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private val thumbs = LruCache<String, ImageBitmap>(THUMB_CACHE)

    val uiState: StateFlow<GalleryUiState> = combine(gallery.observe(), local) { all, l ->
        val filtered = all.filter { item ->
            (!l.favoritesOnly || item.favorite) &&
                (l.query.isBlank() || item.prompt.contains(l.query, ignoreCase = true) || item.engine.contains(l.query, ignoreCase = true))
        }
        GalleryUiState(
            items = filtered,
            total = all.size,
            favoritesOnly = l.favoritesOnly,
            query = l.query,
            open = all.firstOrNull { it.id == l.openId },
            openImage = l.openImage,
            message = l.message,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    /** Decrypts and decodes a thumbnail (cached). */
    suspend fun thumbnail(id: String): ImageBitmap? {
        thumbs.get(id)?.let { return it }
        val bytes = gallery.thumbnail(id) ?: return null
        return withContext(Dispatchers.Default) { ImageCodec.decode(bytes)?.asImageBitmap() }?.also { thumbs.put(id, it) }
    }

    fun setFavoritesOnly(enabled: Boolean) = local.update { it.copy(favoritesOnly = enabled) }
    fun setQuery(query: String) = local.update { it.copy(query = query) }

    fun open(id: String) {
        local.update { it.copy(openId = id, openImage = null) }
        viewModelScope.launch {
            val image = gallery.image(id)?.let { withContext(Dispatchers.Default) { ImageCodec.decode(it, 2048)?.asImageBitmap() } }
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
                val thumb = withContext(Dispatchers.Default) { ImageCodec.thumbnail(png) } ?: png
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
                val bytes = gallery.image(item.id) ?: return@withContext false
                val png = bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
                val resolver = getApplication<Application>().contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "autobot_${item.createdAt}_${item.seed}.${if (png) "png" else "jpg"}")
                    put(MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Autobot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                }.onFailure { resolver.delete(uri, null, null) }.isSuccess
            }
            local.update { it.copy(message = if (ok) "Exported to Pictures/Autobot" else "Export failed") }
        }
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
        const val THUMB_CACHE = 240
    }
}
