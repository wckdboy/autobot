package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.providers.remote.QuotaErrors
import dev.wckdboy.autobot.providers.remote.SystemModels
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ToolState { STREAMING, WAITING_APPROVAL, RUNNING, OK, ERROR, DENIED }

@Serializable
enum class NoticeKind { INFO, WARN, ERROR }

/**
 * One row of the session transcript. Shared by the Compose and SwiftUI chat screens (Compose
 * treats these as stable through compose-stability.conf).
 */
@Serializable
sealed interface SessionItem {
    val key: String

    @Serializable
    @SerialName("user")
    data class User(override val key: String, val text: String, val images: List<String> = emptyList()) : SessionItem

    @Serializable
    @SerialName("assistant")
    data class Assistant(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val interrupted: Boolean,
        val outputTokens: Int?,
        val model: String?,
        /** The turn's privacy receipt, on the turn's last answer once the turn ended. */
        val receipt: Receipt? = null,
        /** The files the turn changed ("Undo turn"), on the turn's last answer once the turn ended. */
        val turnChanges: TurnChanges? = null,
    ) : SessionItem

    @Serializable
    @SerialName("tool")
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val summary: String,
        val state: ToolState,
        val output: String?,
        val code: String?,
        val galleryIds: List<String>,
        /** Wall time from dispatch to result. */
        val durationMs: Long? = null,
        /** A subagent's own tool steps (`task`), one line each: `✓ read · notes.md`. */
        val steps: List<String> = emptyList(),
        /** What a file tool did to its file (diff), from the result's `meta.change`. */
        val change: FileChange? = null,
        /** The turn the call belongs to. */
        val turn: Int = 0,
        /** The user undid this step's change (directly, or by undoing its turn or an earlier step). */
        val undone: Boolean = false,
        /** The revert that undid it, for "Redo". */
        val revertId: String? = null,
    ) : SessionItem

    @Serializable
    @SerialName("notice")
    data class Notice(override val key: String, val text: String, val kind: NoticeKind) : SessionItem
}

@Serializable
data class SessionProjection(
    val items: List<SessionItem>,
    val todos: List<TodoItem>,
    val lastUsage: TokenUsage?,
    val contextWindow: Int?,
    /** Files the agent changed in this session, per turn (the "changes" sheet). */
    val changes: List<ChangeGroup> = emptyList(),
)

/**
 * Projects the append-only session log into transcript rows. Pure, so it is unit tested and
 * cheap to recompute on every log change.
 *
 * @param pendingApprovals call ids currently waiting for the user.
 * @param running call ids currently executing.
 * @param describe one-line argument summary for a tool call (from the tool definition).
 */
fun projectSession(
    entries: List<LogEntry>,
    pendingApprovals: Set<String>,
    running: Set<String>,
    describe: (name: String, arguments: String) -> String,
): SessionProjection {
    val items = mutableListOf<SessionItem>()
    val toolIndex = HashMap<String, Int>()
    val callStarted = HashMap<String, Long>()
    var todos: List<TodoItem> = emptyList()
    var usage: TokenUsage? = null
    var window: Int? = null
    // The current turn's latest answer, which carries the turn's receipt.
    var lastAnswer: Int? = null
    // Undo state: turn of each call, the revert in effect per call and per turn, pruned turns.
    val callTurn = HashMap<String, Int>()
    val undoneBy = HashMap<String, String>()
    val turnRevert = HashMap<Int, String>()
    val prunedTurns = HashSet<Int>()
    val turnAnswer = HashMap<Int, Int>()

    for (entry in entries) {
        val key = "e${entry.seq}"
        when (val e = entry.event) {
            is SessionEvent.UserMessage -> when (e.kind) {
                UserMessageKind.PROMPT -> items += SessionItem.User(key, e.text, e.images)
                UserMessageKind.SUMMARY -> items += SessionItem.Notice(key, "context compacted · ${e.text.length} chars of summary", NoticeKind.INFO)
                UserMessageKind.CONTEXT -> Unit
            }
            is SessionEvent.AssistantMessage -> {
                e.fallback?.let { why -> items += SessionItem.Notice("${key}f", fallbackNotice(e.model.orEmpty(), why), NoticeKind.WARN) }
                if (e.text.isNotBlank() || !e.reasoning.isNullOrBlank()) {
                    lastAnswer = items.size
                    items += SessionItem.Assistant(key, e.text, e.reasoning, e.interrupted, e.usage?.outputTokens, e.model)
                }
                e.usage?.let { usage = it }
                e.toolCalls.forEach { call ->
                    callTurn[call.id] = e.turn
                    toolIndex[call.id] = items.size
                    items += SessionItem.Tool(
                        key = "t${call.id}",
                        callId = call.id,
                        name = call.name,
                        summary = describe(call.name, call.arguments),
                        state = when (call.id) {
                            in pendingApprovals -> ToolState.WAITING_APPROVAL
                            in running -> ToolState.RUNNING
                            else -> ToolState.STREAMING
                        },
                        output = null,
                        code = null,
                        galleryIds = emptyList(),
                        turn = e.turn,
                    )
                }
            }
            is SessionEvent.ToolCall -> callStarted[e.callId] = entry.time
            is SessionEvent.ToolResult -> {
                val index = toolIndex[e.callId] ?: continue
                val tool = items[index] as SessionItem.Tool
                val denied = e.code in setOf("PERMISSION_DENIED", "USER_REJECTED", "APPROVAL_UNAVAILABLE", "APPROVAL_CANCELLED")
                items[index] = tool.copy(
                    state = when {
                        denied -> ToolState.DENIED
                        e.isError -> ToolState.ERROR
                        else -> ToolState.OK
                    },
                    output = e.content,
                    code = e.code,
                    galleryIds = (e.meta?.get("gallery") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    durationMs = callStarted[e.callId]?.let { entry.time - it }?.takeIf { it >= 0 },
                    steps = TaskTool.steps(e.meta).map { (name, summary, state) ->
                        val mark = when (state) { "ok" -> "✓"; "error" -> "×"; else -> "…" }
                        listOf(name, summary).filter { it.isNotBlank() }.joinToString(" · ", prefix = "$mark ")
                    },
                    change = if (e.isError) null else FileChange.from(e.meta),
                )
            }
            is SessionEvent.WorkspaceRevert -> {
                if (e.redoOf == null) {
                    e.callIds.forEach { undoneBy[it] = e.id }
                    e.turn?.let { turnRevert[it] = e.id }
                } else {
                    e.callIds.forEach { if (undoneBy[it] == e.redoOf) undoneBy -= it }
                    turnRevert.entries.removeAll { it.value == e.redoOf }
                }
                items += SessionItem.Notice(key, revertNotice(e), NoticeKind.INFO)
            }
            is SessionEvent.WorkspacePruned -> {
                prunedTurns += e.turns
                val turns = e.turns.joinToString(", ")
                items += SessionItem.Notice(key, "undo history of turn${if (e.turns.size == 1) "" else "s"} $turns dropped" + (e.reason?.let { " · $it" } ?: ""), NoticeKind.WARN)
            }
            is SessionEvent.TurnStart -> lastAnswer = null
            is SessionEvent.TurnReceipt -> lastAnswer?.let { i ->
                items[i] = (items[i] as SessionItem.Assistant).copy(receipt = Receipts.summarize(e))
            }
            is SessionEvent.TodoWrite -> todos = e.todos
            is SessionEvent.TurnEnd -> {
                lastAnswer?.let { turnAnswer[e.turn] = it }
                endNotice(key, e)?.let { items += it }
            }
            is SessionEvent.LlmRetry ->
                items += SessionItem.Notice(key, "retry ${e.attempt} · ${e.code.lowercase()} · ${e.delayMs} ms", NoticeKind.INFO)
            is SessionEvent.PermissionPresetChanged -> items += SessionItem.Notice(key, "permissions → ${e.preset}", NoticeKind.INFO)
            is SessionEvent.AgentSettingsChanged -> items += SessionItem.Notice(key, if (e.toolsEnabled) "tools on" else "tools off · plain chat", NoticeKind.INFO)
            is SessionEvent.CompactionEnd -> e.error?.let { items += SessionItem.Notice(key, "compaction failed: $it", NoticeKind.WARN) }
            is SessionEvent.RequestContext -> window = e.contextWindow
            else -> Unit
        }
    }
    val changes = applyUndo(items, undoneBy, turnRevert, prunedTurns, turnAnswer)
    return SessionProjection(items, todos, usage, window, changes)
}

private fun endNotice(key: String, e: SessionEvent.TurnEnd): SessionItem.Notice? = when (e.reason) {
    TurnEndReason.COMPLETED -> null
    TurnEndReason.ABORTED -> SessionItem.Notice(key, "stopped", NoticeKind.WARN)
    TurnEndReason.MAX_TOKENS -> SessionItem.Notice(key, "output limit reached — say \"continue\"", NoticeKind.WARN)
    TurnEndReason.MAX_STEPS -> SessionItem.Notice(key, "step limit reached for this turn", NoticeKind.WARN)
    TurnEndReason.BLOCKED, TurnEndReason.ERROR, TurnEndReason.INTERRUPTED -> SessionItem.Notice(key, explainFailure(e.detail), NoticeKind.ERROR)
}

/** `undid turn 3 · 1 restored · 1 deleted`, `undid a step · …`, `redid turn 3 · …`. */
fun revertNotice(e: SessionEvent.WorkspaceRevert): String {
    val what = when {
        e.redoOf != null -> if (e.turn != null) "redid turn ${e.turn}" else "redid ${steps(e.callIds.size)}"
        e.turn != null -> "undid turn ${e.turn}"
        else -> "undid ${steps(e.callIds.size)}"
    }
    val counts = listOfNotNull(
        e.restored.size.takeIf { it > 0 }?.let { "$it restored" },
        e.deleted.size.takeIf { it > 0 }?.let { "$it deleted" },
        e.missing.size.takeIf { it > 0 }?.let { "$it not restorable" },
    )
    return (listOf(what) + counts).joinToString(" · ")
}

private fun steps(n: Int) = if (n == 1) "a step" else "$n steps"

/**
 * Marks undone steps, puts "Undo turn" state on each turn's last answer and lists the changed
 * files per turn. A pruned turn's steps can no longer be undone.
 */
private fun applyUndo(
    items: MutableList<SessionItem>,
    undoneBy: Map<String, String>,
    turnRevert: Map<Int, String>,
    prunedTurns: Set<Int>,
    turnAnswer: Map<Int, Int>,
): List<ChangeGroup> {
    val byTurn = LinkedHashMap<Int, MutableList<SessionItem.Tool>>()
    items.forEachIndexed { i, item ->
        val tool = item as? SessionItem.Tool ?: return@forEachIndexed
        val change = tool.change ?: return@forEachIndexed
        val updated = tool.copy(
            change = if (tool.turn in prunedTurns && change.undoable) change.copy(undoable = false, note = "undo history dropped") else change,
            undone = tool.callId in undoneBy,
            revertId = undoneBy[tool.callId],
        )
        items[i] = updated
        byTurn.getOrPut(tool.turn) { mutableListOf() } += updated
    }
    val groups = byTurn.map { (turn, tools) ->
        val files = tools.groupBy { it.change!!.path }.map { (path, steps) ->
            val last = steps.last()
            val first = steps.first().change!!
            ChangedFile(
                path = path,
                op = when {
                    first.op == ChangeOp.CREATED && last.change!!.op != ChangeOp.DELETED -> ChangeOp.CREATED
                    else -> last.change!!.op
                },
                added = steps.sumOf { it.change!!.added },
                removed = steps.sumOf { it.change!!.removed },
                undone = steps.all { it.undone },
                callId = last.callId,
            )
        }
        turnAnswer[turn]?.let { index ->
            val answer = items[index] as? SessionItem.Assistant
            if (answer != null) {
                val undoable = tools.filter { it.change!!.undoable }
                items[index] = answer.copy(
                    turnChanges = TurnChanges(
                        turn = turn,
                        files = files.size,
                        undone = undoable.isNotEmpty() && undoable.all { it.undone },
                        revertId = turnRevert[turn],
                        undoable = undoable.any { !it.undone },
                    ),
                )
            }
        }
        ChangeGroup(turn, files)
    }
    return groups
}

/** The notice above an answer from a stand-in model: the system model, or another route after a usage limit. */
fun fallbackNotice(model: String, why: String): String =
    if (SystemModels.isSystem(model)) SystemModels.fallbackNotice(model, why) else "$model · fallback — $why"

/**
 * A failed turn's `CODE: message` detail in words the user can act on: what went wrong, then
 * the provider's own message. A used-up usage limit ([QuotaErrors]) reads as a sentence with its
 * reset time and what to do meanwhile.
 */
fun explainFailure(detail: String?): String {
    if (detail.isNullOrBlank()) return "The turn failed"
    val code = detail.substringBefore(": ", "")
    val message = if (code.isNotEmpty() && code.all { it.isUpperCase() || it == '_' }) detail.substringAfter(": ") else return detail
    if (code == "QUOTA" && QuotaErrors.isLimitMessage(message)) return "$message $QUOTA_ADVICE"
    val lead = when (code) {
        "AUTH" -> "The provider rejected the API key. Check it under Settings › Model providers"
        "QUOTA" -> "The provider says the account is out of credit or quota"
        "RATE_LIMIT" -> "The provider is rate limiting requests. Wait a moment and send again"
        "TRANSPORT" -> "Couldn't reach the provider"
        "TIMEOUT" -> "The provider didn't answer in time"
        "SERVER" -> "The provider had a server error"
        "BLOCKED" -> "Blocked by the network mode. Change it in the Privacy Center, or pick an on-device model"
        "CONTEXT_WINDOW_EXCEEDED" -> "The conversation is too long for this model. Run /compact or start a new chat"
        "EMPTY_RESPONSE" -> "The model returned an empty answer. Send again"
        else -> return message.replaceFirstChar { it.uppercase() }
    }
    val explained = if (message.isBlank()) lead else "$lead ($message)"
    return if (code == "QUOTA") "$explained. $QUOTA_ADVICE" else explained
}

/** What to do while a provider's limit or credit is used up. */
const val QUOTA_ADVICE = "Switch to the helper model or an on-device model."
