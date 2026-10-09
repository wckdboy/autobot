package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * DSH `web_fetch`. Goes through the shared [HttpClientFactory], so the kill switch, the active
 * route (Tor/SOCKS) and the audit log all apply; in Offline mode it fails with `BLOCKED`.
 * Redirects are not followed (they would bypass the kill switch): the model gets the location
 * and may fetch it explicitly. HTML is reduced to readable text.
 */
class WebFetchTool(private val clients: HttpClientFactory) : ToolDefinition {
    override val name = "web_fetch"
    override val kind = ToolKind.FETCH
    override val description =
        "Fetch a public http(s) URL and return its text content (HTML is converted to plain text). Subject to the " +
            "user's network policy; may be blocked when the device is in Offline mode. Redirects are reported, not followed."
    override val parameters = schema { string("url", "Absolute http(s) URL", required = true) }
    override val timeoutMs: Long = 60_000

    override fun isConcurrencySafe(args: JsonObject) = true
    override fun describeCall(args: JsonObject) = args.string("url").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = withContext(Dispatchers.IO) {
        val url = args.string("url")?.toHttpUrlOrNull()
            ?: return@withContext ToolOutput.error("not a valid http(s) URL", "INVALID_ARGS")
        val request = Request.Builder().url(url).header("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5").get().build()
        try {
            clients.create(tag = "agent:web_fetch").newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    return@withContext ToolOutput("HTTP ${response.code} redirect to ${response.header("Location") ?: "(no location)"}")
                }
                if (!response.isSuccessful) return@withContext ToolOutput.error("HTTP ${response.code}", "HTTP_${response.code}")
                val type = response.body.contentType()
                if (type != null && type.type !in setOf("text", "application") ) {
                    return@withContext ToolOutput.error("unsupported content type $type", "UNSUPPORTED")
                }
                val raw = response.body.source().let { source ->
                    source.request(MAX_BYTES)
                    source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
                }
                val text = if (type?.subtype?.contains("html") == true || raw.trimStart().startsWith("<")) htmlToText(raw) else raw
                ToolOutput(text.ifBlank { "(empty response)" })
            }
        } catch (e: NetworkBlockedException) {
            ToolOutput.error("blocked by the network policy (${e.message})", "BLOCKED")
        } catch (e: IOException) {
            ToolOutput.error(e.message ?: e.javaClass.simpleName, "TRANSPORT")
        }
    }

    companion object {
        private const val MAX_BYTES = 2L * 1024 * 1024
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
