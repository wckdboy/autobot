package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * The agent turn a coroutine works for, so the traffic it causes can be tied to that turn
 * (privacy receipts). The agent runs every turn with one; HTTP layers copy it onto their requests
 * and the audit log records it. Work started inside a turn (tools, subagents, compaction)
 * inherits it, so a subagent's requests count for the turn that ran it.
 */
data class TurnTrace(val sessionId: String, val turn: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TurnTrace> {
        /** The trace of the calling coroutine, if it works for a turn. */
        suspend fun current(): TurnTrace? = currentCoroutineContext()[Key]
    }
}

/** Input of `agent/turn-receipt`: the turn that just ended. */
data class TurnReceiptInput(val agent: Agent, val turn: Int)

/** The part of a [SessionEvent.TurnReceipt] the session log itself knows. */
object TurnReceipts {

    /** Tool results with these codes never reached the tool, so the tool did not run. */
    val NOT_RUN: Set<String> = setOf(
        "UNKNOWN_TOOL",
        "INVALID_ARGS",
        "PERMISSION_DENIED",
        "USER_REJECTED",
        "APPROVAL_CANCELLED",
        "APPROVAL_UNAVAILABLE",
        "ABORTED_BEFORE_DISPATCH",
    )

    /**
     * The receipt of [turn] from the log: [models] (provider and model of each answer, in
     * order), the tools that ran and the fallback notices. The network part is left to the host
     * (`network = null`), which hooks `agent/turn-receipt`.
     */
    fun base(entries: List<LogEntry>, turn: Int, models: List<LlmCallConfig>): SessionEvent.TurnReceipt {
        val start = entries.indexOfLast { (it.event as? SessionEvent.TurnStart)?.turn == turn }
        val events = if (start < 0) emptyList() else entries.subList(start, entries.size).map { it.event }
        val tools = LinkedHashSet<String>()
        val fallbacks = LinkedHashSet<String>()
        events.forEach { e ->
            when (e) {
                is SessionEvent.ToolResult -> if (e.code !in NOT_RUN) tools += e.name
                is SessionEvent.AssistantMessage -> e.fallback?.let { fallbacks += it }
                else -> Unit
            }
        }
        return SessionEvent.TurnReceipt(
            turn = turn,
            models = models.distinctBy { it.provider to it.model }.map { ReceiptModel(it.provider, it.model) },
            tools = tools.toList(),
            fallbacks = fallbacks.toList(),
        )
    }
}
