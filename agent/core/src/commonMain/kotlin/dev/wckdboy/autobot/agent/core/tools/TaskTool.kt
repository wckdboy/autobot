package dev.wckdboy.autobot.agent.core.tools

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.QuestionRequest
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.PromptSection
import dev.wckdboy.autobot.agent.core.prompt.SectionOrder
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DSH `task`: delegates a self-contained job to a subagent and returns its final report. Several
 * `task` calls in one step run in parallel, each worker reporting back into this session.
 *
 * A subagent is a full [Agent] on the parent's model, permission preset, approval policy and
 * session grants, with a fresh history that holds only [prompt][TaskTool]. Its log is kept in
 * memory (never persisted), so the parent's tool result is the record: the report, plus a trace
 * of the steps in `meta.subagent`. It cannot spawn further subagents.
 *
 * Approvals and questions from the subagent surface on the parent's `task` card (the parent
 * session and call id), one at a time per task, so the UI needs no notion of child sessions.
 */
object TaskTool : ToolDefinition {
    override val name = "task"
    override val kind = ToolKind.OTHER
    override val description =
        "Delegate a self-contained job to a subagent that works with the same tools and returns one final report. " +
            "Use it for independent research or multi-step work whose details would clutter this conversation; call " +
            "several in one step to run them in parallel. The subagent sees nothing of this conversation, so the " +
            "prompt must contain everything it needs (goal, inputs, constraints, what to report back). Don't use it " +
            "for a single quick tool call."
    override val parameters = schema {
        string("description", "A short (3–6 word) label for the job, shown to the user", required = true)
        string("prompt", "The complete, standalone instructions for the subagent", required = true)
    }

    /** Subagents run whole agent turns; the parent's per-step tool timeout would cut them short. */
    override val timeoutMs: Long = 30 * 60_000L

    /** Workers are independent; whatever they touch goes through their own tool pipeline. */
    override fun isConcurrencySafe(args: JsonObject) = true

    override fun describeCall(args: JsonObject): String = args.string("description").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val parent = run.agent ?: return ToolOutput.error("subagents need an agent session", "UNAVAILABLE")
        if (isSubagent(parent.id)) return ToolOutput.error("a subagent cannot start further subagents", "PERMISSION_DENIED")
        val prompt = args.string("prompt").orEmpty().ifBlank { return ToolOutput.error("prompt is empty", "INVALID_ARGS") }

        val log = SessionLog.open(childId(parent.id, run.callId), InMemorySessionStore())
        val parentConfig = parent.config.value
        val config = parentConfig.copy(
            restriction = parentConfig.restriction.copy(deny = parentConfig.restriction.deny + name),
            maxStepsPerTurn = minOf(parentConfig.maxStepsPerTurn, MAX_STEPS),
            toolsEnabled = true,
        )
        return coroutineScope {
            val child = Agent(parent.ctx.fork("subagent:${run.callId}"), log, config, this, parent.permission.value, parent.approvalPolicy.value)
            child.sessionGrants += parent.sessionGrants
            forwardInteractions(child, parent, run.callId)
            try {
                child.followup(prompt)
                val end = log.entries.first { entries -> entries.any { it.event is SessionEvent.TurnEnd } }
                    .firstNotNullOf { it.event as? SessionEvent.TurnEnd }
                report(log, end, child)
            } finally {
                withContext(NonCancellable) { child.dispose() }
            }
        }
    }

    /** Routes the child's approvals and questions to the parent's `task` card, serialised. */
    private fun forwardInteractions(child: Agent, parent: Agent, callId: String) {
        val oneAtATime = Mutex()
        child.ctx.intercept(Hooks.ApprovalRequest, priority = Int.MAX_VALUE) { request, _ ->
            oneAtATime.withLock {
                val summary = listOf(request.toolName, request.summary).filter { it.isNotBlank() }.joinToString(" · ")
                val outcome = parent.ctx.run(Hooks.ApprovalRequest, request.copy(agent = parent, callId = callId, summary = summary)) {
                    ApprovalOutcome.UNAVAILABLE
                }
                if (outcome == ApprovalOutcome.ALLOWED_FOR_SESSION) parent.sessionGrants += request.toolName
                outcome
            }
        }
        child.ctx.intercept(Hooks.UserQuestion, priority = Int.MAX_VALUE) { request, _ ->
            oneAtATime.withLock { parent.ctx.run(Hooks.UserQuestion, QuestionRequest(parent, callId, request.question)) { null } }
        }
    }

    private fun report(log: SessionLog, end: SessionEvent.TurnEnd, child: Agent): ToolOutput {
        val entries = log.entries.value.map { it.event }
        val text = entries.filterIsInstance<SessionEvent.AssistantMessage>().lastOrNull { it.text.isNotBlank() && !it.interrupted }?.text?.trim()
        val results = entries.filterIsInstance<SessionEvent.ToolResult>().associateBy { it.callId }
        val registry = child.ctx[ToolRegistry.Key]
        val steps = buildJsonArray {
            entries.filterIsInstance<SessionEvent.ToolCall>().forEach { call ->
                val args = ToolExecutor.parseArgs(call.arguments) ?: JsonObject(emptyMap())
                val result = results[call.callId]
                add(
                    buildJsonObject {
                        put("name", call.name)
                        put("summary", registry?.get(call.name)?.describeCall(args).orEmpty())
                        put("state", if (result == null) "unknown" else if (result.isError) "error" else "ok")
                    },
                )
            }
        }
        val meta = buildJsonObject {
            put("subagent", buildJsonObject {
                put("end", end.reason.name.lowercase())
                put("steps", steps)
            })
        }
        return when {
            end.reason == TurnEndReason.COMPLETED && text != null -> ToolOutput(text, meta = meta)
            end.reason == TurnEndReason.COMPLETED -> ToolOutput.error("the subagent finished without a report", "EMPTY_REPORT").copy(meta = meta)
            else -> ToolOutput(
                "The subagent stopped (${end.reason.name.lowercase()}${end.detail?.let { ": $it" }.orEmpty()})." +
                    (text?.let { "\nLast message:\n$it" }.orEmpty()),
                isError = true,
                code = "SUBAGENT_${end.reason.name}",
                meta = meta,
            )
        }
    }

    private const val MAX_STEPS = 24
    private const val MARKER = "::task:"

    fun childId(parentId: String, callId: String) = "$parentId$MARKER$callId"

    fun isSubagent(sessionId: String) = MARKER in sessionId

    /** Steps recorded in a `task` result's meta: `(name, summary, state)`. */
    fun steps(meta: JsonObject?): List<Triple<String, String, String>> =
        ((meta?.get("subagent") as? JsonObject)?.get("steps") as? JsonArray).orEmpty().mapNotNull { step ->
            val obj = step as? JsonObject ?: return@mapNotNull null
            Triple(obj.string("name") ?: return@mapNotNull null, obj.string("summary").orEmpty(), obj.string("state") ?: "unknown")
        }

    private val SUBAGENT_RULES =
        """
        You are a subagent. Another agent delegated one job to you; the user does not read your messages, only that
        agent does. Work through the job with your tools, then end with one final message that is the complete
        report: findings, file paths, and anything you could not do. Do not ask for confirmation unless a decision
        is genuinely the user's.
        """.trimIndent()

    /** The tool plus the subagent's own prompt section. */
    val Plugin: Plugin = plugin("subagents", ToolRegistry.Key, SystemPromptService.Key) { ctx ->
        ctx.effect { ctx.require(ToolRegistry.Key).register(TaskTool) }
        ctx.effect {
            ctx.require(SystemPromptService.Key).section(
                PromptSection("harness:subagent", SectionOrder.PERSONA_SUFFIX + 1) { agent -> SUBAGENT_RULES.takeIf { isSubagent(agent.id) } },
            )
        }
    }
}
