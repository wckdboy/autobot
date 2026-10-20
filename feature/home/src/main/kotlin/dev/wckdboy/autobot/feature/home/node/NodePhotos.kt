package dev.wckdboy.autobot.feature.home.node

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import dev.wckdboy.autobot.shared.invincible.NodeImages
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos for the node's agent: from the library or the camera, downscaled and re-encoded on the phone. */
internal object NodePhotos {
    /**
     * Decodes [uri] (any format Android reads, HEIC too) to at most [NodeImages.MAX_SIDE] on its
     * longest side and re-encodes it as JPEG; re-encoding drops EXIF, so the photo's location and
     * camera data never leave the phone.
     */
    fun encode(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = NodeImages.fit(info.size.width, info.size.height)
            if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, NodeImages.QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** Where the camera app writes the next shot (the app's cache, through feature:imagine's FileProvider). */
    fun cameraTarget(context: Context): Uri {
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "shot.jpg").apply { delete() }
        return FileProvider.getUriForFile(context, context.packageName + ".images", file)
    }

    /** The camera's shot, once encoded: gone from the cache. */
    fun clearCamera(context: Context) {
        File(File(context.cacheDir, "photos"), "shot.jpg").delete()
    }

    fun base64(jpeg: ByteArray): String = java.util.Base64.getEncoder().encodeToString(jpeg)
}
