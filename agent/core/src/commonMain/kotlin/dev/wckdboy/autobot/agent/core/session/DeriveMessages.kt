package dev.wckdboy.autobot.agent.core.session

import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole

/** Result content for tool calls that have no logged result (crash or cancellation). */
const val TOOL_OUTCOME_UNKNOWN = "Error: tool outcome unknown (the call was interrupted before it reported a result)."
const val ABORTED_BEFORE_DISPATCH = "Error: tool call aborted before dispatch."

/**
 * The [SessionEvent.UserMessage.contextId] of a local-first cascade escalation: a
 * [UserMessageKind.CONTEXT] message that opens the follow-up turn. It never reaches the model;
 * instead the answers since the user's last prompt (the phone's) leave the model surface, so the
 * chat's model (a cloud one, or the user's node) answers that prompt afresh and later requests
 * carry only its answer. Its text is the trigger (`auto` or `asked`); its
 * [SessionEvent.UserMessage.meta] says where the follow-up goes (`target`: `node` or `cloud`).
 */
const val CASCADE_ESCALATION = "cascade/escalate"

/**
 * Derives the model request history from a session log (DSH `deriveMessages()`):
 *
 * - the latest [SessionEvent.SystemMessage] becomes message 0;
 * - compaction summaries ([SurfaceOp.Replace]) stand in for the range they replace, at the
 *   position of its first entry; summaries replaced by a later compaction disappear;
 * - [UserMessageKind.CONTEXT] messages are wrapped in `<system-reminder>`, and so is the line
 *   telling the model the user undid file changes ([SessionEvent.WorkspaceRevert]);
 * - every assistant tool call is answered: a missing result becomes [TOOL_OUTCOME_UNKNOWN] so the
 *   history stays valid for providers that require call/result pairing.
 *
 * - a cascade escalation ([CASCADE_ESCALATION]) drops the answers since the last prompt and is
 *   itself not sent.
 *
 * Each assistant message is tagged with [ModelMessage.inCurrentTurn] when it comes after the last
 * user prompt, which adapters use to decide whether reasoning must be echoed back.
 */
fun deriveMessages(entries: List<LogEntry>): List<ModelMessage> {
    val (activeRanges, summaryAt) = activeReplacements(entries)

    val system = entries.asReversed().firstNotNullOfOrNull { it.event as? SessionEvent.SystemMessage }
    val out = mutableListOf<ModelMessage>()
    if (system != null) out += ModelMessage(ModelRole.SYSTEM, system.text)

    val open = mutableListOf<Pair<String, String>>() // calls awaiting a result: (id, name)
    var lastPrompt = -1 // index in [out] of the latest prompt (or summary)

    fun closeOpenCalls() {
        open.forEach { (id, name) -> out += ModelMessage(ModelRole.TOOL, TOOL_OUTCOME_UNKNOWN, toolCallId = id, toolName = name) }
        open.clear()
    }

    for (entry in entries) {
        summaryAt[entry.seq]?.let { summary ->
            closeOpenCalls()
            out += ModelMessage(ModelRole.USER, summaryText((summary.event as SessionEvent.UserMessage).text))
            lastPrompt = out.lastIndex
        }
        if (entry.surface is SurfaceOp.Replace) continue
        if (activeRanges.any { entry.seq in it }) continue

        when (val event = entry.event) {
            is SessionEvent.UserMessage -> {
                closeOpenCalls()
                if (event.kind == UserMessageKind.CONTEXT && event.contextId == CASCADE_ESCALATION) {
                    val kept = out.filterIndexed { i, m -> i <= lastPrompt || (m.role != ModelRole.ASSISTANT && m.role != ModelRole.TOOL) }
                    out.clear()
                    out += kept
                    continue
                }
                if (event.kind != UserMessageKind.CONTEXT) lastPrompt = out.size
                out += ModelMessage(
                    ModelRole.USER,
                    when (event.kind) {
                        UserMessageKind.PROMPT -> event.text
                        UserMessageKind.CONTEXT -> "<system-reminder>\n${event.text}\n</system-reminder>"
                        UserMessageKind.SUMMARY -> summaryText(event.text)
                    },
                    images = event.images,
                )
            }
            is SessionEvent.AssistantMessage -> {
                closeOpenCalls()
                if (event.text.isEmpty() && event.toolCalls.isEmpty() && event.reasoning.isNullOrEmpty()) continue
                out += ModelMessage(
                    ModelRole.ASSISTANT,
                    event.text,
                    reasoning = event.reasoning,
                    toolCalls = event.toolCalls,
                )
                event.toolCalls.forEach { call -> open += call.id to call.name }
            }
            is SessionEvent.ToolResult -> {
                if (open.removeAll { it.first == event.callId }) {
                    out += ModelMessage(ModelRole.TOOL, event.content, toolCallId = event.callId, toolName = event.name)
                }
            }
            is SessionEvent.WorkspaceRevert -> {
                closeOpenCalls()
                out += ModelMessage(ModelRole.USER, "<system-reminder>\n${revertText(event)}\n</system-reminder>")
            }
            else -> Unit
        }
    }
    closeOpenCalls()

    val lastUser = out.indexOfLast { it.role == ModelRole.USER }
    return out.mapIndexed { index, message ->
        if (message.role == ModelRole.ASSISTANT && index > lastUser) message.copy(inCurrentTurn = true) else message
    }
}

/** Compaction state: hidden seq ranges, and the active summary entry keyed by its range start. */
private fun activeReplacements(entries: List<LogEntry>): Pair<List<LongRange>, Map<Long, LogEntry>> {
    val replacements = entries.filter { it.surface is SurfaceOp.Replace }
    // A replacement is active unless a later replacement covers its start.
    val active = replacements.filter { r ->
        val range = r.surface as SurfaceOp.Replace
        replacements.none { other ->
            other.seq > r.seq && (other.surface as SurfaceOp.Replace).let { range.startSeq in it.startSeq..it.endSeq }
        }
    }
    val ranges = active.map { (it.surface as SurfaceOp.Replace).let { r -> r.startSeq..r.endSeq } }
    return ranges to active.associateBy { (it.surface as SurfaceOp.Replace).startSeq }
}

/** Entries currently on the model-visible surface (active summaries included, replaced ones not). */
fun visibleEntries(entries: List<LogEntry>): List<LogEntry> {
    val (ranges, summaries) = activeReplacements(entries)
    val active = summaries.values.toSet()
    return entries.filter { e ->
        if (e.surface is SurfaceOp.Replace) e in active else ranges.none { e.seq in it }
    }
}

private fun summaryText(text: String) = "<compacted-summary>\n$text\n</compacted-summary>"

/**
 * The context line for a [SessionEvent.WorkspaceRevert]:
 * `The user reverted turn 3's file changes: notes/a.md restored, notes/b.md deleted.`
 */
fun revertText(event: SessionEvent.WorkspaceRevert): String {
    val what = when {
        event.redoOf != null -> if (event.turn != null) "re-applied turn ${event.turn}'s file changes" else "re-applied file changes they had undone"
        event.turn != null -> "reverted turn ${event.turn}'s file changes"
        event.callIds.size == 1 -> "undid the file change of tool call ${event.callIds.single()}"
        else -> "undid the file changes of tool calls ${event.callIds.joinToString()}"
    }
    val files = event.restored.map { "$it restored" } + event.deleted.map { "$it deleted" } + event.missing.map { "$it could not be restored" }
    val list = if (files.isEmpty()) "no files changed" else files.joinToString()
    return "The user $what: $list. Read a file again before editing it."
}
