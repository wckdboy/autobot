package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A component as the surface holds it, with where it was defined (for error paths). */
internal class StoredComponent(val json: JsonObject, val origin: String, val messageIndex: Int?) {
    val id: String get() = json["id"]?.stringOrNull() ?: ""
    val type: String get() = json["component"]?.stringOrNull() ?: ""
}

/** A child reference found in a component through its catalog slots. */
internal class ChildRef(val childId: String, val path: String, val template: String?)

/** The catalog slots of a component type as a tree over property names (`*` = any array item). */
internal class SlotTrie(var single: Boolean = false, var list: Boolean = false) {
    val next = HashMap<String, SlotTrie>()

    operator fun get(key: String): SlotTrie? = next[key] ?: next["*"]?.takeIf { key.all { it.isDigit() } }

    companion object {
        fun of(spec: ComponentSpec?): SlotTrie {
            val root = SlotTrie()
            spec?.slots?.forEach { slot ->
                var t = root
                for (p in slot.path) t = t.next.getOrPut(p) { SlotTrie() }
                if (slot.isList) t.list = true else t.single = true
            }
            return root
        }
    }
}

/**
 * One A2UI surface: its components (an adjacency list keyed by id), its data model and how it
 * renders. Read it through [render]; change it through [A2uiHost].
 */
class A2uiSurface internal constructor(
    val surfaceId: String,
    /** The surface's catalog, fixed at creation. */
    val catalogId: String?,
    /** When true, actions carry the whole data model back to the agent. */
    val sendDataModel: Boolean,
    internal val catalog: A2uiCatalog?,
    private val limits: A2uiLimits,
) {
    internal val components = LinkedHashMap<String, StoredComponent>()
    internal var dataModel: JsonElement = JsonObject(emptyMap())
        set(value) {
            field = value
            dirty = true
        }

    private var dirty = true
    private var cached: RenderNode? = null
    private var lastRenderErrors: List<A2uiError> = emptyList()

    internal fun touch() {
        dirty = true
    }

    val componentIds: List<String> get() = components.keys.toList()
    val componentCount: Int get() = components.size

    /** The data model as JSON text. */
    fun dataModelJson(): String = dataModel.toJsonText()

    /** The JSON text of the value at [pointer], or null when there is none. */
    fun readJson(pointer: String): String? = try {
        JsonPointer.get(dataModel, pointer)?.toJsonText()
    } catch (_: A2uiPointerException) {
        null
    }

    /** The component definition with [id] as JSON text (for debugging and tests). */
    fun componentJson(id: String): String? = components[id]?.json?.toJsonText()

    /** The resolved tree from `root`, or null until a `root` component has arrived. */
    fun render(): RenderNode? {
        if (dirty) {
            val errors = ArrayList<A2uiError>()
            cached = SurfaceRenderer(this, limits) { e -> if (errors.size < 50 && errors.none { it.message == e.message }) errors += e }.build()
            lastRenderErrors = errors
            dirty = false
        }
        return cached
    }

    /** Problems found evaluating expressions in the last [render] (EXPRESSION_ERROR and similar). */
    val renderErrors: List<A2uiError> get() = render().let { lastRenderErrors }

    /**
     * Structural problems of the component tree as it stands: no `root`, references to
     * components that do not exist, cycles, `allowedParents`/`allowedChildren` violations and
     * `@index` outside a list template. During streaming some of these are expected (later
     * messages fill the gaps); after the agent's last message they are real errors.
     */
    fun integrityErrors(): List<A2uiError> = IntegrityChecker(this).check()

    /** URLs of media the tree would load (Image, Video, AudioPlayer, Icon svgPath excluded), for policy checks. */
    fun mediaUrls(): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: RenderNode) {
            if (n.type == "Image" || n.type == "Video" || n.type == "AudioPlayer") {
                n.string("url")?.takeIf { it.isNotBlank() }?.let { out += it }
                n.string("posterUrl")?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            n.children.forEach { walk(it) }
        }
        render()?.let { walk(it) }
        return out.toList()
    }

    internal fun childRefs(c: StoredComponent): List<ChildRef> {
        val out = ArrayList<ChildRef>()
        fun walk(v: JsonElement, t: SlotTrie, path: String) {
            if (t.single && v.isJsonString()) out += ChildRef(v.stringOrNull()!!, path, null)
            if (t.list) {
                if (v is JsonArray) v.forEachIndexed { i, e -> e.stringOrNull()?.let { out += ChildRef(it, "$path/$i", null) } }
                if (v is JsonObject) v["componentId"]?.stringOrNull()?.let { out += ChildRef(it, "$path/componentId", v["path"]?.stringOrNull() ?: "") }
            }
            when (v) {
                is JsonObject -> for ((k, e) in v) t[k]?.let { walk(e, it, JsonPointer.child(path, k)) }
                is JsonArray -> v.forEachIndexed { i, e -> t[i.toString()]?.let { walk(e, it, "$path/$i") } }
                else -> Unit
            }
        }
        val trie = SlotTrie.of(catalog?.component(c.type))
        for ((k, e) in c.json) if (k != "id" && k != "component") trie.next[k]?.let { walk(e, it, JsonPointer.child(c.origin, k)) }
        return out
    }
}

/** Builds the [RenderNode] tree of a surface from `root`, evaluating every binding once. */
private class SurfaceRenderer(
    private val surface: A2uiSurface,
    private val limits: A2uiLimits,
    private val onError: (A2uiError) -> Unit,
) {
    private val eval = A2uiEvaluator(surface.surfaceId, surface.catalog, surface.dataModel, limits, onError)
    private val keys = HashMap<String, Int>()
    private var count = 0
    private var overflowReported = false

    fun build(): RenderNode? {
        if ("root" !in surface.components) return null
        return node("root", EvalScope.ROOT, emptySet(), 0)
    }

    private fun key(id: String, scope: EvalScope): String {
        val base = if (scope.base.isEmpty()) id else "$id@${scope.base}"
        val n = keys[base] ?: 0
        keys[base] = n + 1
        return if (n == 0) base else "$base#$n"
    }

    private fun placeholder(id: String, scope: EvalScope) =
        RenderNode(surface.surfaceId, key(id, scope), id, "", emptyMap(), emptyList(), emptyMap(), emptyList(), null, scope.base, true, scope)

    private fun node(id: String, scope: EvalScope, ancestors: Set<String>, depth: Int): RenderNode {
        val stored = surface.components[id] ?: return placeholder(id, scope)
        if (id in ancestors) {
            onError(A2uiError(A2uiErrorCode.INTEGRITY_ERROR, "component '$id' contains itself", stored.origin, surface.surfaceId))
            return placeholder(id, scope)
        }
        if (++count > limits.maxRenderNodes || depth > limits.maxRenderDepth) {
            if (!overflowReported) {
                overflowReported = true
                onError(A2uiError(A2uiErrorCode.VALIDATION_FAILED, "the surface expands to more than ${limits.maxRenderNodes} components or ${limits.maxRenderDepth} levels; the rest is not shown", "", surface.surfaceId))
            }
            return placeholder(id, scope)
        }
        val type = stored.type
        val trie = SlotTrie.of(surface.catalog?.component(type))
        val props = LinkedHashMap<String, RenderValue>()
        val bindings = LinkedHashMap<String, String>()
        val children = ArrayList<RenderNode>()
        var checks: List<CheckResult> = emptyList()
        var action: RenderAction? = null
        val inner = ancestors + id
        for ((k, v) in stored.json) {
            when (k) {
                "id", "component", "catalogId" -> Unit
                "checks" -> {
                    eval.where = "component '$id' checks"
                    checks = checks(v, scope)
                }
                "action" -> action = (v as? JsonObject)?.let { a ->
                    RenderAction(
                        (a["event"] as? JsonObject)?.get("name")?.stringOrNull(),
                        ((a["functionCall"] as? JsonObject)?.get("@call"))?.stringOrNull(),
                    )
                }
                else -> {
                    eval.where = "component '$id' property '$k'"
                    if (v is JsonObject && v["@path"]?.isJsonString() == true) {
                        bindings[k] = JsonPointer.resolve(scope.base, v["@path"]!!.stringOrNull()!!)
                    }
                    props[k] = value(v, trie.next[k], scope, inner, depth, children)
                }
            }
        }
        return RenderNode(surface.surfaceId, key(id, scope), id, type, props, children, bindings, checks, action, scope.base, false, scope)
    }

    private fun value(v: JsonElement, t: SlotTrie?, scope: EvalScope, ancestors: Set<String>, depth: Int, children: MutableList<RenderNode>): RenderValue {
        if (t != null) {
            if (t.single && v.isJsonString()) {
                val child = node(v.stringOrNull()!!, scope, ancestors, depth + 1)
                children += child
                return RenderValue.NodeValue(child)
            }
            if (t.list && v is JsonArray) {
                val nodes = v.mapNotNull { it.stringOrNull() }.map { node(it, scope, ancestors, depth + 1) }
                children += nodes
                return RenderValue.NodesValue(nodes)
            }
            if (t.list && v is JsonObject && v["componentId"]?.isJsonString() == true) {
                val listPointer = JsonPointer.resolve(scope.base, v["path"]?.stringOrNull() ?: "")
                val items = try {
                    JsonPointer.get(surface.dataModel, listPointer)
                } catch (_: A2uiPointerException) {
                    null
                }
                val templateId = v["componentId"]!!.stringOrNull()!!
                val nodes = (items as? JsonArray)?.indices?.map { i -> node(templateId, scope.item(listPointer, i), ancestors, depth + 1) }.orEmpty()
                children += nodes
                return RenderValue.NodesValue(nodes)
            }
            if (t.next.isNotEmpty() && v is JsonObject && "@path" !in v && "@call" !in v) {
                return RenderValue.ObjectValue(v.entries.associate { (k, e) -> A2uiEvaluator.unescapeKey(k) to value(e, t[k], scope, ancestors, depth, children) })
            }
            if (t.next.isNotEmpty() && v is JsonArray) {
                return RenderValue.ListValue(v.mapIndexed { i, e -> value(e, t[i.toString()], scope, ancestors, depth, children) })
            }
        }
        return RenderValue.of(eval.resolve(v, scope, activation = false))
    }

    private fun checks(v: JsonElement, scope: EvalScope): List<CheckResult> =
        (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { rule -> evaluateCheck(eval, rule, scope) }
}

/** Applies the CheckRule coercions: a boolean or a ValidationResult, with the rule's message as fallback. */
internal fun evaluateCheck(eval: A2uiEvaluator, rule: JsonObject, scope: EvalScope): CheckResult {
    val condition = rule["condition"]
    val fallback = rule["message"]?.stringOrNull()
    val r = eval.resolve(condition, scope, activation = false)
    val default = (condition as? JsonObject)?.get("@call")?.stringOrNull()?.let { defaultCheckMessage(it) } ?: "Invalid value."
    return when {
        r is JsonObject && r["valid"]?.isJsonBoolean() == true -> {
            val valid = r["valid"]!!.booleanOrNull()!!
            CheckResult(
                valid,
                if (valid) null else r["message"]?.stringOrNull() ?: fallback ?: default,
                r["severity"]?.stringOrNull() ?: "error",
                r["code"]?.stringOrNull(),
            )
        }
        r == null || r == JsonNull -> CheckResult(false, fallback ?: default, "error", null)
        else -> {
            val valid = truthyOrValid(r)
            CheckResult(valid, if (valid) null else fallback ?: default, "error", null)
        }
    }
}

private fun defaultCheckMessage(function: String): String = when (function) {
    "required" -> "This field is required."
    "email" -> "Must be a valid email address."
    "regex" -> "Value does not match the required pattern."
    "length" -> "Value has the wrong length."
    "numeric" -> "Value is out of range."
    else -> "Invalid value."
}

/** The static structure checks behind [A2uiSurface.integrityErrors]. */
private class IntegrityChecker(private val surface: A2uiSurface) {
    private val errors = ArrayList<A2uiError>()
    private val visited = HashSet<String>()

    fun check(): List<A2uiError> {
        val root = surface.components["root"]
        if (root == null) {
            errors += A2uiError(A2uiErrorCode.INTEGRITY_ERROR, "no component has id 'root'; the surface shows nothing until one is sent", "", surface.surfaceId)
            return errors
        }
        checkPlacement("Surface", null, root, "")
        visit(root, inTemplate = false, stack = listOf("root"))
        return errors
    }

    private fun visit(c: StoredComponent, inTemplate: Boolean, stack: List<String>) {
        if (!visited.add("${c.id}|$inTemplate")) return
        if (!inTemplate && usesIndex(c.json)) {
            errors += err(A2uiErrorCode.EXPRESSION_ERROR, "component '${c.id}' uses @index but is not inside a list template", c.origin, c)
        }
        for (ref in surface.childRefs(c)) {
            val child = surface.components[ref.childId]
            if (child == null) {
                errors += err(A2uiErrorCode.INTEGRITY_ERROR, "component '${c.id}' refers to '${ref.childId}', which is not defined", ref.path, c)
                continue
            }
            if (ref.childId in stack) {
                errors += err(A2uiErrorCode.INTEGRITY_ERROR, "cycle: ${(stack.dropWhile { it != ref.childId } + ref.childId).joinToString(" -> ")}", ref.path, c)
                continue
            }
            ref.template?.let { p ->
                if (!JsonPointer.isValid(JsonPointer.resolve("", p))) {
                    errors += err(A2uiErrorCode.VALIDATION_FAILED, "template path '$p' is not a valid JSON Pointer", ref.path.substringBeforeLast('/') + "/path", c)
                }
            }
            checkPlacement(c.type, c, child, ref.path)
            if (stack.size < 200) visit(child, inTemplate || ref.template != null, stack + ref.childId)
        }
    }

    private fun checkPlacement(parentType: String, parent: StoredComponent?, child: StoredComponent, refPath: String) {
        val catalog = surface.catalog ?: return
        catalog.component(child.type)?.allowedParents?.let { allowed ->
            if (parentType !in allowed) {
                val where = if (parent == null) "at the top level (id 'root')" else "inside '${parent.id}' ($parentType)"
                errors += err(A2uiErrorCode.UNALLOWED_PARENT, "'${child.id}' (${child.type}) cannot be placed $where; allowed parents: ${allowed.joinToString(", ")}", child.origin + "/component", child)
            }
        }
        if (parent != null) catalog.component(parent.type)?.allowedChildren?.let { allowed ->
            if (child.type !in allowed) {
                errors += err(A2uiErrorCode.UNALLOWED_CHILD, "'${parent.id}' ($parentType) cannot contain '${child.id}' (${child.type}); allowed children: ${allowed.joinToString(", ")}", refPath, parent)
            }
        }
    }

    private fun err(code: A2uiErrorCode, message: String, path: String, c: StoredComponent) =
        A2uiError(code, message, path, surface.surfaceId, c.messageIndex)

    private fun usesIndex(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e["@call"]?.stringOrNull() == "@index" || e.values.any { usesIndex(it) }
        is JsonArray -> e.any { usesIndex(it) }
        is JsonPrimitive -> e.isString && e.content.contains("@index(")
    }
}
