package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.resume

/** The single result of one [HostTool.execute]; call [onResult] exactly once. */
interface HostToolSink {
    /** [metaJson] is a JSON object or null (e.g. `{"gallery":["<id>"]}` so the transcript shows images). */
    fun onResult(content: String, isError: Boolean, code: String?, metaJson: String?)
}

/**
 * A tool the host app implements (iOS: `generate_image` on Core ML / PC backends), run inside the
 * shared agent loop like any built-in tool: same permission gate, approvals, timeouts and log.
 * Register with [AgentBridge.registerTool].
 */
interface HostTool {
    val name: String
    val description: String
    /** JSON Schema of the arguments (an object schema, as `schema { … }` builds it). */
    val parametersJson: String
    /** A [ToolKind] name (`READ`, `GENERATE`, …); unknown names count as `OTHER`. */
    val kind: String
    /** Milliseconds before the call times out; 0 for the harness default. */
    val timeoutMs: Long

    /** One-line summary for tool cards and approval prompts. */
    fun describe(argsJson: String): String

    /** Starts the call; the returned handle cancels it (the turn was stopped). */
    fun execute(callId: String, argsJson: String, sink: HostToolSink): Cancellable
}

/** Wraps a [HostTool] as a harness [ToolDefinition]. */
internal class HostToolDefinition(private val host: HostTool) : ToolDefinition {
    override val name: String = host.name
    override val description: String = host.description
    override val parameters: JsonObject = Json.parseToJsonElement(host.parametersJson).jsonObject
    override val kind: ToolKind = ToolKind.entries.firstOrNull { it.name == host.kind } ?: ToolKind.OTHER
    override val timeoutMs: Long? = host.timeoutMs.takeIf { it > 0 }

    override fun describeCall(args: JsonObject): String = runCatching { host.describe(args.toString()) }.getOrDefault("")

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = suspendCancellableCoroutine { cont ->
        val call = host.execute(
            run.callId,
            args.toString(),
            object : HostToolSink {
                override fun onResult(content: String, isError: Boolean, code: String?, metaJson: String?) {
                    val meta = metaJson?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    if (cont.isActive) cont.resume(ToolOutput(content, isError, code, meta))
                }
            },
        )
        cont.invokeOnCancellation { call.cancel() }
    }
}
