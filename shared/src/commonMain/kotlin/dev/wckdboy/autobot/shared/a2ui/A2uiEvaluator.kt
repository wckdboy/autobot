package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where an expression is evaluated: [base] is the absolute pointer relative paths start from
 * (`""` at the root, `/items/2` inside the third item of a template), [indexes] the template
 * iteration indexes from the outermost to the innermost list (`@index` reads the last).
 */
internal class EvalScope(val base: String, val indexes: List<Int>) {
    fun item(listPointer: String, index: Int) = EvalScope(JsonPointer.child(listPointer, index.toString()), indexes + index)

    companion object {
        val ROOT = EvalScope("", emptyList())
    }
}

/**
 * Resolves A2UI dynamic values against one surface's data model: `{"@path"}` bindings (absolute
 * or relative to the template scope), `{"@call"}` function calls, and literal values (plain
 * objects with `@@`-escaped keys unescaped). Evaluation never fails: a problem is reported
 * through [onError] as EXPRESSION_ERROR (or INVALID_FUNCTION_CALL) and the value is undefined.
 *
 * [activation] is true only while handling a user action; functions that need a user gesture
 * (`openUrl`) refuse to run without it.
 */
internal class A2uiEvaluator(
    val surfaceId: String,
    val catalog: A2uiCatalog?,
    val data: JsonElement,
    val limits: A2uiLimits,
    private val onError: (A2uiError) -> Unit,
) {
    /** Where the current evaluation comes from, for error messages (e.g. "component 'name' property 'text'"). */
    var where: String = ""

    /** URLs `openUrl` accepted during this evaluation, for the renderer to open. */
    val openedUrls = ArrayList<String>()

    private val parser = A2uiTemplateParser(limits.maxTemplateChars, limits.maxCallDepth)

    fun error(message: String, code: A2uiErrorCode = A2uiErrorCode.EXPRESSION_ERROR) {
        onError(A2uiError(code, if (where.isEmpty()) message else "$where: $message", surfaceId = surfaceId))
    }

    fun requestOpenUrl(url: String) {
        openedUrls += url
    }

    /** The value of [v] in [scope]; null is undefined (missing data or a failed expression). */
    fun resolve(v: JsonElement?, scope: EvalScope, activation: Boolean, depth: Int = 0): JsonElement? {
        if (v == null) return null
        if (depth > limits.maxCallDepth) {
            error("expression is nested deeper than ${limits.maxCallDepth} levels")
            return null
        }
        return when (v) {
            is JsonArray -> if (v.none { containsDynamic(it) }) v else JsonArray(v.map { resolve(it, scope, activation, depth + 1) ?: JsonNull })
            is JsonObject -> when {
                v["@path"]?.isJsonString() == true -> read(v["@path"]!!.stringOrNull()!!, scope)
                v["@call"]?.isJsonString() == true -> call(v, scope, activation, depth)
                !containsDynamic(v) && v.keys.none { it.startsWith("@@") } -> v
                else -> JsonObject(v.entries.associate { (k, e) -> unescapeKey(k) to (resolve(e, scope, activation, depth + 1) ?: JsonNull) })
            }
            else -> v
        }
    }

    fun read(path: String, scope: EvalScope): JsonElement? {
        val pointer = JsonPointer.resolve(scope.base, path)
        return try {
            JsonPointer.get(data, pointer)
        } catch (e: A2uiPointerException) {
            error(e.message ?: "invalid path '$path'")
            null
        }
    }

    /** Evaluates a `{"@call": name, "args": {...}}` object. */
    fun call(obj: JsonObject, scope: EvalScope, activation: Boolean, depth: Int): JsonElement? {
        val name = obj["@call"]?.stringOrNull() ?: return null
        val rawArgs = obj["args"] as? JsonObject ?: JsonObject(emptyMap())
        if (name == "@index") {
            if ("catalogId" in obj) {
                error("@index is a system function and takes no catalogId")
                return null
            }
            val index = scope.indexes.lastOrNull()
            if (index == null) {
                error("@index can only be used inside a list template")
                return null
            }
            val offset = resolve(rawArgs["offset"], scope, activation, depth + 1)?.numberOrNull() ?: 0.0
            return jsonNumber(index + offset)
        }
        val catalog = catalog
        val spec = catalog?.function(name)
        if (catalog == null || spec == null) {
            error("unknown function '$name'")
            return null
        }
        obj["catalogId"]?.stringOrNull()?.let { id ->
            if (id != catalog.catalogId) {
                error("function '$name' names catalog '$id'; this surface uses '${catalog.catalogId}' (mixed catalogs are not supported)")
                return null
            }
        }
        if (spec.allowedCallers == "agentOnly") {
            error("function '$name' is agentOnly and cannot run in the UI", A2uiErrorCode.INVALID_FUNCTION_CALL)
            return null
        }
        if (spec.requiresUserActivation && !activation) {
            error("function '$name' needs a user action; use it in a Button action, not in a property or check")
            return null
        }
        val impl = A2uiFunctions.basic[name]
        if (impl == null) {
            error("function '$name' is not implemented by this renderer")
            return null
        }
        val args = rawArgs.entries.associate { (k, e) -> k to resolve(e, scope, activation, depth + 1) }
        return try {
            impl.invoke(FunctionCall(name, args, this, scope, activation, depth + 1))
        } catch (e: A2uiExpressionException) {
            error("$name: ${e.message}")
            null
        }
    }

    /** Interpolates a formatString template in [scope]. */
    fun formatString(template: String, scope: EvalScope, activation: Boolean, depth: Int): String {
        val parts = try {
            parser.parse(template)
        } catch (e: A2uiExpressionException) {
            error("formatString: ${e.message}")
            return ""
        }
        return parts.joinToString("") { p -> if (p is JsonPrimitive) coerceToString(p) else coerceToString(resolve(p, scope, activation, depth + 1)) }
    }

    companion object {
        fun unescapeKey(k: String): String = if (k.startsWith("@@")) k.substring(1) else k

        fun containsDynamic(e: JsonElement): Boolean = when (e) {
            is JsonObject -> e.keys.any { it.startsWith("@") } || e.values.any { containsDynamic(it) }
            is JsonArray -> e.any { containsDynamic(it) }
            else -> false
        }

        /** A DynamicBoolean's value: booleans as is, a ValidationResult as its `valid`, otherwise truthiness. */
        fun asBoolean(v: JsonElement?): Boolean = truthyOrValid(v)
    }
}
