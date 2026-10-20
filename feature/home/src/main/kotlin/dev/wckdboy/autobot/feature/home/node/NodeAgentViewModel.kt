package dev.wckdboy.autobot.feature.home.node

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.invincible.AgentAction
import dev.wckdboy.autobot.shared.invincible.AgentEvent
import dev.wckdboy.autobot.shared.invincible.AgentInfo
import dev.wckdboy.autobot.shared.invincible.AgentSchedule
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeBotDetails
import dev.wckdboy.autobot.shared.invincible.NodeBots
import dev.wckdboy.autobot.shared.invincible.NodeImages
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.wckdboy.autobot.shared.invincible.PendingApproval
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One line of the transcript on screen. */
@Immutable
sealed interface AgentRow {
    val key: String

    /**
     * [photos]: the JPEGs sent with it from here (thumbnails on screen); [photoCount] how many it
     * has, those from the node's history included (their bytes stay on the node).
     */
    data class User(override val key: String, val text: String, val photos: List<ByteArray> = emptyList(), val photoCount: Int = photos.size) : AgentRow {
        override fun equals(other: Any?): Boolean = other is User && other.key == key && other.text == text && other.photoCount == photoCount && other.photos.size == photos.size && other.photos.indices.all { other.photos[it].contentEquals(photos[it]) }
        override fun hashCode(): Int = (key.hashCode() * 31 + text.hashCode()) * 31 + photoCount
    }
    data class Reply(override val key: String, val text: String, val route: String?) : AgentRow
    /** A tool run, a refusal, or a failure: one short line. */
    data class Note(override val key: String, val text: String, val tone: Tone) : AgentRow
    /** What the agent kept on its own after an answer (`profile: …`): a quiet line. */
    data class Kept(override val key: String, val text: String) : AgentRow
    /**
     * [agent]: the team member asking, when it is not the main agent. [instead]: the owner said no,
     * and to do this instead (then [answer] is null).
     */
    data class Question(
        override val key: String,
        val id: String,
        val text: String,
        val action: AgentAction,
        val answer: ApprovalAnswer? = null,
        val agent: String? = null,
        val instead: String? = null,
        /** The bot whose conversation it waits in, shown when the node has several. */
        val bot: String? = null,
    ) : AgentRow {
        val answered: Boolean get() = answer != null || instead != null
    }

    enum class Tone { TOOL, REFUSED, ERROR }
}

@Immutable
data class NodeAgentUiState(
    val node: String = "",
    val info: AgentInfo? = null,
    /** Why the agent cannot be reached right now; null when it can (or is being asked). */
    val problem: String? = null,
    val rows: List<AgentRow> = emptyList(),
    val running: Boolean = false,
    /** Scheduled tasks on the machine; null until loaded. */
    val schedules: List<AgentSchedule>? = null,
    /** Why the last schedule change did not work (a schedule that does not parse, …). */
    val scheduleProblem: String? = null,
    /** What the node and the agent did and know; null until loaded. */
    val activity: NodeActivity? = null,
    val activityProblem: String? = null,
    /** The bot this conversation is with (bots mode); null for the default one. */
    val bot: String? = null,
    /** Photos attached to the message being written (JPEG, downscaled). */
    val photos: List<ByteArray> = emptyList(),
    /** Text handed back to the composer (the model could not see the photo); the screen takes it once. */
    val returned: String? = null,
    /** Why the last photo could not be attached. */
    val photoProblem: String? = null,
) {
    /** The node has more than one bot: the screen says which one this is. */
    val severalBots: Boolean get() = (info?.bots?.size ?: 0) > 1
}

/** Folds a turn's events into the transcript; pure, so the screen's logic is testable on its own. */
object AgentTranscript {
    fun apply(rows: List<AgentRow>, event: AgentEvent, route: String?, key: String): List<AgentRow> = when (event) {
        is AgentEvent.Tool -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.TOOL)
        is AgentEvent.Refused -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.REFUSED)
        is AgentEvent.Approval -> rows + AgentRow.Question(key, event.id, event.text, event.action, agent = event.agent)
        // A team member's answer to the lead: a reply of its own, under its name.
        is AgentEvent.Member -> rows + AgentRow.Reply(key, event.text, event.agent)
        is AgentEvent.Answer -> rows + AgentRow.Reply(key, event.text, route)
        is AgentEvent.Failed -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.ERROR)
        is AgentEvent.Kept -> rows + AgentRow.Kept(key, NodeBotDetails.kept(event.text))
        is AgentEvent.Receipt -> rows
    }

    /**
     * The row whose retry runs the last turn again: the last answer (or failure) after the last
     * message sent, when nothing runs. Null when there is nothing to retry.
     */
    fun retryTarget(rows: List<AgentRow>, running: Boolean): String? {
        if (running) return null
        val user = rows.indexOfLast { it is AgentRow.User }
        if (user < 0) return null
        val last = rows.drop(user + 1).lastOrNull { it is AgentRow.Reply || (it is AgentRow.Note && it.tone == AgentRow.Tone.ERROR) } ?: return null
        // A question still waiting is not a finished turn.
        if (rows.drop(user + 1).any { it is AgentRow.Question && !it.answered }) return null
        return last.key
    }

    /** [rows] as a retry starts: the last turn's rows go, its message stays. */
    fun retrying(rows: List<AgentRow>): List<AgentRow> {
        val user = rows.indexOfLast { it is AgentRow.User }
        return if (user < 0) rows else rows.take(user + 1)
    }

    /** The model could not see the photo: the message goes from the transcript (its text back to the composer). */
    fun unsent(rows: List<AgentRow>, key: String): List<AgentRow> = rows.filterNot { it.key == key }

    fun answered(rows: List<AgentRow>, id: String, answer: ApprovalAnswer): List<AgentRow> =
        rows.map { if (it is AgentRow.Question && it.id == id) it.copy(answer = answer) else it }

    /** "No, do this instead": the question shows what was asked for. */
    fun instead(rows: List<AgentRow>, id: String, text: String): List<AgentRow> =
        rows.map { if (it is AgentRow.Question && it.id == id) it.copy(instead = text) else it }

    /**
     * The approvals waiting on the node (any conversation) as question cards, after [rows]; those
     * already on screen are left as they are.
     */
    fun waiting(rows: List<AgentRow>, approvals: List<PendingApproval>, severalBots: Boolean = false, key: (PendingApproval) -> String): List<AgentRow> {
        val shown = rows.filterIsInstance<AgentRow.Question>().map { it.id }.toSet()
        return rows + approvals.filter { it.id !in shown }.map {
            AgentRow.Question(key(it), it.id, it.text, it.action, agent = it.agent, bot = if (severalBots) NodeBots.of(it.bot) else null)
        }
    }

    /** "qwen3.5-4b · node" for the last receipt. */
    fun route(event: AgentEvent.Receipt): String = listOfNotNull(event.model, event.tier).joinToString(" · ")
}

@HiltViewModel(assistedFactory = NodeAgentViewModel.Factory::class)
class NodeAgentViewModel @AssistedInject constructor(
    @Assisted("provider") private val providerId: String,
    /** A conversation to open instead of this device's own (the one approvals wait in); null for this device's. */
    @Assisted("session") private val openSession: String?,
    /** The bot of [openSession], or with no session, the bot to start a new conversation with. */
    @Assisted("bot") private val bot: String?,
    private val providers: ProviderRepository,
    private val agents: NodeAgents,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("provider") providerId: String, @Assisted("session") openSession: String?, @Assisted("bot") bot: String?): NodeAgentViewModel
    }

    private val _state = MutableStateFlow(NodeAgentUiState(bot = bot))
    val state: StateFlow<NodeAgentUiState> = _state

    private var agent: NodeAgent? = null
    /** The conversation this screen continues: this device's own, one per pairing. */
    private var session = "app"
    private var turn: Job? = null
    private var counter = 0

    init {
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            // Debug builds' demo node: a chat with a photo, an answer and what the agent kept.
            if (BotsDemo.on(providerId)) {
                val photo = withContext(Dispatchers.Default) { NodeChatDemo.photo() }
                _state.update { it.copy(node = BotsDemo.NODE_NAME, info = NodeChatDemo.info, rows = NodeChatDemo.rows(photo), problem = null) }
                return@launch
            }
            val provider = providers.observeProviders().first().firstOrNull { it.id == providerId }
            if (provider == null) {
                _state.update { it.copy(problem = "This node is no longer paired.") }
                return@launch
            }
            _state.update { it.copy(node = provider.displayName, problem = null) }
            val (agent, device) = agents.forProvider(provider) ?: run {
                _state.update { it.copy(problem = "The pairing with ${provider.displayName} is unreadable; pair again in Providers.") }
                return@launch
            }
            this@NodeAgentViewModel.agent = agent
            // A new chat with a bot: a conversation of its own (kept across reconnects).
            val fresh = openSession == null && bot != null
            if (session == "app") session = openSession ?: bot?.let { NodeBots.newSession(device, it, System.currentTimeMillis() / 1000) } ?: NodeAgent.sessionFor(device)
            runCatching {
                val info = agent.info()
                val history = if (fresh && _state.value.rows.isEmpty()) emptyList() else agent.history(session).map { line ->
                    when (line.role) {
                        "user" -> AgentRow.User(nextKey(), line.text, photoCount = line.images.size)
                        "tool" -> AgentRow.Note(nextKey(), line.text, AgentRow.Tone.TOOL)
                        else -> AgentRow.Reply(nextKey(), line.text, null)
                    }
                }
                // Approvals waiting anywhere on the node (an older node cannot list them: none shown).
                val waiting = runCatching { agent.approvals() }.getOrNull().orEmpty()
                _state.update { it.copy(info = info, rows = AgentTranscript.waiting(history, waiting, severalBots = info.bots.size > 1) { nextKey() }) }
            }.onFailure { e -> _state.update { it.copy(problem = e.message ?: "The node did not answer.") } }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        val photos = _state.value.photos
        if (trimmed.isEmpty() || _state.value.running) return
        val key = nextKey()
        _state.update { it.copy(rows = it.rows + AgentRow.User(key, trimmed, photos), running = true, photos = emptyList(), photoProblem = null) }
        if (BotsDemo.on(providerId)) return demoAnswer()
        val agent = agent ?: return _state.update { it.copy(running = false) }
        follow(agent.turn(session, trimmed, bot, photos.map(NodePhotos::base64)), sent = key, text = trimmed)
    }

    /** Runs the last turn again on the node (`POST …/retry`): its rows go, its message stays. */
    fun retry() {
        if (_state.value.running) return
        _state.update { it.copy(rows = AgentTranscript.retrying(it.rows), running = true) }
        if (BotsDemo.on(providerId)) return demoAnswer()
        val agent = agent ?: return _state.update { it.copy(running = false) }
        follow(agent.retry(session), sent = null, text = null)
    }

    /**
     * Folds a turn's events in. The turn ends at its answer (the conversation is free then); the
     * stream stays open a little longer for what the agent kept.
     */
    private fun follow(events: kotlinx.coroutines.flow.Flow<AgentEvent>, sent: String?, text: String?) {
        val job = viewModelScope.launch {
            var route: String? = null
            var current = true
            try {
                events.collect { event ->
                    if (event is AgentEvent.Receipt) route = AgentTranscript.route(event)
                    if (event is AgentEvent.Failed && event.code == NodeImages.CANNOT_SEE && sent != null) {
                        // Said plainly; the text goes back to the composer, the photo does not.
                        _state.update { it.copy(rows = AgentTranscript.unsent(it.rows, sent) + AgentRow.Note(nextKey(), event.text, AgentRow.Tone.ERROR), returned = text, running = false) }
                        current = false
                        return@collect
                    }
                    _state.update { it.copy(rows = AgentTranscript.apply(it.rows, event, route, nextKey())) }
                    if (current && (event is AgentEvent.Answer || event is AgentEvent.Failed)) {
                        current = false
                        _state.update { it.copy(running = false) }
                    }
                }
            } finally {
                if (current) _state.update { it.copy(running = false) }
            }
        }
        turn = job
    }

    private fun demoAnswer() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            _state.update { it.copy(rows = it.rows + AgentRow.Reply(nextKey(), NodeChatDemo.ANSWER, NodeChatDemo.ROUTE), running = false) }
        }
    }

    /** Attaches a photo from the library or the camera: downscaled to [NodeImages.MAX_SIDE], JPEG. */
    fun attach(uri: Uri, fromCamera: Boolean = false) {
        if (_state.value.photos.size >= NodeImages.MAX_PHOTOS) return
        viewModelScope.launch {
            val jpeg = withContext(Dispatchers.Default) { runCatching { NodePhotos.encode(context, uri) }.getOrNull() }
            if (fromCamera) NodePhotos.clearCamera(context)
            if (jpeg == null) return@launch _state.update { it.copy(photoProblem = "Could not read that photo.") }
            _state.update { it.copy(photos = it.photos + jpeg, photoProblem = null) }
        }
    }

    fun removePhoto(index: Int) {
        _state.update { it.copy(photos = it.photos.filterIndexed { i, _ -> i != index }) }
    }

    /** The screen took the returned text into its composer. */
    fun tookReturned() {
        _state.update { it.copy(returned = null) }
    }

    fun photoProblem(text: String) {
        _state.update { it.copy(photoProblem = text) }
    }

    fun answer(id: String, answer: ApprovalAnswer) {
        val agent = agent ?: return
        _state.update { it.copy(rows = AgentTranscript.answered(it.rows, id, answer)) }
        viewModelScope.launch {
            val ok = runCatching { agent.answer(id, answer) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(rows = it.rows + AgentRow.Note(nextKey(), "That question had expired.", AgentRow.Tone.ERROR)) }
        }
    }

    /** "No, and do this instead": answers [id] with [text]. */
    fun answerInstead(id: String, text: String) {
        val agent = agent ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(rows = AgentTranscript.instead(it.rows, id, trimmed)) }
        viewModelScope.launch {
            val ok = runCatching { agent.answerInstead(id, trimmed) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(rows = it.rows + AgentRow.Note(nextKey(), "That question had expired.", AgentRow.Tone.ERROR)) }
        }
    }

    fun newConversation() {
        val agent = agent ?: return
        if (_state.value.running) return
        viewModelScope.launch {
            runCatching { agent.reset(session) }
                .onSuccess { _state.update { it.copy(rows = emptyList()) } }
                .onFailure { e -> _state.update { it.copy(rows = it.rows + AgentRow.Note(nextKey(), e.message ?: "Could not start over.", AgentRow.Tone.ERROR)) } }
        }
    }

    fun loadSchedules() {
        val agent = agent ?: return
        viewModelScope.launch {
            runCatching { agent.schedules() }
                .onSuccess { list -> _state.update { it.copy(schedules = list, scheduleProblem = null) } }
                .onFailure { e -> _state.update { it.copy(scheduleProblem = e.message ?: "The node did not answer.") } }
        }
    }

    /** Adds a scheduled task; [onAdded] runs when it was accepted (so the form can clear). */
    fun addSchedule(schedule: String, task: String, telegram: Boolean, onAdded: () -> Unit) {
        val agent = agent ?: return
        viewModelScope.launch {
            runCatching { agent.addSchedule(schedule.trim(), task.trim(), telegram) }
                .onSuccess {
                    onAdded()
                    loadSchedules()
                }
                .onFailure { e -> _state.update { it.copy(scheduleProblem = e.message ?: "Could not add it.") } }
        }
    }

    fun removeSchedule(id: String) {
        val agent = agent ?: return
        viewModelScope.launch {
            runCatching { agent.removeSchedule(id) }
                .onSuccess { loadSchedules() }
                .onFailure { e -> _state.update { it.copy(scheduleProblem = e.message ?: "Could not remove it.") } }
        }
    }

    fun loadActivity() {
        viewModelScope.launch {
            val provider = providers.observeProviders().first().firstOrNull { it.id == providerId } ?: return@launch
            runCatching { agents.activity(provider) }
                .onSuccess { activity ->
                    _state.update { it.copy(activity = activity, activityProblem = if (activity == null) "The pairing is unreadable; pair again in Providers." else null) }
                }
                .onFailure { e -> _state.update { it.copy(activityProblem = e.message ?: "The node did not answer.") } }
        }
    }

    /** Answers a question a team member asked on the board; [onDone] when it was taken. */
    fun answerBoard(id: String, text: String, onDone: () -> Unit) {
        val agent = agent ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching { agent.answerBoard(id, trimmed) }
                .onSuccess { taken ->
                    if (taken) onDone() else _state.update { it.copy(activityProblem = "That question was already answered.") }
                    loadActivity()
                }
                .onFailure { e -> _state.update { it.copy(activityProblem = e.message ?: "The node did not answer.") } }
        }
    }

    /** Makes the agent forget a remembered fact. */
    fun forget(id: Long) {
        val agent = agent ?: return
        _state.update { s -> s.copy(activity = s.activity?.let { a -> a.copy(facts = a.facts.filterNot { it.id == id }) }) }
        viewModelScope.launch {
            runCatching { agent.forget(id) }.onFailure { e ->
                _state.update { it.copy(activityProblem = e.message ?: "Could not forget it.") }
                loadActivity()
            }
        }
    }

    private fun nextKey() = "r${counter++}"
}
