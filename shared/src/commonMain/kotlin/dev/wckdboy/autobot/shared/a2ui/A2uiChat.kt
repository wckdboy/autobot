package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.shared.session.SessionItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 *
 * A user action becomes a user turn ([actionMessage]) whose `meta.a2ui` records the surface and
 * its data model at that moment ([A2uiSubmission], on [SessionItem.User.a2ui]): the transcript
 * locks that surface and restores the input from it, also after a restart.
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
        SessionEvent.UserMessage(text = actionPrompt(event), display = actionLabel(event), meta = actionMeta(event))

    /** The user turn's meta for [event]: `{"a2ui":{"surfaceId","action","dataModel"}}` (never sent to the model). */
    fun actionMeta(event: A2uiActionResult.SendEvent): JsonObject = buildJsonObject {
        put(META, buildJsonObject {
            put("surfaceId", event.surfaceId)
            put("action", event.name)
            parseOrNull(event.snapshotJson)?.let { put("dataModel", it) }
        })
    }

    /** The submission a user turn's [meta] records (see [actionMeta]); [label] is what the turn shows. */
    fun submission(meta: JsonObject?, label: String): A2uiSubmission? {
        val body = meta?.get(META) as? JsonObject ?: return null
        val surfaceId = (body["surfaceId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val action = (body["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return A2uiSubmission(surfaceId, action, label, body["dataModel"]?.toString())
    }

    /** The submission [event] makes, before its turn is in the log. */
    fun submission(event: A2uiActionResult.SendEvent): A2uiSubmission =
        A2uiSubmission(event.surfaceId, event.name, actionLabel(event), event.snapshotJson)

    private fun parseOrNull(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    /** What the model reads for [event]. */
    fun actionPrompt(event: A2uiActionResult.SendEvent): String = buildString {
        append("[UI action] The user used the UI you showed (surface '").append(event.surfaceId).append("').\n")
        append("<a2ui_action>\n").append(event.messageJson).append("\n</a2ui_action>")
        event.dataModelJson?.let { append("\n<a2ui_data_model>\n").append(it).append("\n</a2ui_data_model>") }
    }

    /** What the transcript shows for [event]: the agent's userMessage, else `Submit contact form`. */
    fun actionLabel(event: A2uiActionResult.SendEvent): String =
        event.userMessage?.takeIf { it.isNotBlank() } ?: humanize(event.name)

    /**
     * The line under an image of generated UI, saying why it waits for a tap: nothing loads
     * until the user asks, and then it goes out on the app's route [mode] (kill switch, audit log).
     */
    fun imageHint(mode: NetworkMode): String = when (mode) {
        NetworkMode.Offline -> "Offline · images can't load"
        NetworkMode.Direct -> "Tap to load · goes through your connection"
        is NetworkMode.Tor -> "Tap to load · goes through Tor"
        is NetworkMode.Socks5 -> "Tap to load · goes through your SOCKS5 proxy"
    }

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
 * A user turn that came from a surface's action: the [surfaceId] it was sent from, the event
 * [action] name, the [label] the transcript shows for it, and the surface's data model at that
 * moment ([dataModel], JSON text; obscured fields left out), restored on replay.
 */
@Serializable
data class A2uiSubmission(
    val surfaceId: String,
    val action: String,
    val label: String,
    val dataModel: String? = null,
)

/**
 * Replays a chat's generative UI into one [host]: each `show_ui` stream of the transcript once,
 * in order, so surfaces, their data and the user's input survive recomposition and new turns.
 * A surface belongs to the transcript item whose stream created it; later streams may update it
 * in place. Not thread-safe: call it from the UI thread, like the host.
 *
 * Sent forms are locked. A surface is *submitted* when, in transcript order, a user turn that
 * carries an action from it ([SessionItem.User.a2ui]) comes after the last `show_ui` stream that
 * created or changed it (`createSurface`, `updateComponents`, `updateDataModel` for that
 * surfaceId). A submitted surface is read-only in [host] (inputs and agent events refused) and
 * shows its data model as sent (the turn's snapshot is re-applied on replay, so the input
 * survives a restart). A later stream that changes the surface makes it editable again; a stream
 * that only touches other surfaces does not; `deleteSurface` drops it. [markSubmitted] locks a
 * surface at the tap, before its turn reaches the log (a queued turn is logged when it starts).
 *
 * ```
 * val transcript = A2uiTranscript()
 * transcript.sync(projection.items)          // on every new projection
 * transcript.surfaces(item.key)              // the surfaces to draw under that item
 * transcript.host.dispatch(node)             // a tap; SendEvent -> markSubmitted + A2uiChat.actionMessage
 * transcript.isSubmitted(surfaceId)          // draw it read-only, with a "Sent" note
 * ```
 */
class A2uiTranscript(
    catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic),
    private val limits: A2uiLimits = A2uiChat.limits,
    nowMillis: () -> Long = ::currentTimeMillis,
) {
    val host = A2uiHost(catalogs, limits, nowMillis)

    /** What the transcript applies, in order: a `show_ui` stream, or a user turn sent from a surface. */
    private sealed class Op {
        data class Stream(val key: String, val text: String) : Op()
        data class Submit(val key: String, val submission: A2uiSubmission) : Op()
    }

    private val applied = ArrayList<Op>()
    private val owner = LinkedHashMap<String, String>()
    private val submitted = LinkedHashMap<String, A2uiSubmission>()
    private val pending = LinkedHashMap<String, A2uiSubmission>()

    /** Bumps whenever [sync] applied something (or started over), or a surface was locked or unlocked. */
    var revision: Int = 0
        private set

    /** Applies the streams and sent actions of [items] not applied yet. Returns true when anything changed. */
    fun sync(items: List<SessionItem>): Boolean {
        val ops = items.mapNotNull { i ->
            when (i) {
                is SessionItem.Tool -> i.a2ui?.let { Op.Stream(i.key, it) }
                is SessionItem.User -> i.a2ui?.let { Op.Submit(i.key, it) }
                else -> null
            }
        }
        val extends = ops.size >= applied.size && applied.indices.all { ops[it] == applied[it] }
        if (!extends) reset()
        if (ops.size == applied.size && extends) return false
        for (i in applied.size until ops.size) {
            applied += ops[i]
            when (val op = ops[i]) {
                is Op.Stream -> apply(op.key, op.text)
                is Op.Submit -> submit(op.submission)
            }
        }
        revision++
        return true
    }

    /** The live surfaces created by the transcript item [itemKey], in creation order. */
    fun surfaces(itemKey: String): List<String> {
        val live = host.surfaceIds.toSet()
        return owner.filter { it.value == itemKey && it.key in live }.keys.toList()
    }

    /** True when [surfaceId] was sent and nothing changed it since: draw it read-only. */
    fun isSubmitted(surfaceId: String): Boolean = surfaceId in submitted || surfaceId in pending

    /** True when [surfaceId] was sent from this screen ([markSubmitted]) but its turn is not in the log yet. */
    fun isPending(surfaceId: String): Boolean = surfaceId !in submitted && surfaceId in pending

    /** The sent action that locks [surfaceId] (logged, else pending), or null when it is editable. */
    fun submission(surfaceId: String): A2uiSubmission? = submitted[surfaceId] ?: pending[surfaceId]

    /** Every locked surface, logged or pending. */
    val submittedSurfaces: Set<String> get() = submitted.keys + pending.keys

    /**
     * Locks [event]'s surface now, at the tap, until its turn reaches the log (then the logged
     * submission takes over) or a later stream changes the surface. Call it when the action is
     * handed to the agent.
     */
    fun markSubmitted(event: A2uiActionResult.SendEvent) {
        if (host.surface(event.surfaceId) == null) return
        pending[event.surfaceId] = A2uiChat.submission(event)
        host.setReadOnly(event.surfaceId, true)
        revision++
    }

    /** Undoes [markSubmitted] for [surfaceId] (the action could not be sent); a logged submission stays. */
    fun unmarkSubmitted(surfaceId: String) {
        if (pending.remove(surfaceId) == null) return
        if (surfaceId !in submitted) host.setReadOnly(surfaceId, false) else host.refresh(surfaceId)
        revision++
    }

    /**
     * Undoes every [markSubmitted] whose turn is not in the log (the queued turns were dropped,
     * e.g. the user stopped the agent); logged submissions stay.
     */
    fun clearPending() {
        pending.keys.toList().forEach(::unmarkSubmitted)
    }

    private fun apply(key: String, stream: String) {
        val parsed = A2uiParser.parse(stream, limits)
        host.receive(stream)
        for ((_, m) in parsed.messages) {
            val kind = A2uiMessage.kindOf(m) ?: continue
            val body = m[kind] as? JsonObject ?: continue
            val id = body["surfaceId"]?.stringOrNull() ?: continue
            when (kind) {
                A2uiMessage.CREATE_SURFACE -> {
                    owner.remove(id)
                    owner[id] = key
                    unlock(id)
                }
                A2uiMessage.UPDATE_COMPONENTS, A2uiMessage.UPDATE_DATA_MODEL -> unlock(id)
                A2uiMessage.DELETE_SURFACE -> {
                    submitted.remove(id)
                    pending.remove(id)
                }
            }
        }
    }

    private fun unlock(surfaceId: String) {
        val was = submitted.remove(surfaceId) != null
        if (pending.remove(surfaceId) != null || was) host.setReadOnly(surfaceId, false)
    }

    private fun submit(submission: A2uiSubmission) {
        val id = submission.surfaceId
        pending.remove(id)
        if (host.surface(id) == null) return
        // Recorded first: listeners re-read the lock state when the host publishes.
        submitted[id] = submission
        val locked = host.setReadOnly(id, true)
        val model = submission.dataModel?.let { text ->
            try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
        }
        val restored = model != null && host.restoreDataModel(id, model)
        if (!locked && !restored) host.refresh(id)
    }

    private fun reset() {
        val ids = host.surfaceIds
        if (ids.isNotEmpty()) {
            host.receive(ids.joinToString("\n") { """{"version":"${A2uiMessage.VERSION}","deleteSurface":{"surfaceId":${JsonPrimitive(it)}}}""" })
        }
        applied.clear()
        owner.clear()
        submitted.clear()
        pending.clear()
        revision++
    }
}
