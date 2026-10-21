package dev.wckdboy.autobot.shared.a2ui.eval

import dev.wckdboy.autobot.shared.a2ui.A2uiCatalog
import dev.wckdboy.autobot.shared.a2ui.A2uiChat
import dev.wckdboy.autobot.shared.a2ui.A2uiHost
import dev.wckdboy.autobot.shared.a2ui.JsonPointer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The eval set: one user message per task and what a good `show_ui` answer must contain. */
@Serializable
data class A2uiEvalSet(val about: String = "", val tasks: List<A2uiEvalTask>) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): A2uiEvalSet = json.decodeFromString(serializer(), text)

        /** The set shipped with the tests (`a2ui/eval/tasks.json`). */
        fun bundled(): A2uiEvalSet =
            parse(checkNotNull(A2uiEvalSet::class.java.getResource("/a2ui/eval/tasks.json")) { "missing a2ui/eval/tasks.json" }.readText())
    }
}

@Serializable
data class A2uiEvalTask(val id: String, val category: String = "", val prompt: String, val expect: A2uiExpect = A2uiExpect())

/**
 * What the shown UI must have. Every set field is one check; see `tasks.json`'s `about`.
 * Component names may list alternatives as `A|B`, function names too.
 */
@Serializable
data class A2uiExpect(
    val noUi: Boolean = false,
    val components: List<String> = emptyList(),
    val minInputs: Int? = null,
    val inputsBound: Boolean = false,
    val actionUsesInputs: Boolean = false,
    val action: Boolean = false,
    val checks: Int? = null,
    val checkFunctions: List<String> = emptyList(),
    val template: Boolean = false,
    val props: List<A2uiPropExpect> = emptyList(),
)

@Serializable
data class A2uiPropExpect(val component: String, val prop: String, val equals: JsonElement? = null, val minItems: Int? = null)

/** One expectation's outcome. */
@Serializable
data class A2uiCheck(val name: String, val pass: Boolean, val detail: String = "")

/**
 * Checks a valid stream against [A2uiExpect], on the surfaces it produced: the stream is played
 * on a fresh [A2uiHost] (so `updateComponents` and `updateDataModel` count) and the stored
 * components and data model are inspected.
 */
object A2uiExpectations {
    val INPUTS = setOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput")

    private class Ui(val components: List<JsonObject>, val dataModels: List<JsonElement>, val sendsDataModel: Boolean)

    private fun play(stream: String): Ui {
        val host = A2uiHost(listOf(A2uiCatalog.basic), A2uiChat.limits)
        host.receive(stream)
        val surfaces = host.surfaceIds.mapNotNull { host.surface(it) }
        return Ui(
            components = surfaces.flatMap { s -> s.components.values.map { it.json } },
            dataModels = surfaces.map { it.dataModel },
            sendsDataModel = surfaces.any { it.sendDataModel },
        )
    }

    fun check(expect: A2uiExpect, stream: String): List<A2uiCheck> {
        val ui = play(stream)
        val byType = ui.components.groupBy { it.str("component") ?: "" }
        val types = byType.keys
        val out = ArrayList<A2uiCheck>()

        for (want in expect.components) {
            val options = want.split('|').map { it.trim() }
            out += A2uiCheck("has $want", options.any { it in types }, if (options.any { it in types }) "" else "types: ${types.sorted()}")
        }
        val inputs = ui.components.filter { it.str("component") in INPUTS }
        expect.minInputs?.let { n -> out += A2uiCheck("inputs >= $n", inputs.size >= n, "${inputs.size} inputs") }

        val bound = inputs.mapNotNull { c -> (c["value"] as? JsonObject)?.str("@path")?.let { c.str("id") to it } }
        if (expect.inputsBound) {
            val unbound = inputs.filter { c -> (c["value"] as? JsonObject)?.str("@path") == null }.map { it.str("id") }
            out += A2uiCheck("inputs bound", inputs.isNotEmpty() && unbound.isEmpty(), if (inputs.isEmpty()) "no inputs" else if (unbound.isEmpty()) "" else "unbound: $unbound")
        }

        val buttons = byType["Button"].orEmpty()
        if (expect.action) {
            val ok = buttons.any { b -> (b["action"] as? JsonObject)?.let { a -> (a["event"] as? JsonObject)?.str("name") != null || a["functionCall"] != null } == true }
            out += A2uiCheck("button action", ok, if (ok) "" else "${buttons.size} buttons, none with an action")
        }

        if (expect.actionUsesInputs) {
            val absolute = bound.filter { it.second.startsWith("/") }
            val sent = buttons.flatMap { b -> referencedPaths((b["action"] as? JsonObject)?.get("event")?.let { (it as? JsonObject)?.get("context") }) }
            val missing = if (ui.sendsDataModel) emptyList() else absolute.filter { (_, p) -> sent.none { q -> covers(q, p) } }.map { it.first }
            val ok = absolute.isNotEmpty() && buttons.isNotEmpty() && missing.isEmpty()
            val detail = when {
                absolute.isEmpty() -> "no bound inputs"
                buttons.isEmpty() -> "no button"
                missing.isNotEmpty() -> "not in any action context: $missing (context paths: ${sent.distinct()})"
                else -> ""
            }
            out += A2uiCheck("action carries inputs", ok, detail)
        }

        val withChecks = ui.components.filter { (it["checks"] as? JsonArray)?.isNotEmpty() == true }
        expect.checks?.let { n -> out += A2uiCheck("checks >= $n", withChecks.size >= n, "${withChecks.size} components with checks") }
        if (expect.checkFunctions.isNotEmpty()) {
            val used = withChecks.flatMap { calls(it["checks"]) }.toSet()
            for (f in expect.checkFunctions) {
                val options = f.split('|')
                out += A2uiCheck("check uses $f", options.any { it in used }, "used: ${used.sorted()}")
            }
        }

        if (expect.template) {
            val templates = ui.components.flatMap { templatesOf(it) }
            val good = templates.filter { path -> ui.dataModels.any { m -> (JsonPointer.get(m, path) as? JsonArray)?.let { it.size >= 2 } == true } }
            out += A2uiCheck(
                "template over data", good.isNotEmpty(),
                if (templates.isEmpty()) "no {componentId,path} template" else if (good.isEmpty()) "template paths $templates hold no array of 2+ items" else "",
            )
        }

        for (p in expect.props) {
            val candidates = byType[p.component].orEmpty()
            val ok = candidates.any { c ->
                val v = c[p.prop]
                (p.equals == null || same(v, p.equals)) && (p.minItems == null || ((v as? JsonArray)?.size ?: 0) >= p.minItems)
            }
            val want = p.equals?.let { "= $it" } ?: "has ${p.minItems}+ items"
            out += A2uiCheck("${p.component}.${p.prop} $want", ok, if (ok) "" else "seen: ${candidates.map { it[p.prop] }}")
        }
        return out
    }

    /** True when a context path [q] carries the input at [p] (the same path or a parent of it). */
    internal fun covers(q: String, p: String): Boolean = q == p || q == "/" || q == "" || p.startsWith(q.trimEnd('/') + "/")

    /** Absolute paths an action context reads: `@path`s and `${/path}` in formatString values. */
    internal fun referencedPaths(e: JsonElement?): List<String> = when (e) {
        is JsonObject -> (e.str("@path")?.let { listOf(it) } ?: emptyList()) + e.values.flatMap { referencedPaths(it) }
        is JsonArray -> e.flatMap { referencedPaths(it) }
        is JsonPrimitive -> if (e.isString) Regex("\\$\\{(/[^}]*)}").findAll(e.content).map { it.groupValues[1].trim() }.toList() else emptyList()
        else -> emptyList()
    }

    private fun calls(e: JsonElement?): List<String> = when (e) {
        is JsonObject -> listOfNotNull(e.str("@call")) + e.values.flatMap { calls(it) }
        is JsonArray -> e.flatMap { calls(it) }
        else -> emptyList()
    }

    /** The data paths of `{componentId, path}` templates anywhere in [c]. */
    private fun templatesOf(c: JsonElement?): List<String> = when (c) {
        is JsonObject -> if (c.str("componentId") != null && c.str("path") != null) listOf(c.str("path")!!) else c.values.flatMap { templatesOf(it) }
        is JsonArray -> c.flatMap { templatesOf(it) }
        else -> emptyList()
    }

    private fun same(a: JsonElement?, b: JsonElement): Boolean {
        if (a is JsonPrimitive && b is JsonPrimitive) {
            if (!a.isString && !b.isString) {
                a.doubleOrNull?.let { x -> b.doubleOrNull?.let { y -> return x == y } }
                a.booleanOrNull?.let { x -> b.booleanOrNull?.let { y -> return x == y } }
            }
            return a.isString == b.isString && a.content == b.content
        }
        return a == b
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
