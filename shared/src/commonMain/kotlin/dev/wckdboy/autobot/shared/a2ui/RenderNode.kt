package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A resolved property value: bindings and function calls are already evaluated, child component
 * references are already [NodeValue]/[NodesValue]. A renderer reads these and never sees JSON.
 */
sealed class RenderValue {
    data class Text(val value: String) : RenderValue()
    data class Number(val value: Double) : RenderValue()
    data class Bool(val value: Boolean) : RenderValue()
    data class ListValue(val items: List<RenderValue>) : RenderValue()
    data class ObjectValue(val fields: Map<String, RenderValue>) : RenderValue()

    /** A child component (a `ComponentId`/`Child` property). */
    class NodeValue(val node: RenderNode) : RenderValue()

    /** Child components (a `ChildList` property, static or expanded from a template). */
    class NodesValue(val nodes: List<RenderNode>) : RenderValue()

    /** Null or undefined (missing data, a failed expression). */
    object Null : RenderValue()

    /** The value as display text (A2UI's string coercion: numbers plainly, null as ""). */
    fun asText(): String = when (this) {
        is Text -> value
        is Number -> formatPlainNumber(value)
        is Bool -> value.toString()
        is Null -> ""
        is ListValue -> items.joinToString(", ") { it.asText() }
        is ObjectValue -> fields.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value.asText()}" }
        is NodeValue -> node.id
        is NodesValue -> nodes.joinToString(", ") { it.id }
    }

    companion object {
        internal fun of(e: JsonElement?): RenderValue = when {
            e == null || e == JsonNull -> Null
            e.isJsonString() -> Text(e.stringOrNull()!!)
            e.isJsonBoolean() -> Bool(e.booleanOrNull()!!)
            e.isJsonNumber() -> Number(e.numberOrNull() ?: 0.0)
            e is JsonArray -> ListValue(e.map { of(it) })
            e is JsonObject -> ObjectValue(e.mapValues { of(it.value) })
            else -> Null
        }
    }
}

/** One evaluated `checks` rule of a component: failed rules carry the message to show. */
data class CheckResult(
    val valid: Boolean,
    val message: String?,
    /** `error` (the default), `warning` or `info`. */
    val severity: String,
    val code: String?,
)

/** What a component's `action` does, for the renderer's affordances (the action itself runs in the host). */
class RenderAction internal constructor(
    /** The event name sent to the agent, or null for a local function action. */
    val eventName: String?,
    /** The local function called, e.g. `openUrl`, or null for an agent event. */
    val functionName: String?,
)

/**
 * One component of a rendered surface, ready for Compose or SwiftUI: its [type] from the catalog
 * (`Text`, `Column`, `Button`...), its resolved [props], and its [children] in order.
 *
 * - [key] is unique in the tree (a template instantiates one component per list item), for list
 *   diffing and for [A2uiHost.dispatch].
 * - [bindings] maps a property to the absolute data-model pointer it is bound to (`value` ->
 *   `/form/email`), so an input can write back with [A2uiHost.setString] and friends.
 * - [checks] are the evaluated `checks` rules; a Button whose checks fail is disabled.
 * - [isPlaceholder] marks a referenced component that has not arrived (yet): show a placeholder.
 *
 * Media URLs (Image/Video/AudioPlayer `url`, `posterUrl`) are plain data here: the core never
 * fetches anything. The renderer applies its network policy before loading them.
 */
class RenderNode internal constructor(
    /** The surface this node belongs to. */
    val surfaceId: String,
    val key: String,
    val id: String,
    val type: String,
    val props: Map<String, RenderValue>,
    val children: List<RenderNode>,
    val bindings: Map<String, String>,
    val checks: List<CheckResult>,
    val action: RenderAction?,
    /** The data-model pointer relative paths resolve against (`""` outside templates). */
    val scopePath: String,
    val isPlaceholder: Boolean,
    internal val scope: EvalScope,
) {
    fun value(name: String): RenderValue = props[name] ?: RenderValue.Null

    fun string(name: String): String? = when (val v = props[name]) {
        null, RenderValue.Null -> null
        is RenderValue.Text -> v.value
        else -> v.asText()
    }

    fun stringOr(name: String, fallback: String): String = string(name) ?: fallback

    fun number(name: String): Double? = when (val v = props[name]) {
        is RenderValue.Number -> v.value
        is RenderValue.Text -> v.value.trim().toDoubleOrNull()
        else -> null
    }

    fun numberOr(name: String, fallback: Double): Double = number(name) ?: fallback

    fun bool(name: String): Boolean? = when (val v = props[name]) {
        is RenderValue.Bool -> v.value
        null, RenderValue.Null -> null
        else -> truthyOrValid(v.toJson())
    }

    fun boolOr(name: String, fallback: Boolean): Boolean = bool(name) ?: fallback

    fun strings(name: String): List<String> = when (val v = props[name]) {
        is RenderValue.ListValue -> v.items.map { it.asText() }
        is RenderValue.Text -> listOf(v.value)
        else -> emptyList()
    }

    /** The single child in property [name] (Card `child`, Modal `trigger`), if it is there. */
    fun node(name: String): RenderNode? = (props[name] as? RenderValue.NodeValue)?.node

    /** The children in property [name] (Row/Column/List `children`). */
    fun nodes(name: String): List<RenderNode> = (props[name] as? RenderValue.NodesValue)?.nodes.orEmpty()

    /** True when every check passes. */
    val isValid: Boolean get() = checks.all { it.valid }

    /** The message of the first failing check, to show under an input. */
    val firstError: String? get() = checks.firstOrNull { !it.valid }?.message

    /** The pointer property [name] writes to, if it is bound to the data model. */
    fun binding(name: String): String? = bindings[name]

    override fun toString(): String = "RenderNode($key $type)"
}

internal fun RenderValue.toJson(): JsonElement = when (this) {
    is RenderValue.Text -> kotlinx.serialization.json.JsonPrimitive(value)
    is RenderValue.Number -> jsonNumber(value)
    is RenderValue.Bool -> kotlinx.serialization.json.JsonPrimitive(value)
    is RenderValue.ListValue -> JsonArray(items.map { it.toJson() })
    is RenderValue.ObjectValue -> JsonObject(fields.mapValues { it.value.toJson() })
    is RenderValue.NodeValue -> kotlinx.serialization.json.JsonPrimitive(node.id)
    is RenderValue.NodesValue -> JsonArray(nodes.map { kotlinx.serialization.json.JsonPrimitive(it.id) })
    RenderValue.Null -> JsonNull
}
