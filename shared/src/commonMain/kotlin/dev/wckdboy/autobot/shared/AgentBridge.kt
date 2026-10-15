package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.loop.LiveStream
import dev.wckdboy.autobot.agent.core.monotonicNanos
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.policy.quotaFallbackPlugin
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.tools.ToolExecutor
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.ImageAttachments
import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter
import dev.wckdboy.autobot.agent.runtime.WebFetchTool
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.RemoteModel
import dev.wckdboy.autobot.shared.mcp.McpHub
import dev.wckdboy.autobot.shared.mcp.McpServerSpec
import dev.wckdboy.autobot.shared.mcp.McpServerStatus
import dev.wckdboy.autobot.shared.memory.BridgedMemoryStore
import dev.wckdboy.autobot.shared.memory.Memory
import dev.wckdboy.autobot.shared.memory.MemoryStoreBridge
import dev.wckdboy.autobot.shared.memory.memoryPlugin
import dev.wckdboy.autobot.shared.vault.VaultHost
import dev.wckdboy.autobot.shared.vault.vaultPlugin
import dev.wckdboy.autobot.shared.search.WebSearchSource
import dev.wckdboy.autobot.shared.search.webSearchPlugin
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.shared.session.ReceiptProvider
import dev.wckdboy.autobot.shared.session.SessionProjection
import dev.wckdboy.autobot.shared.session.receiptsPlugin
import dev.wckdboy.autobot.shared.session.projectSession
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath

/**
 * A configured chat provider as the host stores it. [route] is `INHERIT`, `DIRECT`, `TOR` or
 * `SOCKS5`; [presetId] a `ProviderPresets` id (null for rows saved before presets were stored);
 * [modelsJson] its cached model list ([ModelCache.encode]), whose context windows the agent uses;
 * [helperModel] the model for subagents and summaries (null: the chat's own model).
 */
class ProviderSpec(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String?,
    val defaultModel: String,
    val route: String = "INHERIT",
    val presetId: String? = null,
    val modelsJson: String? = null,
    val helperModel: String? = null,
) {
    val preset: ProviderPreset get() = ProviderPresets.resolve(presetId, kind, baseUrl)
}

/** The host's provider list (decrypting API keys on demand). */
interface ProviderDirectory {
    fun provider(id: String): ProviderSpec?
}

/**
 * Encrypted persistence of session logs. Each entry is one JSON line (a `LogEntry`); the host
 * must keep append order. Called on a background thread.
 */
interface SessionStoreBridge {
    fun load(sessionId: String): List<String>
    fun append(sessionId: String, entries: List<String>)
}

/** Decrypted image attachments for one model call (see [ImageAttachments]). */
interface ImageStoreBridge {
    fun openPlain(id: String): String?
    fun discard(path: String)
}

/** Receives a session's UI state as JSON ([SessionState]), throttled, on a background thread. */
fun interface SessionListener {
    fun onState(json: String)
}

/** The host's MCP bearer tokens (Keychain), read on a background thread when a server connects. */
fun interface McpTokenSource {
    fun token(serverId: String): String?
}

/** Receives the MCP servers' connection state. */
fun interface McpStatusListener {
    fun onStatus(servers: List<McpServerStatus>)
}

/** Receives the ids of sessions with a turn in progress. */
fun interface RunningListener {
    fun onRunning(sessionIds: List<String>)
}

@Serializable
class LiveState(val text: String, val reasoning: String, val toolNames: List<String>, val tokensPerSecond: Float?)

@Serializable
class ApprovalState(
    val callId: String,
    val toolName: String,
    val summary: String,
    val reason: String?,
    /** Private fragments the call would send (taint tracking); the card highlights them. */
    val privateData: List<String> = emptyList(),
)

@Serializable
class QuestionState(val callId: String, val question: String, val options: List<String>, val multiSelect: Boolean)

/** Everything a chat screen renders, computed by the same code as on Android. */
@Serializable
class SessionState(
    val sessionId: String,
    val projection: SessionProjection,
    val live: LiveState?,
    val running: Boolean,
    val approvals: List<ApprovalState>,
    val question: QuestionState?,
    val toolsEnabled: Boolean,
    val permission: String,
    val provider: String,
    val model: String,
)

/**
 * The agent runtime for hosts that are not Kotlin (iOS): the same [Harness], plugins and tools
 * that Android's `AgentHost` wires up, behind plain callbacks and JSON strings. All methods
 * return immediately; work runs on the bridge's own coroutine scope.
 *
 * [audit] is the host's audit log (the one its [transport] records into): each turn's privacy
 * receipt lists the traffic it tied to that turn. Null leaves the receipts' network part unknown.
 *
 * [journalDir] holds the undo journal (copies of files the agent replaced); keep it next to the
 * workspace with the same file protection. Null turns undo off.
 */
class AgentBridge(
    workspaceDir: String,
    private val providers: ProviderDirectory,
    private val transport: HttpTransport,
    private val local: LocalEngine,
    store: SessionStoreBridge,
    images: ImageStoreBridge?,
    mcpTokens: McpTokenSource,
    audit: AuditLog? = null,
    journalDir: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val workspace = Workspace(workspaceDir.toPath())
    private val journal = journalDir?.let { WorkspaceJournal(workspace, it.toPath()) }
    val harness = Harness()
    private val contexts = WorkspaceContexts(workspace)
    private val sessions = BridgedSessionStore(store)
    private val mcp = McpHub(transport, { id -> mcpTokens.token(id) })
    private val attachments = images?.let { bridge ->
        object : ImageAttachments {
            override suspend fun openPlain(id: String): String? = withContext(IoDispatcher) { bridge.openPlain(id) }

            override fun discard(path: String) = bridge.discard(path)
        }
    }

    private val mutex = Mutex()
    /** Agents as they are created, for calls that arrive while [open] is still creating one. */
    private val created = MutableStateFlow<Map<String, Agent>>(emptyMap())
    private val agents = HashMap<String, Agent>()
    private val watchers = HashMap<String, Job>()
    private val running = MutableStateFlow<Set<String>>(emptySet())
    private val interactions = MutableStateFlow<List<Pending>>(emptyList())

    private sealed interface Pending {
        val sessionId: String
        val callId: String

        class Approval(
            override val sessionId: String,
            override val callId: String,
            val state: ApprovalState,
            val answer: CompletableDeferred<ApprovalOutcome>,
        ) : Pending

        class Question(
            override val sessionId: String,
            override val callId: String,
            val question: UserQuestion,
            val answer: CompletableDeferred<List<String>?>,
        ) : Pending
    }

    init {
        harness.llm.setResolver { id ->
            providers.provider(id)?.let {
                ProviderLlmAdapter(
                    it.id, chatProvider(it), it.kind, attachments, it.preset::echoesReasoning, ModelCache.decode(it.modelsJson),
                    helperModel = it.helperModel?.takeIf { m -> m.isNotBlank() },
                )
            }
        }
        val root = harness.root
        root.plugin(
            quotaFallbackPlugin(
                enabled = { quotaFallback.enabled },
                onDevice = { quotaFallback.onDevice },
                eligible = { cfg -> providers.provider(cfg.provider)?.let { ModelDataPolicies.autoPickable(it.preset.id, cfg.model) } ?: true },
            ),
        )
        root.plugin(
            receiptsPlugin(audit) { id ->
                withContext(IoDispatcher) { providers.provider(id) }?.let {
                    ReceiptProvider(it.displayName, it.kind == ProviderKind.LOCAL, it.preset.id, it.preset.privacyNote)
                }
            },
        )
        root.plugin(toolsPlugin("workspace-files", *FileTools(workspace, journal).all.toTypedArray()))
        root.plugin(toolsPlugin("web", WebFetchTool(TransportWebFetcher(transport))))
        root.plugin(contexts.plugin)
        root.plugin(mcp.plugin)
        root.intercept(Hooks.ApprovalRequest) { request, _ ->
            val pending = Pending.Approval(
                request.agent.id,
                request.callId,
                ApprovalState(request.callId, request.toolName, request.summary, request.reason, request.privateData),
                CompletableDeferred(),
            )
            await(pending) { pending.answer.await() }
        }
        root.intercept(Hooks.UserQuestion) { request, _ ->
            val pending = Pending.Question(request.agent.id, request.callId, request.question, CompletableDeferred())
            await(pending) { pending.answer.await() }
        }
    }

    private suspend fun <T> await(pending: Pending, answer: suspend () -> T): T {
        interactions.update { it + pending }
        try {
            return answer()
        } finally {
            interactions.update { list -> list.filterNot { it === pending } }
        }
    }

    /** The chat client for a stored provider: the on-device engine or the host's HTTP transport. */
    fun chatProvider(spec: ProviderSpec): ChatProvider = when (spec.kind) {
        ProviderKind.LOCAL -> LocalEngineChatProvider(local)
        else -> TransportChatProvider(spec.baseUrl, spec.apiKey, transport, spec.displayName, spec.route, spec.preset)
    }

    /**
     * Providers screen "test connection": [callback] gets success, a summary (model count and the
     * first ids, or the error) and, on success, the model list to cache ([ModelCache] JSON).
     */
    fun testConnection(spec: ProviderSpec, callback: (Boolean, String, String?) -> Unit) {
        scope.launch {
            when (val result = chatProvider(spec).testConnection()) {
                is ConnectionTestResult.Success -> callback(true, result.summary(), cache(result.models, spec))
                is ConnectionTestResult.Failure -> callback(false, result.message, null)
            }
        }
    }

    /** The configured MCP servers; sessions pick up enabled servers' tools at their next turn. */
    fun setMcpServers(servers: List<McpServerSpec>) = mcp.update(servers)

    fun observeMcpStatus(listener: McpStatusListener): Cancellable {
        val job = scope.launch { mcp.status.collect { listener.onStatus(it) } }
        return Cancellable { job.cancel() }
    }

    /** MCP "test connection" (initialize + tools/list); [callback] gets the tool count or an error message. */
    fun testMcpServer(spec: McpServerSpec, token: String?, callback: (Boolean, String) -> Unit) {
        scope.launch {
            McpHub.test(transport, spec, token)
                .onSuccess { callback(true, it.toString()) }
                .onFailure { callback(false, McpHub.describe(it)) }
        }
    }

    /** Fetches the provider's chat models; [callback] gets the [ModelCache] JSON or an error message. */
    fun listModels(spec: ProviderSpec, callback: (String?, String?) -> Unit) {
        scope.launch {
            when (val result = chatProvider(spec).listModels()) {
                is ModelListResult.Success -> callback(cache(result.models, spec), null)
                is ModelListResult.Failure -> callback(null, result.message)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun cache(models: List<RemoteModel>, spec: ProviderSpec): String? =
        models.takeIf { it.isNotEmpty() && spec.kind != ProviderKind.LOCAL }?.let { ModelCache(Clock.System.now().toEpochMilliseconds(), it).encode() }

    private suspend fun agent(sessionId: String, providerId: String, model: String): Agent = mutex.withLock {
        agents[sessionId]?.let { return it }
        val agent = harness.openAgent(
            sessionId = sessionId,
            store = sessions,
            config = AgentConfig(LlmCallConfig(provider = providerId, model = model)),
            scope = scope,
        )
        agent.log.latest<SessionEvent.PermissionPresetChanged>()?.let { change ->
            PermissionPreset.fromLabel(change.preset)?.let { agent.setPermission(it) }
        }
        agent.log.latest<SessionEvent.AgentSettingsChanged>()?.let { s -> agent.updateConfig { it.copy(toolsEnabled = s.toolsEnabled) } }
        watchers[sessionId] = scope.launch {
            agent.status.collect { status ->
                running.update { if (status == AgentStatus.RUNNING) it + sessionId else it - sessionId }
            }
        }
        agents[sessionId] = agent
        created.update { it + (sessionId to agent) }
        agent
    }

    /**
     * Opens [sessionId] (with [providerId] / [model] for a new session) and streams its
     * [SessionState] to [listener] until the returned handle is cancelled.
     */
    fun open(sessionId: String, providerId: String, model: String, listener: SessionListener): Cancellable {
        val job = scope.launch {
            val a = agent(sessionId, providerId, model)
            combine(
                combine(a.log.entries, a.runningTools, interactions) { e, r, i -> Triple(e, r, i) },
                a.live,
                a.status,
                a.config,
                a.permission,
            ) { (entries, runningTools, pending), live, status, config, permission ->
                val mine = pending.filter { it.sessionId == sessionId }
                val approvals = mine.filterIsInstance<Pending.Approval>().map { it.state }
                val question = mine.filterIsInstance<Pending.Question>().firstOrNull()?.let {
                    QuestionState(it.callId, it.question.question, it.question.options, it.question.multiSelect)
                }
                val projection = projectSession(entries, approvals.map { it.callId }.toSet(), runningTools) { name, args -> describe(a, name, args) }
                SessionState(
                    sessionId = sessionId,
                    projection = projection,
                    live = live?.toState(),
                    running = status == AgentStatus.RUNNING,
                    approvals = approvals,
                    question = question,
                    toolsEnabled = config.toolsEnabled,
                    permission = permission.label,
                    provider = config.llm.provider,
                    model = config.llm.model,
                )
            }.conflate().collect { state ->
                listener.onState(BridgeJson.encodeToString(SessionState.serializer(), state))
                delay(FRAME_MS)
            }
        }
        return Cancellable { job.cancel() }
    }

    /** Sessions with a turn in progress, for list indicators. */
    fun observeRunning(listener: RunningListener): Cancellable {
        val job = scope.launch { running.collect { listener.onRunning(it.toList()) } }
        return Cancellable { job.cancel() }
    }

    /** Sends a prompt (with optional attached image ids): a new turn, or queued behind the running one. */
    fun send(sessionId: String, text: String, images: List<String>) {
        val prompt = text.trim()
        if (prompt.isEmpty() && images.isEmpty()) return
        withAgent(sessionId) { it.followup(prompt, images) }
    }

    /** Delivers [text] at the next step boundary of the running turn. */
    fun steer(sessionId: String, text: String) = withAgent(sessionId) { it.steer(text) }

    fun cancel(sessionId: String) = withAgent(sessionId) { it.cancel() }

    fun answerApproval(callId: String, outcome: ApprovalOutcome) {
        interactions.value.filterIsInstance<Pending.Approval>().firstOrNull { it.callId == callId }?.answer?.complete(outcome)
    }

    fun answerQuestion(callId: String, answers: List<String>?) {
        interactions.value.filterIsInstance<Pending.Question>().firstOrNull { it.callId == callId }?.answer?.complete(answers)
    }

    fun setRoute(sessionId: String, providerId: String, model: String) =
        withAgent(sessionId) { a -> a.updateConfig { it.copy(llm = it.llm.copy(provider = providerId, model = model)) } }

    fun setToolsEnabled(sessionId: String, enabled: Boolean) = withAgent(sessionId) { a ->
        if (a.config.value.toolsEnabled != enabled) {
            a.updateConfig { it.copy(toolsEnabled = enabled) }
            a.log.append(SessionEvent.AgentSettingsChanged(enabled))
        }
    }

    fun setPermission(sessionId: String, preset: PermissionPreset) = withAgent(sessionId) { it.setPermission(preset) }

    /** Manual `/compact`. */
    fun compact(sessionId: String) = withAgent(sessionId) { a ->
        a.log.append(SessionEvent.CommandRun("compact"))
        a.ctx[Compactor.Key]?.compact(a, "manual")
    }

    /** Undoes one file step (and later changes to the same files); only while the session is idle. */
    fun undoStep(sessionId: String, callId: String) = withAgent(sessionId) { a -> journal?.undoStep(a, callId) }

    /** Undoes every file change of [turn]. */
    fun undoTurn(sessionId: String, turn: Int) = withAgent(sessionId) { a -> journal?.undoTurn(a, turn) }

    /** Re-applies what an undo ([revertId]) put back. */
    fun redo(sessionId: String, revertId: String) = withAgent(sessionId) { a -> journal?.redo(a, revertId) }

    /** Stops and forgets the agent (before deleting its conversation). */
    fun discard(sessionId: String) {
        journal?.drop(sessionId)
        scope.launch {
            val agent = mutex.withLock {
                watchers.remove(sessionId)?.cancel()
                created.update { it - sessionId }
                agents.remove(sessionId)
            } ?: return@launch
            running.update { it - sessionId }
            agent.dispose()
        }
    }

    /** Panic wipe: stop every agent and the bridge itself. */
    fun shutdown(done: () -> Unit) {
        scope.launch {
            val all = mutex.withLock {
                watchers.values.forEach { it.cancel() }
                watchers.clear()
                agents.values.toList().also { agents.clear() }
            }
            all.forEach { runCatching { it.dispose() } }
            runCatching { mcp.close() }
            done()
            scope.cancel()
        }
    }

    /**
     * Adds a host-implemented tool (e.g. iOS `generate_image`) to every session, behind the same
     * permission gate as the built-in tools. Registering a name again replaces the tool.
     */
    fun registerTool(tool: HostTool) {
        harness.root.plugin(toolsPlugin("host:${tool.name}", HostToolDefinition(tool)))
    }

    private var memoryHandle: Disposable? = null
    private var vaultHandle: Disposable? = null
    private var searchHandle: Disposable? = null

    private class QuotaFallback(val enabled: Boolean, val onDevice: LlmCallConfig?)

    @kotlin.concurrent.Volatile
    private var quotaFallback = QuotaFallback(false, null)

    /**
     * "On limit, fall back" (off by default): when a provider's usage limit is used up, a turn is
     * retried once on the provider's helper model, else on the on-device model
     * ([onDeviceProviderId] / [onDeviceModel], null when none is installed), with a notice.
     */
    fun setQuotaFallback(enabled: Boolean, onDeviceProviderId: String?, onDeviceModel: String?) {
        val onDevice = if (onDeviceProviderId.isNullOrBlank() || onDeviceModel.isNullOrBlank()) null else LlmCallConfig(onDeviceProviderId, onDeviceModel)
        quotaFallback = QuotaFallback(enabled, onDevice)
    }

    /**
     * Local memory on ([store] = the host's encrypted notes) or off (null): the memory tools and
     * the recall context come and go with it, in every session.
     */
    fun setMemory(store: MemoryStoreBridge?) {
        memoryHandle?.dispose()
        memoryHandle = store?.let { harness.root.plugin(memoryPlugin(Memory(BridgedMemoryStore(it)))) }
    }

    /**
     * Private vault on ([host] = the host's encrypted vault and embedding engine) or off (null):
     * `vault_search` and the vault context come and go with it, in every session.
     */
    fun setVault(host: VaultHost?) {
        vaultHandle?.dispose()
        vaultHandle = host?.let { harness.root.plugin(vaultPlugin(it.vault)) }
    }

    /** `web_search` on (the host's current search settings, read per call) or off (null). */
    fun setWebSearch(source: WebSearchSource?) {
        searchHandle?.dispose()
        searchHandle = source?.let { s -> harness.root.plugin(webSearchPlugin(transport) { withContext(IoDispatcher) { s.config() } }) }
    }

    /** Skills found in the workspace, as `name: description` lines. */
    fun skills(): List<String> = contexts.skills().map { "${it.name}: ${it.description}" }

    /**
     * Runs [block] on the session's agent. A call made right after [open] (e.g. "chat" turning
     * tools off) waits for the agent [open] is still creating instead of being dropped.
     */
    private fun withAgent(sessionId: String, block: suspend (Agent) -> Unit) {
        scope.launch {
            val agent = mutex.withLock { agents[sessionId] }
                ?: withTimeoutOrNull(OPEN_WAIT_MS) { created.map { it[sessionId] }.filterNotNull().first() }
                ?: return@launch
            block(agent)
        }
    }

    private fun describe(agent: Agent, name: String, arguments: String): String {
        val tool = agent.ctx[ToolRegistry.Key]?.get(name) ?: return arguments.take(80)
        val args = ToolExecutor.parseArgs(arguments) ?: return arguments.take(80)
        return runCatching { tool.describeCall(args) }.getOrDefault("")
    }

    private fun LiveStream.toState(): LiveState {
        val seconds = (monotonicNanos() - startedAtNanos) / 1e9f
        val approxTokens = (text.length + reasoning.length) / 4f
        return LiveState(
            text = text,
            reasoning = reasoning,
            toolNames = toolCalls.mapNotNull { it.name },
            tokensPerSecond = if (seconds > 0.5f && approxTokens > 0) approxTokens / seconds else null,
        )
    }

    private companion object {
        const val OPEN_WAIT_MS = 10_000L
        /** UI frames are coalesced to at most ~30 per second while streaming. */
        const val FRAME_MS = 33L
        val BridgeJson = Json { encodeDefaults = true }
    }
}

/** [SessionStore] over the host's [SessionStoreBridge]; unreadable lines are skipped. */
internal class BridgedSessionStore(private val bridge: SessionStoreBridge) : SessionStore {
    override suspend fun load(sessionId: String): List<LogEntry> = withContext(IoDispatcher) {
        bridge.load(sessionId).mapNotNull { line ->
            try {
                SessionJson.decodeFromString(LogEntry.serializer(), line)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) = withContext(IoDispatcher) {
        bridge.append(sessionId, entries.map { SessionJson.encodeToString(LogEntry.serializer(), it) })
    }
}
