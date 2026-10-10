package dev.wckdboy.autobot.agent.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceContexts
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.model.MessageRole
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.providers.remote.ProviderFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    clients: HttpClientFactory,
    contributions: Set<@JvmSuppressWildcards AgentPluginProvider>,
) {
    val workspace = Workspace(File(context.filesDir, WORKSPACE_DIR))
    val harness = Harness()
    private val contexts = WorkspaceContexts(workspace)

    private val mutex = Mutex()
    private val agents = HashMap<String, Agent>()
    private val watchers = HashMap<String, Job>()

    private val _interactions = MutableStateFlow<List<Interaction>>(emptyList())
    val interactions: StateFlow<List<Interaction>> = _interactions.asStateFlow()

    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Sessions with a turn in progress (for list indicators). */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    init {
        harness.llm.setResolver { id ->
            providers.getProvider(id)?.let { ProviderLlmAdapter(it.id, providerFactory.create(it), it.kind, attachments) }
        }
        val root = harness.root
        root.plugin(toolsPlugin("workspace-files", *FileTools(workspace).all.toTypedArray()))
        root.plugin(toolsPlugin("web", WebFetchTool(clients)))
        root.plugin(contexts.plugin)
        contributions.forEach { root.plugin(it.plugin()) }

        root.intercept(Hooks.ApprovalRequest) { request, _ ->
            await(
                Interaction.Approval(
                    request.agent.id, request.callId, request.toolName, request.summary, request.reason, CompletableDeferred(),
                ),
            )
        }
        root.intercept(Hooks.UserQuestion) { request, _ ->
            await(Interaction.Question(request.agent.id, request.callId, request.question, CompletableDeferred()))
        }
    }

    private suspend fun <T> await(interaction: Interaction): T {
        _interactions.update { it + interaction }
        try {
            @Suppress("UNCHECKED_CAST")
            return when (interaction) {
                is Interaction.Approval -> interaction.answer.await() as T
                is Interaction.Question -> interaction.answer.await() as T
            }
        } finally {
            _interactions.update { list -> list.filterNot { it === interaction } }
        }
    }

    fun answerApproval(callId: String, outcome: ApprovalOutcome) {
        _interactions.value.filterIsInstance<Interaction.Approval>().firstOrNull { it.callId == callId }?.answer?.complete(outcome)
    }

    fun answerQuestion(callId: String, answers: List<String>?) {
        _interactions.value.filterIsInstance<Interaction.Question>().firstOrNull { it.callId == callId }?.answer?.complete(answers)
    }

    /** The agent for [conversationId], opening (and seeding legacy history into) its log on first use. */
    suspend fun agent(conversationId: String): Agent = mutex.withLock {
        agents[conversationId]?.let { return it }
        val conversation = conversations.getConversation(conversationId)
        val providerId = conversation?.providerId ?: providers.firstProviderId()
        val provider = providerId?.let { providers.getProvider(it) }
        val model = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel.orEmpty()
        val agent = harness.openAgent(
            sessionId = conversationId,
            store = store,
            config = AgentConfig(LlmCallConfig(provider = providerId.orEmpty(), model = model)),
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
        agent
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
            val first = prompt.lineSequence().first().ifBlank { "Image" }
            val title = if (c.title == ConversationRepository.DEFAULT_TITLE) first.take(TITLE_LENGTH) else c.title
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
    fun compact(conversationId: String) {
        scope.launch {
            val agent = agent(conversationId)
            agent.log.append(SessionEvent.CommandRun("compact"))
            agent.ctx[Compactor.Key]?.compact(agent, "manual")
        }
    }

    /** Stops and forgets the agent (before deleting its conversation). */
    suspend fun discard(conversationId: String) {
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
        store.clearIncognito()
    }

    companion object {
        const val WORKSPACE_DIR = "workspace"
        private const val TITLE_LENGTH = 48
    }
}
