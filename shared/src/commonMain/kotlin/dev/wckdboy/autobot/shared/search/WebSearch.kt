package dev.wckdboy.autobot.shared.search

import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.int
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.WebFetchTool
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.IoDispatcher
import dev.wckdboy.autobot.shared.fetchAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The search backends a user can configure. [NONE] means web search is off. */
enum class SearchProvider(val label: String) {
    NONE("Off"),

    /** A SearXNG instance's JSON API (self-hosted or one the user trusts). */
    SEARXNG("SearXNG"),

    /** Brave Search API (needs a subscription token). */
    BRAVE("Brave Search"),
    ;

    companion object {
        fun parse(value: String?): SearchProvider = entries.firstOrNull { it.name == value } ?: NONE
    }
}

/**
 * What `web_search` needs at call time. [baseUrl] is the SearXNG instance (ignored for Brave unless
 * set); [apiKey] is Brave's token, read from the secret store per call; [route] is a
 * `RouteOverride` name like a provider's.
 */
class WebSearchConfig(
    val provider: SearchProvider,
    val baseUrl: String = "",
    val apiKey: String? = null,
    val route: String = "INHERIT",
) {
    override fun toString(): String = "WebSearchConfig($provider, $baseUrl, key=${if (apiKey.isNullOrBlank()) "none" else "set"}, $route)"
}

/** The host's current search settings (iOS reads them, and the key from the Keychain, on demand). */
fun interface WebSearchSource {
    fun config(): WebSearchConfig?
}

class SearchResult(val title: String, val url: String, val snippet: String)

/** Why a search produced no results. [code] becomes the tool error code. */
class SearchFailure(val code: String, val message: String)

/**
 * Request building and response parsing for the supported providers: plain functions, so both
 * apps (and their tests) share them. The host transport only moves bytes.
 */
object WebSearchCodec {
    const val TAG = "agent:web_search"
    const val BRAVE_BASE = "https://api.search.brave.com"
    const val MAX_RESULTS = 10
    const val DEFAULT_RESULTS = 5
    const val MAX_BYTES = 1L shl 20
    const val MAX_SNIPPET = 300
    const val MAX_TITLE = 200

    private val BASE = Regex("(?i)https?://[^\\s/?#]+[^\\s?#]*")
    private val TAG_RE = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val json = Json { ignoreUnknownKeys = true }

    /** The configuration problem that keeps [config] from searching, or null when it is usable. */
    fun problem(config: WebSearchConfig?): String? = when {
        config == null || config.provider == SearchProvider.NONE -> "web search is not configured (Settings → Web search)"
        config.provider == SearchProvider.SEARXNG && base(config) == null -> "the SearXNG URL is missing or invalid"
        config.provider == SearchProvider.BRAVE && config.apiKey.isNullOrBlank() -> "the Brave Search API key is missing"
        config.provider == SearchProvider.BRAVE && base(config) == null -> "the Brave Search URL is invalid"
        else -> null
    }

    private fun base(config: WebSearchConfig): String? {
        val raw = config.baseUrl.trim().ifEmpty { if (config.provider == SearchProvider.BRAVE) BRAVE_BASE else "" }
        return raw.trimEnd('/').takeIf { BASE.matches(it) }
    }

    /** The GET for [query]; null when [config] has a [problem]. */
    fun request(config: WebSearchConfig, query: String, count: Int): HttpRequestSpec? {
        if (problem(config) != null) return null
        val base = base(config) ?: return null
        val n = count.coerceIn(1, MAX_RESULTS)
        val q = encode(query.trim())
        return when (config.provider) {
            SearchProvider.SEARXNG -> HttpRequestSpec(
                "GET", "$base/search?q=$q&format=json&pageno=1", mapOf("Accept" to "application/json"), null, TAG, config.route, MAX_BYTES,
            )
            SearchProvider.BRAVE -> HttpRequestSpec(
                "GET",
                "$base/res/v1/web/search?q=$q&count=$n",
                mapOf("Accept" to "application/json", "X-Subscription-Token" to config.apiKey!!.trim()),
                null, TAG, config.route, MAX_BYTES,
            )
            SearchProvider.NONE -> null
        }
    }

    /** Results from a 2xx body, at most [count]; a [SearchFailure] when the body is not what the provider returns. */
    fun parse(provider: SearchProvider, body: String, count: Int): Result {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return Result.Failed(SearchFailure("PARSE", "the ${provider.label} response is not JSON"))
        val items = when (provider) {
            SearchProvider.SEARXNG -> root["results"] as? JsonArray
            SearchProvider.BRAVE -> (root["web"] as? JsonObject)?.get("results") as? JsonArray ?: JsonArray(emptyList())
            SearchProvider.NONE -> null
        } ?: return Result.Failed(SearchFailure("PARSE", "the ${provider.label} response has no results list"))
        val snippetKey = if (provider == SearchProvider.BRAVE) "description" else "content"
        val seen = HashSet<String>()
        val results = items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val url = o.text("url")?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            SearchResult(
                title = clean(o.text("title").orEmpty(), MAX_TITLE).ifEmpty { url },
                url = url,
                snippet = clean(o.text(snippetKey).orEmpty(), MAX_SNIPPET),
            )
        }
        return Result.Ok(results.take(count.coerceIn(1, MAX_RESULTS)))
    }

    sealed interface Result {
        class Ok(val results: List<SearchResult>) : Result
        class Failed(val failure: SearchFailure) : Result
    }

    /** Maps a non-2xx response to a failure the model (and user) can act on. */
    fun httpError(provider: SearchProvider, status: Int, body: String): SearchFailure {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.text("detail") ?: (o["error"] as? JsonObject)?.text("message") ?: o.text("message")
        }.getOrNull()?.let { ": ${clean(it, 200)}" }.orEmpty()
        return when {
            status == 401 || (status == 403 && provider == SearchProvider.BRAVE) ->
                SearchFailure("AUTH", "${provider.label} rejected the API key (HTTP $status)$detail")
            status == 403 && provider == SearchProvider.SEARXNG ->
                SearchFailure("HTTP_403", "the SearXNG instance refused the JSON API (HTTP 403); enable `json` under search.formats in its settings.yml")
            status == 429 -> SearchFailure("RATE_LIMITED", "${provider.label} rate limit reached (HTTP 429)$detail")
            status in 500..599 -> SearchFailure("HTTP_$status", "${provider.label} is unavailable (HTTP $status)")
            else -> SearchFailure("HTTP_$status", "${provider.label} returned HTTP $status$detail")
        }
    }

    /** The tool output: numbered title, URL and snippet per result. */
    fun format(provider: SearchProvider, query: String, results: List<SearchResult>): String {
        if (results.isEmpty()) return "No web results for \"$query\" (${provider.label})."
        return buildString {
            append("Web results for \"").append(query).append("\" (").append(provider.label).append("):\n")
            results.forEachIndexed { i, r ->
                append('\n').append(i + 1).append(". ").append(r.title).append('\n')
                append("   ").append(r.url).append('\n')
                if (r.snippet.isNotEmpty()) append("   ").append(r.snippet).append('\n')
            }
            append("\nSnippets come from the search engine; use web_fetch on a URL to read the page.")
        }
    }

    /** RFC 3986 percent-encoding of a query parameter value (UTF-8, unreserved characters kept). */
    fun encode(value: String): String = buildString {
        for (b in value.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                append(ch)
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun clean(text: String, max: Int): String {
        val plain = WebFetchTool.htmlToText(text.replace(TAG_RE, "")).replace(SPACES, " ").trim()
        return if (plain.length <= max) plain else plain.take(max - 1).trimEnd() + "…"
    }
}

/**
 * `web_search` through the user's provider, over the host [HttpTransport], so the kill switch, the
 * configured route and the audit log (tag `agent:web_search`) apply exactly as for `web_fetch`.
 * Only the query leaves the device. Kind FETCH: approval follows the permission preset.
 */
class WebSearchTool(private val transport: HttpTransport, private val config: suspend () -> WebSearchConfig?) : ToolDefinition {
    override val name = "web_search"
    override val kind = ToolKind.FETCH
    override val description =
        "Search the web through the search provider the user configured. Returns titles, URLs and snippets; fetch a " +
            "result with web_fetch to read it. Subject to the user's network policy (blocked in Offline mode)."
    override val parameters = schema {
        string("query", "Search query", required = true)
        integer(
            "count", "Number of results (1–${WebSearchCodec.MAX_RESULTS}, default ${WebSearchCodec.DEFAULT_RESULTS})",
            minimum = 1, maximum = WebSearchCodec.MAX_RESULTS.toLong(),
        )
    }
    override val timeoutMs: Long = 45_000

    override fun isConcurrencySafe(args: JsonObject) = true
    override fun describeCall(args: JsonObject) = args.string("query").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = withContext(IoDispatcher) {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return@withContext ToolOutput.error("query is empty", "INVALID_ARGS")
        val count = args.int("count") ?: WebSearchCodec.DEFAULT_RESULTS
        val cfg = config()
        WebSearchCodec.problem(cfg)?.let { return@withContext ToolOutput.error(it, "NOT_CONFIGURED") }
        val provider = cfg!!.provider
        val request = WebSearchCodec.request(cfg, query, count) ?: return@withContext ToolOutput.error("invalid search settings", "NOT_CONFIGURED")
        val response = fetchAll(transport, request)
        when {
            response.blocked -> ToolOutput.error("blocked by the network policy (${response.failure})", "BLOCKED")
            response.failure != null -> ToolOutput.error(response.failure, "TRANSPORT")
            response.status !in 200..299 -> WebSearchCodec.httpError(provider, response.status, response.body).let { ToolOutput.error(it.message, it.code) }
            else -> when (val parsed = WebSearchCodec.parse(provider, response.body, count)) {
                is WebSearchCodec.Result.Ok -> ToolOutput(WebSearchCodec.format(provider, query, parsed.results))
                is WebSearchCodec.Result.Failed -> ToolOutput.error(parsed.failure.message, parsed.failure.code)
            }
        }
    }
}

/** The harness plugin that adds `web_search`; load it only while a provider is configured. */
fun webSearchPlugin(transport: HttpTransport, config: suspend () -> WebSearchConfig?): Plugin =
    toolsPlugin("web-search", WebSearchTool(transport, config))
