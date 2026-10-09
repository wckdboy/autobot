package dev.wckdboy.autobot.feature.chat.conversation

import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.loop.LiveStream
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.tools.ToolExecutor
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.agent.runtime.Interaction
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.network.RouteResolver
import dev.wckdboy.autobot.feature.chat.toPrivacyStatus
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.toOverride
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ProviderOption(
    val id: String,
    val name: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
)

@Immutable
data class LiveUi(val text: String, val reasoning: String, val toolNames: List<String>, val tokensPerSecond: Float?)

@Immutable
data class ApprovalUi(val callId: String, val toolName: String, val summary: String, val reason: String?)

@Immutable
data class QuestionUi(val callId: String, val question: UserQuestion)

@Immutable
data class ChatUiState(
    val title: String = "",
    val items: List<SessionItem> = emptyList(),
    val live: LiveUi? = null,
    val running: Boolean = false,
    val todos: List<TodoItem> = emptyList(),
    val approvals: Map<String, ApprovalUi> = emptyMap(),
    val question: QuestionUi? = null,
    val providers: List<ProviderOption> = emptyList(),
    val selectedProviderId: String? = null,
    val selectedProviderName: String? = null,
    val selectedModel: String? = null,
    val toolsEnabled: Boolean = true,
    val permission: PermissionPreset = PermissionPreset.WORKSPACE,
    val contextUsed: Int? = null,
    val contextWindow: Int? = null,
    val privacyStatus: PrivacyStatus = PrivacyStatus.OFFLINE,
    val showOfflineBanner: Boolean = false,
    val isIncognito: Boolean = false,
    val notice: String? = null,
    val loaded: Boolean = false,
)

/**
 * A conversation is an agent session: the transcript is projected from the agent's session log,
 * the live frame from its stream, and approvals/questions from the [AgentHost] bridge. Turns run
 * on the application scope, so leaving the screen does not stop the agent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val conversationId: String,
    private val host: AgentHost,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val gallery: GalleryRepository,
    networkPolicy: NetworkPolicy,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(conversationId: String): ChatViewModel
    }

    private val agent: Flow<Agent> = flow { emit(host.agent(conversationId)) }.shareIn(viewModelScope, SharingStarted.Eagerly, 1)
    private val notice = MutableStateFlow<String?>(null)
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()

    private val policy = combine(networkPolicy.mode, networkPolicy.allowLoopbackWhenOffline, networkPolicy.socksFallback) { m, l, s -> Triple(m, l, s) }

    private val routing = combine(conversations.observeConversation(conversationId), providers.observeProviders(), policy) { c, list, p -> Triple(c, list, p) }

    private val agentState = agent.flatMapLatest { a ->
        combine(
            combine(a.log.entries, a.runningTools, host.interactions) { e, r, i -> Triple(e, r, i) },
            a.live,
            a.status,
            a.config,
            a.permission,
        ) { (entries, running, interactions), live, status, config, permission ->
            val mine = interactions.filter { it.sessionId == conversationId }
            val approvals = mine.filterIsInstance<Interaction.Approval>().associate { it.callId to ApprovalUi(it.callId, it.toolName, it.summary, it.reason) }
            val question = mine.filterIsInstance<Interaction.Question>().firstOrNull()?.let { QuestionUi(it.callId, it.question) }
            val projection = projectSession(entries, approvals.keys, running) { name, args -> describe(a, name, args) }
            AgentSnapshot(projection, live?.toUi(), status == AgentStatus.RUNNING, approvals, question, config.toolsEnabled, permission)
        }
    }

    private data class AgentSnapshot(
        val projection: SessionProjection,
        val live: LiveUi?,
        val running: Boolean,
        val approvals: Map<String, ApprovalUi>,
        val question: QuestionUi?,
        val toolsEnabled: Boolean,
        val permission: PermissionPreset,
    )

    val uiState: StateFlow<ChatUiState> = combine(agentState, routing, notice) { snap, (conversation, providerList, p), n ->
        val (mode, allowLoopback, socks) = p
        val provider = providerList.firstOrNull { it.id == conversation?.providerId } ?: providerList.firstOrNull()
        val effective = provider?.let { RouteResolver.effectiveMode(mode, it.routing.toOverride(), socks) } ?: mode
        val loopback = provider != null && Loopback.isLoopbackUrl(provider.baseUrl)
        val usage = snap.projection.lastUsage
        ChatUiState(
            title = conversation?.title.orEmpty(),
            items = snap.projection.items,
            live = snap.live,
            running = snap.running,
            todos = snap.projection.todos,
            approvals = snap.approvals,
            question = snap.question,
            providers = providerList.map { it.toOption() },
            selectedProviderId = provider?.id,
            selectedProviderName = provider?.displayName,
            selectedModel = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel,
            toolsEnabled = snap.toolsEnabled,
            permission = snap.permission,
            contextUsed = usage?.let { (it.inputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.outputTokens ?: 0) },
            contextWindow = snap.projection.contextWindow,
            privacyStatus = if (loopback) PrivacyStatus.OFFLINE else effective.toPrivacyStatus(),
            showOfflineBanner = provider != null && mode == NetworkMode.Offline && !(loopback && allowLoopback),
            isIncognito = conversations.isIncognito(conversationId),
            notice = n,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isIncognito = conversations.isIncognito(conversationId)))

    private fun describe(agent: Agent, name: String, arguments: String): String {
        val tool = agent.ctx[dev.wckdboy.autobot.agent.core.tools.ToolRegistry.Key]?.get(name) ?: return arguments.take(80)
        val args = ToolExecutor.parseArgs(arguments) ?: return arguments.take(80)
        return runCatching { tool.describeCall(args) }.getOrDefault("")
    }

    private fun LiveStream.toUi(): LiveUi {
        val seconds = (System.nanoTime() - startedAtNanos) / 1e9f
        val approxTokens = (text.length + reasoning.length) / 4f
        return LiveUi(
            text = text,
            reasoning = reasoning,
            toolNames = toolCalls.mapNotNull { it.name },
            tokensPerSecond = if (seconds > 0.5f && approxTokens > 0) approxTokens / seconds else null,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Intents

    /** Sends a prompt or runs a slash command. */
    fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        if (input.startsWith("/") && runCommand(input)) return
        viewModelScope.launch { host.send(conversationId, input) }
    }

    private fun runCommand(input: String): Boolean {
        val parts = input.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        when (parts[0].lowercase()) {
            "compact" -> host.compact(conversationId)
            "tools" -> setToolsEnabled(arg != "off")
            "permission", "perm" -> {
                val preset = PermissionPreset.fromLabel(arg) ?: PermissionPreset.entries.firstOrNull { it.name.equals(arg, true) }
                if (preset == null) notice.value = "usage: /permission read-only | workspace-write | full-access" else setPermission(preset)
            }
            "model" -> if (arg.isNotEmpty()) selectModel(arg) else notice.value = "usage: /model <id>"
            "stop" -> stop()
            else -> return false
        }
        return true
    }

    fun stop() {
        viewModelScope.launch { host.agent(conversationId).cancel() }
    }

    fun approve(callId: String, outcome: ApprovalOutcome) = host.answerApproval(callId, outcome)

    fun answer(callId: String, answers: List<String>?) = host.answerQuestion(callId, answers)

    fun selectProvider(providerId: String) {
        viewModelScope.launch {
            val provider = providers.getProvider(providerId) ?: return@launch
            host.setRoute(conversationId, provider.id, provider.defaultModel)
        }
    }

    fun selectModel(model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val providerId = uiState.value.selectedProviderId ?: providers.firstProviderId() ?: return@launch
            host.setRoute(conversationId, providerId, trimmed)
        }
    }

    fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launch { host.setToolsEnabled(conversationId, enabled) }
    }

    fun setPermission(preset: PermissionPreset) {
        viewModelScope.launch { host.setPermission(conversationId, preset) }
    }

    fun compact() = host.compact(conversationId)

    fun dismissNotice() = notice.update { null }

    /** Decrypted gallery thumbnail for tool cards (cached for the screen's lifetime). */
    suspend fun thumbnail(galleryId: String): ImageBitmap? {
        thumbs[galleryId]?.let { return it }
        val bytes = gallery.thumbnail(galleryId) ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { thumbs[galleryId] = it }
    }

    private fun Provider.toOption() = ProviderOption(
        id = id,
        name = displayName,
        defaultModel = defaultModel,
        suggestedModels = (listOf(defaultModel) + ProviderPresets.forKind(kind).suggestedModels).filter { it.isNotBlank() }.distinct(),
    )
}
