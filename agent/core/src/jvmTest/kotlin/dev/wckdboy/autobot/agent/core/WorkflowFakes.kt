package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.llm.LlmException
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject

/*
 * Fakes for end-to-end agent workflow tests: a scripted model provider that streams chunks and
 * tool calls, configurable tools, and an agent rig on the test scheduler (virtual time, no I/O).
 */

/** One scripted model response: emits chunks for the request (or throws, or hangs). */
internal typealias Reply = suspend FlowCollector<StreamChunk>.(LlmCall) -> Unit

/**
 * A fake provider. Each request is answered by [route] (default: the next scripted [replies], in
 * order). Every request is recorded in [calls].
 */
internal class FakeModel(
    override val provider: String = "fake",
    private val helper: String? = null,
    private val contextWindow: Int = 100_000,
    private val maxTokens: Int? = null,
    replies: List<Reply> = emptyList(),
    private val route: (suspend FlowCollector<StreamChunk>.(LlmCall, Int) -> Unit)? = null,
) : LlmAdapter {
    private val script = ArrayDeque(replies)
    val calls: MutableList<LlmCall> = Collections.synchronizedList(mutableListOf())
    override val helperModel: String? get() = helper

    override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
        val n = synchronized(calls) { calls += call; calls.size }
        // A runaway loop fails the test instead of spinning virtual time forever.
        check(n <= MAX_CALLS) { "runaway: more than $MAX_CALLS requests" }
        val r = route
        if (r != null) {
            r(call, n)
        } else {
            val next = synchronized(script) { script.removeFirstOrNull() } ?: error("Unexpected request #$n")
            next(call)
        }
    }

    override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = contextWindow, defaultMaxTokens = maxTokens)

    val remaining: Int get() = synchronized(script) { script.size }

    companion object {
        const val MAX_CALLS = 500
    }
}

internal fun say(text: String, finish: String = "stop", usage: TokenUsage? = null): Reply = {
    emit(StreamChunk.TextDelta(text))
    usage?.let { emit(StreamChunk.Usage(it)) }
    emit(StreamChunk.Finish(finish))
}

/** Tool calls `(id, name, arguments)` in one message, each streamed in two fragments. */
internal fun calls(vararg calls: Triple<String?, String, String>): Reply = {
    calls.forEachIndexed { i, (id, name, args) ->
        val cut = args.length / 2
        emit(StreamChunk.ToolCallDelta(i, id, name, args.substring(0, cut)))
        emit(StreamChunk.ToolCallDelta(i, null, null, args.substring(cut)))
    }
    emit(StreamChunk.Finish("tool_calls"))
}

internal fun call(id: String, name: String, args: String = "{}"): Reply = calls(Triple(id, name, args))

internal fun chunks(vararg chunks: StreamChunk): Reply = { chunks.forEach { emit(it) } }

internal fun fail(code: LlmErrorCode, message: String = code.name.lowercase()): Reply = {
    throw LlmException(LlmFailure(code, message))
}

/** Streams [chunks], then hangs until cancelled (a request in flight). */
internal fun hang(vararg chunks: StreamChunk): Reply = {
    chunks.forEach { emit(it) }
    awaitCancellation()
}

internal val LlmCall.isSubagent: Boolean get() = messages.any { it.role == ModelRole.SYSTEM && "You are a subagent" in it.text }
internal val LlmCall.toolResults get() = messages.filter { it.role == ModelRole.TOOL }
internal val LlmCall.isSummary: Boolean get() = messages.lastOrNull()?.text?.startsWith("Your context is about to be compacted") == true

/** A configurable tool; [body] runs per call. Records arguments and concurrency. */
internal class FakeTool(
    override val name: String,
    override val kind: ToolKind = ToolKind.READ,
    private val safe: Boolean = true,
    override val timeoutMs: Long? = null,
    override val privateSource: PrivateSource? = null,
    override val parameters: JsonObject = schema { string("text", "text") },
    private val body: suspend (JsonObject, ToolRunContext) -> ToolOutput = { args, _ -> ToolOutput("$name:${args.string("text").orEmpty()}") },
) : ToolDefinition {
    override val description = "fake $name"
    val runs: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    val started = AtomicInteger()
    val finished = AtomicInteger()
    private val running = AtomicInteger()
    val maxConcurrent = AtomicInteger()

    override fun isConcurrencySafe(args: JsonObject) = safe

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        runs += args
        started.incrementAndGet()
        maxConcurrent.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            return body(args, run).also { finished.incrementAndGet() }
        } finally {
            running.decrementAndGet()
        }
    }
}

/** An agent on the test scheduler, in its own scope, with leak checks. */
internal class Rig(
    private val test: TestScope,
    val model: FakeModel,
    vararg tools: ToolDefinition,
    persona: String? = null,
) {
    val harness = Harness(persona).also { h ->
        h.llm.register(model)
        tools.forEach { h.tools.register(it) }
    }
    val job = Job()
    val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + job)

    suspend fun open(
        sessionId: String = "s1",
        permission: PermissionPreset = PermissionPreset.WORKSPACE,
        policy: ApprovalPolicy = ApprovalPolicy.ASK,
        store: SessionStore = InMemorySessionStore(),
        config: AgentConfig = AgentConfig(LlmCallConfig(model.provider, "main-model")),
    ): Agent = harness.openAgent(sessionId, store, config, scope, permission, policy, clock = { test.testScheduler.currentTime })

    /** Coroutines still active under the rig's scope (the drivers of open agents included). */
    fun active(): Int = count(job)

    private fun count(job: Job): Int = job.children.sumOf { (if (it.isActive) 1 else 0) + count(it) }
}

internal fun Agent.events(): List<SessionEvent> = log.entries.value.map { it.event }

internal inline fun <reified T : SessionEvent> Agent.all(): List<T> = events().filterIsInstance<T>()

internal fun Agent.end(): SessionEvent.TurnEnd = all<SessionEvent.TurnEnd>().last()

internal fun Agent.results(): List<SessionEvent.ToolResult> = all()

internal fun JsonObject.text(): String? = string("text")
