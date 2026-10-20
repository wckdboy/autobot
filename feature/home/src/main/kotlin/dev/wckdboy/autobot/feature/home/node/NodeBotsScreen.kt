package dev.wckdboy.autobot.feature.home.node

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.debug.DemoMode
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.EmptyState
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.designsystem.component.Notice
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.Readout
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.linesFor
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.invincible.AgentSession
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeBot
import dev.wckdboy.autobot.shared.invincible.NodeBots
import dev.wckdboy.autobot.shared.invincible.NodeBotsRepository
import dev.wckdboy.autobot.shared.invincible.NodeBotsState
import dev.wckdboy.autobot.shared.invincible.PendingApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Debug builds' demo (`--ez demo true`, [DemoMode]): the seeded node ("demo-node", studio-mac)
 * has three sample bots, for screenshots and UI work without a node.
 */
object BotsDemo {
    const val NODE_ID = "demo-node"
    const val NODE_NAME = "studio-mac"

    /** [providerId] is the demo node and the demo is on. */
    fun on(providerId: String): Boolean = DemoMode.on && providerId == NODE_ID

    fun now(): Long = System.currentTimeMillis() / 1000

    fun state(now: Long = now()): NodeBotsState = NodeBotsState(bots = NodeBots.ordered(NodeBots.sample(now)), supported = true)
}

/**
 * One bot in Remote's list: its name and model, the persona line, its state in words with the
 * channel kinds as quiet tags, a channel that is down in red (words first), and quiet meta.
 */
@Composable
fun BotRow(bot: NodeBot, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                // One element for a screen reader: its lines read in order, words first.
                .clickable(role = Role.Button, onClickLabel = "Open ${bot.name}", onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp)
                .testTag("bot-${bot.name}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bot.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = linesFor(1, 2), overflow = TextOverflow.Ellipsis)
                if (bot.model.isNotEmpty()) Tag(bot.model, asWritten = true)
            }
            if (bot.persona.isNotEmpty()) {
                Text(bot.persona, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = linesFor(1, 3), overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(NodeBots.status(bot), style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurface)
                val kinds = NodeBots.kinds(bot)
                if (kinds.isNotEmpty()) Tag(kinds.joinToString(" · "), asWritten = true)
            }
            NodeBots.problem(bot)?.let { Text(it, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.error, maxLines = linesFor(2, 4)) }
            Text(NodeBots.meta(bot, now), style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = linesFor(1, 2))
        }
        Hairline()
    }
}

@Immutable
data class NodeBotUiState(
    val node: String = "",
    val name: String = "",
    val bots: NodeBotsState = NodeBotsState(),
    /** Its recent sessions, newest first; null until loaded. */
    val sessions: List<AgentSession>? = null,
    /** Approvals waiting for this bot. */
    val approvals: List<PendingApproval> = emptyList(),
    /** Approvals answered here, by id. */
    val answered: Map<String, ApprovalAnswer> = emptyMap(),
    val problem: String? = null,
    /** Unix seconds, for "active 5m ago". */
    val now: Long = 0,
) {
    val bot: NodeBot? get() = bots.bot(name)
}

/** One bot on a paired node: its state, channels, sessions and approvals; switch it on or off. */
@HiltViewModel(assistedFactory = NodeBotViewModel.Factory::class)
class NodeBotViewModel @AssistedInject constructor(
    @Assisted("provider") private val providerId: String,
    @Assisted("bot") private val name: String,
    private val providers: ProviderRepository,
    private val agents: NodeAgents,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("provider") providerId: String, @Assisted("bot") name: String): NodeBotViewModel
    }

    private val _state = MutableStateFlow(NodeBotUiState(name = name, now = BotsDemo.now()))
    val state: StateFlow<NodeBotUiState> = _state

    private var agent: NodeAgent? = null
    private var repository: NodeBotsRepository? = null

    init {
        refresh()
    }

    private val demo: Boolean get() = BotsDemo.on(providerId)

    fun refresh() {
        viewModelScope.launch {
            val now = BotsDemo.now()
            if (demo) {
                _state.update {
                    it.copy(
                        node = BotsDemo.NODE_NAME,
                        bots = BotsDemo.state(now),
                        sessions = NodeBots.sampleSessions(now).filter { s -> NodeBots.of(s.bot) == name },
                        approvals = NodeBots.sampleApprovals(now).filter { a -> NodeBots.of(a.bot) == name },
                        now = now,
                    )
                }
                return@launch
            }
            val provider = providers.observeProviders().first().firstOrNull { it.id == providerId }
            if (provider == null) {
                _state.update { it.copy(problem = "This node is no longer paired.") }
                return@launch
            }
            _state.update { it.copy(node = provider.displayName, problem = null, now = now) }
            val found = agents.forProvider(provider) ?: run {
                _state.update { it.copy(problem = "The pairing with ${provider.displayName} is unreadable; pair again in Providers.") }
                return@launch
            }
            agent = found.first
            val repo = repository ?: NodeBotsRepository(found.first).also { repository = it }
            val bots = repo.refresh()
            _state.update { it.copy(bots = bots) }
            runCatching { found.first.sessions(name).sortedByDescending { it.modified } }
                .onSuccess { list -> _state.update { it.copy(sessions = list) } }
                .onFailure { e -> _state.update { it.copy(sessions = emptyList(), problem = e.message ?: "The node did not answer.") } }
            val waiting = runCatching { found.first.approvals() }.getOrNull().orEmpty()
            _state.update { it.copy(approvals = waiting.filter { a -> NodeBots.of(a.bot) == name }.sortedBy { a -> a.askedAt }) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (demo) {
            _state.update { s -> s.copy(bots = s.bots.copy(bots = s.bots.bots.map { if (it.name == name) NodeBots.switched(it, enabled) else it })) }
            return
        }
        val repo = repository ?: return
        viewModelScope.launch {
            _state.update { it.copy(bots = repo.state.value.copy(bots = repo.state.value.bots.map { b -> if (b.name == name) NodeBots.switched(b, enabled) else b })) }
            repo.setEnabled(name, enabled)
            _state.update { it.copy(bots = repo.state.value) }
        }
    }

    fun answer(id: String, answer: ApprovalAnswer) {
        _state.update { it.copy(answered = it.answered + (id to answer)) }
        val agent = agent ?: return
        viewModelScope.launch {
            val ok = runCatching { agent.answer(id, answer) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(problem = "That question had expired.") }
        }
    }

    fun answerInstead(id: String, text: String) {
        val agent = agent ?: return
        viewModelScope.launch {
            val ok = runCatching { agent.answerInstead(id, text) }.getOrDefault(false)
            _state.update { s -> if (ok) s.copy(approvals = s.approvals.filterNot { it.id == id }) else s.copy(problem = "That question had expired.") }
        }
    }
}

/** Bot detail; [onOpenSession] opens one of its sessions (or a new one, with a null id) in the agent screen. */
@Composable
fun NodeBotRoute(providerId: String, name: String, onBack: () -> Unit, onOpenSession: (session: String?, bot: String) -> Unit) {
    val viewModel = hiltViewModel<NodeBotViewModel, NodeBotViewModel.Factory>(key = "$providerId/$name") { it.create(providerId, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    NodeBotScreen(
        state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onEnabled = viewModel::setEnabled,
        onOpenSession = { onOpenSession(it, name) },
        onNewChat = { onOpenSession(null, name) },
        onAnswer = viewModel::answer,
        onInstead = viewModel::answerInstead,
    )
}

@Composable
fun NodeBotScreen(
    state: NodeBotUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onOpenSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onAnswer: (String, ApprovalAnswer) -> Unit = { _, _ -> },
    onInstead: (String, String) -> Unit = { _, _ -> },
) {
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val bot = state.bot
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Bot · ${state.name}",
                showMark = false,
                navigation = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    // About the node: Invincible's pixel i.
                    NodeMark(size = 14.dp)
                },
                actions = { IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("node-bot"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.problem?.let { item(key = "problem") { Notice(it, tone = NoticeTone.WARNING, action = "Retry", onAction = onRefresh) } }
            if (bot == null) {
                item(key = "empty") {
                    when {
                        state.bots.loading || state.bots.supported == null && state.problem == null -> InkLoader()
                        else -> EmptyState("There is no bot called ${state.name} on ${state.node}.", action = "Refresh", onAction = onRefresh)
                    }
                }
                return@LazyColumn
            }
            item(key = "head") {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(bot.name, style = MaterialTheme.typography.headlineSmall, maxLines = linesFor(1, 2), overflow = TextOverflow.Ellipsis)
                    if (bot.persona.isNotEmpty()) Text(bot.persona, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionLabel("on ${state.node} · ${NodeBots.meta(bot, state.now)}")
                }
            }
            item(key = "switch") {
                // On the content's gutter (a SwitchRow carries its own), so it lines up with the title.
                val failed = bot.status == "error"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = bot.enabled, role = Role.Switch) { on -> if (on) onEnabled(true) else confirmOff = true }
                        .heightIn(min = 56.dp)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("On", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            NodeBots.status(bot) + (bot.error?.takeIf { failed }?.let { ": $it ✗" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = linesFor(2, 4),
                        )
                    }
                    Switch(checked = bot.enabled, onCheckedChange = null)
                }
                Hairline()
            }
            item(key = "channels-label") { SectionLabel("channels", Modifier.padding(top = 16.dp)) }
            if (bot.channels.isEmpty()) {
                item(key = "no-channels") { Text("Only through the API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(bot.channels.withIndex().toList(), key = { "c-${it.index}" }) { (_, c) ->
                Column {
                    val large = isLargeText()
                    val stateText: @Composable () -> Unit = {
                        Text(
                            NodeBots.channelState(c),
                            style = AutobotTheme.styles.micro,
                            color = if (c.down) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = linesFor(2, 4),
                        )
                    }
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.kind, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            // A large font puts the state under the account, where it has the row's width.
                            if (!large) stateText()
                        }
                        if (c.account.isNotEmpty()) Text(c.account, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = linesFor(1, 3))
                        if (large) stateText()
                    }
                    Hairline()
                }
            }
            item(key = "model") {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("model and approvals")
                    Readout("model", bot.model.ifEmpty { "auto" })
                    Readout("approvals", bot.approvals)
                    Text(NodeBots.approvals(bot.approvals), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.approvals.isNotEmpty()) {
                item(key = "waiting-label") { SectionLabel("waiting for you · ${state.approvals.size}", Modifier.padding(top = 16.dp)) }
                items(state.approvals, key = { "a-${it.id}" }) { a ->
                    QuestionCard(
                        AgentRow.Question("q-${a.id}", a.id, a.text, a.action, answer = state.answered[a.id], agent = a.agent),
                        onAnswer,
                        onInstead,
                    )
                }
            }
            item(key = "sessions-label") { SectionLabel("recent sessions", Modifier.padding(top = 16.dp)) }
            when (val sessions = state.sessions) {
                null -> item(key = "sessions-loading") { InkLoader() }
                else -> {
                    if (sessions.isEmpty()) item(key = "no-sessions") { Text("No sessions yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(sessions.take(MAX_SESSIONS), key = { "s-${it.id}" }) { s ->
                        Column {
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenSession(s.id) }.heightIn(min = 48.dp).padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(s.id, Modifier.weight(1f), style = AutobotTheme.styles.readout, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(NodeBots.ago(s.modified, state.now), style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Hairline()
                        }
                    }
                }
            }
            item(key = "new") {
                Spacer(Modifier.height(16.dp))
                PrimaryButton("New chat with ${bot.name}", onNewChat, Modifier.fillMaxWidth(), enabled = bot.enabled)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmOff && bot != null) {
        val kinds = NodeBots.kinds(bot).filter { it != "api" }
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn off ${bot.name}?") },
            text = {
                Text(
                    if (kinds.isEmpty()) "It stops answering until you turn it on again."
                    else "It stops answering on ${kinds.joinToString(" and ")} until you turn it on again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEnabled(false)
                    confirmOff = false
                }) { ActionText("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { ActionText("Cancel") } },
        )
    }
}

private const val MAX_SESSIONS = 8
