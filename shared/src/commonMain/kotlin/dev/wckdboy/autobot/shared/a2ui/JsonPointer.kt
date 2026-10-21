package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A JSON Pointer that does not parse, or a write the data model cannot take. */
class A2uiPointerException(message: String, val pointer: String) : IllegalArgumentException(message)

/**
 * RFC 6901 JSON Pointers over kotlinx JSON trees, with the A2UI conventions:
 * `""` and `"/"` both mean the whole document (A2UI uses `/` for the root), a missing value is
 * `null` (the protocol's `undefined`), and a write of `null` deletes.
 */
object JsonPointer {
    /** Largest array index a write may create, so a pointer cannot allocate a huge array. */
    const val MAX_ARRAY_INDEX = 10_000

    /** The reference tokens of [pointer]; empty for the root. */
    fun parse(pointer: String): List<String> {
        if (pointer.isEmpty() || pointer == "/") return emptyList()
        if (!pointer.startsWith("/")) throw A2uiPointerException("JSON Pointer '$pointer' must start with '/'", pointer)
        return pointer.substring(1).split('/').map { unescape(it, pointer) }
    }

    /** The pointer for [tokens], escaping `~` and `/`. */
    fun format(tokens: List<String>): String =
        if (tokens.isEmpty()) "" else tokens.joinToString("") { "/" + escape(it) }

    fun escape(token: String): String = token.replace("~", "~0").replace("/", "~1")

    /** Whether [pointer] is a well-formed absolute pointer (or the root). */
    fun isValid(pointer: String): Boolean = try {
        parse(pointer); true
    } catch (_: A2uiPointerException) {
        false
    }

    /** Appends [token] to an absolute [pointer] ("" or "/" is the root). */
    fun child(pointer: String, token: String): String = (if (pointer == "/") "" else pointer) + "/" + escape(token)

    /**
     * Resolves a binding path against a scope: an absolute path (`/a/b`) stands alone, a relative
     * one (`name`, `a/b`) is appended to [base] (the template item's absolute pointer, `""` at the
     * root). `""` and `"."` name the scope itself. The result is always absolute, `""` for the root.
     */
    fun resolve(base: String, path: String): String {
        if (path.startsWith("/")) return if (path == "/") "" else path
        val b = if (base == "/") "" else base.trimEnd('/')
        if (path.isEmpty() || path == ".") return b
        return "$b/$path"
    }

    /** The value at [pointer], or null when it does not exist (A2UI's `undefined`). */
    fun get(root: JsonElement?, pointer: String): JsonElement? {
        var current: JsonElement? = root
        for (token in parse(pointer)) {
            current = when (current) {
                is JsonObject -> current[token]
                is JsonArray -> arrayIndex(token)?.let { current.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current
    }

    /**
     * Writes [value] at [pointer] and returns the new tree (the input is not changed). Missing
     * containers are created: an array when the next token is an index, an object otherwise.
     * `null` deletes an object key, or blanks an array slot (indexes of the items after it stay).
     * `-` appends to an array. Writing through a string, number or boolean fails.
     */
    fun set(root: JsonElement?, pointer: String, value: JsonElement?): JsonElement {
        val tokens = parse(pointer)
        if (tokens.isEmpty()) return value ?: JsonObject(emptyMap())
        return setIn(root, tokens, 0, value, pointer) ?: JsonObject(emptyMap())
    }

    private fun setIn(node: JsonElement?, tokens: List<String>, i: Int, value: JsonElement?, pointer: String): JsonElement? {
        val token = tokens[i]
        val last = i == tokens.size - 1
        val container: JsonElement = when (node) {
            null, JsonNull -> {
                if (value == null) return node
                if (arrayIndex(token) != null || token == "-") JsonArray(emptyList()) else JsonObject(emptyMap())
            }
            is JsonObject, is JsonArray -> node
            else -> throw A2uiPointerException(
                "Cannot write '$pointer': '${format(tokens.take(i))}' is a ${jsonTypeName(node)}, not an object or array",
                pointer,
            )
        }
        return when (container) {
            is JsonObject -> {
                val map = LinkedHashMap(container)
                if (last) {
                    if (value == null) map.remove(token) else map[token] = value
                } else {
                    val next = setIn(map[token], tokens, i + 1, value, pointer)
                    if (next == null) map.remove(token) else map[token] = next
                }
                JsonObject(map)
            }
            is JsonArray -> {
                val list = container.toMutableList()
                val index = if (token == "-") list.size else arrayIndex(token)
                    ?: throw A2uiPointerException("Cannot write '$pointer': '$token' is not an array index", pointer)
                if (index > MAX_ARRAY_INDEX) {
                    throw A2uiPointerException("Cannot write '$pointer': index $index is over the limit of $MAX_ARRAY_INDEX", pointer)
                }
                if (value == null && index >= list.size) return container
                if (last && value == null) {
                    list[index] = JsonNull
                    return JsonArray(list)
                }
                while (list.size <= index) list.add(JsonNull)
                list[index] = if (last) value!! else setIn(list[index], tokens, i + 1, value, pointer) ?: JsonNull
                JsonArray(list)
            }
            else -> error("unreachable")
        }
    }

    private fun unescape(token: String, pointer: String): String {
        if ('~' !in token) return token
        val sb = StringBuilder()
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '~') {
                when (token.getOrNull(i + 1)) {
                    '0' -> sb.append('~')
                    '1' -> sb.append('/')
                    else -> throw A2uiPointerException("JSON Pointer '$pointer' has '~' not followed by 0 or 1", pointer)
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** RFC 6901 array index: `0` or a digit string without a leading zero. */
    internal fun arrayIndex(token: String): Int? {
        if (token.isEmpty() || token.length > 9) return null
        if (token.length > 1 && token[0] == '0') return null
        if (!token.all { it in '0'..'9' }) return null
        return token.toInt()
    }
}
