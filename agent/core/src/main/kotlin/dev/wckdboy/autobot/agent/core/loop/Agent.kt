package dev.wckdboy.autobot.agent.core.loop

import dev.wckdboy.autobot.agent.core.Context
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.PreStepDecision
import dev.wckdboy.autobot.agent.core.PreStepInput
import dev.wckdboy.autobot.agent.core.RequestErrorDecision
import dev.wckdboy.autobot.agent.core.RequestErrorInput
import dev.wckdboy.autobot.agent.core.TurnStoppingDecision
import dev.wckdboy.autobot.agent.core.TurnStoppingInput
import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.LlmService
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.ABORTED_BEFORE_DISPATCH
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.session.deriveMessages
import dev.wckdboy.autobot.agent.core.session.visibleEntries
import dev.wckdboy.autobot.agent.core.tools.ToolExecutor
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRestriction
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AgentStatus { IDLE, RUNNING }

data class AgentConfig(
    val llm: LlmCallConfig,
    val restriction: ToolRestriction = ToolRestriction(),
    val maxParallelToolCalls: Int = DEFAULT_MAX_PARALLEL_TOOL_CALLS,
    /**
     * Safety stop per turn. DSH has no built-in budget; on a phone, a runaway loop costs money
     * and battery, so the default is generous but finite.
     */
    val maxStepsPerTurn: Int = DEFAULT_MAX_STEPS,
    /** Send tool schemas at all (plain chat when false). */
    val toolsEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10
        const val DEFAULT_MAX_STEPS = 48
    }
}

/** A tool call being streamed. */
data class PartialToolCall(val index: Int, val id: String?, val name: String?, val arguments: String)

/** The assistant message currently being streamed (DSH `agent/assistant-stream`). */
data class LiveStream(
    val turn: Int,
    val step: Int,
    val text: String,
    val reasoning: String,
    val toolCalls: List<PartialToolCall>,
    val startedAtNanos: Long,
    val chunks: Int,
)

/**
 * The ReAct loop (DSH `ReactLoopAgent`). A *step* is one model request plus the tools it calls;
 * a *turn* is the sequence of steps answering one user message.
 *
 * Inbox: [followup] queues a message as its own turn; [steer] targets the next step boundary of
 * the running turn (or starts a turn when idle). Both wake the driver.
 *
 * Cancellation ([cancel]) commits whatever was streamed as an `interrupted` assistant message,
 * answers undispatched tool calls with [ABORTED_BEFORE_DISPATCH] and ends the turn as
 * [TurnEndReason.ABORTED], so the log stays a valid history.
 */
class Agent(
    /** Agent scope: registrations here shadow global ones and unwind with the agent. */
    val ctx: Context,
    val log: SessionLog,
    config: AgentConfig,
    private val scope: CoroutineScope,
    permission: PermissionPreset = PermissionPreset.WORKSPACE,
    approvalPolicy: ApprovalPolicy = ApprovalPolicy.ASK,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    val id: String get() = log.sessionId

    private val _config = MutableStateFlow(config)
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow(AgentStatus.IDLE)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _live = MutableStateFlow<LiveStream?>(null)
    val live: StateFlow<LiveStream?> = _live.asStateFlow()

    /** Call ids currently executing (for spinners on tool cards). */
    private val _runningTools = MutableStateFlow<Set<String>>(emptySet())
    val runningTools: StateFlow<Set<String>> = _runningTools.asStateFlow()

    private val _permission = MutableStateFlow(permission)
    val permission: StateFlow<PermissionPreset> = _permission.asStateFlow()

    private val _approvalPolicy = MutableStateFlow(approvalPolicy)
    val approvalPolicy: StateFlow<ApprovalPolicy> = _approvalPolicy.asStateFlow()

    /** Tools the user allowed "for this session" at an approval prompt. */
    val sessionGrants: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val inboxLock = Any()
    private val nextTurn = ArrayDeque<SessionEvent.UserMessage>()
    private val nextStep = ArrayDeque<SessionEvent.UserMessage>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var turnJob: Job? = null

    private val llm get() = ctx.require(LlmService.Key)
    private val tools get() = ctx.require(ToolRegistry.Key)
    private val prompts get() = ctx.require(SystemPromptService.Key)
    private val executor = ToolExecutor(this)

    private val driver: Job = scope.launch {
        for (signal in wake) {
            while (hasPendingInput()) {
                val job = launch { runTurn() }
                turnJob = job
                job.join()
                turnJob = null
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Public controls

    /** Queues [text] (with optional attached [images]) as a new turn. */
    fun followup(text: String, images: List<String> = emptyList()) {
        synchronized(inboxLock) { nextTurn.addLast(SessionEvent.UserMessage(text, images = images)) }
        wake.trySend(Unit)
    }

    /** Delivers [text] at the next step boundary of the running turn (or as a new turn). */
    fun steer(text: String) {
        synchronized(inboxLock) { nextStep.addLast(SessionEvent.UserMessage(text)) }
        wake.trySend(Unit)
    }

    /** Queues runtime context for the next step without waking the driver (DSH `inject`). */
    fun inject(text: String, contextId: String? = null) {
        synchronized(inboxLock) { nextStep.addLast(SessionEvent.UserMessage(text, UserMessageKind.CONTEXT, contextId)) }
    }

    /** Cancels the running turn. Queued input is dropped unless [keepInbox]. */
    fun cancel(keepInbox: Boolean = false) {
        if (!keepInbox) synchronized(inboxLock) {
            nextTurn.clear()
            nextStep.clear()
        }
        turnJob?.cancel(CancellationException("Cancelled by user"))
    }

    fun updateConfig(transform: (AgentConfig) -> AgentConfig) = _config.update(transform)

    suspend fun setPermission(preset: PermissionPreset) {
        if (_permission.value == preset) return
        _permission.value = preset
        log.append(SessionEvent.PermissionPresetChanged(preset.label))
    }

    fun setApprovalPolicy(policy: ApprovalPolicy) {
        _approvalPolicy.value = policy
    }

    /** Stops the driver and unwinds the agent scope. */
    suspend fun dispose() {
        cancel()
        turnJob?.join()
        driver.cancel()
        wake.close()
        ctx.emit(Hooks.AgentDisposed, this)
        ctx.dispose()
    }

    // -----------------------------------------------------------------------------------------
    // Turn driver

    private fun hasPendingInput(): Boolean = synchronized(inboxLock) { nextTurn.isNotEmpty() || nextStep.isNotEmpty() }

    private fun claimTurnInput(): List<SessionEvent.UserMessage> = synchronized(inboxLock) {
        val claimed = nextStep.toList()
        nextStep.clear()
        claimed + listOfNotNull(nextTurn.removeFirstOrNull())
    }

    private fun claimStepInput(): List<SessionEvent.UserMessage> = synchronized(inboxLock) {
        nextStep.toList().also { nextStep.clear() }
    }

    private suspend fun runTurn() {
        val turn = (log.latest<SessionEvent.TurnStart>()?.turn ?: 0) + 1
        var steps = 0
        var reason = TurnEndReason.COMPLETED
        var detail: String? = null
        _status.value = AgentStatus.RUNNING
        try {
            log.append(SessionEvent.TurnStart(turn))
            val claimed = claimTurnInput()
            logChangedContexts()
            log.appendAll(claimed)
            if (claimed.isEmpty()) return

            while (true) {
                if (steps >= config.value.maxStepsPerTurn) {
                    reason = TurnEndReason.MAX_STEPS
                    break
                }
                val decision = ctx.run(Hooks.PreStep, PreStepInput(this, turn, steps + 1)) { PreStepDecision.Enter }
                if (decision is PreStepDecision.Reject) {
                    reason = TurnEndReason.BLOCKED
                    detail = decision.reason
                    break
                }
                steps++
                log.append(SessionEvent.StepStart(turn, steps))
                val outcome = try {
                    runStep(turn, steps)
                } finally {
                    withContext(NonCancellable) { log.append(SessionEvent.StepEnd(turn, steps)) }
                }
                when (outcome) {
                    StepOutcome.CONTINUE -> continue
                    StepOutcome.MAX_TOKENS -> {
                        reason = TurnEndReason.MAX_TOKENS
                        break
                    }
                    StepOutcome.CONCLUDED -> break
                    StepOutcome.IDLE -> {
                        if (synchronized(inboxLock) { nextStep.isNotEmpty() }) continue
                        val stop = ctx.run(Hooks.TurnStopping, TurnStoppingInput(this, turn, steps)) { TurnStoppingDecision.Stop }
                        if (stop is TurnStoppingDecision.Continue) {
                            synchronized(inboxLock) { nextStep.addLast(SessionEvent.UserMessage(stop.message, UserMessageKind.CONTEXT)) }
                            continue
                        }
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            reason = TurnEndReason.ABORTED
            detail = e.message
            throw e
        } catch (e: LlmException) {
            reason = TurnEndReason.ERROR
            detail = "${e.failure.code}: ${e.failure.message}"
        } catch (t: Throwable) {
            reason = TurnEndReason.ERROR
            detail = t.message ?: t.javaClass.simpleName
        } finally {
            withContext(NonCancellable) {
                _live.value = null
                log.append(SessionEvent.TurnEnd(turn, reason, detail))
                _status.value = AgentStatus.IDLE
            }
        }
    }

    private enum class StepOutcome { CONTINUE, IDLE, CONCLUDED, MAX_TOKENS }

    /** Logs runtime contexts whose text differs from the copy currently on the model surface. */
    private suspend fun logChangedContexts() {
        val visible = HashMap<String, String>()
        visibleEntries(log.entries.value).forEach { e ->
            val m = e.event as? SessionEvent.UserMessage ?: return@forEach
            if (m.kind == UserMessageKind.CONTEXT && m.contextId != null) visible[m.contextId] = m.text
        }
        val changed = prompts.renderContexts(this).filter { (id, text) -> visible[id] != text }
        log.appendAll(changed.map { (id, text) -> SessionEvent.UserMessage(text, UserMessageKind.CONTEXT, id) })
    }

    private var lastModelInfo: Pair<String, ModelInfo>? = null

    /** Resolves (and caches) model metadata for the current route. */
    suspend fun modelInfo(): ModelInfo {
        val cfg = config.value.llm
        val key = "${cfg.provider}/${cfg.model}"
        lastModelInfo?.takeIf { it.first == key }?.let { return it.second }
        return llm.adapter(cfg.provider).resolveModel(cfg.model).also { lastModelInfo = key to it }
    }

    private suspend fun runStep(turn: Int, step: Int): StepOutcome {
        log.appendAll(claimStepInput())

        val callConfig = ctx.run(Hooks.Request, config.value.llm) { it }
        val adapter = llm.adapter(callConfig.provider)
        val info = modelInfo()
        val requestContext = SessionEvent.RequestContext(callConfig.provider, callConfig.model, info.contextWindow)
        if (log.latest<SessionEvent.RequestContext>() != requestContext) log.append(requestContext)

        val systemPrompt = prompts.assemble(this)
        if (log.latest<SessionEvent.SystemMessage>()?.text != systemPrompt) log.append(SessionEvent.SystemMessage(systemPrompt))

        val restriction = config.value.restriction
        val schemas = if (config.value.toolsEnabled && info.supportsTools) tools.list(restriction).map { it.schema() } else emptyList()

        var attempt = 0
        var acc: StreamAccumulator
        while (true) {
            acc = StreamAccumulator(turn, step, nanoTime())
            val call = LlmCall(callConfig, deriveMessages(log.entries.value), schemas)
            try {
                streamOnce(adapter.provider, call, acc)
                if (acc.isEmpty()) throw LlmException(LlmFailure(LlmErrorCode.EMPTY_RESPONSE, "The model returned an empty response"))
                break
            } catch (e: CancellationException) {
                withContext(NonCancellable) { commitInterrupted(acc, callConfig.model) }
                throw e
            } catch (e: LlmException) {
                _live.value = null
                val decision = ctx.run(Hooks.RequestError, RequestErrorInput(this, e.failure, attempt)) { RequestErrorDecision.Fail }
                if (decision is RequestErrorDecision.Retry) {
                    attempt++
                    log.append(SessionEvent.LlmRetry(attempt, decision.delayMs, e.failure.code.name, e.failure.message))
                    delay(decision.delayMs)
                    continue
                }
                log.append(SessionEvent.AssistantAttempt(turn, step, e.failure.code.name, e.failure.message))
                throw e
            }
        }

        val calls = acc.completedCalls(step)
        log.append(
            SessionEvent.AssistantMessage(
                turn = turn,
                step = step,
                text = acc.text.toString(),
                reasoning = acc.reasoning.toString().ifEmpty { null },
                toolCalls = calls,
                usage = acc.usage,
                model = callConfig.model,
            ),
        )
        _live.value = null

        if (calls.isEmpty()) return if (acc.finishReason == "length") StepOutcome.MAX_TOKENS else StepOutcome.IDLE
        val concluded = executor.runAll(calls, config.value.maxParallelToolCalls)
        return if (concluded) StepOutcome.CONCLUDED else StepOutcome.CONTINUE
    }

    private suspend fun streamOnce(provider: String, call: LlmCall, acc: StreamAccumulator) {
        val adapter = llm.adapter(provider)
        val flow = ctx.run(Hooks.LlmStream, call) { adapter.stream(it) }
        var lastPublish = 0L
        flow.collect { chunk ->
            acc.accept(chunk)
            val now = nanoTime()
            if (now - lastPublish >= PUBLISH_INTERVAL_NS || chunk is StreamChunk.Finish) {
                lastPublish = now
                _live.value = acc.snapshot()
            }
        }
        _live.value = acc.snapshot()
    }

    private suspend fun commitInterrupted(acc: StreamAccumulator, model: String) {
        _live.value = null
        if (acc.text.isEmpty() && acc.reasoning.isEmpty()) return
        // Partially streamed tool calls are dropped: their arguments are incomplete.
        log.append(
            SessionEvent.AssistantMessage(
                turn = acc.turn,
                step = acc.step,
                text = acc.text.toString(),
                reasoning = acc.reasoning.toString().ifEmpty { null },
                model = model,
                interrupted = true,
            ),
        )
    }

    internal fun markRunning(callId: String, running: Boolean) {
        _runningTools.update { if (running) it + callId else it - callId }
    }

    internal fun deferContext(text: String) = inject(text)

    internal fun entries(): List<LogEntry> = log.entries.value

    private class StreamAccumulator(val turn: Int, val step: Int, val startedAt: Long) {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, PartialToolCall>()
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var chunks = 0

        fun accept(chunk: StreamChunk) {
            chunks++
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.ReasoningDelta -> reasoning.append(chunk.text)
                is StreamChunk.ToolCallDelta -> {
                    val prev = calls[chunk.index] ?: PartialToolCall(chunk.index, null, null, "")
                    calls[chunk.index] = prev.copy(
                        id = prev.id ?: chunk.id,
                        name = prev.name ?: chunk.name,
                        arguments = prev.arguments + chunk.argumentsDelta,
                    )
                }
                is StreamChunk.Usage -> usage = chunk.usage
                is StreamChunk.Finish -> finishReason = chunk.reason
            }
        }

        fun isEmpty() = text.isBlank() && calls.isEmpty() && reasoning.isBlank()

        fun snapshot() = LiveStream(turn, step, text.toString(), reasoning.toString(), calls.values.toList(), startedAt, chunks)

        fun completedCalls(step: Int): List<ToolCallRecord> = calls.values.map { c ->
            ToolCallRecord(
                id = c.id?.takeIf { it.isNotBlank() } ?: "call_${turn}_${step}_${c.index}",
                name = c.name.orEmpty(),
                arguments = c.arguments.ifBlank { "{}" },
            )
        }
    }

    companion object {
        private const val PUBLISH_INTERVAL_NS = 33_000_000L
    }
}
