package dev.wckdboy.autobot.feature.imagine.viewer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.Upscaler
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** A full-size image for the viewer: the decoded bitmap (at most [MAX_SIDE]) and the file's real pixel size. */
@Immutable
class ViewerImage(val bitmap: ImageBitmap, val pixelWidth: Int, val pixelHeight: Int)

/** What the viewer can do with an image. Some open the studio (handoffs), the rest act in place. */
enum class ViewerAction(val label: String, val handoff: Boolean = false) {
    /** An unsaved incognito image into the gallery (the only way it is written anywhere). */
    KEEP("save to gallery"),
    IMG2IMG("→ img2img", handoff = true),
    INPAINT("inpaint", handoff = true),
    VARY("vary", handoff = true),
    REUSE("reuse settings", handoff = true),
    SAME_SEED("same seed", handoff = true),
    UPSCALE("upscale 4×"),
    SHARE("share"),
    SAVE("save to device"),
    COPY_PARAMS("copy params"),
    FAVORITE("favorite"),
    DELETE("delete"),
    ;

    companion object {
        /**
         * What an unsaved incognito image offers: viewing, Keep, and studio handoffs that read it
         * from memory (their results follow the studio's incognito switch). Nothing that writes a
         * copy (device export, share file, upscale into the gallery) or edits a gallery row.
         */
        val UNSAVED: Set<ViewerAction> = setOf(KEEP, IMG2IMG, INPAINT, VARY, REUSE, SAME_SEED, COPY_PARAMS)

        /** The actions for [id]: [UNSAVED] for an unsaved incognito image, else everything but Keep. */
        fun forImage(id: String, canUpscale: Boolean): Set<ViewerAction> {
            if (dev.wckdboy.autobot.shared.panic.IncognitoImages.isUnsaved(id)) return UNSAVED
            return entries.toSet() - KEEP - if (canUpscale) emptySet() else setOf(UPSCALE)
        }
    }
}

/**
 * Gallery image operations shared by the gallery, the studio's results and images from chat:
 * full-size decode, share, export, delete, favorite, upscale.
 */
@Singleton
class GalleryImages @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gallery: GalleryRepository,
    private val upscaler: Upscaler,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun image(id: String): ViewerImage? {
        val bytes = runCatching { gallery.image(id) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            val (w, h) = ImageCodec.bounds(bytes) ?: return@withContext null
            val bitmap = ImageCodec.decode(bytes, MAX_SIDE) ?: return@withContext null
            ViewerImage(bitmap.asImageBitmap(), w, h)
        }
    }

    fun params(item: GalleryItem): DiffusionRequest? = runCatching { json.decodeFromString(DiffusionRequest.serializer(), item.paramsJson) }.getOrNull()

    suspend fun canUpscale(): Boolean = runCatching { upscaler.available().isNotEmpty() }.getOrDefault(false)

    suspend fun toggleFavorite(item: GalleryItem) = gallery.setFavorite(item.id, !item.favorite)

    suspend fun delete(id: String) = gallery.delete(id)

    /** Upscales 4× on the NPU and stores the result as a new item; returns it. */
    suspend fun upscale(item: GalleryItem): GalleryItem {
        val bytes = gallery.image(item.id) ?: error("image unavailable")
        val started = System.currentTimeMillis()
        val png = upscaler.upscale(bytes)
        // Never the 4× PNG itself as the thumbnail: decoding it in the grid would take a 64 MB bitmap.
        val thumb = withContext(Dispatchers.Default) { ImageCodec.thumbnail(png) } ?: gallery.thumbnail(item.id) ?: error("cannot make a thumbnail")
        val (w, h) = withContext(Dispatchers.Default) { ImageCodec.bounds(png) } ?: (item.width * 4 to item.height * 4)
        val copy = item.copy(
            id = gallery.newId(),
            createdAt = System.currentTimeMillis(),
            width = w,
            height = h,
            engine = item.engine + " · 4× npu",
            durationMs = System.currentTimeMillis() - started,
            favorite = false,
        )
        gallery.save(copy, png, thumb)
        return copy
    }

    /**
     * Writes a decrypted copy to `Pictures/Autobot` via MediaStore (no storage permission). This
     * deliberately leaves the encrypted vault, so the UI asks first.
     */
    suspend fun export(item: GalleryItem): Boolean = withContext(Dispatchers.IO) {
        // Storage can refuse the new entry (full, no Pictures volume): a message, not a crash.
        runCatching { exportNow(item) }.getOrDefault(false)
    }

    private suspend fun exportNow(item: GalleryItem): Boolean {
        val bytes = gallery.image(item.id) ?: return false
        val png = isPng(bytes)
        val resolver = context.contentResolver
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

    /**
     * A share sheet for a decrypted copy in the cache (`cache/shared-images`, served read-only by
     * this module's FileProvider). The previous shared copy is removed first, so at most one
     * plain image sits outside the vault.
     */
    suspend fun shareIntent(item: GalleryItem): Intent? = withContext(Dispatchers.IO) {
        val bytes = gallery.image(item.id) ?: return@withContext null
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val png = isPng(bytes)
        val file = File(dir, "autobot_${item.seed}.${if (png) "png" else "jpg"}").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (png) "image/png" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        const val MAX_SIDE = 4096
        private const val SHARE_DIR = "shared-images"

        private fun isPng(bytes: ByteArray) = bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()

        /** A1111-style "parameters" text, for pasting into other tools. */
        fun parametersText(item: GalleryItem): String = buildString {
            append(item.prompt)
            if (item.negativePrompt.isNotBlank()) append("\nNegative prompt: ").append(item.negativePrompt)
            append("\nSteps: ").append(item.steps)
            append(", Sampler: ").append(item.sampler)
            append(", CFG scale: ").append(String.format(Locale.ROOT, "%.1f", item.cfgScale))
            append(", Seed: ").append(item.seed)
            append(", Size: ").append(item.width).append('x').append(item.height)
            item.model?.let { append(", Model: ").append(it) }
        }

        /**
         * The studio request a handoff from [item] starts from: its stored settings ([params], or
         * the item's columns for rows from before params were kept) shaped for [action]. Reuse
         * starts a new random seed; same seed keeps it; img2img, inpaint and vary take the image
         * as input at its own size (vary at a light 0.45 denoise, so the result stays close).
         */
        fun handoffRequest(item: GalleryItem, params: DiffusionRequest?, action: ViewerAction): DiffusionRequest {
            val base = (params ?: DiffusionRequest()).copy(
                prompt = params?.prompt ?: item.prompt,
                negativePrompt = params?.negativePrompt ?: item.negativePrompt,
                width = item.width,
                height = item.height,
                steps = params?.steps ?: item.steps,
                cfgScale = params?.cfgScale ?: item.cfgScale,
                model = params?.model ?: item.model,
                batchCount = 1,
            )
            return when (action) {
                ViewerAction.SAME_SEED -> base.copy(seed = item.seed)
                ViewerAction.IMG2IMG -> base.copy(mode = DiffusionMode.IMG2IMG, seed = -1)
                ViewerAction.INPAINT -> base.copy(mode = DiffusionMode.INPAINT, seed = -1)
                ViewerAction.VARY -> base.copy(mode = DiffusionMode.IMG2IMG, seed = -1, denoiseStrength = VARY_DENOISE)
                else -> base.copy(mode = DiffusionMode.TXT2IMG, seed = -1)
            }
        }

        const val VARY_DENOISE = 0.45f
    }
}
