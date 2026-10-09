package dev.wckdboy.autobot.agent.core.tools

import dev.wckdboy.autobot.agent.core.ApprovalRequest
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.PreToolDecision
import dev.wckdboy.autobot.agent.core.QuestionRequest
import dev.wckdboy.autobot.agent.core.ToolCallInput
import dev.wckdboy.autobot.agent.core.ToolPostInput
import dev.wckdboy.autobot.agent.core.approval.ApprovalService
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.session.ABORTED_BEFORE_DISPATCH
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TOOL_OUTCOME_UNKNOWN
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * DSH tool execution pipeline, per call:
 *
 * `tool/call` logged → lookup / parse / validate (`UNKNOWN_TOOL`, `INVALID_ARGS`) →
 * `tools/pre-execute` (allow | deny | ask → `ctx.approval`) → `tools/execute` (timeout) →
 * body → `tools/post-execute` → `tool/result` logged.
 *
 * Scheduling: consecutive calls whose tool says [ToolDefinition.isConcurrencySafe] run together
 * (bounded by `maxParallel`); any other call is an ordering barrier and runs alone. Results are
 * committed in the order the model emitted the calls.
 */
class ToolExecutor(private val agent: Agent) {

    private sealed interface Slot {
        data object NotStarted : Slot
        data object Dispatched : Slot
        data class Done(val output: ToolOutput) : Slot
    }

    /** Runs [calls] and logs their results. Returns `true` if a tool concluded the turn. */
    suspend fun runAll(calls: List<ToolCallRecord>, maxParallel: Int): Boolean {
        val slots = Array<Slot>(calls.size) { Slot.NotStarted }
        var committed = 0
        var concluded = false

        suspend fun commitUpTo(limit: Int) {
            while (committed < limit) {
                val call = calls[committed]
                val output = (slots[committed] as Slot.Done).output
                agent.log.append(
                    SessionEvent.ToolResult(call.id, call.name, output.content, output.isError, output.code, output.meta),
                )
                if (output.concludesTurn) concluded = true
                committed++
            }
        }

        try {
            var index = 0
            while (index < calls.size) {
                val batchEnd = if (isConcurrencySafe(calls[index])) {
                    var end = index
                    while (end < calls.size && isConcurrencySafe(calls[end])) end++
                    end
                } else {
                    index + 1
                }
                val semaphore = Semaphore(maxParallel.coerceAtLeast(1))
                coroutineScope {
                    (index until batchEnd).map { i ->
                        async {
                            semaphore.withPermit {
                                slots[i] = Slot.Dispatched
                                slots[i] = Slot.Done(executeOne(calls[i]))
                            }
                        }
                    }.awaitAll()
                }
                commitUpTo(batchEnd)
                index = batchEnd
            }
        } finally {
            withContext(NonCancellable) {
                // Commit finished results, then answer every remaining call so history stays valid.
                while (committed < calls.size && slots[committed] is Slot.Done) commitUpTo(committed + 1)
                for (i in committed until calls.size) {
                    val call = calls[i]
                    val output = when (val slot = slots[i]) {
                        is Slot.Done -> slot.output
                        Slot.Dispatched -> ToolOutput(TOOL_OUTCOME_UNKNOWN, isError = true, code = "TOOL_OUTCOME_UNKNOWN")
                        Slot.NotStarted -> ToolOutput(ABORTED_BEFORE_DISPATCH, isError = true, code = "ABORTED_BEFORE_DISPATCH")
                    }
                    agent.log.append(
                        SessionEvent.ToolResult(call.id, call.name, output.content, output.isError, output.code, output.meta),
                    )
                    agent.markRunning(call.id, false)
                }
            }
        }
        return concluded
    }

    private fun isConcurrencySafe(call: ToolCallRecord): Boolean {
        val tool = agent.ctx[ToolRegistry.Key]?.get(call.name) ?: return false
        val args = parseArgs(call.arguments) ?: return false
        return runCatching { tool.isConcurrencySafe(args) }.getOrDefault(false)
    }

    private suspend fun executeOne(call: ToolCallRecord): ToolOutput {
        agent.log.append(SessionEvent.ToolCall(call.id, call.name, call.arguments))
        val registry = agent.ctx.require(ToolRegistry.Key)
        val tool = registry.get(call.name)?.takeIf { agent.config.value.restriction.permits(call.name) }
            ?: return ToolOutput.error("unknown tool '${call.name}'", "UNKNOWN_TOOL")
        val args = parseArgs(call.arguments)
            ?: return ToolOutput.error("arguments must be a JSON object", "INVALID_ARGS")
        validateArgs(tool.parameters, args)?.let { return ToolOutput.error("invalid arguments: $it", "INVALID_ARGS") }

        val input = ToolCallInput(agent, call.id, tool, args)
        when (val decision = agent.ctx.run(Hooks.ToolPreExecute, input) { PreToolDecision.Allow }) {
            is PreToolDecision.Deny -> return ToolOutput.error("permission denied — ${decision.reason}", "PERMISSION_DENIED")
            is PreToolDecision.Ask -> {
                val approval = agent.ctx[ApprovalService.Key]
                    ?: return ToolOutput.error("approval required but no approver is available", "APPROVAL_UNAVAILABLE")
                val outcome = approval.request(ApprovalRequest(agent, call.id, tool.name, tool.describeCall(args), decision.reason))
                when (outcome) {
                    ApprovalOutcome.ALLOWED_ONCE, ApprovalOutcome.ALLOWED_FOR_SESSION -> Unit
                    ApprovalOutcome.REJECTED -> return ToolOutput.error("the user rejected this tool call", "USER_REJECTED")
                    ApprovalOutcome.CANCELLED -> return ToolOutput.error("approval was cancelled", "APPROVAL_CANCELLED")
                    ApprovalOutcome.UNAVAILABLE -> return ToolOutput.error("approval required but nobody answered", "APPROVAL_UNAVAILABLE")
                }
            }
            PreToolDecision.Allow -> Unit
        }

        agent.markRunning(call.id, true)
        try {
            val output = try {
                agent.ctx.run(Hooks.ToolExecute, input) { i ->
                    withTimeout(tool.timeoutMs ?: DEFAULT_TIMEOUT_MS) { tool.execute(i.args, RunContext(call.id)) }
                }
            } catch (e: TimeoutCancellationException) {
                ToolOutput.error("timed out after ${tool.timeoutMs ?: DEFAULT_TIMEOUT_MS} ms", "TIMEOUT")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ToolOutput.error(t.message ?: t.javaClass.simpleName, "TOOL_ERROR")
            }
            return agent.ctx.run(Hooks.ToolPostExecute, ToolPostInput(input, output)) { it.output }
        } finally {
            agent.markRunning(call.id, false)
        }
    }

    private inner class RunContext(override val callId: String) : ToolRunContext {
        override val sessionId: String get() = agent.id
        override fun deferContext(text: String) = agent.inject(text)
        override suspend fun log(event: SessionEvent) {
            agent.log.append(event)
        }

        override suspend fun askUser(question: UserQuestion): List<String>? =
            agent.ctx.run(Hooks.UserQuestion, QuestionRequest(agent, callId, question)) { null }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private val ArgsJson = Json { isLenient = false }

        fun parseArgs(raw: String): JsonObject? = try {
            ArgsJson.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
