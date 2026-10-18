package dev.wckdboy.autobot.shared.recipes

import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRestriction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one recipe as a headless agent turn, on both platforms (Android's `AgentHost`, the iOS
 * bridge). Nobody is watching, so the turn is fenced in:
 *
 * - the route is the on-device one the host resolved (cloud models never run recipes);
 * - only the recipe's allowed tools are offered ([ToolRestriction]); they count as approved for the
 *   run (the user picked them for it), except delete / execute tools and calls taint tracking
 *   flags (private data into a network tool): with [ApprovalPolicy.NEVER] those are denied without
 *   a prompt, and the run notes "Needed approval for X";
 * - network tools go through the host's kill switch and audit log like any chat, and the turn
 *   writes its privacy receipt to the result chat;
 * - a run longer than the timeout is cancelled (the log stays a valid history).
 *
 * The agent's interactive settings (tool restriction, step limit, permission preset, approval
 * policy) are put back afterwards, so the result chat behaves like any other chat when the user
 * continues it; a read-only preset never comes back as workspace-write.
 */
object RecipeTurns {
    /** Android's WorkManager stops work after ten minutes; iOS processing tasks get a few. */
    const val DEFAULT_TIMEOUT_MS = 8 * 60_000L

    /** Safety stop for a headless turn (the interactive default is far higher). */
    const val MAX_STEPS = 16

    private const val CONTEXT_ID = "recipe-run"

    /**
     * Runs [recipe]'s prompt on [agent] (a fresh chat session) with [provider]/[model] and waits
     * for the turn to end. [context] (the recent-chats digest) is added as runtime context.
     */
    suspend fun run(
        agent: Agent,
        recipe: Recipe,
        provider: String,
        model: String,
        context: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RecipeTurnResult {
        val allowed = RecipeTools.sanitize(recipe.tools).toSet()
        val before = agent.config.value
        val permissionBefore = agent.permission.value
        val policyBefore = agent.approvalPolicy.value
        val startTurn = agent.log.latest<SessionEvent.TurnStart>()?.turn ?: 0
        agent.updateConfig {
            it.copy(
                llm = LlmCallConfig(provider, model),
                restriction = ToolRestriction(allow = allowed),
                toolsEnabled = allowed.isNotEmpty(),
                maxStepsPerTurn = MAX_STEPS,
            )
        }
        agent.setPermission(PermissionPreset.WORKSPACE)
        agent.setApprovalPolicy(ApprovalPolicy.NEVER)
        // Destructive kinds still need a person: they are never granted, so they are denied.
        val tools = agent.ctx[ToolRegistry.Key]
        val granted = allowed.filter { name -> tools?.get(name)?.kind.let { it != ToolKind.DELETE && it != ToolKind.EXECUTE } }.toSet()
        agent.sessionGrants += granted
        if (before.toolsEnabled != allowed.isNotEmpty()) agent.log.append(SessionEvent.AgentSettingsChanged(allowed.isNotEmpty()))
        var timedOut = false
        try {
            agent.inject(runContext(recipe, context), CONTEXT_ID)
            agent.followup(recipe.prompt)
            val ended = withTimeoutOrNull(timeoutMs) { awaitTurnEnd(agent, startTurn) }
            if (ended == null) {
                timedOut = true
                agent.cancel()
                withTimeoutOrNull(CANCEL_WAIT_MS) { awaitTurnEnd(agent, startTurn) }
            }
        } finally {
            withContext(NonCancellable) {
                agent.sessionGrants -= granted
                agent.setApprovalPolicy(policyBefore)
                agent.setPermission(permissionBefore)
                agent.updateConfig { it.copy(restriction = before.restriction, maxStepsPerTurn = before.maxStepsPerTurn) }
            }
        }
        return outcome(agent.log.entries.value, startTurn + 1, timedOut)
    }

    private const val CANCEL_WAIT_MS = 10_000L

    private suspend fun awaitTurnEnd(agent: Agent, startTurn: Int) =
        agent.log.entries.first { entries -> entries.any { (it.event as? SessionEvent.TurnEnd)?.let { e -> e.turn > startTurn } == true } }

    /** The reminder the run's model sees (not shown as a prompt in the chat). */
    fun runContext(recipe: Recipe, recentChats: String?): String = buildString {
        append("This is the scheduled recipe \"").append(recipe.name).append("\", running unattended on this phone. ")
        append("Nobody can answer questions now: do the task with the tools you have and reply with the result. ")
        append("Tools that need approval are denied.")
        recentChats?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
    }

    /**
     * What [turn] of [entries] produced: its last answer, the calls it was not allowed to make,
     * and its receipt. Pure, so both apps and the tests read a run the same way.
     */
    fun outcome(entries: List<LogEntry>, turn: Int, timedOut: Boolean = false): RecipeTurnResult {
        val events = turnEvents(entries, turn)
        val end = events.filterIsInstance<SessionEvent.TurnEnd>().lastOrNull()
        val answer = events.filterIsInstance<SessionEvent.AssistantMessage>().lastOrNull { it.text.isNotBlank() }?.text.orEmpty()
        val receipt = events.filterIsInstance<SessionEvent.TurnReceipt>().lastOrNull()
        val notes = LinkedHashSet<String>()
        events.filterIsInstance<SessionEvent.ToolResult>().forEach { r ->
            when (r.code) {
                "USER_REJECTED", "PERMISSION_DENIED", "APPROVAL_UNAVAILABLE" -> notes += RecipeCopy.neededApproval(r.name)
                "UNKNOWN_TOOL" -> notes += RecipeCopy.notAllowed(r.name.ifBlank { "a tool" })
            }
        }
        val status = when {
            timedOut -> RecipeRunStatus.TIMED_OUT
            end == null -> RecipeRunStatus.FAILED
            end.reason == TurnEndReason.COMPLETED && answer.isNotBlank() -> RecipeRunStatus.DONE
            end.reason in PARTIAL && answer.isNotBlank() -> RecipeRunStatus.DONE
            else -> RecipeRunStatus.FAILED
        }
        val failure = when {
            timedOut -> RecipeCopy.TIMED_OUT
            status == RecipeRunStatus.DONE -> null
            end?.detail != null -> end.detail
            else -> RecipeCopy.EMPTY_ANSWER
        }
        val network = receipt?.network.orEmpty()
        return RecipeTurnResult(
            status = status,
            answer = answer,
            summary = RecipeCopy.summarize(if (status == RecipeRunStatus.DONE) answer else failure.orEmpty()),
            notes = notes.toList(),
            hosts = network.count { it.requests > it.blocked },
            bytesSent = network.sumOf { it.bytesSent },
        )
    }

    /** A turn cut at its step or token limit still produced an answer worth delivering. */
    private val PARTIAL = setOf(TurnEndReason.MAX_STEPS, TurnEndReason.MAX_TOKENS)

    private fun turnEvents(entries: List<LogEntry>, turn: Int): List<SessionEvent> {
        val start = entries.indexOfFirst { (it.event as? SessionEvent.TurnStart)?.turn == turn }
        if (start < 0) return emptyList()
        val out = ArrayList<SessionEvent>()
        for (i in start until entries.size) {
            val e = entries[i].event
            out += e
            if (e is SessionEvent.TurnEnd && e.turn == turn) break
        }
        return out
    }
}

/** What a recipe turn produced (see [RecipeTurns.outcome]). */
data class RecipeTurnResult(
    val status: RecipeRunStatus,
    val answer: String,
    val summary: String,
    val notes: List<String>,
    val hosts: Int,
    val bytesSent: Long,
) {
    fun record(startedAt: Long, finishedAt: Long, trigger: RecipeTrigger, sessionId: String?) = RecipeRunRecord(
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status,
        trigger = trigger,
        sessionId = sessionId,
        summary = summary,
        notes = notes,
        hosts = hosts,
        bytesSent = bytesSent,
    )

    companion object {
        /** A run that could not start a turn (no model): [reason] is its summary. */
        fun failed(reason: String) = RecipeTurnResult(RecipeRunStatus.FAILED, "", reason, emptyList(), 0, 0)
    }
}

/** Inputs a recipe run can be given as context. */
object RecipeContext {
    /** A chat as the digest sees it: title, last change and its searchable text (prompts and replies). */
    data class ChatExcerpt(val title: String, val updatedAt: Long, val text: String)

    /** Total characters of chat text a run gets: on-device context windows are small. */
    const val MAX_CHARS = 6_000
    private const val MIN_PER_CHAT = 400

    /**
     * The digest of [chats] changed after [sinceMillis] (newest first, each cut to its latest
     * part so the total stays under [maxChars]).
     */
    fun recentChats(chats: List<ChatExcerpt>, sinceMillis: Long, maxChars: Int = MAX_CHARS): String {
        val recent = chats.filter { it.updatedAt > sinceMillis && (it.text.isNotBlank() || it.title.isNotBlank()) }.sortedByDescending { it.updatedAt }
        if (recent.isEmpty()) return "No chats changed since the last run."
        val per = (maxChars / recent.size).coerceAtLeast(MIN_PER_CHAT)
        val out = StringBuilder("Chats changed since the last run (newest first):")
        for (chat in recent) {
            if (out.length >= maxChars) break
            val text = chat.text.trim().replace(Regex("\\s+"), " ")
            val tail = if (text.length > per) "…" + text.takeLast(per) else text
            out.append("\n\n## ").append(chat.title.ifBlank { "Untitled chat" }).append('\n').append(tail)
        }
        return out.toString().take(maxChars + 200)
    }

    /** Where a digest starts: the last run, or a day before [nowMillis] for the first one. */
    fun since(recipe: Recipe, nowMillis: Long): Long =
        recipe.state.lastRun?.startedAt ?: (nowMillis - RecipeSchedule.DAY_MS)
}
