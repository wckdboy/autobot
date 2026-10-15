package dev.wckdboy.autobot.shared.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Model Context Protocol wire basics: JSON-RPC 2.0 framing, protocol revisions and the SSE event
 * reader used by the Streamable HTTP transport. Two eras are spoken: the stateless 2026-07-28
 * revision (per-request `_meta` and headers) and the initialize-based revisions 2025-03-26
 * through 2025-11-25 (sessions); see
 * https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning.
 */

object McpProtocol {
    /** The stateless revision, tried first. */
    const val MODERN_VERSION = "2026-07-28"

    /** The revision this client asks for in a legacy `initialize`. */
    const val LEGACY_VERSION = "2025-11-25"

    /** Initialize-based revisions the client accepts from a server's `initialize` result. */
    val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")

    /** Fixed client identity (like the constant `User-Agent: Autobot`, it carries no version or device data). */
    const val CLIENT_NAME = "Autobot"
    const val CLIENT_VERSION = "1"

    const val HEADER_SESSION = "Mcp-Session-Id"
    const val HEADER_VERSION = "MCP-Protocol-Version"
    const val HEADER_METHOD = "Mcp-Method"
    const val HEADER_NAME = "Mcp-Name"
    const val ACCEPT_POST = "application/json, text/event-stream"
    const val ACCEPT_SSE = "text/event-stream"

    const val META_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    const val METHOD_NOT_FOUND = -32601
    const val HEADER_MISMATCH = -32020
    const val MISSING_CAPABILITY = -32021
    const val UNSUPPORTED_VERSION = -32022

    /** JSON-RPC errors only a 2026-07-28 server sends; they identify the modern era. */
    val MODERN_ERRORS = setOf(HEADER_MISMATCH, MISSING_CAPABILITY, UNSUPPORTED_VERSION)

    /** The per-request `_meta` every 2026-07-28 request carries (no client capabilities are offered). */
    internal fun meta(): JsonObject = buildJsonObject {
        put(META_VERSION, MODERN_VERSION)
        put(META_CLIENT_INFO, buildJsonObject {
            put("name", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        })
        put(META_CAPABILITIES, JsonObject(emptyMap()))
    }

    internal val Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    internal fun request(id: Long, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        params?.let { put("params", it) }
    }

    internal fun notification(method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        params?.let { put("params", it) }
    }

    internal fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    internal fun error(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    /** Session ids must be visible ASCII (0x21–0x7E). */
    internal fun validSessionId(value: String): Boolean = value.isNotEmpty() && value.all { it in '!'..'~' }
}

/** Why an MCP exchange failed. */
enum class McpErrorKind {
    /** The URL is not an absolute http(s) URL (or carries credentials). */
    INVALID_URL,

    /** The network policy refused the request before any I/O (Offline, cleartext, route change). */
    BLOCKED,

    /** No HTTP response (DNS, connect, TLS, reset). */
    NETWORK,

    /** 401 / 403: the token is missing or wrong. */
    AUTH,

    /** Any other non-2xx status, including redirects (never followed). */
    HTTP,

    /** The server spoke something that is not MCP over Streamable HTTP. */
    PROTOCOL,

    /** A JSON-RPC error response. */
    RPC,
}

class McpException(
    val kind: McpErrorKind,
    message: String,
    val status: Int = 0,
    val rpcCode: Int? = null,
    val rpcData: JsonElement? = null,
) : Exception(message)

/** An absolute MCP endpoint URL and its host (credentials in the URL are rejected). */
class McpEndpoint private constructor(val url: String, val host: String, val https: Boolean) {
    companion object {
        private val URL = Regex("(?i)^(https?)://([^/?#\\s]+)([^#\\s]*)$")

        fun parse(raw: String): McpEndpoint? {
            val match = URL.matchEntire(raw.trim()) ?: return null
            val (scheme, authority, rest) = match.destructured
            if ('@' in authority) return null
            val host = if (authority.startsWith('[')) {
                authority.substringAfter('[').substringBefore(']')
            } else {
                authority.substringBefore(':')
            }.lowercase()
            if (host.isEmpty()) return null
            return McpEndpoint("${scheme.lowercase()}://$authority$rest", host, scheme.equals("https", ignoreCase = true))
        }

        /** The lower-case host of [url], or null. */
        fun hostOf(url: String): String? = parse(url)?.host
    }
}

/** One dispatched SSE event. */
internal class SseEvent(val type: String, val data: String)

/**
 * Incremental `text/event-stream` reader (WHATWG event-stream interpretation): `data` lines are
 * joined with `\n`, comments are skipped, and `id` / `retry` are remembered for resuming.
 */
internal class SseEventParser {
    private val data = StringBuilder()
    private var hasData = false
    private var type: String? = null

    var lastEventId: String? = null
        private set
    var retryMs: Long? = null
        private set

    fun feed(raw: String): SseEvent? {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "event" -> type = value
            "id" -> if ('\u0000' !in value) lastEventId = value
            "retry" -> value.toLongOrNull()?.takeIf { it >= 0 }?.let { retryMs = it }
        }
        return null
    }

    /** End of stream: a trailing event without its blank line still counts. */
    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(type ?: "message", data.toString()) else null
        data.clear()
        hasData = false
        type = null
        return event
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
