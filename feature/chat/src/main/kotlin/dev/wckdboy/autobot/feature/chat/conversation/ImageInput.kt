package dev.wckdboy.autobot.feature.chat.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Prepares picked images for vision models. */
internal object ImageInput {
    /** Longest side sent to a model; vision encoders resample to a few hundred pixels anyway. */
    private const val MAX_SIDE = 1344

    /**
     * Decodes [uri] (any format Android reads, including HEIC), scales it to at most [MAX_SIDE]
     * and re-encodes it as JPEG. Re-encoding drops EXIF, so location and camera data never
     * reach a model or the attachment store.
     */
    fun encode(context: Context, uri: Uri): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
