package dev.wckdboy.autobot.feature.chat.conversation

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
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
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.SystemModel
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.network.RouteResolver
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import dev.wckdboy.autobot.feature.chat.toPrivacyStatus
import dev.wckdboy.autobot.feature.chat.voice.SpeakerUnavailable
import dev.wckdboy.autobot.feature.chat.voice.Speakers
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
import dev.wckdboy.autobot.shared.session.projectSession
import dev.wckdboy.autobot.shared.voice.EndOfSpeech
import dev.wckdboy.autobot.shared.voice.SpeechVerdict
import dev.wckdboy.autobot.shared.voice.VoiceText
import dev.wckdboy.autobot.shared.voice.VoiceTurn
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
data class LiveUi(val text: String, val reasoning: String, val toolNames: List<String>, val tokensPerSecond: Float?)

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

/** Voice mode's loop: ready → listening → transcribing → thinking (agent turn) → speaking → ready. */
enum class VoicePhase { READY, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

/**
 * Voice mode (null in [ChatUiState] when off). [handsFree]: listen again after each answer and
 * stop listening at the end of speech. [levels]: recent microphone peaks (0…1), oldest first.
 */
@Immutable
data class VoiceUi(val phase: VoicePhase = VoicePhase.READY, val handsFree: Boolean = false, val levels: List<Float> = emptyList())

@Immutable
data class ChatUiState(
    val title: String = "",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val items: List<SessionItem> = emptyList(),
    /** Tool step numbers in transcript order, by item key (computed with the projection, off the main thread). */
    val stepNumbers: Map<String, Int> = emptyMap(),
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
    handoff: ChatHandoff,
    @ApplicationContext private val appContext: Context,
    networkPolicy: NetworkPolicy,
) : ViewModel() {

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
    /** Voice mode's current step (listening + transcribing, or speaking an answer). */
    private var voiceJob: Job? = null
    private var voiceWatch: Job? = null
    private var turn: VoiceTurn? = null
    private val attachmentThumbs = ConcurrentHashMap<String, ImageBitmap>()
    private val thumbs = ConcurrentHashMap<String, ImageBitmap>()

    private val policy = combine(networkPolicy.mode, networkPolicy.allowLoopbackWhenOffline, networkPolicy.socksFallback) { m, l, s -> Triple(m, l, s) }

    /** Downloaded chat models, newest first, by title: what "This phone" can switch between. */
    private val installedChat = library.models.map { list ->
        list.filter { it.isLocalChat }.sortedByDescending { it.addedAt }.map { RemoteModel(it.id, name = it.title) }
    }
    private val providerModels = combine(providers.observeProviders(), providers.observeModelCaches(), installedChat) { list, caches, installed ->
        Triple(list, caches, installed)
    }
    private val refreshing = MutableStateFlow(false)

    private val routing = combine(conversations.observeConversation(conversationId), providerModels, policy) { c, list, p -> Triple(c, list, p) }

    /** Tool descriptions by call (name + arguments): projections repeat them on every log change. */
    private val described = ConcurrentHashMap<String, String>()

    /**
     * The transcript, re-projected only when the log, running tools or interactions change (never
     * per streamed token) and off the main thread; the live frame is a separate, throttled flow.
     */
    private val agentState = agent.flatMapLatest { a ->
        if (a == null) return@flatMapLatest flowOf(AgentSnapshot())
        val transcript = combine(a.log.entries, a.runningTools, host.interactions) { entries, running, interactions ->
            val mine = interactions.filter { it.sessionId == conversationId }
            val approvals = mine.filterIsInstance<Interaction.Approval>().associate { it.callId to ApprovalUi(it.callId, it.toolName, it.summary, it.reason, it.privateData) }
            val question = mine.filterIsInstance<Interaction.Question>().firstOrNull()?.let { QuestionUi(it.callId, it.question) }
            val projection = projectSession(entries, approvals.keys, running) { name, args -> describe(a, name, args) }
            var n = 0
            val steps = projection.items.filterIsInstance<SessionItem.Tool>().associate { it.key to ++n }
            Transcript(projection, steps, approvals, question)
        }
        combine(transcript, a.status, a.config, a.permission) { t, status, config, permission ->
            AgentSnapshot(t.projection, t.stepNumbers, status == AgentStatus.RUNNING, t.approvals, t.question, config.toolsEnabled, permission)
        }
    }.catch { e ->
        notice.value = "Cannot show this chat: ${userMessage(e)}"
        emit(AgentSnapshot())
    }.flowOn(Dispatchers.Default)

    /** The streaming reply, at most [LIVE_FRAME_MS] apart; its end (null) goes out at once. */
    private val live: Flow<LiveUi?> = agent.flatMapLatest { a -> a?.live?.throttleLatest(LIVE_FRAME_MS) { it == null } ?: flowOf(null) }
        .map { it?.toUi() }
        .catch { emit(null) }
        .flowOn(Dispatchers.Default)

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
    )

    private data class VoiceSnapshot(val dictation: Dictation, val dictated: String?, val speakingKey: String?, val mode: VoiceUi?)

    private val voice = combine(dictation, dictated, speakingKey, voiceMode, ::VoiceSnapshot)

    private val baseState: Flow<ChatUiState> = combine(agentState, routing, notice, pending, voice) { snap, (conversation, withModels, p), n, images, v ->
        val (providerList, caches, installed) = withModels
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
            providers = providerList.map { it.toOption(caches[it.id], installed) },
            selectedProviderId = provider?.id,
            selectedProviderName = provider?.displayName,
            selectedModel = conversation?.model?.takeIf { it.isNotBlank() } ?: provider?.defaultModel,
            toolsEnabled = snap.toolsEnabled,
            permission = snap.permission,
            contextUsed = usage?.let { (it.inputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.outputTokens ?: 0) },
            contextWindow = snap.projection.contextWindow,
            privacyStatus = if (loopback || isLocal) PrivacyStatus.OFFLINE else effective.toPrivacyStatus(),
            showOfflineBanner = provider != null && !isLocal && mode == NetworkMode.Offline && !(loopback && allowLoopback),
            route = when {
                isLocal || loopback -> Route.LOCAL_CPU
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
            loaded = true,
        )
    }

    val uiState: StateFlow<ChatUiState> = combine(baseState, live) { state, l -> state.copy(live = l) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState(isIncognito = conversations.isIncognito(conversationId)))

    /** The composer's text: kept here, not in the saved instance state, so a huge paste cannot overflow it. */
    var draft: String = savedState.get<String>(KEY_DRAFT).orEmpty()
        private set

    fun setDraft(text: String) {
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
        )
    }

    // -----------------------------------------------------------------------------------------
    // Intents

    /** Sends a prompt (with the attached images) or runs a slash command. */
    fun send(text: String) {
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
    fun attach(uri: Uri) {
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
    fun toggleDictation() {
        when (dictation.value) {
            Dictation.RECORDING -> stopRecording.set(true)
            Dictation.TRANSCRIBING -> Unit
            Dictation.IDLE -> viewModelScope.launch {
                val path = speechModelPath()
                if (path == null) {
                    notice.value = "Get a speech model in Models → speech to dictate"
                    return@launch
                }
                stopRecording.set(false)
                dictation.value = Dictation.RECORDING
                try {
                    val audio = speech.record(stopped = stopRecording::get)
                    dictation.value = Dictation.TRANSCRIBING
                    val text = speech.transcribe(path, audio)
                    if (text.isNotBlank()) dictated.value = text
                } catch (e: Exception) {
                    notice.value = "Dictation failed: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                    dictation.value = Dictation.IDLE
                }
            }
        }
    }

    fun consumeDictation() {
        dictated.value = null
    }

    private suspend fun speechModelPath(): String? =
        library.all().firstOrNull { it.isReady && it.engine == EngineKind.WHISPER }?.paths?.get(FileRole.MODEL)

    /** Reads an answer aloud, on the device only (see [Speakers]); tapping again stops. */
    fun speak(key: String, text: String) {
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

    fun setVoiceMode(on: Boolean) {
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

    fun setHandsFree(on: Boolean) = voiceMode.update { it?.copy(handsFree = on) }

    /** The voice button: talk, send what was said, stop the agent, or interrupt the answer and talk. */
    fun voiceTap() {
        when (voiceMode.value?.phase ?: return) {
            VoicePhase.READY -> listen()
            VoicePhase.LISTENING -> stopRecording.set(true)
            VoicePhase.TRANSCRIBING -> Unit
            VoicePhase.THINKING -> stop()
            VoicePhase.SPEAKING -> {
                speakers.stopAll()
                listen()
            }
        }
    }

    /** The microphone permission was refused from the voice button. */
    fun voiceDenied() = voiceFailed("Microphone access is off — allow it in Settings to talk")

    private fun listen() {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            val path = speechModelPath() ?: return@launch voiceFailed("Get a speech model in Models › speech")
            stopRecording.set(false)
            setPhase(VoicePhase.LISTENING)
            val handsFree = voiceMode.value?.handsFree == true
            val detector = EndOfSpeech()
            var nothingSaid = false
            try {
                val audio = speech.record(stopped = stopRecording::get) { peak ->
                    voiceMode.update { it?.copy(levels = (it.levels + peak).takeLast(LEVEL_HISTORY)) }
                    if (handsFree) {
                        when (detector.onLevel(peak, SystemClock.elapsedRealtime())) {
                            SpeechVerdict.LISTENING -> Unit
                            SpeechVerdict.ENDED -> stopRecording.set(true)
                            SpeechVerdict.NO_SPEECH -> {
                                nothingSaid = true
                                stopRecording.set(true)
                            }
                        }
                    }
                }
                if (nothingSaid) {
                    audio.delete()
                    return@launch voiceFailed("Didn't catch that")
                }
                setPhase(VoicePhase.TRANSCRIBING)
                val text = VoiceText.transcript(speech.transcribe(path, audio))
                if (text.isEmpty()) return@launch voiceFailed("Didn't catch that")
                turn = VoiceTurn(uiState.value.items.mapTo(HashSet()) { it.key }, SystemClock.elapsedRealtime())
                setPhase(VoicePhase.THINKING)
                send(text)
                // Re-check once the prompt could have been dropped without any state change.
                delay(VoiceTurn.TIMEOUT_MS)
                onVoiceState(uiState.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voiceFailed("Voice failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Follows the agent during a voice turn: announces approvals, reads the answer when it is done. */
    private fun onVoiceState(state: ChatUiState) {
        val current = turn ?: return
        if (voiceMode.value?.phase != VoicePhase.THINKING) return
        val waiting = state.approvals.keys + listOfNotNull(state.question?.callId)
        if (current.needsAnnouncement(waiting)) {
            val line = if (state.question != null) "The agent has a question." else "Needs your approval."
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
        val finished = current.update(state.items, state.running, SystemClock.elapsedRealtime()) ?: return
        turn = null
        val reply = finished.reply ?: return setPhase(VoicePhase.READY)
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            setPhase(VoicePhase.SPEAKING)
            try {
                speakers.best().read(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeakerUnavailable) {
                return@launch voiceFailed(e.message.orEmpty())
            } catch (e: Exception) {
                return@launch voiceFailed("Voice failed: ${userMessage(e)}")
            }
            if (voiceMode.value?.handsFree == true) listen() else setPhase(VoicePhase.READY)
        }
    }

    private fun setPhase(phase: VoicePhase) = voiceMode.update { it?.copy(phase = phase, levels = emptyList()) }

    private fun voiceFailed(message: String) {
        notice.value = message
        setPhase(VoicePhase.READY)
    }

    private fun stopVoiceWork() {
        voiceJob?.cancel()
        stopRecording.set(true)
        speakers.stopAll()
        turn = null
        setPhase(VoicePhase.READY)
    }

    override fun onCleared() {
        readJob?.cancel()
        stopVoiceWork()
        host.release(conversationId)
        super.onCleared()
    }

    fun removeAttachment(id: String) {
        pending.update { it - id }
        viewModelScope.launch { runCatching { attachments.delete(id) } }
    }

    /** Small preview of an attachment for the composer and the transcript. */
    suspend fun attachmentThumbnail(id: String): ImageBitmap? {
        attachmentThumbs[id]?.let { return it }
        val bytes = runCatching { attachments.bytes(id) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }?.also { attachmentThumbs[id] = it }
    }

    private fun runCommand(input: String): Boolean {
        val parts = input.removePrefix("/").split(Regex("\\s+"), limit = 2)
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        when (parts[0].lowercase()) {
            "compact" -> compact()
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
        viewModelScope.launchSafely(::failed) { host.agent(conversationId).cancel() }
    }

    fun approve(callId: String, outcome: ApprovalOutcome) = host.answerApproval(callId, outcome)

    fun answer(callId: String, answers: List<String>?) = host.answerQuestion(callId, answers)

    fun selectProvider(providerId: String) {
        viewModelScope.launchSafely(::failed) {
            val provider = providers.getProvider(providerId) ?: return@launchSafely
            host.setRoute(conversationId, provider.id, provider.defaultModel)
        }
    }

    /**
     * Switches the chat to [model]. A model whose provider trains on prompts needs [confirmed]
     * (the picker asks first); `/model` with one only explains why it did not switch.
     */
    fun selectModel(model: String, confirmed: Boolean = false) {
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

    fun setToolsEnabled(enabled: Boolean) {
        viewModelScope.launchSafely(::failed) { host.setToolsEnabled(conversationId, enabled) }
    }

    fun setPermission(preset: PermissionPreset) {
        viewModelScope.launchSafely(::failed) { host.setPermission(conversationId, preset) }
    }

    fun compact() = host.compact(conversationId) { e -> notice.value = "Compact failed: ${userMessage(e)}" }

    fun rename(title: String) {
        viewModelScope.launch { conversations.rename(conversationId, title) }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { conversations.setPinned(listOf(conversationId), pinned) }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { conversations.setArchived(listOf(conversationId), archived) }
    }

    /** Deletes this chat (undoable from the list for a few seconds); a running turn is cancelled first. */
    fun delete() {
        stopVoiceWork()
        chats.trash(listOf(conversationId), uiState.value.title)
    }

    /** Undoes one file step (and later changes to the same files). */
    fun undoStep(callId: String) = undo { host.undoStep(conversationId, callId) }

    /** Undoes every file change of [turn]. */
    fun undoTurn(turn: Int) = undo { host.undoTurn(conversationId, turn) }

    /** Re-applies what an undo put back. */
    fun redo(revertId: String) = undo { host.redo(conversationId, revertId) }

    private fun undo(op: suspend () -> Any?) {
        viewModelScope.launchSafely({ e -> notice.value = "Undo failed: ${userMessage(e)}" }) {
            if (uiState.value.running) {
                notice.value = "Wait for the turn to finish, then undo"
                return@launchSafely
            }
            if (op() == null) notice.value = "Nothing to undo: the copies are gone or the files already match"
        }
    }

    fun dismissNotice() = notice.update { null }

    /** Decrypted gallery thumbnail for tool cards (cached for the screen's lifetime). */
    suspend fun thumbnail(galleryId: String): ImageBitmap? {
        thumbs[galleryId]?.let { return it }
        val bytes = runCatching { gallery.thumbnail(galleryId) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
            ?.also { thumbs[galleryId] = it }
    }

    /** "This phone" also offers the OS's model (Gemini Nano in the `playServices` build) when it can run. */
    private fun Provider.localSystemModel(): List<String> =
        listOfNotNull(systemModel.status.value?.takeIf { kind == ProviderKind.LOCAL && it.usable }?.id)

    /** Model switcher "refresh": refetches the selected provider's list (kill switch, route and audit log apply). */
    fun refreshModels() {
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
                cache != null -> "${cache.models.size} models · ${cache.ageLabel(System.currentTimeMillis())}"
                else -> "offline list"
            },
            canRefresh = !local && preset.modelsApi != ModelsApi.NONE,
            presetId = preset.id,
        )
    }
}
