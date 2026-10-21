package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One JSON Schema failure: where in the instance ([path], a JSON Pointer) and why. */
data class SchemaError(val path: String, val message: String, val keyword: String)

/** A schema document known to a [SchemaSet], by [name] (e.g. `common_types.json`) and its `$id`. */
class SchemaDocument internal constructor(val name: String, val root: JsonElement) {
    val id: String? = (root as? JsonObject)?.get("\$id")?.stringOrNull()
}

/**
 * The documents a validation may reference. `$ref` targets resolve by exact name or `$id`, then
 * by file name (`common_types.json` finds `https://a2ui.org/.../common_types.json`). The JSON
 * Schema meta-schema is never fetched: a reference to it accepts any value. An unknown document
 * fails validation (never silently accepts).
 */
class SchemaSet {
    private val docs = ArrayList<SchemaDocument>()

    fun add(name: String, root: JsonElement): SchemaSet {
        docs.removeAll { it.name == name }
        docs.add(SchemaDocument(name, root))
        return this
    }

    fun document(name: String): SchemaDocument? = docs.firstOrNull { it.name == name }

    internal fun find(ref: String): SchemaDocument? {
        docs.firstOrNull { it.name == ref || it.id == ref }?.let { return it }
        val file = ref.substringAfterLast('/')
        if (file == "catalog.json") document("catalog.json")?.let { return it }
        return docs.firstOrNull { it.name.substringAfterLast('/') == file || it.id?.substringAfterLast('/') == file }
    }
}

/**
 * Interprets the subset of JSON Schema 2020-12 the A2UI schemas and catalogs use: `type`, `enum`,
 * `const`, `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`,
 * `unevaluatedProperties` (with annotation tracking through `$ref`, `allOf`, `anyOf`, `oneOf` and
 * `if`/`then`/`else`), `items`, `prefixItems`, `contains`, `minItems`, `maxItems`, `uniqueItems`,
 * `minProperties`, `maxProperties`, `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`,
 * `multipleOf`, `minLength`, `maxLength`, `pattern`, `format` (uri, date, time, date-time, email),
 * `allOf`, `anyOf`, `oneOf`, `not`, `if`/`then`/`else` and `$ref`.
 *
 * Other keywords (annotations such as `description`, `default`, `discriminator`, and the A2UI
 * metadata `returnType`, `allowedParents`...) are ignored, as ajv does with `strict: false`.
 * Not implemented: `$dynamicRef`, `unevaluatedItems`, `dependentSchemas`, `contentMediaType`.
 */
class JsonSchemaValidator(private val schemas: SchemaSet, private val maxDepth: Int = 512) {

    /** Validates [instance] against `doc#fragment` (e.g. `agent_to_renderer.json`). */
    fun validate(instance: JsonElement, ref: String): List<SchemaError> {
        val docName = ref.substringBefore('#')
        val doc = schemas.find(docName) ?: return listOf(SchemaError("", "unknown schema document '$docName'", "\$ref"))
        val target = fragment(doc, ref.substringAfter('#', "")) ?: return listOf(SchemaError("", "unresolvable schema '$ref'", "\$ref"))
        return eval(target, instance, "", doc, 0).errors
    }

    private class Out {
        val errors = ArrayList<SchemaError>()
        val evaluated = HashSet<String>()
        val declared = LinkedHashSet<String>()
        val ok get() = errors.isEmpty()
        fun merge(o: Out) {
            evaluated += o.evaluated
            declared += o.declared
        }
    }

    private fun fragment(doc: SchemaDocument, frag: String): JsonElement? =
        if (frag.isEmpty() || frag == "/") doc.root else try {
            JsonPointer.get(doc.root, frag)
        } catch (_: A2uiPointerException) {
            null
        }

    private fun resolveRef(ref: String, from: SchemaDocument): Pair<JsonElement, SchemaDocument>? {
        val docPart = ref.substringBefore('#')
        if (docPart.startsWith("https://json-schema.org/") || docPart.startsWith("http://json-schema.org/")) {
            return JsonPrimitive(true) to from
        }
        val doc = if (docPart.isEmpty()) from else schemas.find(docPart) ?: return null
        val target = fragment(doc, ref.substringAfter('#', "")) ?: return null
        return target to doc
    }

    private fun eval(schema: JsonElement, inst: JsonElement, path: String, doc: SchemaDocument, depth: Int): Out {
        val out = Out()
        if (schema is JsonPrimitive) {
            if (schema.booleanOrNull() == false) out.errors += SchemaError(path, "no value is allowed here", "false")
            return out
        }
        if (schema !is JsonObject) return out
        if (depth > maxDepth) {
            out.errors += SchemaError(path, "value is nested too deeply to validate", "depth")
            return out
        }

        // Fast failures first: a wrong type or a wrong discriminator const ends the branch, so a
        // oneOf over many components or functions does not explore every non-matching branch.
        schema["type"]?.let { t ->
            val types = if (t is JsonArray) t.mapNotNull { it.stringOrNull() } else listOfNotNull(t.stringOrNull())
            if (types.isNotEmpty() && types.none { typeMatches(it, inst) }) {
                out.errors += SchemaError(path, "expected ${types.joinToString(" or ")}, got ${jsonTypeName(inst)}", "type")
                return out
            }
        }
        if (inst is JsonObject) {
            (schema["properties"] as? JsonObject)?.forEach { (k, s) ->
                val c = (s as? JsonObject)?.get("const") ?: return@forEach
                val v = inst[k] ?: return@forEach
                if (!jsonEquals(c, v)) {
                    out.errors += SchemaError(JsonPointer.child(path, k), "must be ${c.toJsonText()}", "const")
                    return out
                }
            }
        }

        schema["\$ref"]?.stringOrNull()?.let { ref ->
            val resolved = resolveRef(ref, doc)
            if (resolved == null) {
                out.errors += SchemaError(path, "schema reference '$ref' cannot be resolved", "\$ref")
            } else {
                val r = eval(resolved.first, inst, path, resolved.second, depth + 1)
                out.errors += r.errors
                if (r.ok) out.merge(r)
            }
        }

        schema["const"]?.let { c ->
            if (!jsonEquals(c, inst)) out.errors += SchemaError(path, "must be ${c.toJsonText()}", "const")
        }
        (schema["enum"] as? JsonArray)?.let { e ->
            if (e.none { jsonEquals(it, inst) }) {
                out.errors += SchemaError(path, "must be one of: ${e.joinToString(", ") { coerceToString(it) }}", "enum")
            }
        }

        if (inst.isJsonNumber()) numberKeywords(schema, inst.numberOrNull() ?: 0.0, path, out)
        if (inst.isJsonString()) stringKeywords(schema, (inst as JsonPrimitive).content, path, out)
        if (inst is JsonArray) arrayKeywords(schema, inst, path, doc, depth, out)
        if (inst is JsonObject) objectKeywords(schema, inst, path, doc, depth, out)

        (schema["allOf"] as? JsonArray)?.forEach { s ->
            val r = eval(s, inst, path, doc, depth + 1)
            out.errors += r.errors
            if (r.ok) out.merge(r)
        }
        (schema["anyOf"] as? JsonArray)?.let { branches ->
            val results = branches.map { eval(it, inst, path, doc, depth + 1) }
            val passing = results.filter { it.ok }
            if (passing.isEmpty()) out.errors += explain(results, branches, path, "anyOf", doc) else passing.forEach { out.merge(it) }
        }
        (schema["oneOf"] as? JsonArray)?.let { branches ->
            val results = branches.map { eval(it, inst, path, doc, depth + 1) }
            val passing = results.filter { it.ok }
            when {
                passing.isEmpty() -> out.errors += explain(results, branches, path, "oneOf", doc)
                passing.size > 1 -> out.errors += SchemaError(path, "matches more than one of the allowed forms (${passing.size})", "oneOf")
                else -> out.merge(passing[0])
            }
        }
        schema["not"]?.let { s ->
            val r = eval(s, inst, path, doc, depth + 1)
            if (r.ok) {
                val c = (s as? JsonObject)?.get("const")
                out.errors += SchemaError(path, if (c != null) "must not be ${c.toJsonText()}" else "is a form that is not allowed here", "not")
            }
        }
        schema["if"]?.let { s ->
            val r = eval(s, inst, path, doc, depth + 1)
            val branch = if (r.ok) {
                out.merge(r); schema["then"]
            } else schema["else"]
            branch?.let {
                val b = eval(it, inst, path, doc, depth + 1)
                out.errors += b.errors
                if (b.ok) out.merge(b)
            }
        }

        // When another keyword here already failed, the properties it would have evaluated show up
        // as "unevaluated" too; that cascade only buries the real error, so it is not reported.
        if (inst is JsonObject && out.ok) schema["unevaluatedProperties"]?.let { u ->
            for ((k, v) in inst) {
                if (k in out.evaluated) continue
                if (u.booleanOrNull() == false) {
                    out.errors += SchemaError(JsonPointer.child(path, k), unexpected(k, out.declared), "unevaluatedProperties")
                } else {
                    val r = eval(u, v, JsonPointer.child(path, k), doc, depth + 1)
                    out.errors += r.errors
                }
                out.evaluated += k
            }
        }
        return out
    }

    private fun unexpected(key: String, allowed: Set<String>): String =
        if (allowed.isEmpty()) "property '$key' is not allowed here"
        else "property '$key' is not allowed here; allowed: ${allowed.joinToString(", ")}"

    private fun typeMatches(type: String, v: JsonElement): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v.isJsonString()
        "boolean" -> v.isJsonBoolean()
        "null" -> v == JsonNull
        "number" -> v.isJsonNumber()
        "integer" -> v.isJsonNumber() && isIntegral(v as JsonPrimitive)
        else -> true
    }

    private fun numberKeywords(s: JsonObject, n: Double, path: String, out: Out) {
        s["minimum"]?.numberOrNull()?.let { if (n < it) out.errors += SchemaError(path, "must be at least ${formatPlainNumber(it)}", "minimum") }
        s["maximum"]?.numberOrNull()?.let { if (n > it) out.errors += SchemaError(path, "must be at most ${formatPlainNumber(it)}", "maximum") }
        s["exclusiveMinimum"]?.numberOrNull()?.let { if (n <= it) out.errors += SchemaError(path, "must be greater than ${formatPlainNumber(it)}", "exclusiveMinimum") }
        s["exclusiveMaximum"]?.numberOrNull()?.let { if (n >= it) out.errors += SchemaError(path, "must be less than ${formatPlainNumber(it)}", "exclusiveMaximum") }
        s["multipleOf"]?.numberOrNull()?.let { m ->
            if (m > 0) {
                val q = n / m
                if (kotlin.math.abs(q - kotlin.math.round(q)) > 1e-9) out.errors += SchemaError(path, "must be a multiple of ${formatPlainNumber(m)}", "multipleOf")
            }
        }
    }

    private fun stringKeywords(s: JsonObject, str: String, path: String, out: Out) {
        val len by lazy { codePointCount(str) }
        s["minLength"]?.numberOrNull()?.let { if (len < it) out.errors += SchemaError(path, "must be at least ${formatPlainNumber(it)} characters", "minLength") }
        s["maxLength"]?.numberOrNull()?.let { if (len > it) out.errors += SchemaError(path, "must be at most ${formatPlainNumber(it)} characters", "maxLength") }
        s["pattern"]?.stringOrNull()?.let { p ->
            if (!SchemaPatterns.matches(p, str)) out.errors += SchemaError(path, "does not match the pattern $p", "pattern")
        }
        s["format"]?.stringOrNull()?.let { f ->
            if (!SchemaFormats.check(f, str)) out.errors += SchemaError(path, "is not a valid $f", "format")
        }
    }

    private fun arrayKeywords(s: JsonObject, a: JsonArray, path: String, doc: SchemaDocument, depth: Int, out: Out) {
        s["minItems"]?.numberOrNull()?.let { if (a.size < it) out.errors += SchemaError(path, "needs at least ${formatPlainNumber(it)} items, has ${a.size}", "minItems") }
        s["maxItems"]?.numberOrNull()?.let { if (a.size > it) out.errors += SchemaError(path, "allows at most ${formatPlainNumber(it)} items, has ${a.size}", "maxItems") }
        val prefix = (s["prefixItems"] as? JsonArray) ?: JsonArray(emptyList())
        prefix.forEachIndexed { i, ps ->
            if (i < a.size) out.errors += eval(ps, a[i], "$path/$i", doc, depth + 1).errors
        }
        s["items"]?.let { items ->
            for (i in prefix.size until a.size) out.errors += eval(items, a[i], "$path/$i", doc, depth + 1).errors
        }
        s["contains"]?.let { c ->
            if (a.none { eval(c, it, path, doc, depth + 1).ok }) out.errors += SchemaError(path, "contains no item of the required form", "contains")
        }
        if (s["uniqueItems"]?.booleanOrNull() == true) {
            for (i in a.indices) for (j in i + 1 until a.size) {
                if (jsonEquals(a[i], a[j])) {
                    out.errors += SchemaError("$path/$j", "duplicates item $i; items must be unique", "uniqueItems")
                    return
                }
            }
        }
    }

    private fun objectKeywords(s: JsonObject, o: JsonObject, path: String, doc: SchemaDocument, depth: Int, out: Out) {
        (s["required"] as? JsonArray)?.forEach { r ->
            val k = r.stringOrNull() ?: return@forEach
            if (k !in o) out.errors += SchemaError(path, "missing required property '$k'", "required")
        }
        s["minProperties"]?.numberOrNull()?.let { if (o.size < it) out.errors += SchemaError(path, "needs at least ${formatPlainNumber(it)} properties", "minProperties") }
        s["maxProperties"]?.numberOrNull()?.let { if (o.size > it) out.errors += SchemaError(path, "allows at most ${formatPlainNumber(it)} properties", "maxProperties") }
        val props = s["properties"] as? JsonObject
        props?.forEach { (k, ps) ->
            out.declared += k
            val v = o[k] ?: return@forEach
            val r = eval(ps, v, JsonPointer.child(path, k), doc, depth + 1)
            out.errors += r.errors
            out.evaluated += k
        }
        val patterns = s["patternProperties"] as? JsonObject
        patterns?.forEach { (p, ps) ->
            for ((k, v) in o) if (SchemaPatterns.matches(p, k)) {
                out.errors += eval(ps, v, JsonPointer.child(path, k), doc, depth + 1).errors
                out.evaluated += k
            }
        }
        s["additionalProperties"]?.let { ap ->
            for ((k, v) in o) {
                if (props?.containsKey(k) == true) continue
                if (patterns != null && patterns.keys.any { SchemaPatterns.matches(it, k) }) continue
                if (ap.booleanOrNull() == false) {
                    out.errors += SchemaError(JsonPointer.child(path, k), unexpected(k, props?.keys.orEmpty()), "additionalProperties")
                } else {
                    out.errors += eval(ap, v, JsonPointer.child(path, k), doc, depth + 1).errors
                }
                out.evaluated += k
            }
        }
        s["propertyNames"]?.let { pn ->
            for (k in o.keys) {
                if (!eval(pn, JsonPrimitive(k), path, doc, depth + 1).ok) {
                    out.errors += SchemaError(JsonPointer.child(path, k), "property name '$k' is not allowed", "propertyNames")
                }
            }
        }
    }

    /**
     * Why no alternative matched, in a form a model can act on: when every alternative rejects
     * the same discriminator property (`component`, `@call`), the allowed values; otherwise the
     * errors of the alternative that came closest.
     */
    private fun explain(results: List<Out>, branches: JsonArray, path: String, keyword: String, doc: SchemaDocument): List<SchemaError> {
        val discriminators = results.map { r -> r.errors.firstOrNull { it.keyword == "const" && isDirectChild(path, it.path) }?.path }
        val common = discriminators.firstOrNull()
        if (common != null && discriminators.all { it == common }) {
            val allowed = branches.flatMap { constsFor(it, doc, common.substringAfterLast('/')) }.distinct()
            if (allowed.isNotEmpty()) {
                // Reported as a const failure so an enclosing oneOf can merge it the same way.
                return listOf(SchemaError(common, "must be one of: ${allowed.joinToString(", ")}", "const"))
            }
        }
        if (results.all { r -> r.errors.any { it.keyword == "type" && it.path == path } }) {
            val types = results.flatMap { r -> r.errors.filter { it.keyword == "type" && it.path == path }.map { it.message.substringAfter("expected ").substringBefore(", got") } }
            return listOf(SchemaError(path, "expected ${types.distinct().joinToString(" or ")}, got ${results.first().errors.first { it.keyword == "type" }.message.substringAfter(", got ")}", keyword))
        }
        // The closest alternative: a wrong type is furthest, a wrong discriminator value next, then
        // each missing or unexpected property; ties go to the alternative whose errors are deepest.
        val best = results.minByOrNull { r ->
            val wrongType = r.errors.any { it.keyword == "type" && it.path == path }
            val wrongConst = r.errors.count { it.keyword == "const" && isDirectChild(path, it.path) }
            val missing = r.errors.count { it.keyword == "required" && it.path == path }
            val unexpected = r.errors.count { (it.keyword == "additionalProperties" || it.keyword == "unevaluatedProperties") && isDirectChild(path, it.path) }
            val deepest = r.errors.maxOfOrNull { it.path.count { c -> c == '/' } } ?: 0
            (if (wrongType) 1_000_000 else 0) + (2 * wrongConst + missing + unexpected) * 10_000 - deepest * 100 + r.errors.size.coerceAtMost(99)
        } ?: return listOf(SchemaError(path, "matches none of the allowed forms", keyword))
        return best.errors.ifEmpty { listOf(SchemaError(path, "matches none of the allowed forms", keyword)) }
    }

    private fun isDirectChild(parent: String, child: String): Boolean =
        child.startsWith("$parent/") && '/' !in child.substring(parent.length + 1)

    /** The `const` values an alternative declares for property [escapedKey] (following `$ref`s). */
    private fun constsFor(schema: JsonElement, doc: SchemaDocument, escapedKey: String, depth: Int = 0): List<String> {
        if (schema !is JsonObject || depth > 8) return emptyList()
        val key = escapedKey.replace("~1", "/").replace("~0", "~")
        val own = ((schema["properties"] as? JsonObject)?.get(key) as? JsonObject)?.get("const")?.let { listOf(coerceToString(it)) }.orEmpty()
        val viaRef = schema["\$ref"]?.stringOrNull()?.let { ref -> resolveRef(ref, doc) }
            ?.let { (target, targetDoc) -> constsFor(target, targetDoc, escapedKey, depth + 1) }.orEmpty()
        val nested = listOf("allOf", "oneOf", "anyOf").flatMap { k ->
            (schema[k] as? JsonArray)?.flatMap { constsFor(it, doc, escapedKey, depth + 1) }.orEmpty()
        }
        return own + viaRef + nested
    }

    private fun codePointCount(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) i++
            n++; i++
        }
        return n
    }
}

/** `pattern` support. The UAX #31 identifier pattern the A2UI schemas use is matched natively. */
internal object SchemaPatterns {
    private const val UAX31 = "^[\\p{XID_Start}_][\\p{XID_Continue}]*$"
    private val cache = HashMap<String, Regex?>()

    fun matches(pattern: String, s: String): Boolean {
        if (pattern == UAX31) return isIdentifier(s)
        val regex = cache.getOrPut(pattern) {
            try {
                Regex(pattern)
            } catch (_: Throwable) {
                null
            }
        } ?: return false
        return regex.containsMatchIn(s)
    }

    /** UAX #31 identifier (`XID_Start`/`_` then `XID_Continue`), approximated with general categories. */
    fun isIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!(s[0] == '_' || isXidStart(s[0]))) return false
        return s.drop(1).all { isXidContinue(it) }
    }

    private fun isXidStart(c: Char): Boolean = c.isSurrogate() || when (c.category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER, CharCategory.LETTER_NUMBER -> true
        else -> false
    }

    private fun isXidContinue(c: Char): Boolean = isXidStart(c) || when (c.category) {
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.DECIMAL_DIGIT_NUMBER,
        CharCategory.CONNECTOR_PUNCTUATION -> true
        else -> false
    }
}

/** `format` checks, following ajv-formats (full mode) for the formats the A2UI schemas use. */
internal object SchemaFormats {
    private val date = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")
    private val time = Regex("^(\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?(z|Z|[+-]\\d{2}(:?\\d{2})?)$")
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:[^\\s<>\"{}|\\\\^`]*$")
    private val email = Regex("^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")

    fun check(format: String, s: String): Boolean = when (format) {
        "date" -> isDate(s)
        "time" -> isTime(s)
        "date-time" -> s.split('T', 't', ' ').let { it.size == 2 && isDate(it[0]) && isTime(it[1]) }
        "uri" -> scheme.matches(s)
        "email" -> email.matches(s)
        else -> true
    }

    fun isDate(s: String): Boolean {
        val m = date.matchEntire(s) ?: return false
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day < 1) return false
        return day <= daysInMonth(y.toInt(), month)
    }

    fun isTime(s: String): Boolean {
        val m = time.matchEntire(s) ?: return false
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].toInt()
        return h <= 23 && min <= 59 && sec <= 60
    }

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
