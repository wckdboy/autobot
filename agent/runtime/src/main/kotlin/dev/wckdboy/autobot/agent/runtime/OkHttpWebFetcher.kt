package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.turnTrace
import java.io.IOException
import okhttp3.Request

/**
 * `web_fetch` transport over the shared [HttpClientFactory], so the kill switch, the active
 * route (Tor/SOCKS) and the audit log all apply. The client never follows redirects.
 */
class OkHttpWebFetcher(private val clients: HttpClientFactory) : WebFetcher {
    override suspend fun get(url: String, accept: String, maxBytes: Long): WebResponse {
        val request = try {
            Request.Builder().url(url).header("Accept", accept).get()
        } catch (e: IllegalArgumentException) {
            throw WebFetchException("TRANSPORT", e.message ?: "invalid URL")
        }.turnTrace().build()
        try {
            clients.create(tag = "agent:web_fetch").newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) {
                    response.body.source().let { source ->
                        source.request(maxBytes)
                        source.buffer.readUtf8(minOf(source.buffer.size, maxBytes))
                    }
                } else {
                    ""
                }
                return WebResponse(response.code, response.header("Location"), response.header("Content-Type"), body)
            }
        } catch (e: NetworkBlockedException) {
            throw WebFetchException("BLOCKED", e.message ?: "blocked")
        } catch (e: IOException) {
            throw WebFetchException("TRANSPORT", e.message ?: e.javaClass.simpleName)
        }
    }
}
