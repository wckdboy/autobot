package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of validating agent output: every error found, in stream order. */
class A2uiReport internal constructor(val errors: List<A2uiError>, val messageCount: Int) {
    val isValid: Boolean get() = errors.isEmpty()

    /** The errors as lines for the model to fix its output, or "valid". */
    fun forModel(): String =
        if (errors.isEmpty()) "valid: $messageCount A2UI message(s)"
        else errors.joinToString("\n") { it.forModel() }
}

/**
 * Checks agent output before it is shown: what the on-device agent's A2UI tool runs on the model's
 * output, so the model can correct itself. It plays the messages on a scratch [A2uiHost] (schema
 * and catalog validation, the protocol's semantic rules, surface integrity per message) and, at
 * the end, checks each surface's tree: a `root`, every child reference defined, no cycles,
 * `allowedParents`/`allowedChildren`, `@index` only in templates.
 */
class A2uiValidator(private val catalogs: List<A2uiCatalog>, private val limits: A2uiLimits) {
    constructor() : this(listOf(A2uiCatalog.basic), A2uiLimits.standard)

    fun validate(text: String): A2uiReport {
        val host = A2uiHost(catalogs, limits)
        val parsed = A2uiParser.parse(text, limits)
        val errors = ArrayList(parsed.errors)
        for ((i, m) in parsed.messages) errors += host.applyMessage(m, i)
        for (id in host.surfaceIds) {
            val surface = host.surface(id)!!
            errors += surface.integrityErrors()
            // Expressions that fail when the tree is evaluated with the data it has (cycles and
            // @index misuse are already reported above, statically).
            errors += surface.renderErrors.filter { it.code != A2uiErrorCode.INTEGRITY_ERROR && "@index" !in it.message }
        }
        return A2uiReport(errors.sortedBy { it.messageIndex ?: Int.MAX_VALUE }, parsed.messageCount)
    }

    /** Validates one message object against the schemas only (no surface state), as the spec tests do. */
    fun validateSchema(messageJson: String): List<A2uiError> {
        val e = try {
            kotlinx.serialization.json.Json.parseToJsonElement(messageJson)
        } catch (ex: Exception) {
            return listOf(A2uiError(A2uiErrorCode.VALIDATION_FAILED, "not JSON: ${ex.message}"))
        }
        A2uiParser.checkMessage(e, limits)?.let { return listOf(it) }
        val obj = e as JsonObject
        val kind = A2uiMessage.kindOf(obj)
        val catalogId = (obj[kind ?: ""] as? JsonObject)?.get("catalogId")?.stringOrNull()
        val catalog = catalogs.firstOrNull { it.catalogId == catalogId } ?: catalogs.first()
        return MessageChecks.schema(obj, catalog, null)
    }
}

/** Message-level validation: the envelope schema with the surface's catalog, then the semantic rules. */
internal object MessageChecks {
    private const val MAX_ERRORS = 20

    fun schema(msg: JsonObject, catalog: A2uiCatalog, surfaceId: String?): List<A2uiError> {
        val kind = A2uiMessage.kindOf(msg)
        return catalog.messageValidator.validate(msg, "agent_to_renderer.json").take(MAX_ERRORS).map { e ->
            val path = if (kind != null && e.path.startsWith("/$kind")) e.path.removePrefix("/$kind") else e.path
            A2uiError(A2uiErrorCode.VALIDATION_FAILED, e.message, path, surfaceId)
        }.distinct()
    }

    /** The protocol rules a schema cannot express, for a components list at `/components`. */
    fun components(components: JsonArray, catalog: A2uiCatalog, surfaceId: String): List<A2uiError> {
        val errors = ArrayList<A2uiError>()
        val firstAt = HashMap<String, Int>()
        components.forEachIndexed { i, c ->
            val o = c as? JsonObject ?: return@forEachIndexed
            val base = "/components/$i"
            val id = o["id"]?.stringOrNull() ?: return@forEachIndexed
            firstAt[id]?.let { j ->
                errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "duplicate component id '$id' (also at /components/$j); ids must be unique", "$base/id", surfaceId)
            } ?: run { firstAt[id] = i }
            o["catalogId"]?.stringOrNull()?.let { cid ->
                if (cid != catalog.catalogId) {
                    errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "component catalog '$cid' differs from the surface catalog '${catalog.catalogId}'; mixed catalogs are not supported", "$base/catalogId", surfaceId)
                }
            }
            for ((k, v) in o) {
                when (k) {
                    "id", "component", "catalogId", "metadata" -> Unit
                    "action" -> {
                        val a = v as? JsonObject ?: continue
                        a["functionCall"]?.let { walk(it, "$base/action/functionCall", catalog, surfaceId, userAction = true, errors) }
                        a["event"]?.let { walk(it, "$base/action/event", catalog, surfaceId, userAction = false, errors) }
                    }
                    "checks" -> (v as? JsonArray)?.forEachIndexed { r, rule ->
                        val cond = (rule as? JsonObject)?.get("condition") as? JsonObject ?: return@forEachIndexed
                        val name = cond["@call"]?.stringOrNull()
                        val rt = name?.let { catalog.function(it)?.returnType }
                        if (rt != null && rt !in setOf("boolean", "validationResult", "any")) {
                            errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "check condition '$name' returns $rt; a check needs a boolean or a validationResult", "$base/checks/$r/condition", surfaceId)
                        }
                        walk(cond, "$base/checks/$r/condition", catalog, surfaceId, userAction = false, errors)
                    }
                    else -> walk(v, JsonPointer.child(base, k), catalog, surfaceId, userAction = false, errors)
                }
                if (errors.size >= MAX_ERRORS) return errors
            }
        }
        return errors
    }

    private fun walk(v: JsonElement, path: String, catalog: A2uiCatalog, surfaceId: String, userAction: Boolean, errors: MutableList<A2uiError>, depth: Int = 0) {
        if (depth > 64 || errors.size >= MAX_ERRORS) return
        when (v) {
            is JsonArray -> v.forEachIndexed { i, e -> walk(e, "$path/$i", catalog, surfaceId, userAction, errors, depth + 1) }
            is JsonObject -> {
                for (k in v.keys) {
                    if (k.startsWith("@") && !k.startsWith("@@") && k != "@path" && k != "@call") {
                        errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "unknown directive '$k'; only @path and @call are reserved, write a literal key as '@$k'", JsonPointer.child(path, k), surfaceId)
                    }
                }
                v["@path"]?.stringOrNull()?.let { p ->
                    if (!JsonPointer.isValid(JsonPointer.resolve("", p))) {
                        errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "'$p' is not a valid JSON Pointer ('~' must be followed by 0 or 1)", "$path/@path", surfaceId)
                    }
                    return
                }
                if (v["componentId"]?.isJsonString() == true && v["path"]?.isJsonString() == true && v.size == 2) {
                    val p = v["path"]!!.stringOrNull()!!
                    if (!JsonPointer.isValid(JsonPointer.resolve("", p))) {
                        errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "template path '$p' is not a valid JSON Pointer", "$path/path", surfaceId)
                    }
                    return
                }
                val name = v["@call"]?.stringOrNull()
                if (name != null) call(name, v, path, catalog, surfaceId, userAction, errors)
                for ((k, e) in v) if (k != "@call") walk(e, JsonPointer.child(path, k), catalog, surfaceId, userAction && name == null, errors, depth + 1)
            }
            else -> Unit
        }
    }

    private fun call(name: String, v: JsonObject, path: String, catalog: A2uiCatalog, surfaceId: String, userAction: Boolean, errors: MutableList<A2uiError>) {
        if (name == "@index") return
        v["catalogId"]?.stringOrNull()?.let { cid ->
            if (cid != catalog.catalogId) {
                errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "function catalog '$cid' differs from the surface catalog '${catalog.catalogId}'; mixed catalogs are not supported", "$path/catalogId", surfaceId)
            }
        }
        val spec = catalog.function(name) ?: return
        if (spec.allowedCallers == "agentOnly") {
            errors += A2uiError(A2uiErrorCode.INVALID_FUNCTION_CALL, "function '$name' is agentOnly; it cannot be used in the UI", "$path/@call", surfaceId)
        }
        if (spec.requiresUserActivation && !userAction) {
            errors += A2uiError(A2uiErrorCode.EXPRESSION_ERROR, "function '$name' needs a user gesture; use it only as a Button action's functionCall", "$path/@call", surfaceId)
        }
        if (catalog.function(name) != null && name !in A2uiFunctions.basic) {
            errors += A2uiError(A2uiErrorCode.INVALID_FUNCTION_CALL, "function '$name' is not implemented by this renderer", "$path/@call", surfaceId)
        }
        val args = v["args"] as? JsonObject ?: return
        if (name == "formatString") (args["value"] as? JsonPrimitive)?.takeIf { it.isString }?.let { t ->
            try {
                A2uiTemplateParser().parse(t.content)
            } catch (e: A2uiExpressionException) {
                errors += A2uiError(A2uiErrorCode.EXPRESSION_ERROR, "formatString: ${e.message}", "$path/args/value", surfaceId)
            }
        }
        if (name == "regex") args["pattern"]?.stringOrNull()?.let { p ->
            try {
                A2uiFunctions.checkRegexPattern(p, A2uiLimits.standard)
            } catch (e: A2uiExpressionException) {
                errors += A2uiError(A2uiErrorCode.EXPRESSION_ERROR, e.message ?: "bad pattern", "$path/args/pattern", surfaceId)
            }
        }
    }
}
