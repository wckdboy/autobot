package dev.wckdboy.autobot.agent.core.tools

import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.ServiceKey
import dev.wckdboy.autobot.agent.core.llm.ToolSchema
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What a tool does, for permission policy and UI cards (DSH `ToolCallKind`, plus [GENERATE] for
 * on-device media generation).
 */
enum class ToolKind { READ, SEARCH, EDIT, DELETE, EXECUTE, FETCH, GENERATE, INTERACT, OTHER }

/** Result of [ToolDefinition.execute]. [content] is what the model sees. */
data class ToolOutput(
    val content: String,
    val isError: Boolean = false,
    val code: String? = null,
    val meta: JsonObject? = null,
    /** Ends the turn after this step instead of requesting another completion. */
    val concludesTurn: Boolean = false,
) {
    companion object {
        fun error(message: String, code: String = "TOOL_ERROR") = ToolOutput("Error: $message", isError = true, code = code)
    }
}

/** Per-call context handed to a tool. */
interface ToolRunContext {
    val callId: String
    val sessionId: String

    /** Queues runtime context for the next step (DSH `deferContext`). */
    fun deferContext(text: String)

    /** Records a session event (e.g. `todo/write`). */
    suspend fun log(event: dev.wckdboy.autobot.agent.core.session.SessionEvent)

    /** Asks the user a question through the UI and suspends until answered (or `null`). */
    suspend fun askUser(question: UserQuestion): List<String>?
}

data class UserQuestion(val question: String, val options: List<String>, val multiSelect: Boolean = false)

/**
 * A tool (DSH `ToolDefinition`). Arguments arrive as a validated JSON object; tools never see
 * raw strings. Execution happens inside the permission pipeline ([ToolExecutor]).
 */
interface ToolDefinition {
    val name: String
    val description: String
    val parameters: JsonObject
    val kind: ToolKind
    val timeoutMs: Long? get() = null

    /** `true` lets the call run in parallel with neighbours; otherwise it is an ordering barrier. */
    fun isConcurrencySafe(args: JsonObject): Boolean = false

    /** One-line summary for UI cards and approval prompts, e.g. `path=notes.md`. */
    fun describeCall(args: JsonObject): String = args.entries.joinToString("  ") { (k, v) ->
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()
        "$k=${text.replace('\n', ' ').take(60)}"
    }

    suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput

    fun schema(): ToolSchema = ToolSchema(name, description, parameters)
}

/** Allow/deny lists for a scope (DSH `ToolRestriction`). Deny wins. */
data class ToolRestriction(val allow: Set<String>? = null, val deny: Set<String> = emptySet()) {
    fun permits(name: String): Boolean = name !in deny && (allow == null || name in allow)
}

/** `ctx.tools`. */
class ToolRegistry {
    private val tools = CopyOnWriteArrayList<ToolDefinition>()
    private val _version = MutableStateFlow(0)

    /** Bumps whenever the tool set changes (DSH `tools/change`). */
    val version: StateFlow<Int> = _version

    fun register(tool: ToolDefinition): Disposable {
        tools.removeAll { it.name == tool.name }
        tools += tool
        _version.value++
        return Disposable {
            if (tools.remove(tool)) _version.value++
        }
    }

    fun get(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    fun list(restriction: ToolRestriction = ToolRestriction()): List<ToolDefinition> =
        tools.filter { restriction.permits(it.name) }.sortedBy { it.name }

    companion object {
        val Key = ServiceKey<ToolRegistry>("tools")
    }
}

// -------------------------------------------------------------------------------------------
// Schema DSL (DSH `defineTool()`): every property declares its type; objects close
// `additionalProperties` so the model cannot invent arguments.

class SchemaBuilder internal constructor() {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun prop(name: String, description: String, required: Boolean, body: JsonObject) {
        properties[name] = JsonObject(body + ("description" to JsonPrimitive(description)))
        if (required) this.required += name
    }

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        prop(
            name, description, required,
            buildJsonObject {
                put("type", "string")
                if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            },
        )

    fun integer(name: String, description: String, required: Boolean = false, minimum: Long? = null, maximum: Long? = null) =
        prop(
            name, description, required,
            buildJsonObject {
                put("type", "integer")
                minimum?.let { put("minimum", it) }
                maximum?.let { put("maximum", it) }
            },
        )

    fun number(name: String, description: String, required: Boolean = false) =
        prop(name, description, required, buildJsonObject { put("type", "number") })

    fun boolean(name: String, description: String, required: Boolean = false) =
        prop(name, description, required, buildJsonObject { put("type", "boolean") })

    fun stringArray(name: String, description: String, required: Boolean = false, minItems: Int? = null, maxItems: Int? = null) =
        prop(
            name, description, required,
            buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                minItems?.let { put("minItems", it) }
                maxItems?.let { put("maxItems", it) }
            },
        )

    fun objectArray(name: String, description: String, required: Boolean = false, items: SchemaBuilder.() -> Unit) =
        prop(
            name, description, required,
            buildJsonObject {
                put("type", "array")
                put("items", SchemaBuilder().apply(items).build())
            },
        )

    internal fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}

fun schema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()

/**
 * Validates [args] against a schema produced by [schema]. Returns a human-readable problem or
 * `null` when valid. Checks object shape, required keys, unknown keys, primitive types, enums,
 * integer bounds and array item types — enough to turn malformed calls into `INVALID_ARGS`.
 */
fun validateArgs(schema: JsonObject, args: JsonElement, path: String = "arguments"): String? {
    val type = (schema["type"] as? JsonPrimitive)?.content
    when (type) {
        "object" -> {
            if (args !is JsonObject) return "$path must be an object"
            val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.forEach { key ->
                val name = key.jsonPrimitive.content
                if (args[name] == null || args[name] is JsonNull) return "missing required '$name'"
            }
            if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false) {
                args.keys.firstOrNull { it !in props }?.let { return "unknown property '$it'" }
            }
            for ((key, value) in args) {
                if (value is JsonNull) continue
                val sub = props[key] as? JsonObject ?: continue
                validateArgs(sub, value, "$path.$key")?.let { return it }
            }
        }
        "array" -> {
            if (args !is JsonArray) return "$path must be an array"
            (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { if (args.size < it) return "$path needs at least $it items" }
            (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (args.size > it) return "$path allows at most $it items" }
            val items = schema["items"] as? JsonObject
            if (items != null) args.forEachIndexed { i, item -> validateArgs(items, item, "$path[$i]")?.let { return it } }
        }
        "string" -> {
            if (args !is JsonPrimitive || !args.isString) return "$path must be a string"
            (schema["enum"] as? JsonArray)?.let { options ->
                if (options.none { it.jsonPrimitive.content == args.content }) {
                    return "$path must be one of ${options.joinToString { it.jsonPrimitive.content }}"
                }
            }
        }
        "integer" -> {
            val value = (args as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return "$path must be an integer"
            (schema["minimum"] as? JsonPrimitive)?.longOrNull?.let { if (value < it) return "$path must be ≥ $it" }
            (schema["maximum"] as? JsonPrimitive)?.longOrNull?.let { if (value > it) return "$path must be ≤ $it" }
        }
        "number" -> if ((args as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull == null) return "$path must be a number"
        "boolean" -> if ((args as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == null) return "$path must be a boolean"
    }
    return null
}

// Typed argument accessors for tool bodies (arguments are already validated).
fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
