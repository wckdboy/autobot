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
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.NodeAgent
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

    data class User(override val key: String, val text: String) : AgentRow
    data class Reply(override val key: String, val text: String, val route: String?) : AgentRow
    /** A tool run, a refusal, or a failure: one short line. */
    data class Note(override val key: String, val text: String, val tone: Tone) : AgentRow
    data class Question(override val key: String, val id: String, val text: String, val action: AgentAction, val answer: ApprovalAnswer? = null) : AgentRow

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
)

/** Folds a turn's events into the transcript; pure, so the screen's logic is testable on its own. */
object AgentTranscript {
    fun apply(rows: List<AgentRow>, event: AgentEvent, route: String?, key: String): List<AgentRow> = when (event) {
        is AgentEvent.Tool -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.TOOL)
        is AgentEvent.Refused -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.REFUSED)
        is AgentEvent.Approval -> rows + AgentRow.Question(key, event.id, event.text, event.action)
        is AgentEvent.Answer -> rows + AgentRow.Reply(key, event.text, route)
        is AgentEvent.Failed -> rows + AgentRow.Note(key, event.text, AgentRow.Tone.ERROR)
        is AgentEvent.Receipt -> rows
    }

    fun answered(rows: List<AgentRow>, id: String, answer: ApprovalAnswer): List<AgentRow> =
        rows.map { if (it is AgentRow.Question && it.id == id) it.copy(answer = answer) else it }

    /** "qwen3.5-4b · node" for the last receipt. */
    fun route(event: AgentEvent.Receipt): String = listOfNotNull(event.model, event.tier).joinToString(" · ")
}

@HiltViewModel(assistedFactory = NodeAgentViewModel.Factory::class)
class NodeAgentViewModel @AssistedInject constructor(
    @Assisted private val providerId: String,
    private val providers: ProviderRepository,
    private val agents: NodeAgents,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(providerId: String): NodeAgentViewModel
    }

    private val _state = MutableStateFlow(NodeAgentUiState())
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
            session = NodeAgent.sessionFor(device)
            runCatching {
                val info = agent.info()
                val history = agent.history(session).map { line ->
                    when (line.role) {
                        "user" -> AgentRow.User(nextKey(), line.text)
                        "tool" -> AgentRow.Note(nextKey(), line.text, AgentRow.Tone.TOOL)
                        else -> AgentRow.Reply(nextKey(), line.text, null)
                    }
                }
                _state.update { it.copy(info = info, rows = history) }
            }.onFailure { e -> _state.update { it.copy(problem = e.message ?: "The node did not answer.") } }
        }
    }

    fun send(text: String) {
        val agent = agent ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.running) return
        _state.update { it.copy(rows = it.rows + AgentRow.User(nextKey(), trimmed), running = true) }
        turn = viewModelScope.launch {
            var route: String? = null
            try {
                agent.turn(session, trimmed).collect { event ->
                    if (event is AgentEvent.Receipt) route = AgentTranscript.route(event)
                    _state.update { it.copy(rows = AgentTranscript.apply(it.rows, event, route, nextKey())) }
                }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun answer(id: String, answer: ApprovalAnswer) {
        val agent = agent ?: return
        _state.update { it.copy(rows = AgentTranscript.answered(it.rows, id, answer)) }
        viewModelScope.launch {
            val ok = runCatching { agent.answer(id, answer) }.getOrDefault(false)
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

    private fun nextKey() = "r${counter++}"
}
