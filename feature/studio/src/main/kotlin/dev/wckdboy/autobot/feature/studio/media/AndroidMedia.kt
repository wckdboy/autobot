package dev.wckdboy.autobot.feature.studio.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioCodec

/** Plays decrypted bytes from memory: nothing is written to disk in the clear. */
class ByteDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(size.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() = Unit
}

/** Size, length and preview of an asset's bytes. */
object MediaInfo {
    class Info(val width: Int = 0, val height: Int = 0, val durationMs: Long = 0)

    fun of(type: AssetType, mimeType: String, bytes: ByteArray): Info = runCatching {
        when (type) {
            AssetType.IMAGE -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                Info(o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
            }
            AssetType.AUDIO -> WavFile.decode(bytes)?.let { Info(durationMs = it.durationMs) } ?: retrieve(bytes)
            AssetType.VIDEO -> if (mimeType.startsWith("image/")) imageFrames(bytes) else retrieve(bytes)
        }
    }.getOrDefault(Info())

    private fun imageFrames(bytes: ByteArray): Info {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return Info(o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
    }

    private fun retrieve(bytes: ByteArray): Info {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ByteDataSource(bytes))
            Info(
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
            )
        } finally {
            runCatching { r.release() }
        }
    }

    /** The encrypted preview to keep with an asset: a JPEG thumbnail, or waveform peaks for audio. */
    fun preview(type: AssetType, mimeType: String, bytes: ByteArray): ByteArray? = runCatching {
        when (type) {
            AssetType.IMAGE -> ImageCodec.thumbnail(bytes)
            AssetType.AUDIO -> WavFile.decode(bytes)?.let { WavFile.encodePeaks(WavFile.peaks(it)) }
            AssetType.VIDEO -> if (mimeType.startsWith("image/")) ImageCodec.thumbnail(bytes) else frame(bytes, 0)?.let { ImageCodec.jpeg(scaled(it, 384)) }
        }
    }.getOrNull()

    /** The video frame at [timeMs] (closest), or null. */
    fun frame(bytes: ByteArray, timeMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ByteDataSource(bytes))
            r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val s = side.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }
}

/**
 * The one way an asset leaves the encrypted store: a decrypted copy in shared storage
 * (`Pictures/Autobot`, `Music/Autobot`, `Movies/Autobot`) through MediaStore, after the user
 * confirmed. No storage permission is needed.
 */
object StudioExport {
    fun export(context: Context, asset: StudioAsset, bytes: ByteArray, name: String = "autobot_${asset.id}"): Boolean {
        val resolver = context.contentResolver
        val mime = asset.mimeType.ifBlank { if (asset.type == AssetType.IMAGE) ImageSniff.mimeType(bytes) else "application/octet-stream" }
        val (collection, dir) = when {
            asset.type == AssetType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            asset.type == AssetType.VIDEO && mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.${StudioCodec.extension(mime)}")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Autobot")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.onFailure { runCatching { resolver.delete(uri, null, null) } }.isSuccess
    }

    /** Where [asset] lands, for the confirmation. */
    fun destination(asset: StudioAsset): String = when {
        asset.type == AssetType.AUDIO -> "Music/Autobot"
        asset.type == AssetType.VIDEO && asset.mimeType.startsWith("video/") -> "Movies/Autobot"
        else -> "Pictures/Autobot"
    }
}
