package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The validator's errors as a small model reads them best: the same error in several messages
 * said once, a missing child with the component to add, a component sent as a message with the
 * envelope to wrap it in, and at most [MAX_LINES] lines. The errors themselves (and
 * [A2uiReport.forModel]) are unchanged; this only words them for the retry.
 */
object A2uiFeedback {
    const val MAX_LINES = 10

    private val UNDEFINED = Regex("^component '(.+)' refers to '(.+)', which is not defined$")

    fun lines(report: A2uiReport, messages: JsonArray?): List<String> {
        val groups = LinkedHashMap<Triple<A2uiErrorCode, String, String>, MutableList<A2uiError>>()
        val out = ArrayList<String>()
        // Several missing children of one parent: one line naming them all.
        val missing = report.errors.mapNotNull { e -> UNDEFINED.find(e.message)?.let { e to it.groupValues } }
            .groupBy { (e, g) -> Triple(e.messageIndex, e.surfaceId, g[1]) }.filterValues { it.size > 1 }
        val merged = missing.values.flatten().map { it.first }.toSet()
        val said = HashSet<Triple<Int?, String?, String>>()
        for (e in report.errors) {
            if (e in merged) {
                val parent = UNDEFINED.find(e.message)!!.groupValues[1]
                val key = Triple(e.messageIndex, e.surfaceId, parent)
                if (!said.add(key)) continue
                val ids = missing[key]!!.map { it.second[2] }.distinct().joinToString(", ") { "'$it'" }
                out += e.copy(
                    path = e.path.substringBeforeLast('/'),
                    message = "component '$parent' refers to $ids, which are not defined",
                ).forModel() + ". Fix: add a component for each of these ids to the same \"components\" list (naming a child does not create it)"
                continue
            }
            groups.getOrPut(Triple(e.code, e.path, e.message)) { ArrayList() } += e
        }
        for ((_, errs) in groups) {
            val first = errs.first()
            val line = if (errs.size > 1) {
                val n = errs.mapNotNull { it.messageIndex }.distinct().size
                val scope = if (n > 1 && n == (messages?.size ?: -1)) "[every message]" else "[${errs.size} times]"
                first.copy(messageIndex = null, surfaceId = first.surfaceId.takeIf { errs.all { e -> e.surfaceId == it } }).forModel()
                    .replaceFirst(first.code.name, "${first.code.name} $scope")
            } else {
                first.forModel()
            }
            out += line + hint(first, messages).orEmpty()
        }
        if (out.size > MAX_LINES) {
            val more = out.size - MAX_LINES + 1
            return out.take(MAX_LINES - 1) + "… and $more more"
        }
        return out
    }

    private fun hint(e: A2uiError, messages: JsonArray?): String? {
        UNDEFINED.find(e.message)?.let { m ->
            val (parent, child) = m.destructured
            val parentType = componentType(messages, e.messageIndex, parent)
            val text = if (A2uiIntake.looksLikeLabel(child)) child else "…"
            return if (parentType == "Button") {
                ". Fix: add {\"id\":\"$child\",\"component\":\"Text\",\"text\":\"$text\"} to components (a Button's child is the id of a Text)"
            } else {
                ". Fix: add a component with id '$child' to the same \"components\" list (naming a child does not create it), or use an id that exists"
            }
        }
        if (e.message.startsWith("a message needs one of")) {
            val m = e.messageIndex?.let { messages?.getOrNull(it) } as? JsonObject
            if (m?.get("component")?.isJsonString() == true) {
                return ". This looks like a component, not a message: put components in {\"version\":\"v1.0\",\"createSurface\":{\"surfaceId\":\"…\",\"catalogId\":\"…\",\"components\":[…]}}"
            }
        }
        return null
    }

    private fun componentType(messages: JsonArray?, index: Int?, id: String): String? {
        val candidates = if (index != null) listOfNotNull(messages?.getOrNull(index)) else messages.orEmpty()
        for (m in candidates) {
            val o = m as? JsonObject ?: continue
            val kind = A2uiMessage.kindOf(o) ?: continue
            val comps = (o[kind] as? JsonObject)?.get("components") as? JsonArray ?: continue
            comps.firstOrNull { (it as? JsonObject)?.get("id")?.stringOrNull() == id }?.let {
                return (it as JsonObject)["component"]?.stringOrNull()
            }
        }
        return null
    }
}
