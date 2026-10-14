package dev.wckdboy.autobot.agent.core.approval

import dev.wckdboy.autobot.agent.core.ApprovalRequest
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.PreToolDecision
import dev.wckdboy.autobot.agent.core.ServiceKey
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import kotlin.coroutines.cancellation.CancellationException

/**
 * Permission presets (DSH `dsh-permission-presets`), adapted to a phone: there is no shell
 * sandbox, every file tool is already confined to the agent workspace, and network access is
 * treated as an escalation because egress is the main privacy risk.
 *
 * | kind               | READ_ONLY | WORKSPACE (default) | FULL_ACCESS |
 * |--------------------|-----------|---------------------|-------------|
 * | read, search, other| allow     | allow               | allow       |
 * | edit, generate     | deny      | allow               | allow       |
 * | delete, execute    | deny      | ask                 | allow       |
 * | fetch (network)    | ask       | ask                 | allow       |
 * | interact           | allow     | allow               | allow       |
 */
enum class PermissionPreset(val label: String) {
    READ_ONLY("read-only"),
    WORKSPACE("workspace-write"),
    FULL_ACCESS("full-access"),
    ;

    fun decide(kind: ToolKind): PreToolDecision = when (this) {
        READ_ONLY -> when (kind) {
            ToolKind.READ, ToolKind.SEARCH, ToolKind.OTHER, ToolKind.INTERACT -> PreToolDecision.Allow
            ToolKind.FETCH -> PreToolDecision.Ask("Network access")
            else -> PreToolDecision.Deny("The read-only preset does not allow ${kind.name.lowercase()} tools")
        }
        WORKSPACE -> when (kind) {
            ToolKind.DELETE -> PreToolDecision.Ask("Deletes workspace files")
            ToolKind.EXECUTE -> PreToolDecision.Ask("Executes code")
            ToolKind.FETCH -> PreToolDecision.Ask("Network access")
            else -> PreToolDecision.Allow
        }
        FULL_ACCESS -> PreToolDecision.Allow
    }

    companion object {
        fun fromLabel(label: String?): PermissionPreset? = entries.firstOrNull { it.label == label }
    }
}

/** DSH approval policy: `NEVER` rejects every ask deterministically (no UI prompt). */
enum class ApprovalPolicy { ASK, NEVER }

/**
 * `ctx.approval`: asks registered answerers through [Hooks.ApprovalRequest]. Fail-closed: no
 * answerer, or an answerer that throws, yields [ApprovalOutcome.UNAVAILABLE].
 */
class ApprovalService {
    suspend fun request(request: ApprovalRequest): ApprovalOutcome {
        val agent = request.agent
        agent.log.append(SessionEvent.ApprovalAsked(request.callId, request.toolName, request.reason))
        val outcome = if (agent.approvalPolicy.value == ApprovalPolicy.NEVER) {
            ApprovalOutcome.REJECTED
        } else {
            try {
                agent.ctx.run(Hooks.ApprovalRequest, request) { ApprovalOutcome.UNAVAILABLE }
            } catch (e: CancellationException) {
                agent.log.append(SessionEvent.ApprovalDecided(request.callId, ApprovalOutcome.CANCELLED))
                throw e
            } catch (_: Throwable) {
                ApprovalOutcome.UNAVAILABLE
            }
        }
        if (outcome == ApprovalOutcome.ALLOWED_FOR_SESSION) agent.sessionGrants += request.toolName
        agent.log.append(SessionEvent.ApprovalDecided(request.callId, outcome))
        return outcome
    }

    companion object {
        val Key = ServiceKey<ApprovalService>("approval")
    }
}

/** Applies the agent's [PermissionPreset] in `tools/pre-execute`, honouring session grants. */
val PermissionPresetsPlugin: Plugin = plugin("permission-presets") { ctx ->
    ctx.intercept(Hooks.ToolPreExecute, priority = 100) { input, next ->
        val agent: Agent = input.agent
        when (val decision = agent.permission.value.decide(input.tool.kind)) {
            is PreToolDecision.Deny -> decision
            is PreToolDecision.Ask -> if (input.tool.name in agent.sessionGrants) next(input) else decision
            PreToolDecision.Allow -> next(input)
        }
    }
}
