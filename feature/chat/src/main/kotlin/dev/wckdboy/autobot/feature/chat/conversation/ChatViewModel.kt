package dev.wckdboy.autobot.feature.chat.conversation

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.providers.remote.ThinkingDefaults
import dev.wckdboy.autobot.providers.remote.ThinkingSupport
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.loop.LiveStream
import dev.wckdboy.autobot.agent.core.monotonicNanos
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.tools.ToolExecutor
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.agent.runtime.ChatLibrary
import dev.wckdboy.autobot.agent.runtime.Interaction
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.markdown.LiteMarkdown
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.SystemModel
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.network.RouteResolver
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import dev.wckdboy.autobot.engine.speech.SAMPLE_RATE
import dev.wckdboy.autobot.feature.chat.toPrivacyStatus
import dev.wckdboy.autobot.feature.chat.voice.LiveDictation
import dev.wckdboy.autobot.feature.chat.voice.LocalDictationEngine
import dev.wckdboy.autobot.feature.chat.voice.SpeakerUnavailable
import dev.wckdboy.autobot.feature.chat.voice.Speakers
import dev.wckdboy.autobot.feature.chat.voice.SpeechQueue
import dev.wckdboy.autobot.feature.chat.voice.read
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ModelsApi
import dev.wckdboy.autobot.providers.remote.ProviderFactory
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.RemoteModel
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.providers.remote.toOverride
import dev.wckdboy.autobot.shared.session.ChangeGroup
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.SessionProjection
import dev.wckdboy.autobot.shared.session.SessionProjector
import dev.wckdboy.autobot.shared.session.projectSession
import dev.wckdboy.autobot.shared.voice.BargeIn
import dev.wckdboy.autobot.shared.voice.DictationText
import dev.wckdboy.autobot.shared.voice.EndOfSpeech
import dev.wckdboy.autobot.shared.voice.ReadAloudStream
import dev.wckdboy.autobot.shared.voice.SpeechVerdict
import dev.wckdboy.autobot.shared.voice.VoiceText
import dev.wckdboy.autobot.shared.voice.VoiceTurn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A provider in the model switcher. [models] is its fetched list (or the preset's offline
 * suggestions), [modelsLabel] `42 models · fetched 3 min ago` / `offline list`; [presetId] keys
 * its per-model data policies ([ModelDataPolicies]).
 */
@Immutable
data class ProviderOption(
    val id: String,
    val name: String,
    val defaultModel: String,
    val models: List<RemoteModel>,
    val modelsLabel: String,
    val canRefresh: Boolean,
    val presetId: String? = null,
) {
    val suggestedModels: List<String> get() = (listOf(defaultModel) + models.map { it.id }).filter { it.isNotBlank() }.distinct()

    /** Picker caption: context, price, data policy. */
    fun caption(model: RemoteModel): String = ModelDataPolicies.modelCaption(presetId, model)

    /** Switching to [model] needs a confirmation: its provider trains on prompts. */
    fun trains(model: String): Boolean = ModelDataPolicies.trains(presetId, model.trim())
}

@Immutable
data class LiveUi(
    val text: String,
    val reasoning: String,
    val toolNames: List<String>,
    val tokensPerSecond: Float?,
    /** Reasoning tokens counted by the engine (on-device), null when unknown. */
    val reasoningTokens: Int? = null,
    /** The reasoning closed; the answer streams. */
    val reasoningDone: Boolean = false,
    /** "budget" or "loop" when the engine ended the reasoning early. */
    val reasoningStop: String? = null,
)

@Immutable
data class ApprovalUi(
    val callId: String,
    val toolName: String,
    val summary: String,
    val reason: String?,
    /** Private fragments the call would send (taint tracking), highlighted on the card. */
    val privateData: List<String> = emptyList(),
)

@Immutable
data class QuestionUi(val callId: String, val question: UserQuestion)

enum class Dictation { IDLE, RECORDING, TRANSCRIBING }

/** Microphone levels kept for voice mode's meter (10 a second). */
private const val LEVEL_HISTORY = 32

/** Audio kept from the barge-in microphone for the dictation it starts (0.8 s: the first words). */
private const val BARGE_PREROLL = SAMPLE_RATE * 8 / 10

/** Voice mode's loop: ready → listening → transcribing → thinking (agent turn) → speaking → ready. */
enum class VoicePhase { READY, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

/**
 * Voice mode (null in [ChatUiState] when off). [handsFree]: listen again after each answer and
 * stop listening at the end of speech. The microphone levels are a separate flow
 * ([ChatViewModel.micLevels]): ten readings a second must reach the meter only.
 */
@Immutable
data class VoiceUi(val phase: VoicePhase = VoicePhase.READY, val handsFree: Boolean = false)

/** The redaction gateway for the chat's route: [NONE] when the route stays on the phone (nothing to hide). */
enum class RedactionUi { NONE, ON, OFF }

@Immutable
data class ChatUiState(
    val title: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val items: List<SessionItem> = emptyList(),
    /** Tool step numbers in transcript order, by item key (computed with the projection, off the main thread). */
    val stepNumbers: Map<String, Int> = emptyMap(),
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
    val route: Route = Route.CLOUD_API,
    val modelLabel: String? = null,
    /** Images attached to the message being written (attachment ids). */
    val pendingImages: List<String> = emptyList(),
    val dictation: Dictation = Dictation.IDLE,
    /** Text dictated since the composer last took it (see [ChatViewModel.consumeDictation]). */
    val dictated: String? = null,
    /** Key of the assistant message being read aloud. */
    val speakingKey: String? = null,
    val voice: VoiceUi? = null,
    /** Files the agent changed in this chat, per turn (the "changes" sheet). */
    val changes: List<ChangeGroup> = emptyList(),
    /** The Think switch requests use now. */
    val thinking: ThinkingSetting = ThinkingSetting.AUTO,
    /** Whether the chat's model reasons (the Think chip shows unless [ThinkingSupport.NONE]). */
    val thinkingSupport: ThinkingSupport = ThinkingSupport.NONE,
    /** The user's Think default for this chat's model, if set. */
    val thinkingDefault: ThinkingSetting? = null,
    /** Whether personal data is swapped for placeholders before this chat's cloud requests. */
    val redaction: RedactionUi = RedactionUi.NONE,
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
    private val savedState: SavedStateHandle,
    private val host: AgentHost,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val providerFactory: ProviderFactory,
    private val gallery: GalleryRepository,
    private val attachments: AttachmentRepository,
    private val speech: LocalSpeech,
    private val speakers: Speakers,
    private val library: ModelLibrary,
    private val chats: ChatLibrary,
    private val systemModel: SystemModel,
    private val compute: ComputeProfile,
    handoff: ChatHandoff,
    @ApplicationContext private val appContext: Context,
    networkPolicy: NetworkPolicy,
) : ViewModel(), ChatActions {

    @AssistedFactory
    interface Factory {
        fun create(conversationId: String): ChatViewModel
    }

    private val notice = MutableStateFlow<String?>(null)
    /** The conversation's agent; null when it cannot be opened (the screen then shows why). */
    private val agent: Flow<Agent?> = flow<Agent?> { emit(host.agent(conversationId)) }
        .catch { e ->
            notice.value = "Cannot open this chat: ${userMessage(e)}"
            emit(null)
        }
        .shareIn(viewModelScope, SharingStarted.Eagerly, 1)
    private val pending = MutableStateFlow<List<String>>(emptyList())
    private val dictation = MutableStateFlow(Dictation.IDLE)
    private val dictated = MutableStateFlow<String?>(null)
    private val stopRecording = AtomicBoolean(false)
    private val speakingKey = MutableStateFlow<String?>(null)
    private var readJob: Job? = null
    private val voiceMode = MutableStateFlow<VoiceUi?>(null)
    private val levels = MutableStateFlow<List<Float>>(emptyList())
    /** Words heard so far while dictating or listening (live, before the final transcript). */
    private val heard = MutableStateFlow<DictationText?>(null)
    /** Voice mode's current step (listening + transcribing, or speaking an answer). */
    private var voiceJob: Job? = null
    private var voiceWatch: Job? = null
    private var turn: VoiceTurn? = null
    /** The answer being read while it streams (voice mode), its speaking queue and job. */
    private var reading: ReadAloudStream? = null
    private var queue: SpeechQueue? = null
    private var queueJob: Job? = null
    private var liveWatch: Job? = null
    /** Hands-free: the microphone listening for the user to talk over the answer. */
    private var bargeJob: Job? = null

    /** The running barge-in's gate (levels arrive on the capture thread: use it synchronized). */
    @Volatile
    private var bargeGate: BargeIn? = null
    private val stopBarge = AtomicBoolean(false)
    private val attachmentThumbs = ThumbnailCache()
    private val thumbs = ThumbnailCache()

    private val policy = combine(networkPolicy.mode, networkPolicy.allowLoopbackWhenOffline, networkPolicy.socksFallback) { m, l, s -> Triple(m, l, s) }

    /** Downloaded chat models, newest first, by title: what "This phone" can switch between. */
    private val installedChat = library.models.map { list ->
        list.filter { it.isLocalChat }.sortedByDescending { it.addedAt }.map { RemoteModel(it.id, name = it.title) }
    }
    /**
     * Providers with their switcher options, built only when providers, model lists or installs
     * change (OpenRouter lists hundreds of models), not on every transcript change.
     */
    private val providerModels = combine(providers.observeProviders(), providers.observeModelCaches(), installedChat, systemModel.status) { list, caches, installed, _ ->
        list to list.map { it.toOption(caches[it.id], installed) }
    }.flowOn(Dispatchers.Default)
    private val refreshing = MutableStateFlow(false)

    /** Where each downloaded model runs, for the route tag (known once the engine has answered, or picked). */
    private val localBackends = combine(library.models, compute.lastRan, compute.fastestByModel) { models, ran, measured ->
        models.associate { it.id to ComputeProfile.shownBackend(ran[it.id], it.backend, measured[it.id]) }
    }.distinctUntilChanged()

    private val routing = combine(conversations.observeConversation(conversationId), providerModels, policy, localBackends) { c, list, p, b ->
        Routing(c, list, p, b)
    }

    private data class Routing<C, L, P>(val conversation: C, val withModels: L, val policy: P, val backends: Map<String, Backend?>)

    /** Tool descriptions by call (name + arguments): projections repeat them on every log change. */
    private val described = ConcurrentHashMap<String, String>()

    /**
     * The transcript, re-projected only when the log, running tools or interactions change (never
     * per streamed token) and off the main thread; the live frame is a separate, throttled flow.
     */
    private val agentState = agent.flatMapLatest { a ->
        if (a == null) return@flatMapLatest flowOf(AgentSnapshot())
        // Incremental: each log entry is projected once (shared SessionProjector).
        val projector = SessionProjector { name, args -> describe(a, name, args) }
        val transcript = combine(a.log.entries, a.runningTools, host.interactions) { entries, running, interactions ->
            val mine = interactions.filter { it.sessionId == conversationId }
            val approvals = mine.filterIsInstance<Interaction.Approval>().associate { it.callId to ApprovalUi(it.callId, it.toolName, it.summary, it.reason, it.privateData) }
            val question = mine.filterIsInstance<Interaction.Question>().firstOrNull()?.let { QuestionUi(it.callId, it.question) }
            val projection = projector.project(entries, approvals.keys, running)
            var n = 0
            val steps = projection.items.filterIsInstance<SessionItem.Tool>().associate { it.key to ++n }
            // Parse the latest answers' markdown here, off the main thread: their bubbles then
            // compose from the cache (and a finished reply continues its streamed parse).
            projection.items.asReversed().asSequence().filterIsInstance<SessionItem.Assistant>().take(WARM_BUBBLES)
                .forEach { if (it.text.isNotEmpty()) LiteMarkdown.cached(it.text) }
            Transcript(projection, steps, approvals, question)
        }
        combine(transcript, a.status, a.config, a.permission, host.redactionDefault) { t, status, config, permission, redactByDefault ->
            AgentSnapshot(
                t.projection, t.stepNumbers, status == AgentStatus.RUNNING, t.approvals, t.question, config.toolsEnabled, permission,
                config.llm.thinking ?: ThinkingSetting.AUTO,
                redacting = config.redaction ?: redactByDefault,
            )
        }
    }.catch { e ->
        notice.value = "Cannot show this chat: ${userMessage(e)}"
        emit(AgentSnapshot())
    }.flowOn(Dispatchers.Default)

    /**
     * The streaming reply, at most [LIVE_FRAME_MS] apart; its end (null) goes out at once. Kept
     * out of [uiState]: its frames must recompose the live message only, not the whole screen.
     */
    val live: StateFlow<LiveUi?> = agent.flatMapLatest { a -> a?.live?.throttleLatest(LIVE_FRAME_MS) { it == null } ?: flowOf(null) }
        .map { it?.toUi() }
        .catch { emit(null) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Voice mode's recent microphone peaks (0…1), oldest first, ten a second while listening: for the meter only. */
    val micLevels: StateFlow<List<Float>> = levels

    /** The words heard so far while dictating or listening: for the live transcript line only. */
    val liveDictation: StateFlow<DictationText?> = heard

    private class Transcript(
        val projection: SessionProjection,
        val stepNumbers: Map<String, Int>,
        val approvals: Map<String, ApprovalUi>,
        val question: QuestionUi?,
    )

    private data class AgentSnapshot(
        val projection: SessionProjection = projectSession(emptyList(), emptySet(), emptySet()) { _, _ -> "" },
        val stepNumbers: Map<String, Int> = emptyMap(),
        val running: Boolean = false,
        val approvals: Map<String, ApprovalUi> = emptyMap(),
        val question: QuestionUi? = null,
        val toolsEnabled: Boolean = true,
        val permission: PermissionPreset = PermissionPreset.WORKSPACE,
        val thinking: ThinkingSetting = ThinkingSetting.AUTO,
        /** The chat's own redaction choice, else Settings. */
        val redacting: Boolean = true,
    )

    private data class ThinkingInfo(val support: ThinkingSupport = ThinkingSupport.NONE, val default: ThinkingSetting? = null)

    /** Whether the chat's model reasons, and the user's default for it; follows the route and Settings. */
    private val thinkingInfo: Flow<ThinkingInfo> = routing.map { (conversation, withModels, _) ->
        val provider = withModels.first.firstOrNull { it.id == conversation?.providerId } ?: withModels.first.firstOrNull()
        val model = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel
        if (provider == null || model.isNullOrBlank()) null else provider.id to model
    }.distinctUntilChanged().combine(host.thinkingDefaults) { target, text -> target to text }
        .mapLatest { (target, text) ->
            if (target == null) ThinkingInfo() else ThinkingInfo(
                runCatching { host.thinkingSupport(target.first, target.second) }.getOrDefault(ThinkingSupport.NONE),
                ThinkingDefaults.get(text, target.first, target.second),
            )
        }
        .catch { emit(ThinkingInfo()) }

    private data class VoiceSnapshot(val dictation: Dictation, val dictated: String?, val speakingKey: String?, val mode: VoiceUi?)

    private val voice = combine(dictation, dictated, speakingKey, voiceMode, ::VoiceSnapshot)

    private val baseState: Flow<ChatUiState> = combine(agentState, routing, notice, pending, voice) { snap, (conversation, withModels, p, localBackend), n, images, v ->
        val (providerList, options) = withModels
        val (mode, allowLoopback, socks) = p
        val provider = providerList.firstOrNull { it.id == conversation?.providerId } ?: providerList.firstOrNull()
        val effective = provider?.let { RouteResolver.effectiveMode(mode, it.routing.toOverride(), socks) } ?: mode
        val loopback = provider != null && Loopback.isLoopbackUrl(provider.baseUrl)
        val isLocal = provider?.kind == ProviderKind.LOCAL
        val usage = snap.projection.lastUsage
        ChatUiState(
            title = conversation?.title.orEmpty(),
            pinned = conversation?.pinned == true,
            archived = conversation?.archived == true,
            items = snap.projection.items,
            stepNumbers = snap.stepNumbers,
            running = snap.running,
            todos = snap.projection.todos,
            approvals = snap.approvals,
            question = snap.question,
            providers = options,
            selectedProviderId = provider?.id,
            selectedProviderName = provider?.displayName,
            selectedModel = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel,
            toolsEnabled = snap.toolsEnabled,
            permission = snap.permission,
            contextUsed = usage?.let { (it.inputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.outputTokens ?: 0) },
            contextWindow = snap.projection.contextWindow,
            privacyStatus = if (loopback || isLocal) PrivacyStatus.OFFLINE else effective.toPrivacyStatus(),
            showOfflineBanner = provider != null && !isLocal && mode == NetworkMode.Offline && !(loopback && allowLoopback),
            // On this phone: the engine the model actually runs on (NPU / GPU / CPU) once known.
            route = when {
                isLocal -> Route.local((conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel)?.let { localBackend[it] }?.id)
                loopback -> Route.LOCAL
                else -> Route.CLOUD_API
            },
            modelLabel = conversation?.model?.let { m ->
                when {
                    !isLocal -> m
                    SystemModels.isSystem(m) -> SystemModels.title(m)
                    else -> m.substringAfter("catalog:").substringAfterLast('/').substringBefore(".gguf")
                }
            },
            isIncognito = conversations.isIncognito(conversationId),
            notice = n,
            pendingImages = images,
            dictation = v.dictation,
            dictated = v.dictated,
            speakingKey = v.speakingKey,
            voice = v.mode,
            changes = snap.projection.changes,
            thinking = snap.thinking,
            redaction = when {
                provider == null || isLocal || loopback -> RedactionUi.NONE
                snap.redacting -> RedactionUi.ON
                else -> RedactionUi.OFF
            },
            loaded = true,
        )
    }.combine(thinkingInfo.onStart { emit(ThinkingInfo()) }) { state, info -> state.copy(thinkingSupport = info.support, thinkingDefault = info.default) }

    val uiState: StateFlow<ChatUiState> = baseState
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isIncognito = conversations.isIncognito(conversationId)))

    /** The composer's text: kept here, not in the saved instance state, so a huge paste cannot overflow it. */
    override var draft: String = savedState.get<String>(KEY_DRAFT).orEmpty()
        private set

    override fun setDraft(text: String) {
        draft = text
        // Only a bounded prefix survives process death (the saved state travels in one binder transaction).
        savedState[KEY_DRAFT] = text.take(MAX_SAVED_DRAFT)
    }

    init {
        host.retain(conversationId)
        // Started from the share sheet, the text selection menu, a tile, shortcut or widget.
        handoff.take(conversationId)?.let { start ->
            start.draft?.let { dictated.value = it }
            start.images.forEach { attach(Uri.parse(it)) }
            if (start.voice) setVoiceMode(true)
        }
        prepareLocalModel()
    }

    /**
     * Loads this chat's on-device model while the chat is open (and again after a switch), so the
     * first message does not wait seconds for it. The engine skips it when a reply already
     * queued, or while an image or audio job holds the memory.
     */
    private fun prepareLocalModel() {
        viewModelScope.launch {
            routing.map { (conversation, withModels, _) ->
                val provider = withModels.first.firstOrNull { it.id == conversation?.providerId } ?: withModels.first.firstOrNull()
                val model = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel
                if (provider?.kind == ProviderKind.LOCAL && model != null && !SystemModels.isSystem(model)) provider to model else null
            }.distinctUntilChangedBy { target -> target?.let { (provider, model) -> provider.id to model } }
                .collectLatest { target ->
                    if (target != null) runCatching { providerFactory.create(target.first).prepare(target.second) }
                }
        }
    }

    private fun describe(agent: Agent, name: String, arguments: String): String {
        val key = name + '\u0000' + arguments
        described[key]?.let { return it }
        if (described.size > MAX_DESCRIBED) described.clear()
        return describeNow(agent, name, arguments).also { described[key] = it }
    }

    private fun describeNow(agent: Agent, name: String, arguments: String): String {
        val tool = agent.ctx[dev.wckdboy.autobot.agent.core.tools.ToolRegistry.Key]?.get(name) ?: return arguments.take(80)
        val args = ToolExecutor.parseArgs(arguments) ?: return arguments.take(80)
        return runCatching { tool.describeCall(args) }.getOrDefault("")
    }

    private fun LiveStream.toUi(): LiveUi {
        val seconds = (monotonicNanos() - startedAtNanos) / 1e9f
        val approxTokens = (text.length + reasoning.length) / 4f
        return LiveUi(
            text = text,
            reasoning = reasoning,
            toolNames = toolCalls.mapNotNull { it.name },
            tokensPerSecond = if (seconds > 0.5f && approxTokens > 0) approxTokens / seconds else null,
            reasoningTokens = reasoningTokens,
            reasoningDone = reasoningDone,
            reasoningStop = reasoningStop,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Intents

    /** Sends a prompt (with the attached images) or runs a slash command. */
    override fun send(text: String) {
        val input = text.trim()
        if (input.isEmpty() && pending.value.isEmpty()) return
        if (input.startsWith("/") && runCommand(input)) return
        val images = pending.value
        pending.value = emptyList()
        viewModelScope.launchSafely(::failed) { host.send(conversationId, input, images) }
    }

    /** A user action failed: say so instead of crashing. */
    private fun failed(e: Throwable) {
        notice.value = userMessage(e)
    }

    /** Attaches a picked image: downscaled and re-encoded (which also drops EXIF/GPS metadata). */
    override fun attach(uri: Uri) {
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { runCatching { ImageInput.encode(appContext, uri) }.getOrNull() }
            if (encoded == null) {
                notice.value = "Could not read that image"
                return@launch
            }
            val id = try {
                attachments.put(encoded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Could not attach the image: ${userMessage(e)}"
                return@launch
            }
            pending.update { it + id }
        }
    }

    /** Starts dictation, or stops it and transcribes on the phone (whisper.cpp). */
    override fun toggleDictation() {
        when (dictation.value) {
            Dictation.RECORDING -> stopRecording.set(true)
            Dictation.TRANSCRIBING -> Unit
            Dictation.IDLE -> viewModelScope.launch {
                val path = speechModelPath()
                if (path == null) {
                    notice.value = "Get a speech model in Models › speech to dictate"
                    return@launch
                }
                missingSpeechModel(path)?.let {
                    notice.value = it
                    return@launch
                }
                stopRecording.set(false)
                dictation.value = Dictation.RECORDING
                try {
                    // Live words while recording; the last pass runs once the button is tapped.
                    val stopped = { stopRecording.get().also { if (it) dictation.value = Dictation.TRANSCRIBING } }
                    val text = VoiceText.transcript(LiveDictation(LocalDictationEngine(speech, path)).run(stopped, onText = { heard.value = it }))
                    if (text.isNotBlank()) dictated.value = text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notice.value = "Dictation failed: ${userMessage(e)}"
                } finally {
                    heard.value = null
                    dictation.value = Dictation.IDLE
                }
            }
        }
    }

    override fun consumeDictation() {
        dictated.value = null
    }

    private suspend fun speechModelPath(): String? =
        library.all().firstOrNull { it.isReady && it.engine == EngineKind.WHISPER }?.paths?.get(FileRole.MODEL)

    /** A "ready" speech model whose file was deleted or moved: say so instead of failing in the engine. */
    private suspend fun missingSpeechModel(path: String): String? =
        if (withContext(Dispatchers.IO) { File(path).isFile }) null else "The speech model's file is missing — remove it and download it again in Models › speech"



    /** Reads an answer aloud, on the device only (see [Speakers]); tapping again stops. */
    override fun speak(key: String, text: String) {
        readJob?.cancel()
        if (speakingKey.value == key) {
            speakers.stopAll()
            speakingKey.value = null
            return
        }
        if (voiceMode.value != null) stopVoiceWork()
        speakingKey.value = key
        readJob = viewModelScope.launch {
            try {
                speakers.best().read(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeakerUnavailable) {
                notice.value = e.message
            } catch (e: Exception) {
                notice.value = "Read aloud failed: ${userMessage(e)}"
            } finally {
                speakingKey.compareAndSet(key, null)
            }
        }
    }

    private fun stopReading() {
        readJob?.cancel()
        speakingKey.value = null
    }

    // -----------------------------------------------------------------------------------------
    // Voice mode: push to talk → transcribe → send → read the answer aloud, all on the phone.

    override fun setVoiceMode(on: Boolean) {
        if (on == (voiceMode.value != null)) return
        stopVoiceWork()
        voiceWatch?.cancel()
        if (!on) {
            voiceMode.value = null
            return
        }
        if (dictation.value == Dictation.RECORDING) stopRecording.set(true)
        stopReading()
        voiceMode.value = VoiceUi()
        voiceWatch = viewModelScope.launch { uiState.collect(::onVoiceState) }
    }

    override fun setHandsFree(on: Boolean) = voiceMode.update { it?.copy(handsFree = on) }

    /** The voice button: talk, send what was said, stop the agent, or interrupt the answer and talk. */
    override fun voiceTap() {
        when (voiceMode.value?.phase ?: return) {
            VoicePhase.READY -> listen()
            VoicePhase.LISTENING -> endListening()
            VoicePhase.TRANSCRIBING -> Unit
            VoicePhase.THINKING -> stop()
            VoicePhase.SPEAKING -> interrupt()
        }
    }

    /** The microphone permission was refused from the voice button. */
    override fun voiceDenied() = voiceFailed("Microphone access is off — allow it in Settings to talk")

    /** Stop listening: the last pass over what is left gives the transcript. */
    private fun endListening() {
        stopRecording.set(true)
        if (voiceMode.value?.phase == VoicePhase.LISTENING) setPhase(VoicePhase.TRANSCRIBING)
    }

    /**
     * Listens with live words on screen until the user taps or (hands-free) pauses, then sends
     * what was said. [preroll]: audio heard just before (a barge-in's first words).
     */
    private fun listen(preroll: FloatArray? = null) {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            // The barge-in microphone is released before this one opens.
            bargeJob?.join()
            val path = speechModelPath() ?: return@launch voiceFailed("Get a speech model in Models › speech")
            missingSpeechModel(path)?.let { return@launch voiceFailed(it) }
            stopRecording.set(false)
            setPhase(VoicePhase.LISTENING)
            val handsFree = voiceMode.value?.handsFree == true
            // After a barge-in the user is mid-sentence: the detector must not wait for them to start.
            val detector = EndOfSpeech().apply { if (preroll != null) primeHeard() }
            var nothingSaid = false
            try {
                val raw = LiveDictation(LocalDictationEngine(speech, path)).run(
                    stopped = stopRecording::get,
                    preroll = preroll,
                    onLevel = { peak ->
                        levels.update { (it + peak).takeLast(LEVEL_HISTORY) }
                        if (handsFree) {
                            when (detector.onLevel(peak, SystemClock.elapsedRealtime())) {
                                SpeechVerdict.LISTENING -> Unit
                                SpeechVerdict.ENDED -> endListening()
                                SpeechVerdict.NO_SPEECH -> {
                                    nothingSaid = true
                                    stopRecording.set(true)
                                }
                            }
                        }
                    },
                    onText = { heard.value = it },
                )
                heard.value = null
                val text = VoiceText.transcript(raw)
                if (nothingSaid || text.isEmpty()) return@launch voiceFailed("Didn't catch that")
                turn = VoiceTurn(uiState.value.items.mapTo(HashSet()) { it.key }, SystemClock.elapsedRealtime())
                // The answer is read while it streams, from the chat renderer's own text.
                reading = ReadAloudStream(LiteMarkdown::speakable)
                setPhase(VoicePhase.THINKING)
                watchLive()
                send(text)
                // Re-check once the prompt could have been dropped without any state change.
                delay(VoiceTurn.TIMEOUT_MS)
                onVoiceState(uiState.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                heard.value = null
                voiceFailed("Voice failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Feeds the streaming answer of the voice turn to the read-aloud stream, sentence by sentence. */
    private fun watchLive() {
        liveWatch?.cancel()
        liveWatch = viewModelScope.launch {
            live.collect { frame ->
                val text = frame?.text?.takeIf { it.isNotEmpty() } ?: return@collect
                // Only once this turn's prompt is in: a reply still streaming for an earlier one is not ours.
                if (turn?.promptSeen(uiState.value.items) != true) return@collect
                speakPieces(reading?.update(text).orEmpty())
            }
        }
    }

    /** Queues [pieces] to speak; the first one starts the speaking phase (and barge-in). */
    private fun speakPieces(pieces: List<String>) {
        if (pieces.isEmpty()) return
        val q = queue ?: SpeechQueue(onResume = ::speakingResumed) { index -> if (index == 0) startedSpeaking() }.also { q ->
            queue = q
            queueJob = viewModelScope.launch {
                try {
                    q.run(speakers.best())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SpeakerUnavailable) {
                    stopSpeaking()
                    return@launch voiceFailed(e.message.orEmpty())
                } catch (e: Exception) {
                    stopSpeaking()
                    return@launch voiceFailed("Voice failed: ${userMessage(e)}")
                }
                // Everything was read and the turn is over.
                if (queue !== q) return@launch
                stopSpeaking()
                if (voiceMode.value?.handsFree == true) listen() else setPhase(VoicePhase.READY)
            }
        }
        q.add(pieces)
    }

    private fun startedSpeaking() {
        setPhase(VoicePhase.SPEAKING)
        if (voiceMode.value?.handsFree == true) startBargeIn()
    }

    /** The answer is read on after the speaker waited for more of it: barge-in learns the echo again. */
    private fun speakingResumed() {
        bargeGate?.let { gate -> synchronized(gate) { gate.resume(SystemClock.elapsedRealtime()) } }
    }

    /**
     * Hands-free: listens while the answer plays, with the platform's echo canceller and the
     * shared [BargeIn] gate, so talking over the answer stops it and starts listening (the first
     * words heard are kept). If this phone cannot capture during playback, tapping still works.
     */
    private fun startBargeIn() {
        if (bargeJob?.isActive == true) return
        stopBarge.set(false)
        bargeJob = viewModelScope.launch {
            val gate = BargeIn()
            bargeGate = gate
            val ring = FloatArray(BARGE_PREROLL)
            var at = 0
            var filled = 0
            var triggered = false
            try {
                gate.start(SystemClock.elapsedRealtime())
                speech.capture(stopped = stopBarge::get, echoCancel = true) { samples, count ->
                    var peak = 0f
                    for (i in 0 until count) {
                        val s = samples[i]
                        ring[at] = s
                        at = (at + 1) % ring.size
                        peak = maxOf(peak, kotlin.math.abs(s))
                    }
                    filled = minOf(ring.size, filled + count)
                    if (synchronized(gate) { gate.onLevel(peak, SystemClock.elapsedRealtime()) }) {
                        triggered = true
                        stopBarge.set(true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            } finally {
                if (bargeGate === gate) bargeGate = null
            }
            if (!triggered || voiceMode.value?.phase != VoicePhase.SPEAKING) return@launch
            val preroll = FloatArray(filled) { ring[(at - filled + it + ring.size) % ring.size] }
            interrupt(preroll)
        }
    }

    /** The user talks over the answer (a tap, or barge-in): stop reading it and the agent, then listen. */
    private fun interrupt(preroll: FloatArray? = null) {
        stopSpeaking()
        if (turn != null && uiState.value.running) stop()
        turn = null
        listen(preroll)
    }

    /** Stops reading the voice answer (and the barge-in microphone). */
    private fun stopSpeaking() {
        liveWatch?.cancel()
        liveWatch = null
        queueJob?.cancel()
        queueJob = null
        queue = null
        reading = null
        stopBarge.set(true)
        speakers.stopAll()
    }

    /** Follows the agent during a voice turn: announces approvals, ends the reading when the turn ends. */
    private fun onVoiceState(state: ChatUiState) {
        val current = turn ?: return
        val waiting = state.approvals.keys + listOfNotNull(state.question?.callId)
        if (current.needsAnnouncement(waiting)) {
            val line = if (state.question != null) "The agent has a question." else "Needs your approval."
            val q = queue
            if (q != null) {
                q.add(listOf(line))
            } else {
                viewModelScope.launch {
                    try {
                        speakers.best().speak(line)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Only an announcement: the card is on screen anyway.
                    }
                }
            }
        }
        // Each step's committed answer: its last words may never have been in a live frame.
        reading?.let { r -> current.committedSteps(state.items).forEach { speakPieces(r.stepDone(it)) } }
        val finished = current.update(state.items, state.running, SystemClock.elapsedRealtime()) ?: return
        turn = null
        liveWatch?.cancel()
        liveWatch = null
        // The rest of the answer (or all of it, when it did not stream), then the queue ends.
        reading?.let { speakPieces(it.finish(finished.reply)) }
        val q = queue
        if (q == null) {
            reading = null
            setPhase(VoicePhase.READY)
        } else {
            q.close()
        }
    }

    private fun setPhase(phase: VoicePhase) {
        levels.value = emptyList()
        voiceMode.update { it?.copy(phase = phase) }
    }

    private fun voiceFailed(message: String) {
        notice.value = message
        // A failed turn is no longer followed: its end must not reset a new recording to READY.
        turn = null
        setPhase(VoicePhase.READY)
    }

    private fun stopVoiceWork() {
        voiceJob?.cancel()
        stopRecording.set(true)
        stopSpeaking()
        heard.value = null
        turn = null
        setPhase(VoicePhase.READY)
    }


    override fun onCleared() {
        readJob?.cancel()
        stopVoiceWork()
        host.release(conversationId)
        super.onCleared()
    }

    override fun removeAttachment(id: String) {
        pending.update { it - id }
        viewModelScope.launch { runCatching { attachments.delete(id) } }
    }

    /** Small preview of an attachment for the composer and the transcript. */
    override suspend fun attachmentThumbnail(id: String): ImageBitmap? {
        attachmentThumbs[id]?.let { return it }
        val bytes = runCatching { attachments.bytes(id) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { decodeThumbnail(bytes) }?.also { attachmentThumbs[id] = it }
    }

    private fun runCommand(input: String): Boolean {
        val parts = input.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        when (parts[0].lowercase()) {
            "compact" -> compact()
            "tools" -> setToolsEnabled(arg != "off")
            "think" -> parseThinking(arg)?.let(::setThinking) ?: run { notice.value = "usage: /think off | auto | on [budget]" }
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

    override fun stop() {
        viewModelScope.launchSafely(::failed) { host.agent(conversationId).cancel() }
    }

    override fun approve(callId: String, outcome: ApprovalOutcome) = host.answerApproval(conversationId, callId, outcome)

    override fun answer(callId: String, answers: List<String>?) = host.answerQuestion(conversationId, callId, answers)

    override fun selectProvider(providerId: String) {
        viewModelScope.launchSafely(::failed) {
            val provider = providers.getProvider(providerId) ?: return@launchSafely
            host.setRoute(conversationId, provider.id, provider.defaultModel)
        }
    }

    /**
     * Switches the chat to [model]. A model whose provider trains on prompts needs [confirmed]
     * (the picker asks first); `/model` with one only explains why it did not switch.
     */
    override fun selectModel(model: String, confirmed: Boolean) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launchSafely(::failed) {
            val providerId = uiState.value.selectedProviderId ?: providers.firstProviderId() ?: return@launchSafely
            val presetId = providers.getProvider(providerId)?.preset()?.id
            if (!confirmed && ModelDataPolicies.trains(presetId, trimmed)) {
                notice.value = "${ModelDataPolicies.TRAINS_WARNING} Pick it in the model sheet to confirm."
                return@launchSafely
            }
            host.setRoute(conversationId, providerId, trimmed)
        }
    }

    override fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launchSafely(::failed) { host.setToolsEnabled(conversationId, enabled) }
    }

    override fun setThinking(setting: ThinkingSetting) {
        viewModelScope.launchSafely(::failed) { host.setThinking(conversationId, setting) }
    }

    override fun setThinkingDefault(setting: ThinkingSetting?) {
        val state = uiState.value
        val provider = state.selectedProviderId ?: return
        val model = state.selectedModel ?: return
        viewModelScope.launchSafely(::failed) { host.setThinkingDefault(provider, model, setting) }
    }

    override fun setRedaction(enabled: Boolean) {
        viewModelScope.launchSafely(::failed) { host.setRedaction(conversationId, enabled) }
    }

    override fun setPermission(preset: PermissionPreset) {
        viewModelScope.launchSafely(::failed) { host.setPermission(conversationId, preset) }
    }

    override fun compact() = host.compact(conversationId) { e -> notice.value = "Compact failed: ${userMessage(e)}" }

    override fun rename(title: String) {
        viewModelScope.launch { conversations.rename(conversationId, title) }
    }

    override fun setPinned(pinned: Boolean) {
        viewModelScope.launch { conversations.setPinned(listOf(conversationId), pinned) }
    }

    override fun setArchived(archived: Boolean) {
        viewModelScope.launch { conversations.setArchived(listOf(conversationId), archived) }
    }

    /** Deletes this chat (undoable from the list for a few seconds); a running turn is cancelled first. */
    override fun delete() {
        stopVoiceWork()
        chats.trash(listOf(conversationId), uiState.value.title)
    }

    /** Undoes one file step (and later changes to the same files). */
    override fun undoStep(callId: String) = undo { host.undoStep(conversationId, callId) }

    /** Undoes every file change of [turn]. */
    override fun undoTurn(turn: Int) = undo { host.undoTurn(conversationId, turn) }

    /** Re-applies what an undo put back. */
    override fun redo(revertId: String) = undo { host.redo(conversationId, revertId) }

    private fun undo(op: suspend () -> Any?) {
        viewModelScope.launchSafely({ e -> notice.value = "Undo failed: ${userMessage(e)}" }) {
            if (uiState.value.running) {
                notice.value = "Wait for the turn to finish, then undo"
                return@launchSafely
            }
            if (op() == null) notice.value = "Nothing to undo: the copies are gone or the files already match"
        }
    }

    override fun dismissNotice() = notice.update { null }

    /** Decrypted gallery thumbnail for tool cards (cached for the screen's lifetime). */
    override suspend fun thumbnail(galleryId: String): ImageBitmap? {
        thumbs[galleryId]?.let { return it }
        val bytes = runCatching { gallery.thumbnail(galleryId) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { decodeThumbnail(bytes) }?.also { thumbs[galleryId] = it }
    }

    /** "This phone" also offers the OS's model (Gemini Nano in the `playServices` build) when it can run. */
    private fun Provider.localSystemModel(): List<String> =
        listOfNotNull(systemModel.status.value?.takeIf { kind == ProviderKind.LOCAL && it.usable }?.id)

    /** Model switcher "refresh": refetches the selected provider's list (kill switch, route and audit log apply). */
    override fun refreshModels() {
        val id = uiState.value.selectedProviderId ?: return
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val provider = providers.getProvider(id) ?: return@launch
                when (val result = providerFactory.create(provider).listModels()) {
                    is ModelListResult.Success -> if (result.models.isNotEmpty()) providers.saveModelCache(id, ModelCache(System.currentTimeMillis(), result.models))
                    is ModelListResult.Failure -> notice.value = "Models: ${result.message}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = "Models: ${userMessage(e)}"
            } finally {
                refreshing.value = false
            }
        }
    }

    private companion object {
        /** About ten live frames a second: smooth, and the transcript is not re-laid out per token. */
        const val LIVE_FRAME_MS = 100L
        const val KEY_DRAFT = "draft"
        const val MAX_SAVED_DRAFT = 16_000
        const val MAX_DESCRIBED = 1_024

        /** Answers whose markdown is parsed ahead, newest first (about a screenful and a half). */
        const val WARM_BUBBLES = 24
    }

    private fun Provider.toOption(cache: ModelCache?, installed: List<RemoteModel>): ProviderOption {
        val preset = preset()
        val local = kind == ProviderKind.LOCAL
        return ProviderOption(
            id = id,
            name = displayName,
            defaultModel = defaultModel,
            models = (if (local) installed else cache?.models ?: preset.suggestedModels.map { RemoteModel(it) }) + localSystemModel().map { RemoteModel(it) },
            modelsLabel = when {
                local -> "${installed.size} downloaded"
                cache != null -> "${counted(cache.models.size, "model")} · ${cache.ageLabel(System.currentTimeMillis())}"
                else -> "offline list"
            },
            canRefresh = !local && preset.modelsApi != ModelsApi.NONE,
            presetId = preset.id,
        )
    }
}

/** Longest side of a decoded chat thumbnail (cards and strips are at most 120 dp). */
private const val THUMBNAIL_PX = 480

/** Decoded thumbnails by id, bounded by their pixel bytes. */
private class ThumbnailCache : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4

    operator fun set(key: String, value: ImageBitmap) {
        put(key, value)
    }
}

/** [bytes] decoded at most [THUMBNAIL_PX] on its longest side (power-of-two subsampling). */
private fun decodeThumbnail(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PX) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/** `/think off`, `/think auto`, `/think on`, `/think on 512` (or `/think 512`); null when unreadable. */
internal fun parseThinking(arg: String): ThinkingSetting? {
    val words = arg.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val number = words.firstNotNullOfOrNull { w -> w.removeSuffix("k").toIntOrNull()?.let { if (w.endsWith("k")) it * 1024 else it } }
    return when (words.firstOrNull()) {
        "off" -> ThinkingSetting.OFF
        "auto" -> ThinkingSetting.AUTO
        "on" -> ThinkingSetting(ThinkingMode.ON, number?.takeIf { it > 0 })
        else -> number?.takeIf { it > 0 }?.let { ThinkingSetting(ThinkingMode.ON, it) }
    }
}
