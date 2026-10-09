package dev.wckdboy.autobot.agent.core.policy

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.RequestErrorDecision
import dev.wckdboy.autobot.agent.core.ServiceKey
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmService
import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SurfaceOp
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.session.deriveMessages
import dev.wckdboy.autobot.agent.core.session.visibleEntries
import kotlin.math.min
import kotlinx.coroutines.CancellationException

/**
 * DSH `dsh-compaction-basic`.
 *
 * Trigger: before a step, when estimated prompt tokens exceed
 * `min(W × 0.8, W − O − reserve)` (W = context window, O = output reservation), or after a
 * `CONTEXT_WINDOW_EXCEEDED` failure. The newest ~[retainRatio] of history stays verbatim; the
 * rest is summarized by the same model and logged as one `user/message` whose
 * [SurfaceOp.Replace] hides the summarized range. Nothing is deleted from the log.
 */
class Compactor(private val retainRatio: Double = 0.16) {

    fun threshold(contextWindow: Int, outputReserve: Int): Int {
        val reserve = min(65_536, contextWindow / 8)
        return min((contextWindow * 0.8).toInt(), contextWindow - outputReserve - reserve).coerceAtLeast(contextWindow / 4)
    }

    /** Estimated prompt tokens: last reported usage when it is the newest fact, else chars / 4. */
    fun estimateTokens(entries: List<LogEntry>): Int {
        val visible = visibleEntries(entries)
        val lastAssistant = visible.indexOfLast { it.event is SessionEvent.AssistantMessage }
        val usage = (visible.getOrNull(lastAssistant)?.event as? SessionEvent.AssistantMessage)?.usage
        val reported = usage?.let { (it.inputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.outputTokens ?: 0) }
        if (reported != null && reported > 0) {
            val tail = visible.drop(lastAssistant + 1).sumOf { entryTokens(it) }
            return reported + tail
        }
        return visible.sumOf { entryTokens(it) }
    }

    private fun entryTokens(entry: LogEntry): Int = when (val e = entry.event) {
        is SessionEvent.SystemMessage -> e.text.length / 4
        is SessionEvent.UserMessage -> e.text.length / 4 + 4
        is SessionEvent.AssistantMessage -> (e.text.length + (e.reasoning?.length ?: 0) + e.toolCalls.sumOf { it.arguments.length + 16 }) / 4
        is SessionEvent.ToolResult -> e.content.length / 4 + 8
        else -> 0
    }

    /** Summarizes older history. Returns `false` when there was nothing worth compacting. */
    suspend fun compact(agent: Agent, reason: String): Boolean {
        val entries = agent.log.entries.value
        val visible = visibleEntries(entries).filter { it.event !is SessionEvent.SystemMessage }
        // Cut only where call/result pairs cannot be split: before a prompt or an assistant message.
        val boundaries = visible.filter {
            val e = it.event
            (e is SessionEvent.UserMessage && e.kind == UserMessageKind.PROMPT) || e is SessionEvent.AssistantMessage
        }
        if (boundaries.size < 2) return false
        val total = visible.sumOf { entryTokens(it) }.coerceAtLeast(1)
        val target = (total * retainRatio).toInt()
        val boundary = boundaries.drop(1).lastOrNull { b -> visible.filter { it.seq >= b.seq }.sumOf { entryTokens(it) } >= target }
            ?: boundaries.last()
        val firstSeq = entries.first().seq
        val endSeq = boundary.seq - 1
        if (endSeq < firstSeq) return false

        agent.log.append(SessionEvent.CompactionStart(reason, estimateTokens(entries)))
        val summary = try {
            summarize(agent, entries)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            agent.log.append(SessionEvent.CompactionEnd(0, 0, error = t.message ?: t.javaClass.simpleName))
            return false
        }
        if (summary.isBlank()) {
            agent.log.append(SessionEvent.CompactionEnd(0, 0, error = "empty summary"))
            return false
        }
        agent.log.append(SessionEvent.UserMessage(summary, UserMessageKind.SUMMARY), SurfaceOp.Replace(firstSeq, endSeq))
        agent.log.append(SessionEvent.CompactionEnd(entries.count { it.seq in firstSeq..endSeq }, summary.length))
        return true
    }

    /** Re-sends the current history (prefix-cache friendly) with the instruction appended last. */
    private suspend fun summarize(agent: Agent, entries: List<LogEntry>): String {
        val config = agent.config.value.llm
        val adapter = agent.ctx.require(LlmService.Key).adapter(config.provider)
        val messages = deriveMessages(entries) + ModelMessage(ModelRole.USER, SUMMARY_INSTRUCTION)
        val out = StringBuilder()
        adapter.stream(LlmCall(config.copy(temperature = 0.2), messages, emptyList())).collect { chunk ->
            if (chunk is StreamChunk.TextDelta) out.append(chunk.text)
        }
        return out.toString().trim()
    }

    companion object {
        val Key = ServiceKey<Compactor>("compaction")

        /** Section list from DSH `summarizer.ts`. */
        val SUMMARY_INSTRUCTION = """
            Your context is about to be compacted. Write a summary that lets you continue the work without the
            earlier messages. Do not call tools. Use exactly these sections:

            1. Primary Request and Intent — what the user asked for, in their words where it matters.
            2. Key Technical Concepts
            3. Files and Code — paths touched or read, with the important snippets.
            4. Errors and Fixes
            5. Pending Jobs
            6. Current Work — precisely what was in progress.
            7. Next Step — the immediate next action, consistent with the user's latest request.
            8. Critical Context — anything else you must not forget (constraints, preferences, ids).
        """.trimIndent()
    }
}

/** Wires [Compactor] into `agent/pre-step` (pressure) and `agent/request-error` (overflow). */
fun compactionPlugin(compactor: Compactor = Compactor(), outputReserve: Int = 8_192): Plugin =
    plugin("compaction-basic", LlmService.Key) { ctx ->
        ctx.effect { ctx.provide(Compactor.Key, compactor) }
        ctx.intercept(Hooks.PreStep) { input, next ->
            val agent = input.agent
            val info = agent.modelInfo()
            val reserve = agent.config.value.llm.maxTokens ?: info.defaultMaxTokens ?: outputReserve
            val estimate = compactor.estimateTokens(agent.log.entries.value)
            if (estimate > compactor.threshold(info.contextWindow, reserve)) compactor.compact(agent, "pressure")
            next(input)
        }
        ctx.intercept(Hooks.RequestError) { input, next ->
            if (input.failure.code == LlmErrorCode.CONTEXT_WINDOW_EXCEEDED && input.attempt == 0 &&
                compactor.compact(input.agent, "overflow")
            ) {
                RequestErrorDecision.Retry(0)
            } else {
                next(input)
            }
        }
    }
