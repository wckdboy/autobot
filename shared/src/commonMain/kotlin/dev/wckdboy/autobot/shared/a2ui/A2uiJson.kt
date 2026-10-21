package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** JSON helpers shared by the validator and the evaluator. */
internal val A2uiJsonFormat: Json = Json { prettyPrint = false }

/** JSON Schema type name of a value: object, array, string, number, integer, boolean or null. */
internal fun jsonTypeName(e: JsonElement): String = when (e) {
    is JsonObject -> "object"
    is JsonArray -> "array"
    JsonNull -> "null"
    is JsonPrimitive -> when {
        e.isString -> "string"
        e.content == "true" || e.content == "false" -> "boolean"
        isIntegral(e) -> "integer"
        else -> "number"
    }
}

internal fun JsonElement.isJsonString(): Boolean = this is JsonPrimitive && this !is JsonNull && isString

internal fun JsonElement.isJsonBoolean(): Boolean =
    this is JsonPrimitive && this !is JsonNull && !isString && (content == "true" || content == "false")

internal fun JsonElement.isJsonNumber(): Boolean =
    this is JsonPrimitive && this !is JsonNull && !isString && content != "true" && content != "false"

internal fun JsonElement.stringOrNull(): String? = if (isJsonString()) (this as JsonPrimitive).content else null

internal fun JsonElement.booleanOrNull(): Boolean? = if (isJsonBoolean()) (this as JsonPrimitive).content == "true" else null

internal fun JsonElement.numberOrNull(): Double? = if (isJsonNumber()) (this as JsonPrimitive).content.toDoubleOrNull() else null

/** A JSON number with no fractional part (1 and 1.0 both count, as in JSON Schema). */
internal fun isIntegral(p: JsonPrimitive): Boolean {
    if (p.isString || p is JsonNull) return false
    val c = p.content
    if (c == "true" || c == "false") return false
    if (c.all { it.isDigit() || it == '-' }) return c.any { it.isDigit() }
    val d = c.toDoubleOrNull() ?: return false
    return d.isFinite() && d == kotlin.math.floor(d)
}

/** JSON equality where numbers compare by value (1 == 1.0), as JSON Schema `const`/`enum` need. */
internal fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonObject && b is JsonObject -> a.size == b.size && a.all { (k, v) -> b[k]?.let { jsonEquals(v, it) } == true }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
    a.isJsonNumber() && b.isJsonNumber() -> a.numberOrNull() == b.numberOrNull()
    a is JsonPrimitive && b is JsonPrimitive -> a == b
    else -> false
}

internal fun jsonNumber(d: Double): JsonPrimitive =
    if (d.isFinite() && d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

/** A number as people write it: `3`, not `3.0`; `0.5`, not `5.0E-1`. */
internal fun formatPlainNumber(d: Double): String {
    if (!d.isFinite()) return d.toString()
    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    return d.toString()
}

/**
 * The string form A2UI uses when a value is interpolated or shown: strings as is, numbers and
 * booleans in their usual form, null/undefined as `""`, objects and arrays as JSON.
 */
internal fun coerceToString(e: JsonElement?): String = when {
    e == null || e == JsonNull -> ""
    e.isJsonString() -> (e as JsonPrimitive).content
    e.isJsonNumber() -> e.numberOrNull()?.let { formatPlainNumber(it) } ?: (e as JsonPrimitive).content
    e is JsonPrimitive -> e.content
    else -> A2uiJsonFormat.encodeToString(JsonElement.serializer(), e)
}

/** JavaScript-like truthiness, with a ValidationResult object counting as its `valid` member. */
internal fun truthyOrValid(e: JsonElement?): Boolean = when {
    e == null || e == JsonNull -> false
    e is JsonObject -> (e["valid"]?.booleanOrNull()) ?: true
    e is JsonArray -> true
    e.isJsonBoolean() -> e.booleanOrNull() == true
    e.isJsonNumber() -> e.numberOrNull().let { it != null && it != 0.0 && !it.isNaN() }
    e.isJsonString() -> (e as JsonPrimitive).content.isNotEmpty()
    else -> false
}

internal fun JsonElement.toJsonText(): String = A2uiJsonFormat.encodeToString(JsonElement.serializer(), this)

/**
 * Bounds a JSON text before it is parsed: its length and its nesting depth (brackets outside
 * strings). The kotlinx parser is not trusted with arbitrarily deep input.
 */
internal fun checkJsonShape(text: String, maxChars: Int, maxDepth: Int): String? {
    if (text.length > maxChars) return "message is ${text.length} characters; the limit is $maxChars"
    var depth = 0
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[' -> {
                depth++
                if (depth > maxDepth) return "JSON nesting is deeper than the limit of $maxDepth"
            }
            '}', ']' -> depth--
        }
        i++
    }
    return null
}

/** Walks a parsed value and reports the first array, string or object over the limits. */
internal fun checkJsonSizes(e: JsonElement, limits: A2uiLimits, path: String = ""): Pair<String, String>? {
    when (e) {
        is JsonArray -> {
            if (e.size > limits.maxArrayLength) return path to "array has ${e.size} items; the limit is ${limits.maxArrayLength}"
            e.forEachIndexed { i, v -> checkJsonSizes(v, limits, "$path/$i")?.let { return it } }
        }
        is JsonObject -> {
            if (e.size > limits.maxObjectKeys) return path to "object has ${e.size} keys; the limit is ${limits.maxObjectKeys}"
            for ((k, v) in e) checkJsonSizes(v, limits, JsonPointer.child(path, k))?.let { return it }
        }
        is JsonPrimitive -> if (e.isString && e.content.length > limits.maxStringLength) {
            return path to "string is ${e.content.length} characters; the limit is ${limits.maxStringLength}"
        }
    }
    return null
}
