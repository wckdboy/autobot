package dev.wckdboy.autobot.agent.core.loop

import dev.wckdboy.autobot.agent.core.Context
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.PreStepDecision
import dev.wckdboy.autobot.agent.core.PreStepInput
import dev.wckdboy.autobot.agent.core.RequestErrorDecision
import dev.wckdboy.autobot.agent.core.RequestErrorInput
import dev.wckdboy.autobot.agent.core.TurnReceiptInput
import dev.wckdboy.autobot.agent.core.TurnReceipts
import dev.wckdboy.autobot.agent.core.TurnStoppingDecision
import dev.wckdboy.autobot.agent.core.TurnStoppingInput
import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.LlmService
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.llm.ToolSchema
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
import dev.wckdboy.autobot.agent.core.ConcurrentSet
import dev.wckdboy.autobot.agent.core.monotonicNanos
import dev.wckdboy.autobot.agent.core.Lock
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
    /**
     * The chat's redaction override: true / false hides personal data from cloud providers in
     * this chat or not, null follows the app setting (see the host's redaction gateway).
     */
    val redaction: Boolean? = null,
    /**
     * The chat's local-first cascade override (`OFF`, `ASK`, `AUTO`, `NEVER`), null follows the
     * app setting (see the host's cascade plugin).
     */
    val cascade: String? = null,
) {
    companion object {
        const val DEFAULT_MAX_PARALLEL_TOOL_CALLS = 10
        const val DEFAULT_MAX_STEPS = 48
    }
}

/**
 * A tool call being streamed. [arguments] is only the tail of what streamed so far (at most
 * [LIVE_ARGUMENTS_TAIL] characters, for a progress hint); [argumentsLength] is the full length.
 * The complete arguments exist only once the call is committed to the log.
 */
data class PartialToolCall(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String,
    val argumentsLength: Int = arguments.length,
) {
    companion object {
        const val LIVE_ARGUMENTS_TAIL = 256
    }
}

/** The assistant message currently being streamed (DSH `agent/assistant-stream`). */
data class LiveStream(
    val turn: Int,
    val step: Int,
    val text: String,
    val reasoning: String,
    val toolCalls: List<PartialToolCall>,
    val startedAtNanos: Long,
    val chunks: Int,
    /** Reasoning tokens so far, when the engine counts them (on-device); null otherwise. */
    val reasoningTokens: Int? = null,
    /** The reasoning span closed (the answer is streaming). */
    val reasoningDone: Boolean = false,
    /** Why the engine closed the reasoning early ("budget", "loop"), if it did. */
    val reasoningStop: String? = null,
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
    private val nanoTime: () -> Long = ::monotonicNanos,
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
    val sessionGrants: MutableSet<String> = ConcurrentSet()

    /**
     * The user messages that opened the current turn, set before its prompt contexts render, so
     * a context can depend on the request (e.g. recalling memory notes relevant to it).
     */
    @kotlin.concurrent.Volatile
    var turnInput: List<SessionEvent.UserMessage> = emptyList()
        private set

    private val inboxLock = Lock()
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
                // A turn that fails outside its own handling (a store that cannot write) must not
                // take the driver down with it: the next message still gets a turn.
                val job = launch {
                    try {
                        runTurn()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        _status.value = AgentStatus.IDLE
                    }
                }
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
        inboxLock.withLock { nextTurn.addLast(SessionEvent.UserMessage(text, images = images)) }
        wake.trySend(Unit)
    }

    /**
     * Queues [message] as a turn of its own, ahead of queued prompts when [first]: a turn the
     * runtime starts itself (the local-first cascade's cloud follow-up) rather than the user.
     */
    fun queueTurn(message: SessionEvent.UserMessage, first: Boolean = false) {
        inboxLock.withLock { if (first) nextTurn.addFirst(message) else nextTurn.addLast(message) }
        wake.trySend(Unit)
    }

    /** Delivers [text] at the next step boundary of the running turn (or as a new turn). */
    fun steer(text: String) {
        inboxLock.withLock { nextStep.addLast(SessionEvent.UserMessage(text)) }
        wake.trySend(Unit)
    }

    /** Queues runtime context for the next step without waking the driver (DSH `inject`). */
    fun inject(text: String, contextId: String? = null) {
        inboxLock.withLock { nextStep.addLast(SessionEvent.UserMessage(text, UserMessageKind.CONTEXT, contextId)) }
    }

    /** Cancels the running turn. Queued input is dropped unless [keepInbox]. */
    fun cancel(keepInbox: Boolean = false) {
        if (!keepInbox) inboxLock.withLock {
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

    private fun hasPendingInput(): Boolean = inboxLock.withLock { nextTurn.isNotEmpty() || nextStep.isNotEmpty() }

    private fun claimTurnInput(): List<SessionEvent.UserMessage> = inboxLock.withLock {
        val claimed = nextStep.toList()
        nextStep.clear()
        claimed + listOfNotNull(nextTurn.removeFirstOrNull())
    }

    private fun claimStepInput(): List<SessionEvent.UserMessage> = inboxLock.withLock {
        nextStep.toList().also { nextStep.clear() }
    }

    /**
     * Runs one turn under its [TurnTrace], so the traffic it causes is tied to it. A subagent's
     * turn keeps its parent's trace (its requests count for the parent turn) and writes no
     * receipt of its own.
     */
    private suspend fun runTurn() {
        val turn = (log.latest<SessionEvent.TurnStart>()?.turn ?: 0) + 1
        val inherited = TurnTrace.current()
        withContext(inherited ?: TurnTrace(id, turn)) { runTurn(turn, receipt = inherited == null) }
    }

    private suspend fun runTurn(turn: Int, receipt: Boolean) {
        var steps = 0
        var reason = TurnEndReason.COMPLETED
        var detail: String? = null
        var answered = false
        _status.value = AgentStatus.RUNNING
        try {
            turnRoute = null
            routeNotice = null
            modelsLock.withLock { turnModels.clear() }
            // Claimed before anything is logged: a turn that fails on the store still consumes its
            // input, or the driver would start it again forever.
            val claimed = claimTurnInput()
            log.append(SessionEvent.TurnStart(turn))
            turnInput = claimed
            logChangedContexts()
            log.appendAll(claimed)
            if (claimed.isEmpty()) return
            answered = true

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
                    withContext(NonCancellable) { runCatching { log.append(SessionEvent.StepEnd(turn, steps)) } }
                }
                when (outcome) {
                    StepOutcome.CONTINUE -> continue
                    StepOutcome.MAX_TOKENS -> {
                        reason = TurnEndReason.MAX_TOKENS
                        break
                    }
                    StepOutcome.CONCLUDED -> break
                    StepOutcome.IDLE -> {
                        if (inboxLock.withLock { nextStep.isNotEmpty() }) continue
                        val stop = ctx.run(Hooks.TurnStopping, TurnStoppingInput(this, turn, steps)) { TurnStoppingDecision.Stop }
                        if (stop is TurnStoppingDecision.Continue) {
                            inboxLock.withLock { nextStep.addLast(SessionEvent.UserMessage(stop.message, UserMessageKind.CONTEXT)) }
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
            reason = if (e.failure.code == LlmErrorCode.BLOCKED) TurnEndReason.BLOCKED else TurnEndReason.ERROR
            detail = "${e.failure.code}: ${e.failure.message}"
        } catch (t: Throwable) {
            reason = TurnEndReason.ERROR
            detail = t.message ?: (t::class.simpleName ?: "error")
        } finally {
            withContext(NonCancellable) {
                _live.value = null
                turnRoute = null
                routeNotice = null
                // The status goes back first: a store that fails here must not leave the agent RUNNING.
                try {
                    if (receipt && answered) appendReceipt(turn)
                    log.append(SessionEvent.TurnEnd(turn, reason, detail))
                } catch (_: Throwable) {
                } finally {
                    _status.value = AgentStatus.IDLE
                }
            }
        }
    }

    /** Provider and model of each request of the running turn (the prompt went there), in order. */
    private val turnModels = mutableListOf<LlmCallConfig>()
    private val modelsLock = Lock()

    /** The routes the latest turn's requests went to, in order (its subagents' and summaries' included). */
    val turnRoutes: List<LlmCallConfig> get() = modelsLock.withLock { turnModels.toList() }

    /**
     * Records a request of the running turn made outside its own steps (a subagent's, a compaction
     * summary's), so the turn's receipt lists the model the data went to.
     */
    fun recordModel(config: LlmCallConfig) {
        modelsLock.withLock { turnModels += config }
    }

    /** Logs the turn's privacy receipt; a failing receipt hook never fails the turn. */
    private suspend fun appendReceipt(turn: Int) {
        val receipt = try {
            ctx.run(Hooks.TurnReceipt, TurnReceiptInput(this, turn)) { TurnReceipts.base(log.entries.value, it.turn, turnRoutes) }
        } catch (_: Throwable) {
            TurnReceipts.base(log.entries.value, turn, turnRoutes)
        }
        log.append(receipt)
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

    /**
     * The route of the rest of this turn after a [RequestErrorDecision.Fallback], and the notice
     * still to be shown with the next answer. Both reset when a turn starts.
     */
    @kotlin.concurrent.Volatile
    private var turnRoute: LlmCallConfig? = null
    private var routeNotice: String? = null

    /** The route requests go to right now: the configured one, or this turn's fallback. */
    val activeLlm: LlmCallConfig get() = turnRoute ?: config.value.llm

    /** Whether this turn already moved to a fallback route (there is only one per turn). */
    val onFallback: Boolean get() = turnRoute != null

    /**
     * The configured route on the provider's helper model, or null when the provider has none
     * (or it is the main model itself). Used for subagents and compaction summaries.
     */
    suspend fun helperConfig(): LlmCallConfig? {
        val cfg = config.value.llm
        val helper = runCatching { llm.adapter(cfg.provider).helperModel }.getOrNull()?.trim()
        return if (helper.isNullOrEmpty() || helper == cfg.model) null else cfg.copy(model = helper)
    }

    /** Resolves (and caches) model metadata for the current route. */
    suspend fun modelInfo(): ModelInfo = modelInfo(activeLlm)

    private suspend fun modelInfo(cfg: LlmCallConfig): ModelInfo {
        val key = "${cfg.provider}/${cfg.model}"
        lastModelInfo?.takeIf { it.first == key }?.let { return it.second }
        return llm.adapter(cfg.provider).resolveModel(cfg.model).also { lastModelInfo = key to it }
    }

    /** Adapter, metadata and tool schemas for one request on [callConfig]; logs a changed route. */
    private suspend fun prepare(callConfig: LlmCallConfig): Triple<LlmAdapter, ModelInfo, List<ToolSchema>> {
        val adapter = llm.adapter(callConfig.provider)
        val info = modelInfo(callConfig)
        val requestContext = SessionEvent.RequestContext(callConfig.provider, callConfig.model, info.contextWindow)
        if (log.latest<SessionEvent.RequestContext>() != requestContext) log.append(requestContext)
        val restriction = config.value.restriction
        val schemas = if (config.value.toolsEnabled && info.supportsTools) tools.list(restriction).filter { it.offeredTo(this) }.map { it.schema() } else emptyList()
        return Triple(adapter, info, schemas)
    }

    private suspend fun runStep(turn: Int, step: Int): StepOutcome {
        log.appendAll(claimStepInput())

        var callConfig = turnRoute ?: ctx.run(Hooks.Request, config.value.llm) { it }
        var (adapter, _, schemas) = prepare(callConfig)

        val systemPrompt = prompts.assemble(this)
        if (log.latest<SessionEvent.SystemMessage>()?.text != systemPrompt) log.append(SessionEvent.SystemMessage(systemPrompt))

        var attempt = 0
        var acc: StreamAccumulator
        while (true) {
            acc = StreamAccumulator(turn, step, nanoTime())
            val call = LlmCall(callConfig, deriveMessages(log.entries.value), schemas, sessionId = id)
            recordModel(callConfig)
            try {
                streamOnce(adapter.provider, call, acc)
                // An answer cut short before any content (engine stopped, filtered, out of tokens) is
                // reported as such below; retrying it as "empty" would only repeat the cut.
                if (acc.isEmpty() && acc.finishReason !in CUT_SHORT) {
                    throw LlmException(LlmFailure(LlmErrorCode.EMPTY_RESPONSE, "The model returned an empty response"))
                }
                break
            } catch (e: CancellationException) {
                withContext(NonCancellable) { commitInterrupted(acc, acc.servedBy ?: callConfig.model) }
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
                if (decision is RequestErrorDecision.Fallback && turnRoute == null) {
                    // One switch per turn: the failure stays in the log, the answer carries the notice.
                    log.append(SessionEvent.AssistantAttempt(turn, step, e.failure.code.name, e.failure.message))
                    turnRoute = decision.config
                    routeNotice = decision.notice
                    callConfig = decision.config
                    prepare(callConfig).let { (a, _, s) ->
                        adapter = a
                        schemas = s
                    }
                    attempt = 0
                    continue
                }
                log.append(SessionEvent.AssistantAttempt(turn, step, e.failure.code.name, e.failure.message))
                throw e
            }
        }
        acc.fallback = acc.fallback ?: routeNotice
        routeNotice = null

        val calls = uniqueCallIds(acc.completedCalls(step), turn, step)
        // An on-device stand-in answered instead of the requested model.
        acc.servedBy?.let { recordModel(callConfig.copy(model = it)) }
        log.append(
            SessionEvent.AssistantMessage(
                turn = turn,
                step = step,
                text = acc.text.toString(),
                reasoning = acc.reasoning.toString().ifEmpty { null },
                toolCalls = calls,
                usage = acc.finalUsage(),
                reasoningStop = acc.reasoningStop,
                model = acc.servedBy ?: callConfig.model,
                fallback = acc.fallback,
            ),
        )
        _live.value = null

        // An answer cut short by the engine or a provider filter must not read as a finished one.
        when (acc.finishReason) {
            "error" -> throw LlmException(
                LlmFailure(LlmErrorCode.PROTOCOL, "the model stopped mid-answer (on-device: out of memory, or the app was in the background)"),
            )
            "content_filter" -> throw LlmException(LlmFailure(LlmErrorCode.PROTOCOL, "the provider's content filter cut the answer off"))
        }
        if (calls.isEmpty()) return if (acc.finishReason == "length") StepOutcome.MAX_TOKENS else StepOutcome.IDLE
        val concluded = executor.runAll(calls, config.value.maxParallelToolCalls)
        return if (concluded) StepOutcome.CONCLUDED else StepOutcome.CONTINUE
    }

    /**
     * Rewrites call ids already used in this session (or twice in one message): results, approvals
     * and transcript rows are keyed by call id, and some engines and models repeat them.
     */
    private fun uniqueCallIds(calls: List<ToolCallRecord>, turn: Int, step: Int): List<ToolCallRecord> {
        if (calls.isEmpty()) return calls
        val seen = HashSet<String>()
        log.entries.value.forEach { e -> (e.event as? SessionEvent.AssistantMessage)?.toolCalls?.forEach { seen += it.id } }
        return calls.mapIndexed { i, call ->
            var id = call.id
            var n = 0
            while (!seen.add(id)) id = "${call.id}_${turn}_${step}_${i}_${++n}"
            if (id == call.id) call else call.copy(id = id)
        }
    }

    private suspend fun streamOnce(provider: String, call: LlmCall, acc: StreamAccumulator) {
        val adapter = llm.adapter(provider)
        val flow = ctx.run(Hooks.LlmStream, call) { adapter.stream(it) }
        // The clock starts near zero with the process, so the first chunk always publishes.
        var lastPublish: Long? = null
        flow.collect { chunk ->
            acc.accept(chunk)
            val now = nanoTime()
            if (lastPublish.let { it == null || now - it >= PUBLISH_INTERVAL_NS } || chunk is StreamChunk.Finish) {
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
                reasoningStop = acc.reasoningStop,
                fallback = acc.fallback,
            ),
        )
    }

    internal fun markRunning(callId: String, running: Boolean) {
        _runningTools.update { if (running) it + callId else it - callId }
    }

    internal fun deferContext(text: String) = inject(text)

    internal fun entries(): List<LogEntry> = log.entries.value

    /**
     * Accumulates one streamed step. Tool-call arguments grow in a [StringBuilder] per call index
     * (appending is amortised O(1); a 1 MB `write` streams in O(n), not O(n²)), and [snapshot]
     * reuses the strings of the previous snapshot while nothing new arrived.
     */
    internal class StreamAccumulator(val turn: Int, val step: Int, val startedAt: Long) {
        val text = StringBuilder()
        val reasoning = StringBuilder()

        private class Call(val index: Int) {
            var id: String? = null
            var name: String? = null
            val arguments = StringBuilder()
        }

        /** Streamed tool calls by index. */
        private val calls = HashMap<Int, Call>()
        private val ordered: List<Call> get() = calls.values.sortedBy { it.index }
        var usage: TokenUsage? = null
        var finishReason: String? = null
        var servedBy: String? = null
        var fallback: String? = null
        var chunks = 0
        var reasoningTokens: Int? = null
        var reasoningDone = false
        var reasoningStop: String? = null
        private var textSnapshot = ""
        private var reasoningSnapshot = ""

        fun accept(chunk: StreamChunk) {
            chunks++
            when (chunk) {
                is StreamChunk.TextDelta -> text.append(chunk.text)
                is StreamChunk.ReasoningDelta -> reasoning.append(chunk.text)
                is StreamChunk.ToolCallDelta -> {
                    val call = calls.getOrPut(chunk.index) { Call(chunk.index) }
                    if (call.id == null) call.id = chunk.id
                    if (call.name == null) call.name = chunk.name
                    call.arguments.append(chunk.argumentsDelta)
                }
                is StreamChunk.Usage -> usage = chunk.usage
                is StreamChunk.Finish -> finishReason = chunk.reason
                is StreamChunk.ServedBy -> {
                    servedBy = chunk.model
                    fallback = chunk.fallback
                }
                is StreamChunk.ReasoningStatus -> {
                    reasoningTokens = chunk.tokens
                    reasoningDone = chunk.done
                    if (chunk.stop != null) reasoningStop = chunk.stop
                }
            }
        }

        fun isEmpty() = text.isBlank() && calls.isEmpty() && reasoning.isBlank()

        fun snapshot(): LiveStream {
            if (textSnapshot.length != text.length) textSnapshot = text.toString()
            if (reasoningSnapshot.length != reasoning.length) reasoningSnapshot = reasoning.toString()
            val partial = ordered.map { c ->
                val n = c.arguments.length
                val tail = c.arguments.substring((n - PartialToolCall.LIVE_ARGUMENTS_TAIL).coerceAtLeast(0), n)
                PartialToolCall(c.index, c.id, c.name, tail, n)
            }
            return LiveStream(turn, step, textSnapshot, reasoningSnapshot, partial, startedAt, chunks, reasoningTokens, reasoningDone, reasoningStop)
        }

        /** The provider's usage, with the engine's reasoning count when the provider gave none. */
        fun finalUsage(): TokenUsage? {
            val counted = reasoningTokens ?: return usage
            val u = usage ?: return TokenUsage(reasoningTokens = counted)
            return if (u.reasoningTokens == null) u.copy(reasoningTokens = counted) else u
        }

        fun completedCalls(step: Int): List<ToolCallRecord> = ordered.map { c ->
            ToolCallRecord(
                id = c.id?.takeIf { it.isNotBlank() } ?: "call_${turn}_${step}_${c.index}",
                name = c.name.orEmpty(),
                arguments = c.arguments.toString().ifBlank { "{}" },
            )
        }
    }

    companion object {
        private const val PUBLISH_INTERVAL_NS = 33_000_000L

        /** Finish reasons of an answer the engine or provider cut short. */
        private val CUT_SHORT = setOf("error", "content_filter", "length")
    }
}
