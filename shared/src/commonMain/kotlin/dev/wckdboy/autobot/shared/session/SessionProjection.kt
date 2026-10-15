package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.TaskTool
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
                UserMessageKind.PROMPT -> items += SessionItem.User(key, e.text, e.images)
                UserMessageKind.SUMMARY -> items += SessionItem.Notice(key, "context compacted · ${e.text.length} chars of summary", NoticeKind.INFO)
                UserMessageKind.CONTEXT -> Unit
            }
            is SessionEvent.AssistantMessage -> {
                e.fallback?.let { why -> items += SessionItem.Notice("${key}f", SystemModels.fallbackNotice(e.model.orEmpty(), why), NoticeKind.WARN) }
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
                    steps = TaskTool.steps(e.meta).map { (name, summary, state) ->
                        val mark = when (state) { "ok" -> "✓"; "error" -> "×"; else -> "…" }
                        listOf(name, summary).filter { it.isNotBlank() }.joinToString(" · ", prefix = "$mark ")
                    },
                )
            }
            is SessionEvent.TodoWrite -> todos = e.todos
            is SessionEvent.TurnEnd -> when (e.reason) {
                TurnEndReason.COMPLETED -> Unit
                TurnEndReason.ABORTED -> items += SessionItem.Notice(key, "stopped", NoticeKind.WARN)
                TurnEndReason.MAX_TOKENS -> items += SessionItem.Notice(key, "output limit reached — say \"continue\"", NoticeKind.WARN)
                TurnEndReason.MAX_STEPS -> items += SessionItem.Notice(key, "step limit reached for this turn", NoticeKind.WARN)
                TurnEndReason.BLOCKED, TurnEndReason.ERROR, TurnEndReason.INTERRUPTED ->
                    items += SessionItem.Notice(key, explainFailure(e.detail), NoticeKind.ERROR)
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

/**
 * A failed turn's `CODE: message` detail in words the user can act on: what went wrong, then
 * the provider's own message.
 */
fun explainFailure(detail: String?): String {
    if (detail.isNullOrBlank()) return "The turn failed"
    val code = detail.substringBefore(": ", "")
    val message = if (code.isNotEmpty() && code.all { it.isUpperCase() || it == '_' }) detail.substringAfter(": ") else return detail
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
    return if (message.isBlank()) lead else "$lead ($message)"
}
