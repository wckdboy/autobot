package dev.wckdboy.autobot.agent.runtime

import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.agent.core.Disposable
import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.policy.quotaFallbackPlugin
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.McpServerRepository
import dev.wckdboy.autobot.core.data.MemoryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.model.MessageRole
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.WebSearchRepository
import dev.wckdboy.autobot.core.data.toSpec
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.network.OkHttpTransport
import dev.wckdboy.autobot.providers.remote.ChatRoutes
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ProviderFactory
import dev.wckdboy.autobot.providers.remote.RouteTarget
import dev.wckdboy.autobot.shared.chats.ChatNames
import dev.wckdboy.autobot.providers.remote.vault.OnDeviceVault
import dev.wckdboy.autobot.shared.mcp.McpHub
import dev.wckdboy.autobot.shared.mcp.McpServerStatus
import dev.wckdboy.autobot.shared.memory.memoryPlugin
import dev.wckdboy.autobot.shared.search.SearchProvider
import dev.wckdboy.autobot.shared.search.webSearchPlugin
import dev.wckdboy.autobot.shared.session.ReceiptProvider
import dev.wckdboy.autobot.shared.session.receiptsPlugin
import dev.wckdboy.autobot.shared.vault.vaultPlugin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toOkioPath

/** Lets other modules (image generation, …) add plugins to the harness via Hilt multibinding. */
fun interface AgentPluginProvider {
    fun plugin(): Plugin
}

/** Something the agent is waiting on the user for. */
sealed interface Interaction {
    val sessionId: String
    val callId: String

    class Approval(
        override val sessionId: String,
        override val callId: String,
        val toolName: String,
        val summary: String,
        val reason: String?,
        internal val answer: CompletableDeferred<ApprovalOutcome>,
        /** Private fragments the call would send (taint tracking). */
        val privateData: List<String> = emptyList(),
    ) : Interaction

    class Question(
        override val sessionId: String,
        override val callId: String,
        val question: UserQuestion,
        internal val answer: CompletableDeferred<List<String>?>,
    ) : Interaction
}

/**
 * App-wide owner of the agent [Harness]: one cached [Agent] per conversation, driven on the
 * application scope so turns survive navigation. Approvals and questions are surfaced through
 * [interactions] and fail closed if the turn is cancelled.
 */
@Singleton
class AgentHost @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val providers: ProviderRepository,
    private val providerFactory: ProviderFactory,
    private val conversations: ConversationRepository,
    private val store: RoutingSessionStore,
    private val attachments: AttachmentRepository,
    mcpServers: McpServerRepository,
    memory: MemoryRepository,
    vault: OnDeviceVault,
    settings: SettingsRepository,
    search: WebSearchRepository,
    clients: HttpClientFactory,
    audit: AuditLog,
    networkPolicy: NetworkPolicy,
    contributions: Set<@JvmSuppressWildcards AgentPluginProvider>,
) {
    val workspace = Workspace(File(context.filesDir, WORKSPACE_DIR).toOkioPath())

    /** Undo for agents: copies of what file tools replaced, next to (not inside) the workspace. */
    val journal = WorkspaceJournal(workspace, File(context.filesDir, JOURNAL_DIR).toOkioPath())
    val harness = Harness()
    private val contexts = WorkspaceContexts(workspace)

    /** MCP servers: tools of enabled servers join every session at its next turn. */
    private val mcp = McpHub(OkHttpTransport(clients), { id -> mcpServers.token(id)?.reveal() })

    /** Connection state per configured MCP server. */
    val mcpStatus: StateFlow<List<McpServerStatus>> get() = mcp.status

    /**
     * App lifecycle hook: `false` when the app goes to the background (MCP list-change streams
     * close and stop reconnecting), `true` when it returns (they are re-listed at the next turn).
     * Call it from a process-lifecycle observer (ON_STOP / ON_START).
     */
    fun setForeground(foreground: Boolean) = mcp.setForeground(foreground)

    /** Attachments are decrypted into short-lived files for a single model call. */
    private val images = object : ImageAttachments {
        override suspend fun openPlain(id: String): String? = attachments.openPlain(id)?.path

        override fun discard(path: String) {
            File(path).delete()
        }
    }

    private val mutex = Mutex()

    /** Open agents, least recently used first; idle ones beyond [MAX_OPEN_AGENTS] are closed. */
    private val agents = LinkedHashMap<String, Agent>(16, 0.75f, true)
    private val watchers = HashMap<String, Job>()

    /** Conversations on screen (see [retain]): their agents are never evicted. */
    private val retained = HashMap<String, Int>()

    private val board = InteractionBoard()
    val interactions: StateFlow<List<Interaction>> get() = board.items

    /** One adapter per provider, shared by every step and agent until the provider, its models or the route change. */
    private val adapters = AdapterCache { id ->
        providers.getProvider(id)?.let {
            ProviderLlmAdapter(
                it.id, providerFactory.create(it), it.kind, images, it.preset()::echoesReasoning, providers.modelCache(it.id),
                helperModel = it.helperModel?.takeIf { m -> m.isNotBlank() },
            )
        }
    }

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Sessions with a turn in progress (for list indicators). */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    init {
        // MCP list-change streams close while the app is in the background (battery).
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = setForeground(true)
                override fun onStop(owner: LifecycleOwner) = setForeground(false)
            })
        }
        harness.llm.setResolver { id -> adapters.get(id) }
        // A cached adapter's client is bound to the route at build time (the kill switch rejects
        // it after a change), its key and settings to the provider row, its context windows to
        // the model list: any change rebuilds on the next step.
        scope.launch {
            combine(
                providers.observeProviders(),
                providers.observeModelCaches(),
                networkPolicy.mode,
                networkPolicy.socksFallback,
                networkPolicy.allowLoopbackWhenOffline,
            ) { _, _, _, _, _ -> Unit }.collect { adapters.clear() }
        }
        val root = harness.root
        // "On limit, fall back" (Settings, off by default): helper model, else the on-device one.
        val quotaFallback = settings.settings.map { it.quotaFallback }.stateIn(scope, SharingStarted.Eagerly, false)
        root.plugin(
            quotaFallbackPlugin(
                enabled = { quotaFallback.value },
                onDevice = {
                    providers.observeProviders().first().firstOrNull { it.kind == ProviderKind.LOCAL && it.defaultModel.isNotBlank() }
                        ?.let { LlmCallConfig(it.id, it.defaultModel) }
                },
                eligible = { cfg -> providers.getProvider(cfg.provider)?.let { ModelDataPolicies.autoPickable(it.preset().id, cfg.model) } ?: true },
            ),
        )
        // Each turn's privacy receipt: the traffic the audit log tied to it, models and policies.
        root.plugin(
            receiptsPlugin(audit) { id ->
                providers.getProvider(id)?.let { ReceiptProvider(it.displayName, it.kind == ProviderKind.LOCAL, it.preset().id, it.preset().privacyNote) }
            },
        )
        root.plugin(toolsPlugin("workspace-files", *FileTools(workspace, journal).all.toTypedArray()))
        root.plugin(toolsPlugin("web", WebFetchTool(OkHttpWebFetcher(clients))))
        root.plugin(contexts.plugin)
        root.plugin(mcp.plugin)
        contributions.forEach { root.plugin(it.plugin()) }

        // Opt-in features follow their settings: memory while switched on, web search while a
        // provider is chosen. Each plugin brings and takes away its tools and contexts.
        val searchTransport = OkHttpTransport(clients)
        toggle(settings.settings.map { it.memoryEnabled }) { memoryPlugin(memory.memory) }
        toggle(settings.settings.map { it.vaultEnabled }) { vaultPlugin(vault.vault) }
        toggle(settings.settings.map { it.searchProvider != SearchProvider.NONE }) { webSearchPlugin(searchTransport) { search.config() } }

        root.intercept(Hooks.ApprovalRequest) { request, _ ->
            board.await(
                Interaction.Approval(
                    request.agent.id, request.callId, request.toolName, request.summary, request.reason, CompletableDeferred(),
                    request.privateData,
                ),
            )
        }
        root.intercept(Hooks.UserQuestion) { request, _ ->
            board.await(Interaction.Question(request.agent.id, request.callId, request.question, CompletableDeferred()))
        }
        scope.launch { mcpServers.observeServers().collect { list -> mcp.update(list.map { it.toSpec() }) } }
        // A chat whose provider was deleted (or that had none yet) moves to the first provider.
        // Only a change that can move a chat scans them (not every edit of a provider row).
        scope.launch {
            var previous: List<RouteTarget>? = null
            providers.observeProviders()
                .map { list -> list.map { RouteTarget(it.id, it.defaultModel) } }
                .distinctUntilChanged()
                .collect { targets ->
                    if (RepointGate.needsScan(previous, targets)) repoint(targets)
                    previous = targets
                }
        }
    }

    /** Re-points every conversation (and its open agent) whose provider is not in [targets] (shared `ChatRoutes`). */
    private suspend fun repoint(targets: List<RouteTarget>) {
        for (c in conversations.observeConversations().first()) {
            val route = ChatRoutes.repoint(c.providerId, c.model, targets) ?: continue
            conversations.updateConversation(c.copy(providerId = route.providerId, model = route.model))
            mutex.withLock { agents[c.id] }?.updateConfig { it.copy(llm = it.llm.copy(provider = route.providerId, model = route.model)) }
        }
    }

    private fun toggle(enabled: Flow<Boolean>, plugin: () -> Plugin) {
        scope.launch {
            var handle: Disposable? = null
            enabled.distinctUntilChanged().collect { on ->
                handle?.dispose()
                handle = if (on) harness.root.plugin(plugin()) else null
            }
        }
    }

    /** Answers [conversationId]'s approval for [callId] (call ids repeat across chats). */
    fun answerApproval(conversationId: String, callId: String, outcome: ApprovalOutcome) {
        board.answerApproval(conversationId, callId, outcome)
    }

    /** Answers [conversationId]'s question [callId]; null answers skip it. */
    fun answerQuestion(conversationId: String, callId: String, answers: List<String>?) {
        board.answerQuestion(conversationId, callId, answers)
    }

    /** The agent for [conversationId], opening (and seeding legacy history into) its log on first use. */
    suspend fun agent(conversationId: String): Agent = mutex.withLock {
        agents[conversationId]?.let { return it }
        val conversation = conversations.getConversation(conversationId)
        val stored = conversation?.providerId?.let { providers.getProvider(it) }
        val provider = stored ?: providers.firstProviderId()?.let { providers.getProvider(it) }
        val model = conversation?.model?.takeIf { it.isNotBlank() && stored != null } ?: provider?.defaultModel.orEmpty()
        val agent = harness.openAgent(
            sessionId = conversationId,
            store = store,
            config = AgentConfig(LlmCallConfig(provider = provider?.id.orEmpty(), model = model)),
            scope = scope,
        )
        if (agent.log.entries.value.isEmpty()) seedLegacyMessages(agent)
        agent.log.latest<SessionEvent.PermissionPresetChanged>()?.let { change ->
            PermissionPreset.fromLabel(change.preset)?.let { agent.setPermission(it) }
        }
        agent.log.latest<SessionEvent.AgentSettingsChanged>()?.let { s -> agent.updateConfig { it.copy(toolsEnabled = s.toolsEnabled) } }
        watchers[conversationId] = scope.launch {
            agent.status.collect { status ->
                _running.update { if (status == AgentStatus.RUNNING) it + conversationId else it - conversationId }
            }
        }
        agents[conversationId] = agent
        evictIdle()
        agent
    }

    /** Keeps [conversationId]'s agent open while a screen shows it; pair with [release]. */
    fun retain(conversationId: String) = synchronized(retained) {
        retained[conversationId] = (retained[conversationId] ?: 0) + 1
    }

    fun release(conversationId: String) = synchronized(retained) {
        val n = (retained[conversationId] ?: return@synchronized) - 1
        if (n <= 0) retained.remove(conversationId) else retained[conversationId] = n
    }

    /** Closes the least recently used agents that are idle, unwatched and not waiting on the user. */
    private fun evictIdle() {
        val waiting = board.items.value.mapTo(HashSet()) { it.sessionId }
        val pinned = synchronized(retained) { retained.keys.toSet() }
        val idle = AgentEviction.evictable(
            openLru = agents.keys.toList(),
            busy = agents.filterValues { it.status.value != AgentStatus.IDLE }.keys + waiting + pinned,
            max = MAX_OPEN_AGENTS,
        )
        for (id in idle) {
            val agent = agents.remove(id) ?: continue
            watchers.remove(id)?.cancel()
            scope.launch { agent.dispose() }
        }
    }

    /** Phase-1 conversations stored plain messages; replay them as log events once. */
    private suspend fun seedLegacyMessages(agent: Agent) {
        val legacy = conversations.getMessages(agent.id)
        if (legacy.isEmpty()) return
        agent.log.appendAll(
            legacy.mapNotNull { m ->
                when (m.role) {
                    MessageRole.USER -> SessionEvent.UserMessage(m.content)
                    MessageRole.ASSISTANT -> SessionEvent.AssistantMessage(0, 0, m.content, m.reasoning)
                    MessageRole.SYSTEM -> null
                }
            },
        )
    }

    /**
     * Sends a prompt (with optional attached [images], attachment ids): a new turn, or queued
     * behind the running one. Titles new conversations.
     */
    suspend fun send(conversationId: String, text: String, images: List<String> = emptyList()) {
        val prompt = text.trim()
        if (prompt.isEmpty() && images.isEmpty()) return
        val agent = agent(conversationId)
        conversations.getConversation(conversationId)?.let { c ->
            // Only an untitled chat is named after its prompt; a renamed one keeps its name.
            val title = if (c.title == ConversationRepository.DEFAULT_TITLE) ChatNames.fromPrompt(prompt) else c.title
            conversations.updateConversation(c.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
        agent.followup(prompt, images)
    }

    /** Switches provider/model for the conversation and its agent. */
    suspend fun setRoute(conversationId: String, providerId: String, model: String) {
        val conversation = conversations.getConversation(conversationId) ?: return
        conversations.updateConversation(conversation.copy(providerId = providerId, model = model))
        agent(conversationId).updateConfig { it.copy(llm = it.llm.copy(provider = providerId, model = model)) }
    }

    suspend fun setToolsEnabled(conversationId: String, enabled: Boolean) {
        val agent = agent(conversationId)
        if (agent.config.value.toolsEnabled == enabled) return
        agent.updateConfig { it.copy(toolsEnabled = enabled) }
        agent.log.append(SessionEvent.AgentSettingsChanged(enabled))
    }

    suspend fun setPermission(conversationId: String, preset: PermissionPreset) = agent(conversationId).setPermission(preset)

    /** Manual `/compact`. */
    fun compact(conversationId: String, onError: (Throwable) -> Unit = {}) {
        scope.launch {
            try {
                val agent = agent(conversationId)
                agent.log.append(SessionEvent.CommandRun("compact"))
                agent.ctx[Compactor.Key]?.compact(agent, "manual")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    /** Undoes one file step of [conversationId] (and later changes to the same files); idle sessions only. */
    suspend fun undoStep(conversationId: String, callId: String) = journal.undoStep(agent(conversationId), callId)

    /** Undoes every file change of [turn]. */
    suspend fun undoTurn(conversationId: String, turn: Int) = journal.undoTurn(agent(conversationId), turn)

    /** Re-applies what an undo ([revertId]) put back. */
    suspend fun redo(conversationId: String, revertId: String) = journal.redo(agent(conversationId), revertId)

    /** Cancels [conversationId]'s running turn, if its agent is open (it stays open). */
    suspend fun cancel(conversationId: String) {
        mutex.withLock { agents[conversationId] }?.cancel()
    }

    /** Stops and forgets the agent (before deleting its conversation). */
    suspend fun discard(conversationId: String) {
        journal.drop(conversationId)
        val agent = mutex.withLock {
            watchers.remove(conversationId)?.cancel()
            agents.remove(conversationId)
        } ?: return
        _running.update { it - conversationId }
        agent.dispose()
    }

    /** Panic wipe: stop everything and drop in-memory logs. */
    suspend fun shutdown() {
        val all = mutex.withLock {
            watchers.values.forEach { it.cancel() }
            watchers.clear()
            agents.values.toList().also { agents.clear() }
        }
        _running.value = emptySet()
        all.forEach { runCatching { it.dispose() } }
        adapters.clear()
        runCatching { mcp.close() }
        store.clearIncognito()
    }

    companion object {
        const val WORKSPACE_DIR = "workspace"
        const val JOURNAL_DIR = "workspace-undo"

        /** Open agents kept beyond the ones running or on screen (each holds its whole log in memory). */
        const val MAX_OPEN_AGENTS = 4
    }
}
