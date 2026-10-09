package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.runtime.Immutable
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

enum class ToolState { STREAMING, WAITING_APPROVAL, RUNNING, OK, ERROR, DENIED }

enum class NoticeKind { INFO, WARN, ERROR }

/** One row of the session transcript. */
@Immutable
sealed interface SessionItem {
    val key: String

    data class User(override val key: String, val text: String) : SessionItem

    data class Assistant(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val interrupted: Boolean,
        val outputTokens: Int?,
        val model: String?,
    ) : SessionItem

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
    ) : SessionItem

    data class Notice(override val key: String, val text: String, val kind: NoticeKind) : SessionItem
}

@Immutable
data class SessionProjection(
    val items: List<SessionItem>,
    val todos: List<TodoItem>,
    val lastUsage: TokenUsage?,
    val contextWindow: Int?,
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

    for (entry in entries) {
        val key = "e${entry.seq}"
        when (val e = entry.event) {
            is SessionEvent.UserMessage -> when (e.kind) {
                UserMessageKind.PROMPT -> items += SessionItem.User(key, e.text)
                UserMessageKind.SUMMARY -> items += SessionItem.Notice(key, "context compacted · ${e.text.length} chars of summary", NoticeKind.INFO)
                UserMessageKind.CONTEXT -> Unit
            }
            is SessionEvent.AssistantMessage -> {
                if (e.text.isNotBlank() || !e.reasoning.isNullOrBlank()) {
                    items += SessionItem.Assistant(key, e.text, e.reasoning, e.interrupted, e.usage?.outputTokens, e.model)
                }
                e.usage?.let { usage = it }
                e.toolCalls.forEach { call ->
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
                )
            }
            is SessionEvent.TodoWrite -> todos = e.todos
            is SessionEvent.TurnEnd -> when (e.reason) {
                TurnEndReason.COMPLETED -> Unit
                TurnEndReason.ABORTED -> items += SessionItem.Notice(key, "stopped", NoticeKind.WARN)
                TurnEndReason.MAX_TOKENS -> items += SessionItem.Notice(key, "output limit reached — say \"continue\"", NoticeKind.WARN)
                TurnEndReason.MAX_STEPS -> items += SessionItem.Notice(key, "step limit reached for this turn", NoticeKind.WARN)
                TurnEndReason.BLOCKED -> items += SessionItem.Notice(key, "blocked: ${e.detail.orEmpty()}", NoticeKind.ERROR)
                TurnEndReason.ERROR, TurnEndReason.INTERRUPTED -> items += SessionItem.Notice(key, e.detail ?: "turn failed", NoticeKind.ERROR)
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
    return SessionProjection(items, todos, usage, window)
}
