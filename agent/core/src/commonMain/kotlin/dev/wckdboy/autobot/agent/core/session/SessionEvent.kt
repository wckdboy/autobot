package dev.wckdboy.autobot.agent.core.session

import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

    /**
     * The turn's privacy receipt: the models that answered (with their data policy), the hosts
     * contacted with bytes and route, the tools that ran and any fallbacks. Appended just before
     * [TurnEnd]; built on the device, never sent anywhere and not model-visible.
     *
     * @property network the turn's requests by host; null when the host app does not track
     *   traffic (the receipt then cannot say whether anything left the phone).
     * @property redacted what the redaction gateway hid from the cloud in this turn's requests:
     *   distinct values by kind (`PERSON` → 2, `EMAIL` → 1), never the values themselves; empty
     *   when it was on and found nothing, null when no request of the turn went through it.
     * @property cascade what the local-first cascade did in this turn (the phone answered first,
     *   the judge's verdict, the policy, whether it went to the cloud), never any content; null
     *   when the cascade was not in play.
     */
    @Serializable
    @SerialName("turn/receipt")
    data class TurnReceipt(
        val turn: Int,
        val models: List<ReceiptModel> = emptyList(),
        val tools: List<String> = emptyList(),
        val fallbacks: List<String> = emptyList(),
        val network: List<ReceiptHost>? = null,
        val redacted: Map<String, Int>? = null,
        val cascade: ReceiptCascade? = null,
        /** What each paired Invincible node said about the requests it answered, from its signed receipts. */
        val nodes: List<ReceiptNode>? = null,
    ) : SessionEvent

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
        /** Attached images (attachment ids, see the app's attachment store). */
        val images: List<String> = emptyList(),
        /**
         * What the transcript shows instead of [text], when the two differ: a generated-UI action
         * reaches the model as its A2UI `action` message and shows as the words the user acted on.
         */
        val display: String? = null,
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
        /** Why [model] answered instead of the requested model (on-device fallback), if it did. */
        val fallback: String? = null,
        /** Why the engine ended the reasoning early: "budget" (budget or answer reserve) or "loop". */
        val reasoningStop: String? = null,
    ) : SessionEvent

    /** A failed model request (after retries). Not model-visible. */
    @Serializable
    @SerialName("assistant/attempt")
    data class AssistantAttempt(val turn: Int, val step: Int, val code: String, val message: String) : SessionEvent

    /**
     * A call dispatched to its tool. The arguments live in the [AssistantMessage.toolCalls] entry
     * with the same id; new logs write [arguments] as `""` (the key stays, so older readers still
     * decode the line). Logs written before carry the full arguments here too.
     */
    @Serializable
    @SerialName("tool/call")
    data class ToolCall(
        val callId: String,
        val name: String,
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val arguments: String = "",
    ) : SessionEvent

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

    /**
     * The chat's own redaction choice (restored when the session is reopened): on or off for
     * this chat, or null to follow Settings again. Not model-visible.
     */
    @Serializable
    @SerialName("agent/redaction")
    data class RedactionChanged(val enabled: Boolean? = null) : SessionEvent

    /**
     * The chat's own local-first cascade policy (`OFF`, `ASK`, `AUTO`, `NEVER`; restored when the
     * session is reopened), or null to follow Settings again. Not model-visible.
     */
    @Serializable
    @SerialName("agent/cascade")
    data class CascadeChanged(val policy: String? = null) : SessionEvent

    /** The chat's Think switch (restored when the session is reopened). Not model-visible. */
    @Serializable
    @SerialName("agent/thinking")
    data class ThinkingChanged(val mode: ThinkingMode, val budget: Int? = null) : SessionEvent {
        val setting: ThinkingSetting get() = ThinkingSetting(mode, budget)
    }

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

    /**
     * The user undid (or, with [redoOf], re-applied) file changes the agent made in the
     * workspace. Model-visible as a context line, so the next turn knows the files moved back.
     *
     * @property id the revert's journal id; a later redo names it in [redoOf].
     * @property callIds the tool calls whose changes this undid (or re-applied).
     * @property turn set when the user undid a whole turn.
     * @property restored workspace-relative paths put back to earlier content.
     * @property deleted paths removed (the agent had created them).
     * @property missing paths that could not be put back (no copy kept: too large or pruned).
     */
    @Serializable
    @SerialName("workspace/revert")
    data class WorkspaceRevert(
        val id: String,
        val callIds: List<String> = emptyList(),
        val turn: Int? = null,
        val redoOf: String? = null,
        val restored: List<String> = emptyList(),
        val deleted: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
    ) : SessionEvent

    /** The undo journal dropped the copies of these turns to stay under its size cap. Not model-visible. */
    @Serializable
    @SerialName("workspace/pruned")
    data class WorkspacePruned(val turns: List<Int>, val reason: String? = null) : SessionEvent
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

/**
 * One model call's token counts. [inputTokens] is the whole input, cached parts included;
 * [cacheReadTokens] / [cacheWriteTokens] are the parts read from / written to the provider's
 * prompt cache. [outputTokens] includes [reasoningTokens] where the provider reports both.
 */
@Serializable
data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
)

/**
 * A model that answered in a turn. [name] is the provider's display name, [policy] a short data
 * policy (`no retention`, `kept 30 days`, `trains on prompts`; null when not documented) and
 * [policyNote] the provider's own words, when the host knows them.
 */
@Serializable
data class ReceiptModel(
    val provider: String,
    val model: String,
    val name: String? = null,
    val onDevice: Boolean = false,
    val policy: String? = null,
    val policyNote: String? = null,
    /** The turn's tokens on this model (its subagents' and summaries' included), summed over [requests] calls. */
    val usage: TokenUsage? = null,
    val requests: Int? = null,
    /** What those tokens cost by the price table when the turn ended, in US dollars; null: no price known. On device: 0. */
    val costUsd: Double? = null,
)

/**
 * The local-first cascade in a turn's receipt: codes only, never content.
 *
 * @property role `local` when this phone answered first, `cloud` for the follow-up the cascade
 *   sent to the chat's cloud model.
 * @property policy the chat's policy then (`ASK`, `AUTO`, `NEVER`).
 * @property verdict the judge's verdict on the phone's answer (`GOOD`, `UNSURE`, `WEAK`), or
 *   `NONE` when it never judged (no on-device model, a prompt too long for it).
 * @property reasons why, as codes (`refusal`, `unsure`, `fresh`, `truncated`, `self-check`, …).
 * @property action `KEPT` (the phone's answer stands), `OFFERED` (the user may ask the cloud),
 *   `ESCALATED` (sent to the cloud), `DIRECT` (the cloud answered, the phone could not).
 * @property risk why the prompt counts as sensitive (`personal`, `health`, `finance`,
 *   `attachment`, `memory`); empty when it is low-risk.
 * @property judge `self-check` (the on-device model graded its own answer) or `heuristic`.
 * @property judgeMs what judging added to the turn, in milliseconds.
 * @property trigger for a `cloud` follow-up: `auto` (the policy sent it) or `asked` (the user did).
 * @property target `node` when the chat's model is the user's own paired node.
 */
@Serializable
data class ReceiptCascade(
    val role: String,
    val policy: String,
    val verdict: String,
    val reasons: List<String> = emptyList(),
    val action: String,
    val risk: List<String> = emptyList(),
    val judge: String? = null,
    val judgeMs: Long? = null,
    val trigger: String? = null,
    /** `node` when the follow-up goes to the user's own paired node (kept private there), not a cloud model; null: the cloud. */
    val target: String? = null,
)

/**
 * The requests of one turn to one [host] over one [route] (`direct`, `tor`, `socks5`,
 * `loopback`, `offline`). Bytes are bodies only; [blocked] requests were refused by the kill
 * switch before any I/O. [tags] are the audit labels (provider name, `agent:web_fetch`, …).
 */
/**
 * One request a paired Invincible node answered, from the node's own receipt (Invincible's
 * spec/receipts.md): where it ran there and what left the node. [verified]: the signature checks
 * out with the receipt key pinned at pairing; an unverified receipt is shown as such, never trusted.
 *
 * @property tier `node` (the node's own model) or `cloud` (a cloud model, through its redaction).
 * @property egress hosts the node reached for this request; empty means nothing left the node.
 * @property redacted what the node's redaction hid, by kind.
 */
@Serializable
data class ReceiptNode(
    val node: String,
    val requestId: String,
    val verified: Boolean,
    val model: String? = null,
    val tier: String? = null,
    val dataPolicy: String? = null,
    val egress: List<ReceiptHost> = emptyList(),
    val redacted: Map<String, Int> = emptyMap(),
    /** Why there is no receipt (the node did not keep it, could not be reached); null when there is one. */
    val missing: String? = null,
)

@Serializable
data class ReceiptHost(
    val host: String,
    val route: String,
    val requests: Int,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val blocked: Int = 0,
    val tags: List<String> = emptyList(),
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
