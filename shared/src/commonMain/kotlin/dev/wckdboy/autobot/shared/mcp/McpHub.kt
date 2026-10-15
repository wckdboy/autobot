package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.PreToolDecision
import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.ContextOrder
import dev.wckdboy.autobot.agent.core.prompt.PromptContext
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A configured MCP server as the host stores it. The bearer token is not part of it: the hub
 * asks the host's secret store for it when it connects. [route] is `INHERIT`, `DIRECT`, `TOR` or
 * `SOCKS5` (same rules as providers). [revision] changes on every save so edits (including a new
 * token) reconnect.
 */
data class McpServerSpec(
    val id: String,
    val name: String,
    val url: String,
    val route: String = "INHERIT",
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val revision: Long = 0,
)

enum class McpServerState { DISABLED, IDLE, READY, UNAVAILABLE }

/** What the settings list and the agent notice show for one server. */
data class McpServerStatus(
    val id: String,
    val name: String,
    val host: String,
    val state: McpServerState,
    val toolCount: Int,
    val message: String?,
    /** The detected revision (`2026-07-28`, `2025-11-25`, …) once connected. */
    val protocolVersion: String? = null,
)

/** Result of "test connection". */
class McpTestResult(val toolCount: Int, val protocolVersion: String) {
    override fun toString(): String = "$toolCount tools · MCP $protocolVersion"
}

/**
 * Connects the enabled MCP servers and keeps their tools registered in the harness. Nothing is
 * dialled until an agent turn with tools starts ([prepare], run from the `mcp-servers` prompt
 * context): unreachable, blocked (Offline) or misconfigured servers simply contribute no tools,
 * and the model is told why. A server that announced `tools/list_changed` is re-listed at the
 * next turn; a failed one is retried after [RETRY_MS], doubling up to [MAX_RETRY_MS]. Connecting
 * runs in the background: a turn waits for it at most [FIRST_WAIT_MS] (a server's first attempt)
 * or [RETRY_WAIT_MS] (a retry), a few hundred milliseconds, so an unreachable server never holds
 * up the conversation; the turn's notice says "not connected yet" and the tools join as soon as
 * the connection is up (at the next step).
 *
 * App lifecycle: the host calls [setForeground] (`false` when the app goes to the background,
 * `true` when it returns). In the background no `subscriptions/listen` stream stays open or
 * reconnects; servers that had one are re-listed at the next [prepare] after returning, so a list
 * change missed meanwhile is picked up.
 */
class McpHub(
    private val transport: HttpTransport,
    private val tokens: suspend (serverId: String) -> String?,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Lock()
    private var servers: List<McpServerSpec> = emptyList()
    private val links = HashMap<String, Link>()
    private val pending = HashMap<String, Deferred<Unit>>()
    /** Failed attempts per configuration (a saved change starts over). */
    private val failures = HashMap<McpServerSpec, Failures>()
    private var registry: ToolRegistry? = null

    @kotlin.concurrent.Volatile
    private var foreground = true

    private val _status = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val status: StateFlow<List<McpServerStatus>> = _status.asStateFlow()

    private class Link(val spec: McpServerSpec, val endpoint: McpEndpoint, val client: McpClient) {
        var tools: List<McpToolDefinition> = emptyList()
        var registrations: List<Disposable> = emptyList()
        var ready = false
        var error: String? = null
    }

    private class Failures(var count: Int, var at: Long)

    /** Replaces the configuration; changed, disabled and removed servers are disconnected. */
    fun update(list: List<McpServerSpec>) {
        val dropped = lock.withLock {
            servers = list
            failures.keys.retainAll(list.toSet())
            val wanted = list.filter { it.enabled }.associateBy { it.id }
            val gone = links.filter { (id, link) -> wanted[id] != link.spec }
            gone.forEach { (id, link) ->
                unregister(link)
                links.remove(id)
            }
            publish()
            gone.values.map { it.client }
        }
        dropped.forEach { client -> scope.launch { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { client.close() } } }
    }

    fun spec(id: String): McpServerSpec? = lock.withLock { servers.firstOrNull { it.id == id } }

    /**
     * App lifecycle hook (both apps call it): `false` stops every server's `subscriptions/listen`
     * stream (no background network, no reconnect loop); `true` allows them again, and each server
     * that was listening is re-listed (and resubscribed) at the next [prepare].
     */
    fun setForeground(foreground: Boolean) {
        val clients = lock.withLock {
            if (this.foreground == foreground) return
            this.foreground = foreground
            links.values.map { it.client }
        }
        clients.forEach { if (foreground) it.resumeListening() else it.pauseListening() }
    }

    val isForeground: Boolean get() = foreground

    /**
     * Starts connecting (or re-listing) every enabled server that is due, and waits for them a
     * bounded time: [FIRST_WAIT_MS] for a first attempt, [RETRY_WAIT_MS] for a server that failed
     * before. Whatever is still connecting finishes in the background; its tools register then.
     */
    suspend fun prepare() {
        val jobs = lock.withLock {
            servers.filter { it.enabled && due(it) }.map { spec ->
                val job = pending.getOrPut(spec.id) { scope.async { connect(spec) } }
                job to if (spec in failures) RETRY_WAIT_MS else FIRST_WAIT_MS
            }
        }
        if (jobs.isEmpty()) return
        coroutineScope { jobs.map { (job, wait) -> async { withTimeoutOrNull(wait) { job.join() } } }.awaitAll() }
    }

    /** Ends every session (DELETE) and stops the hub's work (panic wipe, shutdown). */
    suspend fun close() {
        val clients = lock.withLock {
            links.values.forEach(::unregister)
            links.values.map { it.client }.also {
                links.clear()
                pending.values.forEach { it.cancel() }
                pending.clear()
                publish()
            }
        }
        coroutineScope { clients.map { c -> async { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { c.close() } } }.awaitAll() }
        scope.cancel()
    }

    /**
     * Harness wiring: registers the remote tools, the approval gate (every call to an untrusted
     * server asks; a trusted server's calls follow the permission preset like `web_fetch`) and the
     * per-turn `mcp-servers`
     * context, which also triggers [prepare].
     */
    val plugin: Plugin = plugin("mcp", ToolRegistry.Key, SystemPromptService.Key) { ctx ->
        val prompts = ctx.require(SystemPromptService.Key)
        val tools = ctx.require(ToolRegistry.Key)
        ctx.effect { attach(tools) }
        ctx.effect {
            prompts.context(
                PromptContext("mcp-servers", ContextOrder.MCP_SERVERS) { agent ->
                    if (!agent.config.value.toolsEnabled) return@PromptContext null
                    prepare()
                    notice()
                },
            )
        }
        ctx.intercept(Hooks.ToolPreExecute, priority = GATE_PRIORITY) { input, next ->
            val tool = input.tool as? McpToolDefinition ?: return@intercept next(input)
            val spec = spec(tool.serverId)?.takeIf { it.enabled }
                ?: return@intercept PreToolDecision.Deny("the MCP server ${tool.serverName} is no longer enabled")
            val reason = "MCP · ${spec.name} · ${tool.host}"
            when {
                // Trusted: the preset decides as for any network tool; its prompt still names the server.
                spec.trusted -> when (val decision = next(input)) {
                    is PreToolDecision.Ask -> PreToolDecision.Ask(reason)
                    else -> decision
                }
                tool.name in input.agent.sessionGrants -> next(input)
                else -> PreToolDecision.Ask(reason)
            }
        }
    }

    /** The model-facing summary of enabled servers, or null when there are none. */
    fun notice(): String? {
        val list = status.value.filter { it.state != McpServerState.DISABLED }
        if (list.isEmpty()) return null
        val lines = list.map { s ->
            val slug = lock.withLock { slugs()[s.id] }
            when (s.state) {
                McpServerState.READY -> "- ${s.name} (${s.host}): ${s.toolCount} tools, named ${McpToolDefinition.PREFIX}${slug}__*"
                McpServerState.IDLE -> "- ${s.name} (${s.host}): not connected yet"
                else -> "- ${s.name} (${s.host}): unavailable — ${s.message ?: "unknown error"}. Its tools are absent."
            }
        }
        return "MCP servers (remote tools; every call goes over the network to that host and may need the user's approval):\n" +
            lines.joinToString("\n")
    }

    // ---------------------------------------------------------------------------------------

    private fun due(spec: McpServerSpec): Boolean {
        if (spec.id in pending) return true
        val link = links[spec.id]?.takeIf { it.spec == spec }
        if (link != null && link.ready) return link.client.toolsChanged
        val failed = failures[spec] ?: return true
        return clock() - failed.at >= retryDelay(failed.count)
    }

    private suspend fun connect(spec: McpServerSpec) {
        try {
            val endpoint = McpEndpoint.parse(spec.url)
            if (endpoint == null) {
                record(spec, null, Result.failure(McpException(McpErrorKind.INVALID_URL, "Invalid URL")))
                return
            }
            val existing = lock.withLock { links[spec.id]?.takeIf { it.spec == spec } }
            // The host's secret store can fail (e.g. locked): that is this server's failure, not the turn's.
            val link = existing ?: runCatchingMcp {
                Link(spec, endpoint, McpClient(endpoint, transport, withContext(IoDispatcher) { tokens(spec.id) }, spec.route, tag(spec), scope)).also {
                    if (!foreground) it.client.pauseListening()
                }
            }.getOrElse {
                record(spec, null, Result.failure(it))
                return
            }
            val result = runCatchingMcp {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    link.client.initialize()
                    link.client.listTools()
                } ?: throw McpException(McpErrorKind.NETWORK, "Timed out")
            }
            record(spec, link, result)
        } finally {
            lock.withLock { pending.remove(spec.id) }
        }
    }

    private fun record(spec: McpServerSpec, candidate: Link?, result: Result<List<McpTool>>) {
        val orphan = lock.withLock {
            if (servers.none { it == spec }) return@withLock candidate?.client
            if (result.isSuccess) {
                failures.remove(spec)
            } else {
                failures.getOrPut(spec) { Failures(0, 0) }.apply {
                    count++
                    at = clock()
                }
            }
            val link = candidate ?: run {
                links.remove(spec.id)?.let(::unregister)
                publish(failed = spec to (result.exceptionOrNull()?.let(::describe) ?: "unknown error"))
                return@withLock null
            }
            links[spec.id]?.takeIf { it !== link }?.let(::unregister)
            links[spec.id] = link
            unregister(link)
            result.onSuccess { remote ->
                link.ready = true
                link.error = null
                link.tools = definitions(link, remote)
                register(link)
            }.onFailure { e ->
                link.ready = false
                link.error = describe(e)
                link.tools = emptyList()
            }
            publish()
            null
        }
        orphan?.let { client -> scope.launch { withTimeoutOrNull(CLOSE_TIMEOUT_MS) { client.close() } } }
    }

    private fun definitions(link: Link, remote: List<McpTool>): List<McpToolDefinition> {
        val slug = slugs()[link.spec.id] ?: McpNames.serverSlug(link.spec.name, link.spec.id)
        val seen = HashSet<String>()
        return remote.mapNotNull { tool ->
            val name = McpNames.toolName(slug, tool.name)
            if (!seen.add(name)) return@mapNotNull null
            McpToolDefinition(
                serverId = link.spec.id,
                serverName = link.spec.name,
                host = link.endpoint.host,
                remoteName = tool.name,
                name = name,
                description = McpNames.description(link.spec.name, tool),
                parameters = McpNames.schema(tool.inputSchema),
                readOnlyHint = tool.readOnlyHint,
                client = link.client,
            )
        }
    }

    /** Tool-name segments per server id, unique across the configuration. */
    private fun slugs(): Map<String, String> {
        val used = HashSet<String>()
        return servers.associate { spec ->
            val base = McpNames.serverSlug(spec.name, spec.id)
            var slug = base
            var n = 2
            while (!used.add(slug)) slug = "${base}_${n++}"
            spec.id to slug
        }
    }

    private fun attach(tools: ToolRegistry): Disposable {
        lock.withLock {
            registry = tools
            links.values.forEach(::register)
        }
        return Disposable {
            lock.withLock {
                links.values.forEach(::unregister)
                if (registry === tools) registry = null
            }
        }
    }

    private fun register(link: Link) {
        val r = registry ?: return
        link.registrations = link.tools.map { r.register(it) }
    }

    private fun unregister(link: Link) {
        link.registrations.forEach { it.dispose() }
        link.registrations = emptyList()
    }

    private fun publish(failed: Pair<McpServerSpec, String>? = null) {
        _status.value = servers.map { spec ->
            val link = links[spec.id]?.takeIf { it.spec == spec }
            val host = McpEndpoint.hostOf(spec.url).orEmpty()
            when {
                !spec.enabled -> McpServerStatus(spec.id, spec.name, host, McpServerState.DISABLED, 0, "Disabled")
                failed?.first == spec -> McpServerStatus(spec.id, spec.name, host, McpServerState.UNAVAILABLE, 0, failed.second)
                link == null -> McpServerStatus(spec.id, spec.name, host, McpServerState.IDLE, 0, null)
                link.ready -> McpServerStatus(spec.id, spec.name, host, McpServerState.READY, link.tools.size, null, link.client.serverInfo?.protocolVersion)
                else -> McpServerStatus(spec.id, spec.name, host, McpServerState.UNAVAILABLE, 0, link.error)
            }
        }
    }

    private fun tag(spec: McpServerSpec) = "mcp:${spec.name}"

    companion object {
        const val RETRY_MS = 15_000L
        const val MAX_RETRY_MS = 10 * 60_000L

        /** How long a turn waits for a server's first connection attempt (it goes on in the background). */
        const val FIRST_WAIT_MS = 300L

        /** How long a turn waits for a server that failed before to come back. */
        const val RETRY_WAIT_MS = 300L

        /** Back-off after [count] failed attempts in a row. */
        fun retryDelay(count: Int): Long = (RETRY_MS shl (count - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_MS)
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val CLOSE_TIMEOUT_MS = 5_000L

        /** Outside the permission presets (priority 100), so untrusted servers always ask. */
        const val GATE_PRIORITY = 200

        fun describe(e: Throwable): String = when (e) {
            is McpException if e.kind == McpErrorKind.BLOCKED -> "blocked: ${e.message}"
            else -> e.message ?: "error"
        }

        /**
         * Settings "test connection": a fresh connection (era detection, discovery or initialize,
         * tools/list; no subscription), then DELETE for a legacy session. Returns the tool count
         * and detected revision, or the failure.
         */
        suspend fun test(transport: HttpTransport, spec: McpServerSpec, token: String?): Result<McpTestResult> {
            val endpoint = McpEndpoint.parse(spec.url) ?: return Result.failure(McpException(McpErrorKind.INVALID_URL, "Invalid URL"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = McpClient(endpoint, transport, token, spec.route, "mcp:${spec.name}", scope, subscribe = false)
            return try {
                runCatchingMcp {
                    withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        val info = client.initialize()
                        McpTestResult(client.listTools().size, info.protocolVersion)
                    } ?: throw McpException(McpErrorKind.NETWORK, "Timed out")
                }
            } finally {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { client.close() }
                scope.cancel()
            }
        }
    }
}

/** [runCatching] that lets cancellation through. */
internal inline fun <T> runCatchingMcp(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
