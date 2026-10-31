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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The local-first cascade (ROADMAP Phase 2.5, item 3): in a plain chat (tools off) on a cloud
 * model or the user's own node ([owned]), "This phone" answers first; a small judge
 * ([CascadeJudge]) decides whether its answer is good enough, and only then may the prompt go to
 * the chat's model: offered ("Ask"), or on its own ("Auto": to a cloud model, low-risk prompts
 * only, [CascadeRiskClassifier]). On the node the rules differ ([CascadeRoutes]): "Auto" and
 * "Never" send a weak answer's prompt on, a sensitive one included, and under "Never" too the node
 * answers a turn the phone cannot take.
 *
 * The follow-up is a turn of its own, opened by a [CASCADE_ESCALATION] context message: the
 * phone's answer stays on screen, the chat's model answers the user's prompt afresh (the phone's
 * answer leaves the model surface, see `deriveMessages`), and the request goes through
 * `llm/stream` like any other, so the redaction gateway hides personal data from a cloud model. A
 * follow-up to the user's own node is not redacted by the phone; it goes marked private instead.
 * Where a follow-up goes, and by which rules, is the chat's model as it is then, not the one the
 * phone's answer was judged for: the user may switch models in between (an offer outlives that).
 *
 * Every request of a turn this cascade handles (the phone's own excepted) is checked when it is
 * made, against the chat's policy and model as they are then ([refusal]), so no switch between
 * queueing and sending gets round the rules: under "Never" nothing goes to a cloud model, a
 * compaction summary included; a follow-up goes to the user's node, or to a cloud model only
 * where "Ask" may send it ([canAsk]; one sent on its own only where "Auto" would send it there on
 * its own), and never to "This phone" or a loopback server. A refused request is not made; a
 * refused follow-up's notice says it was not sent. Both turns' receipts say what happened and why
 * ([ReceiptCascade]); never any content.
 *
 * Not in play for agent runs (tools on), subagents, chats on "This phone" or a loopback server,
 * and with the policy [CascadePolicy.OFF] (the default).
 *
 * @param defaultPolicy Settings → "Phone first", for chats without their own choice.
 * @param onDevice the on-device chat route (the installed default model), null when none.
 * @param cloud whether a provider is a cloud one (`RedactionGateway.isCloud`: not "This phone", a
 *   loopback server or the user's node).
 * @param owned whether a provider is the user's own paired node. Every request of a turn this
 *   cascade handles that goes to one (a follow-up, a turn the phone cannot take, a compaction
 *   summary written by the chat's model) goes marked private in [privateTurns], so the node never
 *   runs it on a cloud model ("phone → my node"). A request to a provider this cannot be told for
 *   (the lookup fails) goes marked all the same, and the rules treat that provider as a cloud one.
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
        /** A follow-up (to a cloud model or the node): its trigger (`auto` / `asked`) and the phone's judged answer it follows. */
        val trigger: String? = null,
        val origin: ReceiptCascade? = null,
        /** It goes to the user's own node (known to be; a follow-up's is set again by each request it makes). */
        @kotlin.concurrent.Volatile var owned: Boolean = false,
        /** The chat's policy as it is now, against which each request of the turn is checked ([refusal]). */
        val rules: () -> CascadePolicy = { policy },
    ) {
        /** A request of the turn was refused ([refusal]): it was not made. */
        @kotlin.concurrent.Volatile var refused = false
        @kotlin.concurrent.Volatile var judging = false
        @kotlin.concurrent.Volatile var result: CascadeJudge.Result? = null
        @kotlin.concurrent.Volatile var judgeName: String? = null
        @kotlin.concurrent.Volatile var judgeMs: Long? = null
        @kotlin.concurrent.Volatile var failed = false
    }

    /**
     * The routes (provider, model) of a running turn's checked requests: those refused, and those
     * made. A refused compaction summary is listed among the turn's models before it is refused
     * (`Agent.recordModel`); its receipt drops a route that only refused requests had.
     */
    private class Routes(val turn: Int) {
        val refused = HashSet<Pair<String, String>>()
        val sent = HashSet<Pair<String, String>>()
    }

    /** [Routes] of the running turn, by session id. */
    private val routes = HashMap<String, Routes>()

    /** The policy [agent]'s chat uses: its own choice, else Settings. */
    fun policyOf(agent: Agent): CascadePolicy = CascadePolicy.resolve(agent.config.value.cascade, defaultPolicy())

    /** What a chat stores when the user picks [policy] (null: follow Settings). */
    fun override(policy: CascadePolicy): String? = CascadePolicy.override(policy, defaultPolicy())

    /**
     * The chat bar's chip: the effective policy's name, or `NONE` when the cascade cannot apply
     * (an agent run, or a chat whose model is on the phone or a loopback server). [cloudRoute]: the
     * chat's model is a cloud one; [owned]: it is the user's own paired node, where the cascade
     * applies too ([CascadeRoutes.route]).
     */
    fun chipState(config: AgentConfig, cloudRoute: Boolean, owned: Boolean): String =
        if (config.toolsEnabled || !(cloudRoute || owned)) NONE else CascadePolicy.resolve(config.cascade, defaultPolicy()).name

    /**
     * Whether "Ask" under an offered answer can send it on now, for a chat with [config] whose
     * model is the user's own node ([owned]) or a cloud one ([cloudRoute]): the rule [escalate]
     * applies (the companion's [canAsk], with the chat's policy). The bridge hands it to iOS with
     * each frame, and Android applies the same rule, so the offer shows only where a tap sends it.
     */
    fun canAsk(config: AgentConfig, owned: Boolean, cloudRoute: Boolean): Boolean =
        canAsk(CascadePolicy.resolve(config.cascade, defaultPolicy()), owned, cloudRoute)

    /**
     * "Ask <model>": sends the prompt of the offered answer ([pendingOffer]) to the chat's model as
     * it is now (a cloud one, or the user's node) as a follow-up turn. False when there is no offer
     * (another turn started), a turn is running, or [canAsk] says no: the chat's model is "This
     * phone" or a loopback server, or the chat's policy is "Never" and its model is not the user's
     * node (or that cannot be told). The follow-up is checked again when it starts and when it
     * sends ([refusal]).
     */
    suspend fun escalate(agent: Agent): Boolean {
        val provider = agent.config.value.llm.provider
        val owned = ownership(provider)
        val cloudRoute = cloudRoute(provider, owned)
        if (agent.status.value == AgentStatus.RUNNING) return false
        pendingOffer(agent.log.entries.value) ?: return false
        if (!canAsk(agent.config.value, owned == true, cloudRoute)) return false
        agent.queueTurn(escalation(TRIGGER_ASKED, owned == true), first = true)
        return true
    }

    /** Whether [providerId] is the user's own paired node; null when that cannot be told (the lookup failed). */
    private suspend fun ownership(providerId: String): Boolean? = try {
        owned(providerId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    /**
     * Whether the rules treat [providerId] as a cloud model, given [owned] ([ownership]): not when
     * it is the user's node, always when that cannot be told, else when [cloud] says so or cannot
     * tell (not "This phone", not a loopback server).
     */
    private suspend fun cloudRoute(providerId: String, owned: Boolean?): Boolean = when (owned) {
        true -> false
        null -> true
        false -> try {
            cloud(providerId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            true
        }
    }

    /** [trace]'s cascade state, if this cascade handles that turn. */
    private fun stateOf(trace: TurnTrace): TurnState? = lock.withLock { turns[trace.sessionId] }?.takeIf { it.turn == trace.turn }

    val plugin: Plugin = plugin("local-first-cascade") { ctx ->
        ctx.on(Hooks.AgentDisposed) { agent ->
            lock.withLock {
                turns.remove(agent.id)
                routes.remove(agent.id)
            }
        }

        // Ahead of compaction, whose summary request goes to the chat's model before a turn's first
        // step: refuse a follow-up the rules no longer allow, before that request is made, and
        // count the turn as this cascade's, so that request is checked and goes marked private on
        // the node (`llm/stream` below).
        ctx.intercept(Hooks.PreStep, priority = AHEAD_OF_COMPACTION) { input, next ->
            if (input.step == 1 && !TaskTool.isSubagent(input.agent.id)) {
                val refused = prepare(input.agent, input.turn)
                if (refused != null) return@intercept PreStepDecision.Reject(refused)
            }
            next(input)
        }

        // Before a turn's first step (after compaction): route it (phone, the chat's model, or not at all).
        ctx.intercept(Hooks.PreStep) { input, next ->
            if (input.step == 1 && !TaskTool.isSubagent(input.agent.id)) {
                val blocked = begin(input.agent, input.turn)
                if (blocked != null) return@intercept PreStepDecision.Reject(blocked)
            }
            next(input)
        }

        // The phone answers the steps of a local-first turn. Any other step's request is checked
        // against the rules for where it goes now, before it is made ([admit]): a switch after the
        // turn started cannot take it anywhere they rule out.
        ctx.intercept(Hooks.Request) { config, next ->
            val routed = next(config)
            val trace = TurnTrace.current() ?: return@intercept routed
            val state = stateOf(trace) ?: return@intercept routed
            val local = state.local
            if (state.trigger == null && state.route == CascadeRoute.Local && local != null) {
                return@intercept local.copy(maxTokens = routed.maxTokens, temperature = routed.temperature)
            }
            admit(trace, state, routed, sending = false)
            routed
        }

        // Every request of a turn this cascade handles, when it is made (a compaction summary
        // before the first step and after a switch mid-turn included): checked once more, and
        // marked private when it goes to the user's node or to a provider that cannot be told.
        ctx.intercept(Hooks.LlmStream) { call, next ->
            val trace = TurnTrace.current()
            val state = trace?.let { stateOf(it) }
            if (trace != null && state != null && admit(trace, state, call.config, sending = true) != false) {
                privateTurns?.mark(trace)
            }
            next(call)
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

        // The receipt says what the cascade did; an escalation queues the follow-up here.
        ctx.intercept(Hooks.TurnReceipt) { input, next ->
            val base = next(input)
            privateTurns?.clear(TurnTrace(input.agent.id, input.turn))
            val (state, used) = lock.withLock { turns.remove(input.agent.id) to routes.remove(input.agent.id) }
            // A route only refused requests had: nothing went there.
            val dropped = used?.takeIf { it.turn == input.turn }?.let { r -> r.refused - r.sent }.orEmpty()
            val receipt = if (dropped.isEmpty()) base else base.copy(models = base.models.filterNot { (it.provider to it.model) in dropped })
            state?.takeIf { it.turn == input.turn } ?: return@intercept receipt
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

    /**
     * Checks a request of [state]'s turn ([trace]) to [config] against the chat's rules as they
     * are now ([refusal]); throws [CascadeRefused] when they rule it out, so it is not made. Returns
     * whether it goes to the user's node (null: that cannot be told; false for the phone's own
     * requests, which are not checked). [sending]: the request is made right after (`llm/stream`).
     */
    private suspend fun admit(trace: TurnTrace, state: TurnState, config: LlmCallConfig, sending: Boolean): Boolean? {
        if (state.local?.provider == config.provider) return false
        val owned = ownership(config.provider)
        val why = refusal(state.trigger, state.origin, state.rules(), owned, cloudRoute(config.provider, owned))
        val route = config.provider to config.model
        lock.withLock {
            val r = routes[trace.sessionId]?.takeIf { it.turn == trace.turn } ?: Routes(trace.turn).also { routes[trace.sessionId] = it }
            if (why != null) r.refused += route else if (sending) r.sent += route
        }
        if (why != null) {
            state.refused = true
            throw CascadeRefused(why)
        }
        // A follow-up's words name the node only where it is known to be the node.
        if (state.trigger != null) state.owned = owned == true
        return owned
    }

    /**
     * Before compaction (which may send the chat to its model ahead of [turn]'s first step): a
     * follow-up the chat's rules rule out now is refused, so nothing is sent ([followUp]); a turn
     * this cascade may handle counts as its own from here (not routed yet), so a summary request is
     * checked ([admit]) and, to the user's node, goes marked private. [begin] decides again after
     * compaction. Returns a message when the turn must not run.
     */
    private suspend fun prepare(agent: Agent, turn: Int): String? {
        lock.withLock { turns.remove(agent.id) }
        val input = agent.turnInput
        escalationMarker(input)?.let { return followUp(agent, turn, it) }
        val policy = policyOf(agent)
        if (!policy.localFirst || agent.config.value.toolsEnabled || input.none { it.kind == UserMessageKind.PROMPT }) return null
        val state = TurnState(turn, policy, CascadeRoute.NotApplicable, null, CascadeRisk(emptyList()), "", rules = { policyOf(agent) })
        lock.withLock { turns[agent.id] = state }
        return null
    }

    /**
     * Sets up the follow-up [marker] opens. It goes to the chat's model now, which need not be the
     * one the phone's answer was judged for: since it was queued the user may have switched models
     * or changed the chat's policy. Returns why it must not run ([refusal]), or null.
     */
    private suspend fun followUp(agent: Agent, turn: Int, marker: SessionEvent.UserMessage): String? {
        val origin = lastLocal(agent.log.entries.value)
        val trigger = marker.text.ifBlank { TRIGGER_ASKED }
        val provider = agent.config.value.llm.provider
        val owned = ownership(provider)
        val refused = refusal(trigger, origin, policyOf(agent), owned, cloudRoute(provider, owned))
        val state = TurnState(
            turn, CascadePolicy.parse(origin?.policy) ?: policyOf(agent), CascadeRoute.NotApplicable, null,
            CascadeRisk(origin?.risk.orEmpty()), "", trigger = trigger, origin = origin,
            owned = owned == true, rules = { policyOf(agent) },
        )
        state.refused = refused != null
        lock.withLock { turns[agent.id] = state }
        return refused
    }

    /** Sets up [turn]'s cascade state (after compaction); returns a message when the turn must not run. */
    private suspend fun begin(agent: Agent, turn: Int): String? {
        lock.withLock { turns.remove(agent.id) }
        val input = agent.turnInput
        val entries = agent.log.entries.value
        // Checked again: the chat may have left the node, or its policy changed, during compaction.
        escalationMarker(input)?.let { return followUp(agent, turn, it) }
        val config = agent.config.value
        val policy = policyOf(agent)
        if (!policy.localFirst || config.toolsEnabled) return null
        val prompt = input.lastOrNull { it.kind == UserMessageKind.PROMPT } ?: return null
        // A model that cannot be told follows the rules of a cloud one (stricter); what goes to it
        // goes marked private all the same (`llm/stream` above).
        val owned = ownership(config.llm.provider)
        val toNode = owned == true
        if (!toNode && !cloudRoute(config.llm.provider, owned)) return null
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
        val state = TurnState(turn, policy, route, local, risk(agent, prompt, entries), prompt.text, owned = toNode, rules = { policyOf(agent) })
        lock.withLock { turns[agent.id] = state }
        return null
    }

    /** The risk of sending this chat's request off the phone: the prompt, attachments, memory on the surface. */
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
        // "Never" only reports; a hard signal already decides. Neither needs the extra pass. (With a
        // node, "Never" sends a weak answer on as "Auto" does, still judged by the rules alone.)
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

    private suspend fun finish(agent: Agent, state: TurnState): ReceiptCascade? {
        // Refused ([refusal]) before it got an answer: its request was not made, the phone's answer stands.
        val notSent = state.refused && answerOf(agent.log.entries.value, state.turn) == null
        state.trigger?.let { trigger ->
            val origin = state.origin
            return ReceiptCascade(
                role = ROLE_CLOUD,
                policy = state.policy.name,
                verdict = origin?.verdict ?: NOT_JUDGED,
                reasons = origin?.reasons.orEmpty(),
                action = if (notSent) CascadeAction.KEPT.name else CascadeAction.ESCALATED.name,
                risk = origin?.risk.orEmpty(),
                trigger = trigger,
                target = TARGET_NODE.takeIf { state.owned && !notSent },
            )
        }
        val route = state.route
        // Counted from before compaction ([prepare]) but never routed: nothing to say.
        if (route == CascadeRoute.NotApplicable) return null
        if (route is CascadeRoute.Direct) {
            // Refused (a switch to a cloud model under "Never"): the turn's notice says why.
            if (notSent) return null
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
        // The rules, and the target, of the chat's model now: the user may have switched models
        // while the phone answered. One that cannot be told follows a cloud model's rules (stricter);
        // on "This phone" or a loopback server there is nothing to ask, so the answer is kept.
        val provider = agent.config.value.llm.provider
        val owned = ownership(provider)
        val toNode = owned == true
        val action = if (toNode || cloudRoute(provider, owned)) CascadeRoutes.action(state.policy, result.verdict, state.risk, toNode) else CascadeAction.KEPT
        if (action == CascadeAction.ESCALATED) agent.queueTurn(escalation(TRIGGER_AUTO, toNode), first = true)
        return ReceiptCascade(
            role = ROLE_LOCAL,
            policy = state.policy.name,
            verdict = result.verdict.name,
            reasons = result.reasons,
            action = action.name,
            risk = state.risk.reasons,
            judge = state.judgeName,
            judgeMs = state.judgeMs,
            target = TARGET_NODE.takeIf { toNode },
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
        const val TARGET_CLOUD = "cloud"
        private const val META_TARGET = "target"

        /** Ahead of the compaction plugin's `agent/pre-step` (priority 0): [prepare] runs first. */
        private const val AHEAD_OF_COMPACTION = 100

        /**
         * Whether "Ask" may send an offered answer on to the chat's model under the chat's [policy]
         * (its own, else Settings): to the user's own node ([owned]) always; to a cloud model
         * ([cloudRoute]; one that cannot be told counts as one) unless "Never"
         * ([CascadePolicy.allowsAsk]); never to "This phone" or a loopback server, where there is
         * nothing to ask. One rule for [escalate], an asked follow-up's requests ([refusal]) and
         * both apps' offer row.
         */
        fun canAsk(policy: CascadePolicy, owned: Boolean, cloudRoute: Boolean): Boolean = (owned || cloudRoute) && policy.allowsAsk(owned)

        /**
         * Why a request of a turn this cascade handles must not go to a model under the chat's
         * [policy] as it is now, or null when it may. [owned]: that model is the user's node (null:
         * that cannot be told, and the rules treat it as a cloud one); [cloudRoute]: it is a cloud
         * one. The node is always allowed: what goes there goes marked private. A follow-up
         * ([trigger]) goes where [canAsk] allows, and one sent on its own (`auto`) to a cloud model
         * only where "Auto" sends the answer it follows ([origin]) there on its own. Any other
         * request of the turn (the turn the phone cannot take, a compaction summary) never goes to a
         * cloud model under "Never". Whatever happened since a follow-up was queued.
         */
        private fun refusal(trigger: String?, origin: ReceiptCascade?, policy: CascadePolicy, owned: Boolean?, cloudRoute: Boolean): String? {
            if (owned == true) return null
            val never = if (owned == null) CascadeRoutes.NOT_SENT_UNKNOWN else CascadeRoutes.NOT_SENT_NEVER
            return when {
                trigger == null -> never.takeIf { policy == CascadePolicy.NEVER && cloudRoute }
                !cloudRoute -> CascadeRoutes.NOT_SENT_LOCAL
                !canAsk(policy, owned = false, cloudRoute = true) -> never
                trigger == TRIGGER_AUTO && !sendsOnItsOwn(policy, origin) -> CascadeRoutes.NOT_SENT_AUTO
                else -> null
            }
        }

        /** Whether [policy]'s rules for a cloud model send the phone's judged answer [origin] on, on their own. */
        private fun sendsOnItsOwn(policy: CascadePolicy, origin: ReceiptCascade?): Boolean {
            val verdict = CascadeVerdict.entries.firstOrNull { it.name == origin?.verdict } ?: return false
            return CascadeRoutes.action(policy, verdict, CascadeRisk(origin?.risk.orEmpty()), owned = false) == CascadeAction.ESCALATED
        }

        /** A request stopped by [refusal]: it is not made, and the turn ends with [message] (or a compaction fails). */
        private class CascadeRefused(message: String) : IllegalStateException(message)

        /** The context message that opens a follow-up, if [input] (a turn's) has one. */
        private fun escalationMarker(input: List<SessionEvent.UserMessage>): SessionEvent.UserMessage? =
            input.lastOrNull { it.kind == UserMessageKind.CONTEXT && it.contextId == CASCADE_ESCALATION }

        /**
         * The context message that opens a follow-up: [trigger] is its text, and where it goes (the
         * chat's model when it is queued: [TARGET_NODE] or [TARGET_CLOUD]) is app data the model
         * never sees, for the notice above the follow-up ([escalationTarget]).
         */
        private fun escalation(trigger: String, node: Boolean) = SessionEvent.UserMessage(
            trigger, UserMessageKind.CONTEXT, CASCADE_ESCALATION,
            meta = buildJsonObject { put(META_TARGET, if (node) TARGET_NODE else TARGET_CLOUD) },
        )

        /**
         * Where the follow-up that [marker] opens goes: [TARGET_NODE] or [TARGET_CLOUD]; null for a
         * marker written before it said (then the phone's receipt's target stands in).
         */
        fun escalationTarget(marker: SessionEvent.UserMessage): String? = (marker.meta?.get(META_TARGET) as? JsonPrimitive)?.contentOrNull

        /** The phone's offered answer still waiting for "Ask": the latest turn's, when the judge offered the chat's model. */
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
