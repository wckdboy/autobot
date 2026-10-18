package dev.wckdboy.autobot.shared.redaction

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.approval.PrivateTerm
import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.network.Loopback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The redaction gateway (ROADMAP Phase 2.5, item 2). Before a request goes to a cloud provider,
 * personal data in everything it carries (the system prompt, the user's messages, the model's
 * earlier answers and reasoning, tool calls and results) is replaced with stable placeholders
 * ([RedactionVault]); the streamed answer, its reasoning and its tool-call arguments get the real
 * values back on the device, so the log, the screen and the tools only ever see real values.
 *
 * What counts as personal: [PiiDetector]'s rules and name heuristics, plus the user's own memory
 * facts from the taint index ([TaintTracker.privateTerms], sources in [memoryLabels]).
 *
 * Applies to requests whose provider [cloud] says leaves the phone (never "This phone" or a
 * loopback server), when the chat's own choice ([dev.wckdboy.autobot.agent.core.loop.AgentConfig.redaction])
 * or else [enabled] (the app setting) says so. Each turn's receipt counts what was hidden, by
 * kind ([dev.wckdboy.autobot.agent.core.session.SessionEvent.TurnReceipt.redacted]); the values
 * never leave the vault.
 */
class RedactionGateway(
    private val cloud: suspend (providerId: String) -> Boolean,
    private val enabled: () -> Boolean,
    private val memoryLabels: Set<String> = setOf("memory"),
) {
    private val lock = Lock()
    private val vaults = HashMap<String, RedactionVault>()
    private val agents = HashMap<String, Agent>()

    /** Placeholders sent per (root session, turn); present (maybe empty) once the gateway ran in that turn. */
    private val turns = HashMap<Pair<String, Int>, MutableMap<String, PiiKind>>()

    /** The vault of [sessionId]'s chat (subagents share their parent's). */
    fun vault(sessionId: String): RedactionVault = lock.withLock { vaults.getOrPut(TaskTool.rootId(sessionId)) { RedactionVault() } }

    /**
     * The chat override for a user who switches redaction [enabled] in a chat: null (follow the
     * app setting) when that is what the setting says anyway.
     */
    fun override(enabled: Boolean): Boolean? = if (enabled == enabled()) null else enabled

    /** Whether requests of [agent]'s chat are redacted (when they go to a cloud provider). */
    fun isOn(agent: Agent?): Boolean = agent?.config?.value?.redaction ?: enabled()

    /**
     * [call] as it may leave the phone, with the vault that restores its answer; null when the
     * gateway does not apply (on-device or loopback provider, switched off, no session).
     */
    suspend fun prepare(call: LlmCall): Pair<LlmCall, RedactionVault>? {
        val sessionId = call.sessionId ?: return null
        val root = TaskTool.rootId(sessionId)
        val agent = lock.withLock { agents[root] }
        if (!isOn(agent) || !cloud(call.config.provider)) return null
        val vault = vault(root)
        // The user's memory facts, from the same index taint tracking keeps (brought up to date first).
        agent?.ctx?.get(TaintTracker.Key)?.let { tracker ->
            tracker.ingestAll(agent)
            vault.addTerms(tracker.privateTerms(root) { it in memoryLabels }.mapNotNull(::term))
        }
        val used = LinkedHashSet<String>()
        fun text(t: String) = vault.redact(t).also { used += it.placeholders }.text
        val messages = call.messages.map { m ->
            m.copy(
                text = text(m.text),
                reasoning = m.reasoning?.let(::text),
                toolCalls = m.toolCalls.map { tc -> tc.copy(arguments = vault.redactJson(tc.arguments).also { used += it.placeholders }.text) },
            )
        }
        val noted = if (used.isEmpty()) messages else withNote(messages)
        TurnTrace.current()?.let { trace ->
            lock.withLock {
                if (turns.size > MAX_TURNS) turns.clear()
                val seen = turns.getOrPut(trace.sessionId to trace.turn) { HashMap() }
                used.forEach { p -> vault.kindOf(p)?.let { seen[p] = it } }
            }
        }
        return call.copy(messages = noted) to vault
    }

    /** What the gateway hid in [sessionId]'s [turn], by kind tag; null when it did not run then. Forgets the turn. */
    fun takeCounts(sessionId: String, turn: Int): Map<String, Int>? = lock.withLock {
        val seen = turns.remove(TaskTool.rootId(sessionId) to turn) ?: return@withLock null
        PiiKind.entries.mapNotNull { kind -> seen.values.count { it == kind }.takeIf { it > 0 }?.let { kind.tag to it } }.toMap()
    }

    val plugin: Plugin = plugin("redaction-gateway") { ctx ->
        ctx.on(Hooks.AgentCreated) { agent -> lock.withLock { agents[agent.id] = agent } }
        ctx.on(Hooks.AgentDisposed) { agent ->
            if (!TaskTool.isSubagent(agent.id)) lock.withLock {
                if (agents[agent.id] === agent) agents.remove(agent.id)
                vaults.remove(agent.id)
            }
        }
        ctx.intercept(Hooks.LlmStream) { call, next ->
            val (redacted, vault) = prepare(call) ?: return@intercept next(call)
            next(redacted).restoring(vault)
        }
        ctx.intercept(Hooks.TurnReceipt) { input, next ->
            val receipt = next(input)
            val counts = takeCounts(input.agent.id, input.turn) ?: return@intercept receipt
            receipt.copy(redacted = counts)
        }
    }

    companion object {
        private const val MAX_TURNS = 256

        /** Tells the model what the placeholders are, so it keeps them verbatim (also in tool arguments). */
        const val NOTE =
            "Personal details in this chat were replaced on the user's device with placeholders such as [PERSON_1] or [EMAIL_1]. " +
                "Use them exactly as written, also in tool arguments; the device puts the real values back. Do not ask for the real values."

        /**
         * Whether a provider's requests leave hardware the user owns: not "This phone", not a
         * loopback server, not a paired node (which applies the same redaction before anything
         * of its own goes further).
         */
        fun isCloud(kind: ProviderKind, baseUrl: String): Boolean =
            kind != ProviderKind.LOCAL && kind != ProviderKind.INVINCIBLE_NODE && !Loopback.isLoopbackUrl(baseUrl)

        /** Programming words a memory note may capitalise; never hidden as memory facts. */
        private val TECH = setOf(
            "kotlin", "swift", "java", "python", "rust", "javascript", "typescript", "android", "ios", "linux", "windows",
            "macos", "docker", "postgres", "postgresql", "sqlite", "react", "compose", "swiftui", "gradle", "xcode", "github",
            "gitlab", "markdown", "json", "english", "german", "french", "spanish", "italian",
        )

        /** A taint-index term as a vault candidate, or null when it should stay visible. */
        internal fun term(t: PrivateTerm): Pair<PiiKind, String>? = when (t.kind) {
            PrivateTerm.Kind.EMAIL -> PiiKind.EMAIL to t.text
            PrivateTerm.Kind.NUMBER -> {
                val digits = t.text.filter { it.isDigit() }
                (if (digits.length in 13..19 && Luhn.valid(digits)) PiiKind.CARD else PiiKind.PRIVATE) to t.text
            }
            PrivateTerm.Kind.ID -> PiiKind.PRIVATE to t.text
            PrivateTerm.Kind.NAME -> {
                val lower = t.text.lowercase()
                when {
                    lower in TECH || lower in PiiDetector.NOT_NAMES -> null
                    lower in PiiDetector.FIRST_NAMES -> PiiKind.PERSON to t.text
                    else -> PiiKind.PRIVATE to t.text
                }
            }
        }

        /** [messages] with [NOTE] at the end of the system prompt (or as one). */
        private fun withNote(messages: List<ModelMessage>): List<ModelMessage> {
            val i = messages.indexOfFirst { it.role == ModelRole.SYSTEM }
            if (i < 0) return listOf(ModelMessage(ModelRole.SYSTEM, NOTE)) + messages
            return messages.toMutableList().also { it[i] = it[i].copy(text = it[i].text.trimEnd() + "\n\n" + NOTE) }
        }
    }
}

/**
 * The stream with placeholders restored: text and reasoning as they stream (a placeholder split
 * across chunks is held back until complete), tool-call arguments per call with JSON-escaped
 * values. What is held back goes out before the finish chunk, or when the stream ends.
 */
fun Flow<StreamChunk>.restoring(vault: RedactionVault): Flow<StreamChunk> = flow {
    val text = PlaceholderStream(vault)
    val reasoning = PlaceholderStream(vault)
    val calls = LinkedHashMap<Int, PlaceholderStream>()
    suspend fun flushAll() {
        reasoning.flush().takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.ReasoningDelta(it)) }
        text.flush().takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.TextDelta(it)) }
        for ((index, stream) in calls) stream.flush().takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.ToolCallDelta(index, null, null, it)) }
    }
    collect { chunk ->
        when (chunk) {
            is StreamChunk.TextDelta -> text.push(chunk.text).takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.TextDelta(it)) }
            is StreamChunk.ReasoningDelta -> reasoning.push(chunk.text).takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.ReasoningDelta(it)) }
            // Always forwarded: the first fragment of a call carries its id and name.
            is StreamChunk.ToolCallDelta ->
                emit(chunk.copy(argumentsDelta = calls.getOrPut(chunk.index) { PlaceholderStream(vault, json = true) }.push(chunk.argumentsDelta)))
            is StreamChunk.Finish -> {
                flushAll()
                emit(chunk)
            }
            else -> emit(chunk)
        }
    }
    flushAll()
}
