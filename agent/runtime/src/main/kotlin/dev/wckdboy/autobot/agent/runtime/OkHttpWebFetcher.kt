package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.turnTrace
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/**
 * `web_fetch` transport over the shared [HttpClientFactory], so the kill switch, the active
 * route (Tor/SOCKS) and the audit log all apply. The client never follows redirects.
 *
 * The call is asynchronous and bounded by [callTimeoutSeconds]: cancelling the turn (Stop, or
 * deleting the chat) cancels the request, including a body that is still downloading.
 */
class OkHttpWebFetcher(
    private val clients: HttpClientFactory,
    private val callTimeoutSeconds: Long = CALL_TIMEOUT_SECONDS,
) : WebFetcher {
    override suspend fun get(url: String, accept: String, maxBytes: Long): WebResponse {
        val request = try {
            Request.Builder().url(url).header("Accept", accept).get()
        } catch (e: IllegalArgumentException) {
            throw WebFetchException("TRANSPORT", e.message ?: "invalid URL")
        }.turnTrace().build()
        val call = clients.create(tag = "agent:web_fetch").newCall(request)
        call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
        try {
            return call.fetch(maxBytes)
        } catch (e: NetworkBlockedException) {
            throw WebFetchException("BLOCKED", e.message ?: "blocked")
        } catch (e: IOException) {
            throw WebFetchException("TRANSPORT", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Runs the call on OkHttp's dispatcher and reads up to [maxBytes] of the body there. */
    private suspend fun Call.fetch(maxBytes: Long): WebResponse = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { read(it, maxBytes) }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            },
        )
    }

    private fun read(response: Response, maxBytes: Long): WebResponse {
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

    private companion object {
        /** Whole request, connect to last byte (the shared client has no call timeout: model streams are long). */
        const val CALL_TIMEOUT_SECONDS = 60L
    }
}
