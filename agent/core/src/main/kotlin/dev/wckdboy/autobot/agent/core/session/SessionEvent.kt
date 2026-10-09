package dev.wckdboy.autobot.agent.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Durable session events. As in DSH, the log is the single source of truth: model-visible means
 * logged, and the request history is *derived* from it ([deriveMessages]).
 *
 * Serial names follow DSH's `area/verb` event types.
 */
@Serializable
sealed interface SessionEvent {

    @Serializable
    @SerialName("turn/start")
    data class TurnStart(val turn: Int) : SessionEvent

    @Serializable
    @SerialName("turn/end")
    data class TurnEnd(val turn: Int, val reason: TurnEndReason, val detail: String? = null) : SessionEvent

    @Serializable
    @SerialName("step/start")
    data class StepStart(val turn: Int, val step: Int) : SessionEvent

    @Serializable
    @SerialName("step/end")
    data class StepEnd(val turn: Int, val step: Int) : SessionEvent

    /** The assembled system prompt. Only the latest one is sent, as message 0. */
    @Serializable
    @SerialName("system/message")
    data class SystemMessage(val text: String) : SessionEvent

    @Serializable
    @SerialName("user/message")
    data class UserMessage(
        val text: String,
        val kind: UserMessageKind = UserMessageKind.PROMPT,
        /** Identifies the [UserMessageKind.CONTEXT] source so changes can be detected. */
        val contextId: String? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("assistant/message")
    data class AssistantMessage(
        val turn: Int,
        val step: Int,
        val text: String,
        val reasoning: String? = null,
        val toolCalls: List<ToolCallRecord> = emptyList(),
        val usage: TokenUsage? = null,
        val model: String? = null,
        /** Cut short by cancellation; kept so the next request contains what the user saw. */
        val interrupted: Boolean = false,
    ) : SessionEvent

    /** A failed model request (after retries). Not model-visible. */
    @Serializable
    @SerialName("assistant/attempt")
    data class AssistantAttempt(val turn: Int, val step: Int, val code: String, val message: String) : SessionEvent

    @Serializable
    @SerialName("tool/call")
    data class ToolCall(val callId: String, val name: String, val arguments: String) : SessionEvent

    @Serializable
    @SerialName("tool/result")
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
        val code: String? = null,
        val meta: JsonObject? = null,
    ) : SessionEvent

    @Serializable
    @SerialName("approval/asked")
    data class ApprovalAsked(val callId: String, val toolName: String, val reason: String?) : SessionEvent

    @Serializable
    @SerialName("approval/decided")
    data class ApprovalDecided(val callId: String, val outcome: ApprovalOutcome) : SessionEvent

    @Serializable
    @SerialName("permission/preset")
    data class PermissionPresetChanged(val preset: String) : SessionEvent

    /** Per-session agent settings chosen by the user (restored when the session is reopened). */
    @Serializable
    @SerialName("agent/config")
    data class AgentSettingsChanged(val toolsEnabled: Boolean) : SessionEvent

    @Serializable
    @SerialName("request/context")
    data class RequestContext(val provider: String, val model: String, val contextWindow: Int?) : SessionEvent

    @Serializable
    @SerialName("llm/retry")
    data class LlmRetry(val attempt: Int, val delayMs: Long, val code: String, val message: String) : SessionEvent

    @Serializable
    @SerialName("todo/write")
    data class TodoWrite(val todos: List<TodoItem>) : SessionEvent

    @Serializable
    @SerialName("compaction/start")
    data class CompactionStart(val reason: String, val estimatedTokens: Int) : SessionEvent

    @Serializable
    @SerialName("compaction/end")
    data class CompactionEnd(val replacedEntries: Int, val summaryChars: Int, val error: String? = null) : SessionEvent

    @Serializable
    @SerialName("session/title")
    data class SessionTitle(val title: String) : SessionEvent

    @Serializable
    @SerialName("command/run")
    data class CommandRun(val name: String, val input: String? = null) : SessionEvent
}

@Serializable
enum class UserMessageKind {
    /** Typed by the user. */
    PROMPT,

    /** Runtime context (instructions, policies, skills catalog), wrapped in `<system-reminder>`. */
    CONTEXT,

    /** A compaction summary that replaces earlier history. */
    SUMMARY,
}

@Serializable
enum class TurnEndReason { COMPLETED, ABORTED, BLOCKED, ERROR, MAX_TOKENS, MAX_STEPS, INTERRUPTED }

@Serializable
enum class ApprovalOutcome { ALLOWED_ONCE, ALLOWED_FOR_SESSION, REJECTED, CANCELLED, UNAVAILABLE }

@Serializable
data class ToolCallRecord(val id: String, val name: String, val arguments: String)

@Serializable
data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val reasoningTokens: Int? = null,
)

@Serializable
enum class TodoStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
}

@Serializable
data class TodoItem(val content: String, val status: TodoStatus)

/** How an entry changes the model-visible surface. Compaction uses [Replace]. */
@Serializable
sealed interface SurfaceOp {
    @Serializable
    @SerialName("append")
    data object Append : SurfaceOp

    /** This entry stands in for entries `startSeq..endSeq` (inclusive). */
    @Serializable
    @SerialName("replace")
    data class Replace(val startSeq: Long, val endSeq: Long) : SurfaceOp
}

/** One persisted log row. [seq] is dense and strictly increasing within a session. */
@Serializable
data class LogEntry(
    val seq: Long,
    val time: Long,
    val event: SessionEvent,
    val surface: SurfaceOp = SurfaceOp.Append,
)

/** JSON codec for [LogEntry] (polymorphic on `type`). */
val SessionJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}
