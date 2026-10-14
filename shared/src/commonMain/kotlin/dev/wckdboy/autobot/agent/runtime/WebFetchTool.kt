package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** One GET as seen by `web_fetch`. Redirects are reported, never followed. */
class WebResponse(val status: Int, val location: String?, val contentType: String?, val body: String)

/** Why a fetch failed before a response: [code] is `BLOCKED` (network policy) or `TRANSPORT`. */
class WebFetchException(val code: String, message: String) : Exception(message)

/**
 * The host's HTTP client for `web_fetch`: Android uses the shared OkHttp client, iOS its
 * URLSession client. Either way the kill switch, the active route (Tor/SOCKS) and the audit log
 * apply, and at most [maxBytes] of the body are read.
 */
interface WebFetcher {
    suspend fun get(url: String, accept: String, maxBytes: Long): WebResponse
}

/**
 * DSH `web_fetch` over the host's [WebFetcher]; in Offline mode it fails with `BLOCKED`.
 * Redirects are not followed (they would bypass the kill switch): the model gets the location
 * and may fetch it explicitly. HTML is reduced to readable text.
 */
class WebFetchTool(private val fetcher: WebFetcher) : ToolDefinition {
    override val name = "web_fetch"
    override val kind = ToolKind.FETCH
    override val description =
        "Fetch a public http(s) URL and return its text content (HTML is converted to plain text). Subject to the " +
            "user's network policy; may be blocked when the device is in Offline mode. Redirects are reported, not followed."
    override val parameters = schema { string("url", "Absolute http(s) URL", required = true) }
    override val timeoutMs: Long = 60_000

    override fun isConcurrencySafe(args: JsonObject) = true
    override fun describeCall(args: JsonObject) = args.string("url").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = withContext(IoDispatcher) {
        val url = args.string("url")?.trim()?.takeIf { HTTP_URL.matches(it) }
            ?: return@withContext ToolOutput.error("not a valid http(s) URL", "INVALID_ARGS")
        val response = try {
            fetcher.get(url, ACCEPT, MAX_BYTES)
        } catch (e: WebFetchException) {
            return@withContext if (e.code == "BLOCKED") {
                ToolOutput.error("blocked by the network policy (${e.message})", "BLOCKED")
            } else {
                ToolOutput.error(e.message ?: "transport error", "TRANSPORT")
            }
        }
        if (response.status in 300..399) {
            return@withContext ToolOutput("HTTP ${response.status} redirect to ${response.location ?: "(no location)"}")
        }
        if (response.status !in 200..299) return@withContext ToolOutput.error("HTTP ${response.status}", "HTTP_${response.status}")
        val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type.substringBefore('/') !in setOf("text", "application")) {
            return@withContext ToolOutput.error("unsupported content type ${response.contentType}", "UNSUPPORTED")
        }
        val raw = response.body
        val text = if (type?.substringAfter('/')?.contains("html") == true || raw.trimStart().startsWith("<")) htmlToText(raw) else raw
        ToolOutput(text.ifBlank { "(empty response)" })
    }

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private const val ACCEPT = "text/html,text/plain,application/json;q=0.9,*/*;q=0.5"
        private val HTTP_URL = Regex("(?i)https?://[^\\s/?#]+[^\\s]*")
        private val DROP = Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>")
        private val BLOCK = Regex("(?i)</?(p|div|br|li|tr|h[1-6]|section|article|header|footer|pre|blockquote)[^>]*>")
        private val TAG = Regex("<[^>]+>")
        private val SPACES = Regex("[ \\t\\x0B\\f\\r]+")
        private val BLANKS = Regex("\\n\\s*\\n+")

        fun htmlToText(html: String): String = html
            .replace(DROP, " ")
            .replace(BLOCK, "\n")
            .replace(TAG, "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(SPACES, " ")
            .replace(BLANKS, "\n\n")
            .trim()
    }
}
