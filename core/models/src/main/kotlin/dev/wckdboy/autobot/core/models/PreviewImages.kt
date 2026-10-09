package dev.wckdboy.autobot.core.models

import android.util.LruCache
import dev.wckdboy.autobot.core.network.HttpClientFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Fetches catalog preview images (Civitai) through the policy-bound client: kill switch, route
 * and audit log apply, no cookies. Only known image CDNs are allowed and responses are capped.
 */
@Singleton
class PreviewImages @Inject constructor(private val clients: HttpClientFactory) {
    private val sized = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    suspend fun load(url: String, width: Int = 320): ByteArray? {
        sized.get(url)?.let { return it }
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host !in ALLOWED_HOSTS) return null
        // Civitai's image CDN resizes on request via a `width=` path segment.
        val sizedUrl = if (parsed.host == "image.civitai.com") url.replace("/original=true/", "/width=$width/") else url
        return withContext(Dispatchers.IO) {
            runCatching {
                clients.create("models:preview").newCall(Request.Builder().url(sizedUrl).header("Accept", "image/*").build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    if (r.body.contentLength() > MAX_BYTES) return@use null
                    r.body.source().let { source ->
                        source.request(MAX_BYTES)
                        source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES))
                    }
                }
            }.getOrNull()?.also { sized.put(url, it) }
        }
    }

    private companion object {
        const val MAX_BYTES = 3L * 1024 * 1024
        val ALLOWED_HOSTS = setOf("image.civitai.com")
    }
}
