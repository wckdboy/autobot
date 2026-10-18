package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.PreStepDecision
import dev.wckdboy.autobot.agent.core.RequestErrorDecision
import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmService
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.monotonicNanos
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.CASCADE_ESCALATION
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptCascade
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.session.visibleEntries
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The local-first cascade (ROADMAP Phase 2.5, item 3): in a plain chat (tools off) on a cloud
 * model, "This phone" answers first; a small judge ([CascadeJudge]) decides whether its answer is
 * good enough, and only then may the prompt go to the cloud: offered ("Ask"), or on its own
 * ("Auto", low-risk prompts only, [CascadeRiskClassifier]).
 *
 * The cloud follow-up is a turn of its own, opened by a [CASCADE_ESCALATION] context message: the
 * phone's answer stays on screen, the cloud model answers the user's prompt afresh (the phone's
 * answer leaves the model surface, see `deriveMessages`), and the request goes through
 * `llm/stream` like any other, so the redaction gateway hides personal data from it. Both turns'
 * receipts say what happened and why ([ReceiptCascade]); never any content.
 *
 * Not in play for agent runs (tools on), subagents, chats on "This phone" or a loopback server,
 * and with the policy [CascadePolicy.OFF] (the default).
 *
 * @param defaultPolicy Settings → "Phone first", for chats without their own choice.
 * @param onDevice the on-device chat route (the installed default model), null when none.
 * @param cloud whether a provider's requests leave the phone.
 * @param owned whether a provider is the user's own paired node: then the follow-up goes there,
 *   marked private in [privateTurns] so the node keeps it on its own models ("phone → my node").
 * @param selfCheck whether the judge may ask the on-device model to grade its answer (else rules only).
 */
class LocalFirstCascade(
    private val defaultPolicy: () -> CascadePolicy,
    private val onDevice: suspend () -> LlmCallConfig?,
    private val cloud: suspend (providerId: String) -> Boolean,
    private val owned: suspend (providerId: String) -> Boolean = { false },
    private val privateTurns: dev.wckdboy.autobot.shared.invincible.PrivateTurns? = null,
    private val judge: CascadeJudge = CascadeJudge(),
    private val selfCheck: () -> Boolean = { true },
    private val selfCheckTimeoutMs: Long = CascadeJudge.SELF_CHECK_TIMEOUT_MS,
    private val millis: () -> Long = { monotonicNanos() / 1_000_000 },
) {
    private val lock = Lock()

    /** The running turn's cascade state, by session id. */
    private val turns = HashMap<String, TurnState>()

    private class TurnState(
        val turn: Int,
        val policy: CascadePolicy,
        val route: CascadeRoute,
        val local: LlmCallConfig?,
        val risk: CascadeRisk,
        val prompt: String,
        /** A cloud follow-up: its trigger (`auto` / `asked`) and the phone's judged answer it follows. */
        val trigger: String? = null,
        val origin: ReceiptCascade? = null,
        /** The follow-up target is the user's own node. */
        val owned: Boolean = false,
    ) {
        @kotlin.concurrent.Volatile var judging = false
        @kotlin.concurrent.Volatile var result: CascadeJudge.Result? = null
        @kotlin.concurrent.Volatile var judgeName: String? = null
        @kotlin.concurrent.Volatile var judgeMs: Long? = null
        @kotlin.concurrent.Volatile var failed = false
    }

    /** The policy [agent]'s chat uses: its own choice, else Settings. */
    fun policyOf(agent: Agent): CascadePolicy = CascadePolicy.resolve(agent.config.value.cascade, defaultPolicy())

    /** What a chat stores when the user picks [policy] (null: follow Settings). */
    fun override(policy: CascadePolicy): String? = CascadePolicy.override(policy, defaultPolicy())

    /**
     * The chat bar's chip: the effective policy's name, or `NONE` when the cascade cannot apply
     * (an agent run, or a chat whose model is on the phone or a loopback server).
     */
    fun chipState(config: AgentConfig, cloudRoute: Boolean): String =
        if (config.toolsEnabled || !cloudRoute) NONE else CascadePolicy.resolve(config.cascade, defaultPolicy()).name

    /**
     * "Ask <cloud model>": sends the prompt of the offered answer ([pendingOffer]) to the chat's
     * cloud model as a follow-up turn. False when there is no offer (another turn started) or a
     * turn is running.
     */
    fun escalate(agent: Agent): Boolean {
        if (agent.status.value == AgentStatus.RUNNING) return false
        pendingOffer(agent.log.entries.value) ?: return false
        agent.queueTurn(SessionEvent.UserMessage(TRIGGER_ASKED, UserMessageKind.CONTEXT, CASCADE_ESCALATION), first = true)
        return true
    }

    val plugin: Plugin = plugin("local-first-cascade") { ctx ->
        ctx.on(Hooks.AgentDisposed) { agent -> lock.withLock { turns.remove(agent.id) } }

        // Before a turn's first step: route it (phone, cloud, or not at all).
        ctx.intercept(Hooks.PreStep) { input, next ->
            if (input.step == 1 && !TaskTool.isSubagent(input.agent.id)) {
                val blocked = begin(input.agent, input.turn)
                if (blocked != null) return@intercept PreStepDecision.Reject(blocked)
            }
            next(input)
        }

        // The phone answers the steps of a local-first turn.
        ctx.intercept(Hooks.Request) { config, next ->
            val routed = next(config)
            val trace = TurnTrace.current() ?: return@intercept routed
            val state = lock.withLock { turns[trace.sessionId] }?.takeIf { it.turn == trace.turn } ?: return@intercept routed
            val local = state.local
            if (state.route == CascadeRoute.Local && local != null) local.copy(maxTokens = routed.maxTokens, temperature = routed.temperature) else routed
        }

        // The phone's answer is in: judge it while the turn is still running (cancellable).
        ctx.intercept(Hooks.TurnStopping) { input, next ->
            val state = lock.withLock { turns[input.agent.id] }?.takeIf { it.turn == input.turn }
            if (state != null && state.route == CascadeRoute.Local && state.result == null) judgeAnswer(input.agent, state)
            next(input)
        }

        ctx.intercept(Hooks.RequestError) { input, next ->
            val decision = next(input)
            if (decision is RequestErrorDecision.Fail) {
                val trace = TurnTrace.current()
                lock.withLock { turns[input.agent.id] }
                    ?.takeIf { it.route == CascadeRoute.Local && (trace == null || it.turn == trace.turn) }
                    ?.failed = true
            }
            decision
        }

        // The receipt says what the cascade did; "Auto" queues the cloud follow-up here.
        ctx.intercept(Hooks.TurnReceipt) { input, next ->
            val receipt = next(input)
            privateTurns?.clear(TurnTrace(input.agent.id, input.turn))
            val state = lock.withLock { turns.remove(input.agent.id) }?.takeIf { it.turn == input.turn } ?: return@intercept receipt
            val note = try {
                finish(input.agent, state)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            } ?: return@intercept receipt
            receipt.copy(cascade = note)
        }
    }

    /** Sets up [turn]'s cascade state; returns a message when the turn must not run. */
    private suspend fun begin(agent: Agent, turn: Int): String? {
        lock.withLock { turns.remove(agent.id) }
        val input = agent.turnInput
        val entries = agent.log.entries.value
        input.lastOrNull { it.kind == UserMessageKind.CONTEXT && it.contextId == CASCADE_ESCALATION }?.let { marker ->
            val origin = lastLocal(entries)
            val toNode = origin?.target == TARGET_NODE
            val state = TurnState(
                turn, CascadePolicy.parse(origin?.policy) ?: policyOf(agent), CascadeRoute.NotApplicable, null,
                CascadeRisk(origin?.risk.orEmpty()), "", trigger = marker.text.ifBlank { TRIGGER_ASKED }, origin = origin, owned = toNode,
            )
            // The follow-up to the user's own node stays there.
            if (toNode) privateTurns?.mark(TurnTrace(agent.id, turn))
            lock.withLock { turns[agent.id] = state }
            return null
        }
        val config = agent.config.value
        val policy = policyOf(agent)
        if (!policy.localFirst || config.toolsEnabled) return null
        val prompt = input.lastOrNull { it.kind == UserMessageKind.PROMPT } ?: return null
        val toNode = runCatching { owned(config.llm.provider) }.getOrDefault(false)
        if (!toNode && !runCatching { cloud(config.llm.provider) }.getOrDefault(true)) return null
        val local = try {
            onDevice()?.takeIf { it.provider.isNotBlank() && it.model.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val window = local?.let { l ->
            try {
                agent.ctx.require(LlmService.Key).adapter(l.provider).resolveModel(l.model).contextWindow
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
        val route = CascadeRoutes.route(policy, cloudRoute = !toNode, toolsEnabled = false, hasLocalModel = local != null, Compactor().estimateTokens(entries), window, owned = toNode)
        when (route) {
            CascadeRoute.NotApplicable -> return null
            is CascadeRoute.Blocked -> return route.message
            else -> Unit
        }
        val state = TurnState(turn, policy, route, local, risk(agent, prompt, entries), prompt.text, owned = toNode)
        // The phone cannot answer, so the node does: privately.
        if (toNode && route is CascadeRoute.Direct) privateTurns?.mark(TurnTrace(agent.id, turn))
        lock.withLock { turns[agent.id] = state }
        return null
    }

    /** The risk of sending this chat's request to the cloud: the prompt, attachments, memory on the surface. */
    private fun risk(agent: Agent, prompt: SessionEvent.UserMessage, entries: List<LogEntry>): CascadeRisk {
        val visible = visibleEntries(entries).mapNotNull { it.event as? SessionEvent.UserMessage }
        val images = prompt.images.isNotEmpty() || visible.any { it.images.isNotEmpty() }
        val prompts = agent.ctx[SystemPromptService.Key]
        val privateContext = visible.any { m ->
            m.kind == UserMessageKind.CONTEXT && m.contextId != null && m.text.isNotBlank() && prompts?.privateSource(m.contextId!!) != null
        }
        val terms = agent.ctx[TaintTracker.Key]?.let { tracker ->
            runCatching {
                tracker.ingestAll(agent)
                tracker.privateTerms(agent.id) { it == "memory" }.map { it.text }
            }.getOrDefault(emptyList())
        }.orEmpty()
        return CascadeRiskClassifier.classify(prompt.text, images, privateContext, terms)
    }

    private suspend fun judgeAnswer(agent: Agent, state: TurnState) {
        val answer = answerOf(agent.log.entries.value, state.turn) ?: return
        state.judging = true
        val started = millis()
        val signals = judge.signals(state.prompt, answer.text)
        // "Never" only reports; a hard signal already decides. Neither needs the extra pass.
        val grade = if (signals.hard.isEmpty() && state.policy != CascadePolicy.NEVER && selfCheck()) selfCheck(agent, state, answer.text) else null
        state.result = judge.judge(signals, grade)
        state.judgeName = if (grade != null) JUDGE_SELF_CHECK else JUDGE_HEURISTIC
        state.judgeMs = millis() - started
    }

    /** One short grading pass on the on-device model; null when it fails, times out or says nothing usable. */
    private suspend fun selfCheck(agent: Agent, state: TurnState, answer: String): Int? {
        val local = state.local ?: return null
        return try {
            val adapter = agent.ctx.require(LlmService.Key).adapter(local.provider)
            val call = LlmCall(
                local.copy(maxTokens = CascadeJudge.SELF_CHECK_MAX_TOKENS, temperature = 0.0, thinking = ThinkingSetting.OFF),
                judge.selfCheckMessages(state.prompt, answer),
                emptyList(),
                sessionId = agent.id,
            )
            val reply = withTimeoutOrNull(selfCheckTimeoutMs) {
                val text = StringBuilder()
                adapter.stream(call).collect { if (it is StreamChunk.TextDelta) text.append(it.text) }
                text.toString()
            }
            reply?.let(CascadeJudge::parseSelfCheck)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun finish(agent: Agent, state: TurnState): ReceiptCascade? {
        state.trigger?.let { trigger ->
            val origin = state.origin
            return ReceiptCascade(
                role = ROLE_CLOUD,
                policy = state.policy.name,
                verdict = origin?.verdict ?: NOT_JUDGED,
                reasons = origin?.reasons.orEmpty(),
                action = CascadeAction.ESCALATED.name,
                risk = origin?.risk.orEmpty(),
                trigger = trigger,
                target = TARGET_NODE.takeIf { state.owned },
            )
        }
        val route = state.route
        if (route is CascadeRoute.Direct) {
            return ReceiptCascade(
                ROLE_CLOUD, state.policy.name, NOT_JUDGED, listOf(route.reason), CascadeAction.DIRECT.name, state.risk.reasons,
                target = TARGET_NODE.takeIf { state.owned },
            )
        }
        var result = state.result
        if (result == null) {
            // Stopped while judging, or before the phone answered: no verdict, no follow-up.
            if (state.judging) return null
            val answer = answerOf(agent.log.entries.value, state.turn)
            result = when {
                state.failed -> judge.judge(judge.signals(state.prompt, answer?.text.orEmpty(), failed = true), null)
                answer == null || answer.interrupted -> return null
                // The turn ended without the stop hook: the engine's output limit.
                else -> judge.judge(judge.signals(state.prompt, answer.text, truncated = true), null)
            }
            state.judgeName = JUDGE_HEURISTIC
        }
        val action = CascadeRoutes.action(state.policy, result.verdict, state.risk, state.owned)
        if (action == CascadeAction.ESCALATED) {
            agent.queueTurn(SessionEvent.UserMessage(TRIGGER_AUTO, UserMessageKind.CONTEXT, CASCADE_ESCALATION), first = true)
        }
        return ReceiptCascade(
            role = ROLE_LOCAL,
            policy = state.policy.name,
            verdict = result.verdict.name,
            reasons = result.reasons,
            action = action.name,
            risk = state.risk.reasons,
            judge = state.judgeName,
            judgeMs = state.judgeMs,
            target = TARGET_NODE.takeIf { state.owned },
        )
    }

    companion object {
        const val NONE = "NONE"
        const val ROLE_LOCAL = "local"
        const val ROLE_CLOUD = "cloud"
        const val TRIGGER_AUTO = "auto"
        const val TRIGGER_ASKED = "asked"
        const val JUDGE_SELF_CHECK = "self-check"
        const val JUDGE_HEURISTIC = "heuristic"
        const val NOT_JUDGED = "NONE"
        const val TARGET_NODE = "node"

        /** The phone's offered answer still waiting for "Ask": the latest turn's, when the judge offered the cloud. */
        fun pendingOffer(entries: List<LogEntry>): ReceiptCascade? {
            for (entry in entries.asReversed()) {
                when (val e = entry.event) {
                    is SessionEvent.TurnStart -> return null
                    is SessionEvent.TurnReceipt -> return e.cascade?.takeIf { it.role == ROLE_LOCAL && it.action == CascadeAction.OFFERED.name }
                    else -> Unit
                }
            }
            return null
        }

        /** The latest receipt of a turn the phone answered (what a follow-up follows). */
        private fun lastLocal(entries: List<LogEntry>): ReceiptCascade? =
            entries.asReversed().firstNotNullOfOrNull { (it.event as? SessionEvent.TurnReceipt)?.cascade?.takeIf { c -> c.role == ROLE_LOCAL } }

        /** [turn]'s latest answer with text. */
        private fun answerOf(entries: List<LogEntry>, turn: Int): SessionEvent.AssistantMessage? =
            entries.asReversed().firstNotNullOfOrNull { (it.event as? SessionEvent.AssistantMessage)?.takeIf { m -> m.turn == turn } }
    }
}
