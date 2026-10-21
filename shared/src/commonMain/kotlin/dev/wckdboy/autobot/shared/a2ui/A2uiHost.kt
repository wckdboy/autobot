package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Told when surfaces change. Called on the thread that called into the [A2uiHost]. */
interface A2uiListener {
    /** [surfaceId] changed; [root] is its new tree (null until a `root` component arrives). */
    fun onSurfaceUpdated(surfaceId: String, root: RenderNode?)

    fun onSurfaceDeleted(surfaceId: String)

    /** Messages were rejected or expressions failed; [A2uiError.forModel] is ready to show the agent. */
    fun onErrors(errors: List<A2uiError>)
}

/** What firing a component's action asks the app to do. */
sealed class A2uiActionResult {
    /**
     * Send [messageJson] (a renderer-to-agent `action` message) to the agent that owns the
     * surface. [dataModelJson] is the `a2uiRendererDataModel` metadata to attach when the surface
     * asked for `sendDataModel`, null otherwise. [userMessage] is the resolved text to show in
     * the chat for the user's action, when the agent gave one. [snapshotJson] is the surface's
     * whole data model at the moment of the action (whatever `sendDataModel` says), without the
     * values of obscured (password) fields: what the app keeps with the chat so the user's input
     * comes back after a restart ([A2uiChat.actionMessage]).
     */
    class SendEvent internal constructor(
        val surfaceId: String,
        val name: String,
        val messageJson: String,
        val dataModelJson: String?,
        val userMessage: String?,
        val snapshotJson: String = "{}",
    ) : A2uiActionResult()

    /** Open [url] (always http or https) after the app's own link policy. */
    class OpenUrl internal constructor(val url: String) : A2uiActionResult()

    /** A local function ran; nothing else to do. */
    object Done : A2uiActionResult()

    /** Nothing happened: [reason] says why (a failing check, no action, an error). */
    class Refused internal constructor(val reason: String, val errors: List<A2uiError>) : A2uiActionResult()
}

/**
 * The renderer side of A2UI v1.0 for one chat: it receives agent messages, keeps each surface's
 * components and data model, renders them to [RenderNode] trees, takes input write-backs and
 * turns user actions into renderer-to-agent messages.
 *
 * Every message is validated (size limits, version, envelope schema with the surface's catalog,
 * the protocol's semantic rules) before it changes anything; a rejected message changes nothing
 * and comes back as errors. Not thread-safe: call it from one thread (the UI thread).
 *
 * ```
 * val host = A2uiHost()
 * host.addListener(listener)               // onSurfaceUpdated(surfaceId, root) -> recompose
 * val errors = host.receive(agentJsonl)    // createSurface, updateComponents, ...
 * host.setString(node, "value", "Ada")     // TextField typing
 * when (val r = host.dispatch(button)) {   // Button tap
 *     is A2uiActionResult.SendEvent -> send(r.messageJson)
 *     is A2uiActionResult.OpenUrl -> open(r.url)
 *     else -> Unit
 * }
 * ```
 */
class A2uiHost(
    catalogs: List<A2uiCatalog>,
    val limits: A2uiLimits,
    private val nowMillis: () -> Long,
) {
    constructor() : this(listOf(A2uiCatalog.basic), A2uiLimits.standard, ::currentTimeMillis)
    constructor(catalogs: List<A2uiCatalog>) : this(catalogs, A2uiLimits.standard, ::currentTimeMillis)
    constructor(catalogs: List<A2uiCatalog>, limits: A2uiLimits) : this(catalogs, limits, ::currentTimeMillis)

    private val catalogs: Map<String, A2uiCatalog> = catalogs.associateBy { it.catalogId }
    private val surfaces = LinkedHashMap<String, A2uiSurface>()
    private val listeners = ArrayList<A2uiListener>()
    private val reportedRenderErrors = HashMap<String, List<A2uiError>>()
    private val readOnly = HashSet<String>()

    val surfaceIds: List<String> get() = surfaces.keys.toList()

    fun surface(surfaceId: String): A2uiSurface? = surfaces[surfaceId]

    fun render(surfaceId: String): RenderNode? = surfaces[surfaceId]?.render()

    fun addListener(listener: A2uiListener) {
        listeners += listener
    }

    fun removeListener(listener: A2uiListener) {
        listeners -= listener
    }

    /**
     * True when [surfaceId] is read-only: its inputs refuse write-back and its agent events are
     * refused ([dispatch]); local functions (`openUrl`) still run. A chat sets it once the
     * surface's action was sent ([A2uiTranscript]); agent messages still apply.
     */
    fun isReadOnly(surfaceId: String): Boolean = surfaceId in readOnly

    /** Makes [surfaceId] read-only or editable again; listeners hear about a change. False if nothing changed. */
    fun setReadOnly(surfaceId: String, readOnly: Boolean): Boolean {
        if (surfaceId !in surfaces) return false
        val changed = if (readOnly) this.readOnly.add(surfaceId) else this.readOnly.remove(surfaceId)
        if (changed) publish(surfaceId)
        return changed
    }

    /** Re-renders [surfaceId] and tells listeners (its lock state changed around it). */
    internal fun refresh(surfaceId: String) {
        if (surfaceId in surfaces) publish(surfaceId)
    }

    /**
     * Replaces [surfaceId]'s whole data model with [model] (a restored snapshot), read-only or
     * not, and re-renders. False if the surface is gone or the model is too large.
     */
    internal fun restoreDataModel(surfaceId: String, model: JsonElement): Boolean {
        val surface = surfaces[surfaceId] ?: return false
        val before = surface.dataModel
        surface.dataModel = model
        if (checkModelSize(surface) != null) {
            surface.dataModel = before
            return false
        }
        publish(surfaceId)
        return true
    }

    /**
     * Applies agent output: a JSONL stream, a JSON array of messages or one message. Each message
     * is applied in order; rejected ones are skipped. Listeners hear about each changed surface
     * once, after the whole text. Returns every error (also passed to [A2uiListener.onErrors]).
     */
    fun receive(text: String): List<A2uiError> {
        val parsed = A2uiParser.parse(text, limits)
        val errors = ArrayList(parsed.errors)
        val changed = LinkedHashSet<String>()
        val deleted = LinkedHashSet<String>()
        for ((i, m) in parsed.messages) {
            errors += applyMessage(m, i) { id, gone ->
                if (gone) {
                    deleted += id; changed -= id
                } else {
                    changed += id; deleted -= id
                }
            }
        }
        deleted.forEach { id -> listeners.forEach { it.onSurfaceDeleted(id) } }
        changed.forEach { id -> errors += publish(id) }
        if (errors.isNotEmpty()) listeners.forEach { it.onErrors(errors) }
        return errors
    }

    /** Applies one parsed message; [changed] hears (surfaceId, deleted?) when state changed. */
    internal fun applyMessage(msg: JsonObject, index: Int?, changed: (String, Boolean) -> Unit = { _, _ -> }): List<A2uiError> {
        A2uiParser.checkMessage(msg, limits)?.let { return listOf(it.copy(messageIndex = index)) }
        val kind = A2uiMessage.kindOf(msg)
        val body = kind?.let { msg[it] as? JsonObject }
        val surfaceId = body?.get("surfaceId")?.stringOrNull()
        fun err(code: A2uiErrorCode, message: String, path: String = "") = listOf(A2uiError(code, message, path, surfaceId, index))
        if (kind == null || body == null) {
            val present = A2uiMessage.KINDS.filter { it in msg }
            return err(
                A2uiErrorCode.VALIDATION_FAILED,
                if (present.size > 1) "a message has exactly one of ${A2uiMessage.KINDS.joinToString(", ")}; this one has ${present.joinToString(", ")}"
                else "a message needs one of ${A2uiMessage.KINDS.joinToString(", ")} as an object",
            )
        }
        val existing = surfaceId?.let { surfaces[it] }
        val catalog: A2uiCatalog? = when (kind) {
            A2uiMessage.CREATE_SURFACE -> body["catalogId"]?.stringOrNull()?.let { id ->
                catalogs[id] ?: return err(A2uiErrorCode.VALIDATION_FAILED, "unknown catalog '$id'; this renderer supports: ${catalogs.keys.joinToString(", ")}", "/catalogId")
            }
            else -> existing?.catalog
        }
        val schemaCatalog = catalog ?: catalogs.values.first()
        MessageChecks.schema(msg, schemaCatalog, surfaceId).map { it.copy(messageIndex = index) }.ifEmpty { null }?.let { return it }

        when (val m = A2uiMessage.from(msg)) {
            is A2uiMessage.CreateSurface -> {
                if (m.surfaceId in surfaces) {
                    return err(A2uiErrorCode.INTEGRITY_ERROR, "surface '${m.surfaceId}' already exists; send deleteSurface first or use a new surfaceId", "/surfaceId")
                }
                if (surfaces.size >= limits.maxSurfaces) {
                    return err(A2uiErrorCode.VALIDATION_FAILED, "too many surfaces (limit ${limits.maxSurfaces}); delete one first")
                }
                if (catalog == null && m.components.isNotEmpty()) {
                    return err(A2uiErrorCode.VALIDATION_FAILED, "createSurface has components but no catalogId; set catalogId (e.g. ${A2uiCatalog.BASIC_CATALOG_ID})", "/catalogId")
                }
                val surface = A2uiSurface(m.surfaceId, m.catalogId, m.sendDataModel, catalog, limits)
                if (m.components.isNotEmpty()) {
                    val problems = MessageChecks.components(body["components"] as JsonArray, catalog!!, m.surfaceId)
                    if (problems.isNotEmpty()) return problems.map { it.copy(messageIndex = index) }
                    putComponents(surface, m.components, index)?.let { return listOf(it.copy(messageIndex = index)) }
                }
                m.dataModel?.let { surface.dataModel = it }
                checkModelSize(surface)?.let { return listOf(it.copy(messageIndex = index)) }
                surfaces[m.surfaceId] = surface
                changed(m.surfaceId, false)
            }
            is A2uiMessage.UpdateComponents -> {
                val surface = existing ?: return missingSurface(surfaceId, index)
                val cat = surface.catalog
                    ?: return err(A2uiErrorCode.VALIDATION_FAILED, "surface '${surface.surfaceId}' was created without a catalogId, so its components cannot be resolved; recreate it with a catalogId")
                val problems = MessageChecks.components(body["components"] as JsonArray, cat, surface.surfaceId)
                if (problems.isNotEmpty()) return problems.map { it.copy(messageIndex = index) }
                putComponents(surface, m.components, index)?.let { return listOf(it.copy(messageIndex = index)) }
                changed(surface.surfaceId, false)
            }
            is A2uiMessage.UpdateDataModel -> {
                val surface = existing ?: return missingSurface(surfaceId, index)
                val before = surface.dataModel
                try {
                    surface.dataModel = JsonPointer.set(before, m.path, m.value)
                } catch (e: A2uiPointerException) {
                    return err(A2uiErrorCode.VALIDATION_FAILED, e.message ?: "bad path", "/path")
                }
                checkModelSize(surface)?.let {
                    surface.dataModel = before
                    return listOf(it.copy(messageIndex = index))
                }
                changed(surface.surfaceId, false)
            }
            is A2uiMessage.DeleteSurface -> {
                if (surfaces.remove(m.surfaceId) == null) return missingSurface(surfaceId, index)
                reportedRenderErrors.remove(m.surfaceId)
                readOnly.remove(m.surfaceId)
                changed(m.surfaceId, true)
            }
            is A2uiMessage.Unsupported -> return listOf(
                A2uiError(
                    A2uiErrorCode.INVALID_FUNCTION_CALL,
                    if (m.kind == A2uiMessage.CALL_RENDERER_FUNCTION) "callRendererFunction is not supported by this renderer yet; render the result with updateComponents/updateDataModel instead"
                    else "agentFunctionResponse is not expected: this renderer does not call agent functions yet",
                    "",
                    m.surfaceId,
                    index,
                    m.functionCallId,
                ),
            )
            null -> return err(A2uiErrorCode.VALIDATION_FAILED, "message could not be read")
        }
        return emptyList()
    }

    private fun missingSurface(surfaceId: String?, index: Int?) = listOf(
        A2uiError(A2uiErrorCode.INTEGRITY_ERROR, "surface '$surfaceId' does not exist; send createSurface first", "/surfaceId", surfaceId, index),
    )

    private fun putComponents(surface: A2uiSurface, components: List<JsonObject>, index: Int?): A2uiError? {
        val newIds = components.mapNotNull { it["id"]?.stringOrNull() }.filter { it !in surface.components }.toSet()
        if (surface.components.size + newIds.size > limits.maxComponentsPerSurface) {
            return A2uiError(A2uiErrorCode.VALIDATION_FAILED, "surface would hold more than ${limits.maxComponentsPerSurface} components", "/components", surface.surfaceId)
        }
        components.forEachIndexed { i, c ->
            val id = c["id"]?.stringOrNull() ?: return@forEachIndexed
            surface.components[id] = StoredComponent(c, "/components/$i", index)
        }
        surface.touch()
        return null
    }

    private fun checkModelSize(surface: A2uiSurface): A2uiError? {
        val size = surface.dataModel.toJsonText().length
        return if (size > limits.maxDataModelChars) {
            A2uiError(A2uiErrorCode.VALIDATION_FAILED, "data model would be $size characters; the limit is ${limits.maxDataModelChars}", "/value", surface.surfaceId)
        } else null
    }

    /** Renders [surfaceId], tells listeners, and returns expression errors not reported before. */
    private fun publish(surfaceId: String): List<A2uiError> {
        val surface = surfaces[surfaceId] ?: return emptyList()
        val root = surface.render()
        listeners.forEach { it.onSurfaceUpdated(surfaceId, root) }
        val now = surface.renderErrors
        val before = reportedRenderErrors[surfaceId].orEmpty()
        reportedRenderErrors[surfaceId] = now
        return now.filter { e -> before.none { it.message == e.message } }
    }

    // ---- Input write-back ----

    /**
     * Writes [value] (JSON text; `null` deletes) at [pointer] in the surface's data model, as an
     * input does, and re-renders. Returns false if the surface or pointer is invalid.
     */
    fun setJson(surfaceId: String, pointer: String, value: String): Boolean {
        val e = try {
            Json.parseToJsonElement(value)
        } catch (_: Exception) {
            return false
        }
        return write(surfaceId, pointer, if (e == JsonNull) null else e)
    }

    fun setString(surfaceId: String, pointer: String, value: String): Boolean = write(surfaceId, pointer, JsonPrimitive(value))
    fun setNumber(surfaceId: String, pointer: String, value: Double): Boolean = write(surfaceId, pointer, jsonNumber(value))
    fun setBoolean(surfaceId: String, pointer: String, value: Boolean): Boolean = write(surfaceId, pointer, JsonPrimitive(value))
    fun setStrings(surfaceId: String, pointer: String, value: List<String>): Boolean = write(surfaceId, pointer, JsonArray(value.map { JsonPrimitive(it) }))

    /** Writes through [node]'s binding for [prop] (e.g. a TextField's `value`); false if it is not bound. */
    fun setString(node: RenderNode, prop: String, value: String): Boolean = node.binding(prop)?.let { setString(node.surfaceId, it, value) } ?: false
    fun setNumber(node: RenderNode, prop: String, value: Double): Boolean = node.binding(prop)?.let { setNumber(node.surfaceId, it, value) } ?: false
    fun setBoolean(node: RenderNode, prop: String, value: Boolean): Boolean = node.binding(prop)?.let { setBoolean(node.surfaceId, it, value) } ?: false
    fun setStrings(node: RenderNode, prop: String, value: List<String>): Boolean = node.binding(prop)?.let { setStrings(node.surfaceId, it, value) } ?: false

    private fun write(surfaceId: String, pointer: String, value: JsonElement?): Boolean {
        val surface = surfaces[surfaceId] ?: return false
        if (surfaceId in readOnly) return false
        val before = surface.dataModel
        try {
            surface.dataModel = JsonPointer.set(before, pointer, value)
        } catch (_: A2uiPointerException) {
            return false
        }
        if (checkModelSize(surface) != null) {
            surface.dataModel = before
            return false
        }
        val errors = publish(surfaceId)
        if (errors.isNotEmpty()) listeners.forEach { it.onErrors(errors) }
        return true
    }

    // ---- Actions ----

    /**
     * Fires [node]'s `action`, as a tap on it does. An agent event becomes a renderer-to-agent
     * `action` message with its context resolved now (in the node's template scope); a local
     * `functionCall` runs with user activation (only `openUrl` has an effect: it is returned for
     * the app to open, http and https only). A node whose checks fail does nothing.
     */
    fun dispatch(node: RenderNode): A2uiActionResult {
        val surface = surfaces[node.surfaceId] ?: return refused("surface '${node.surfaceId}' is gone")
        val component = surface.components[node.id] ?: return refused("component '${node.id}' is gone")
        val action = component.json["action"] as? JsonObject ?: return refused("component '${node.id}' has no action")
        val errors = ArrayList<A2uiError>()
        val eval = A2uiEvaluator(surface.surfaceId, surface.catalog, surface.dataModel, limits) { errors += it }
        eval.where = "component '${node.id}' action"
        val failing = (component.json["checks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { evaluateCheck(eval, it, node.scope) }.filter { !it.valid }
        if (failing.isNotEmpty()) return refused(failing.first().message ?: "a check fails")

        val event = action["event"] as? JsonObject
        if (event != null) {
            if (surface.surfaceId in readOnly) return refused("this was already sent")
            val name = event["name"]?.stringOrNull() ?: return refused("action has no name")
            val context = LinkedHashMap<String, JsonElement>()
            (event["context"] as? JsonObject)?.forEach { (k, v) ->
                eval.resolve(v, node.scope, activation = true)?.let { context[k] = it }
            }
            val userMessage = event["userMessage"]?.let { eval.resolve(it, node.scope, activation = true) }?.let { coerceToString(it) }
            val message = buildJsonObject {
                put("version", A2uiMessage.VERSION)
                put("action", buildJsonObject {
                    put("name", name)
                    put("surfaceId", surface.surfaceId)
                    put("sourceComponentId", node.id)
                    put("timestamp", A2uiFunctions.toIsoInstant(nowMillis()))
                    put("context", JsonObject(context))
                    if (userMessage != null) put("userMessage", userMessage)
                })
            }
            val dataModel = if (surface.sendDataModel) buildJsonObject {
                put("version", A2uiMessage.VERSION)
                put("surfaces", buildJsonObject { put(surface.surfaceId, surface.dataModel) })
            }.toJsonText() else null
            if (errors.isNotEmpty()) listeners.forEach { it.onErrors(errors) }
            return A2uiActionResult.SendEvent(surface.surfaceId, name, message.toJsonText(), dataModel, userMessage, snapshot(surface).toJsonText())
        }
        val call = action["functionCall"] as? JsonObject ?: return refused("action has neither event nor functionCall")
        eval.call(call, node.scope, activation = true, depth = 0)
        if (errors.isNotEmpty()) {
            listeners.forEach { it.onErrors(errors) }
            return A2uiActionResult.Refused(errors.first().message, errors)
        }
        return eval.openedUrls.lastOrNull()?.let { A2uiActionResult.OpenUrl(it) } ?: A2uiActionResult.Done
    }

    private fun refused(reason: String) = A2uiActionResult.Refused(reason, emptyList())

    /** [surface]'s data model without what its obscured TextFields are bound to. */
    private fun snapshot(surface: A2uiSurface): JsonElement {
        val secret = LinkedHashSet<String>()
        val seen = HashSet<String>()
        fun walk(node: RenderNode) {
            if (!seen.add(node.key)) return
            if (node.type == "TextField" && node.string("variant") == "obscured") node.binding("value")?.let { secret += it }
            node.children.forEach(::walk)
            node.props.values.forEach { walkValue(it, ::walk) }
        }
        surface.render()?.let(::walk)
        var model = surface.dataModel
        for (pointer in secret) {
            model = try {
                JsonPointer.set(model, pointer, null)
            } catch (_: A2uiPointerException) {
                model
            }
        }
        return model
    }

    private fun walkValue(v: RenderValue, visit: (RenderNode) -> Unit) {
        when (v) {
            is RenderValue.NodeValue -> visit(v.node)
            is RenderValue.NodesValue -> v.nodes.forEach(visit)
            is RenderValue.ListValue -> v.items.forEach { walkValue(it, visit) }
            is RenderValue.ObjectValue -> v.fields.values.forEach { walkValue(it, visit) }
            else -> Unit
        }
    }
}
