package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.session.SessionItem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generative UI in a chat, the same on Android and iOS: what the `show_ui` tool stores with the
 * chat, how a stored chat is replayed, and what a user action sends back to the agent.
 *
 * Persistence: a successful `show_ui` call's A2UI messages are kept in its `tool/result`'s
 * `meta.a2ui` (the session log is the chat's record, encrypted at rest like the rest of it).
 * [SessionItem.Tool.a2ui] carries them to the transcript, and [A2uiTranscript] replays them,
 * in order, into one [A2uiHost] per open chat.
 */
object A2uiChat {
    /** The tool's name. */
    const val TOOL = "show_ui"

    /** The `tool/result` meta key holding the shown messages. */
    const val META = "a2ui"

    /**
     * Limits for a chat's host: the standard ones, with room for every surface a long chat
     * created (a turn's surfaces stay in the transcript).
     */
    val limits: A2uiLimits = A2uiLimits.standard.copy(maxSurfaces = 256)

    /** The messages stored in a `show_ui` result's [meta], if any. */
    fun stream(meta: JsonObject?): String? = (meta?.get(META) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The meta a `show_ui` result stores for [text]. */
    fun meta(text: String): JsonObject = buildJsonObject { put(META, text) }

    /** Every stream the chat's `show_ui` calls showed, oldest first (what a new stream applies after). */
    fun streams(entries: List<LogEntry>): List<String> = entries.mapNotNull { e ->
        (e.event as? SessionEvent.ToolResult)?.takeIf { it.name == TOOL && !it.isError }?.let { stream(it.meta) }
    }

    /**
     * The user turn an action becomes: the model reads the renderer-to-agent `action` message
     * (and the data model when the surface asked for it); the transcript shows [A2uiActionResult.SendEvent.userMessage],
     * or the action's name in words.
     */
    fun actionMessage(event: A2uiActionResult.SendEvent): SessionEvent.UserMessage =
        SessionEvent.UserMessage(text = actionPrompt(event), display = actionLabel(event))

    /** What the model reads for [event]. */
    fun actionPrompt(event: A2uiActionResult.SendEvent): String = buildString {
        append("[UI action] The user used the UI you showed (surface '").append(event.surfaceId).append("').\n")
        append("<a2ui_action>\n").append(event.messageJson).append("\n</a2ui_action>")
        event.dataModelJson?.let { append("\n<a2ui_data_model>\n").append(it).append("\n</a2ui_data_model>") }
    }

    /** What the transcript shows for [event]: the agent's userMessage, else `Submit contact form`. */
    fun actionLabel(event: A2uiActionResult.SendEvent): String =
        event.userMessage?.takeIf { it.isNotBlank() } ?: humanize(event.name)

    /** `submitContactForm` / `submit_contact_form` → `Submit contact form`. */
    fun humanize(name: String): String {
        val words = StringBuilder()
        name.forEachIndexed { i, c ->
            when {
                c == '_' || c == '-' || c == '.' -> words.append(' ')
                c.isUpperCase() && i > 0 && !name[i - 1].isUpperCase() -> words.append(' ').append(c.lowercaseChar())
                else -> words.append(if (i == 0) c else c.lowercaseChar())
            }
        }
        val text = words.toString().trim().replace(Regex(" +"), " ")
        return text.replaceFirstChar { it.uppercaseChar() }.ifEmpty { "UI action" }
    }
}

/**
 * Replays a chat's generative UI into one [host]: each `show_ui` stream of the transcript once,
 * in order, so surfaces, their data and the user's input survive recomposition and new turns.
 * A surface belongs to the transcript item whose stream created it; later streams may update it
 * in place. Not thread-safe: call it from the UI thread, like the host.
 *
 * ```
 * val transcript = A2uiTranscript()
 * transcript.sync(projection.items)          // on every new projection
 * transcript.surfaces(item.key)              // the surfaces to draw under that item
 * transcript.host.dispatch(node)             // a tap; SendEvent -> A2uiChat.actionMessage
 * ```
 */
class A2uiTranscript(
    catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic),
    private val limits: A2uiLimits = A2uiChat.limits,
    nowMillis: () -> Long = ::currentTimeMillis,
) {
    val host = A2uiHost(catalogs, limits, nowMillis)

    private val applied = ArrayList<Pair<String, String>>()
    private val owner = LinkedHashMap<String, String>()

    /** Bumps whenever [sync] applied something (or started over). */
    var revision: Int = 0
        private set

    /** Applies the streams of [items] not applied yet. Returns true when anything changed. */
    fun sync(items: List<SessionItem>): Boolean {
        val streams = items.mapNotNull { i -> (i as? SessionItem.Tool)?.a2ui?.let { i.key to it } }
        val extends = streams.size >= applied.size && applied.indices.all { streams[it] == applied[it] }
        if (!extends) reset()
        if (streams.size == applied.size && extends) return false
        for (i in applied.size until streams.size) apply(streams[i].first, streams[i].second)
        revision++
        return true
    }

    /** The live surfaces created by the transcript item [itemKey], in creation order. */
    fun surfaces(itemKey: String): List<String> {
        val live = host.surfaceIds.toSet()
        return owner.filter { it.value == itemKey && it.key in live }.keys.toList()
    }

    private fun apply(key: String, stream: String) {
        applied += key to stream
        val parsed = A2uiParser.parse(stream, limits)
        host.receive(stream)
        for ((_, m) in parsed.messages) {
            val body = m[A2uiMessage.CREATE_SURFACE] as? JsonObject ?: continue
            val id = body["surfaceId"]?.stringOrNull() ?: continue
            owner.remove(id)
            owner[id] = key
        }
    }

    private fun reset() {
        val ids = host.surfaceIds
        if (ids.isNotEmpty()) {
            host.receive(ids.joinToString("\n") { """{"version":"${A2uiMessage.VERSION}","deleteSurface":{"surfaceId":${JsonPrimitive(it)}}}""" })
        }
        applied.clear()
        owner.clear()
        revision++
    }
}
