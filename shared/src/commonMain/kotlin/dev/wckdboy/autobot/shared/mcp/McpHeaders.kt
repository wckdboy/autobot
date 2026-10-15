package dev.wckdboy.autobot.shared.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One tool parameter the server asked to mirror into an `Mcp-Param-{header}` HTTP header. */
class McpHeaderParam(val path: List<String>, val header: String)

/**
 * The 2026-07-28 Streamable HTTP request-metadata rules: `Mcp-Name` and `x-mcp-header`
 * mirroring, and the value encoding both use
 * (https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
 */
object McpHeaders {
    private const val KEY = "x-mcp-header"
    private const val SENTINEL_START = "=?base64?"
    private const val SENTINEL_END = "?="
    private val TCHAR = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /**
     * The `x-mcp-header` annotations of [inputSchema], or null when any of them is invalid (the
     * tool must then be left out): every annotation must sit on a property reachable through
     * `properties` keys only, name a unique RFC 9110 token, and annotate a string, integer or
     * boolean.
     */
    fun params(inputSchema: JsonObject): List<McpHeaderParam>? {
        val found = ArrayList<McpHeaderParam>()
        if (!walk(inputSchema, emptyList(), found)) return null
        if (found.size != countAnnotations(inputSchema)) return null
        val names = found.map { it.header.lowercase() }
        if (names.toSet().size != names.size) return null
        return found
    }

    /** Headers for one `tools/call`: absent or null arguments are omitted. */
    fun values(params: List<McpHeaderParam>, arguments: JsonObject): Map<String, String> = buildMap {
        for (param in params) {
            var node: JsonElement? = arguments
            for (key in param.path) node = (node as? JsonObject)?.get(key)
            val primitive = node as? JsonPrimitive ?: continue
            if (primitive is JsonNull) continue
            val text = when {
                primitive.isString -> primitive.content
                primitive.booleanOrNull != null -> primitive.booleanOrNull.toString()
                else -> primitive.longOrNull?.toString() ?: primitive.content
            }
            put("Mcp-Param-${param.header}", encode(text))
        }
    }

    /** A header value as is, or `=?base64?…?=` when it is not plain printable ASCII. */
    fun encode(value: String): String {
        val plain = value.all { it == '\t' || it in ' '..'~' } &&
            value == value.trim() &&
            !(value.startsWith(SENTINEL_START) && value.endsWith(SENTINEL_END))
        return if (plain) value else SENTINEL_START + base64(value.encodeToByteArray()) + SENTINEL_END
    }

    private fun walk(schema: JsonObject, path: List<String>, found: MutableList<McpHeaderParam>): Boolean {
        val properties = schema["properties"] as? JsonObject ?: return true
        for ((name, element) in properties) {
            val property = element as? JsonObject ?: continue
            property[KEY]?.let { annotation ->
                val header = (annotation as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                if (!TCHAR.matches(header)) return false
                val type = (property["type"] as? JsonPrimitive)?.content
                if (type !in setOf("string", "integer", "boolean")) return false
                found += McpHeaderParam(path + name, header)
            }
            if (!walk(property, path + name, found)) return false
        }
        return true
    }

    private fun countAnnotations(element: JsonElement): Int = when (element) {
        is JsonObject -> element.entries.sumOf { (key, value) -> (if (key == KEY) 1 else 0) + countAnnotations(value) }
        is JsonArray -> element.sumOf { countAnnotations(it) }
        else -> 0
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    internal fun base64(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            append(ALPHABET[b0 shr 2])
            append(ALPHABET[((b0 and 0x3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            append(if (b1 < 0) '=' else ALPHABET[((b1 and 0xF) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            append(if (b2 < 0) '=' else ALPHABET[b2 and 0x3F])
            i += 3
        }
    }
}
