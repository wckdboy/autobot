package dev.wckdboy.autobot.feature.chat.conversation

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.wckdboy.autobot.core.a2ui.A2uiMedia
import dev.wckdboy.autobot.core.network.HttpClientFactory
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Images of generated UI, loaded only when the user taps one: through a fresh client of the
 * app's [HttpClientFactory] (kill switch, the global route, the audit log as `a2ui:image`, no
 * cookies, no redirects), at most [MAX_BYTES], decoded down to [MAX_SIDE] px.
 */
class ChatA2uiImages @Inject constructor(private val clients: HttpClientFactory) : A2uiMedia {
    override suspend fun image(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return@withContext null
        try {
            clients.create(AUDIT_TAG).newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > MAX_BYTES) return@use null
                val bytes = body.byteStream().readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) return@use null
                decode(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val AUDIT_TAG = "a2ui:image"
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_SIDE = 2048
    }
}
