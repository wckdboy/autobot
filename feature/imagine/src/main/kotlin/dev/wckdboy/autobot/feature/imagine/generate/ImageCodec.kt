package dev.wckdboy.autobot.feature.imagine.generate

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Bitmap plumbing for generation inputs, thumbnails and previews. Call off the main thread. */
object ImageCodec {

    fun decode(bytes: ByteArray, maxSide: Int = Int.MAX_VALUE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 85): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

    /** JPEG thumbnail whose longer side is [side] px. */
    fun thumbnail(bytes: ByteArray, side: Int = 384): ByteArray? {
        val source = decode(bytes, side * 2) ?: return null
        val scale = side.toFloat() / max(source.width, source.height)
        val thumb = if (scale < 1f) {
            Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            source
        }
        return jpeg(thumb, 80)
    }

    /** Reads an image the user picked (photo picker grant; no storage permission). */
    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Int = 40 * 1024 * 1024): ByteArray? =
        resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return null
            }
            out.toByteArray()
        }

    /** Center-crops ("cover") [source] to exactly [width]×[height]. */
    fun cover(source: Bitmap, width: Int, height: Int): Bitmap {
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
