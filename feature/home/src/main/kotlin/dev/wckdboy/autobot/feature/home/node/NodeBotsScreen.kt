package dev.wckdboy.autobot.feature.home.node

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.EmptyContent
import dev.wckdboy.autobot.core.designsystem.component.InfoItem
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.Loading
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.Subheader
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.invincible.AgentSession
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeBot
import dev.wckdboy.autobot.shared.invincible.NodeBotDetail
import dev.wckdboy.autobot.shared.invincible.NodeBotDetails
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
 * channel kinds as quiet labels, a channel that is down in red (words first), and quiet meta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BotRow(bot: NodeBot, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val large = isLargeText()
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(bot.name, Modifier.weight(1f))
                // A large font puts the model with the state lines, where it has the row's width.
                if (bot.model.isNotEmpty() && !large) TonalLabel(bot.model)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (bot.persona.isNotEmpty()) Text(bot.persona)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(NodeBots.status(bot), color = MaterialTheme.colorScheme.onSurface)
                    val kinds = NodeBots.kinds(bot)
                    if (kinds.isNotEmpty()) TonalLabel(kinds.joinToString(" · "))
                    if (bot.model.isNotEmpty() && large) TonalLabel(bot.model)
                }
                NodeBots.problem(bot)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(NodeBots.meta(bot, now))
            }
        },
        // One element for a screen reader: its lines read in order, words first.
        modifier = modifier
            .testTag("bot-${bot.name}")
            .clickable(role = Role.Button, onClickLabel = "Open ${bot.name}", onClick = onClick),
    )
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
    /** Its personality, profile, usage and insights; null until loaded (each part null on an older node). */
    val detail: NodeBotDetail? = null,
    /** A personality being set: its name ("none" for none). */
    val settingPersonality: String? = null,
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
                        detail = it.detail ?: NodeBotDetails.sample(name),
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
            val edits = personalityEdits
            val detail = NodeBotDetails.fetch(found.first, name)
            // A personality chosen while this was asked for wins over what the node said before it.
            _state.update { it.copy(detail = if (edits != personalityEdits) detail.copy(personalities = it.detail?.personalities ?: detail.personalities) else detail) }
        }
    }

    /** Sets the bot's personality ([personality] null for none), shown at once and put back when the node says no. */
    /** Personalities set here, so a refresh that started before one does not undo it on screen. */
    private var personalityEdits = 0

    fun setPersonality(personality: String?) {
        val before = _state.value.detail?.personalities ?: return
        personalityEdits++
        val wanted = personality?.takeUnless { it == NodeBotDetails.NONE }
        _state.update { s -> s.copy(detail = s.detail?.copy(personalities = before.copy(active = wanted)), settingPersonality = wanted ?: NodeBotDetails.NONE, problem = null) }
        if (demo) return _state.update { it.copy(settingPersonality = null) }
        val agent = agent ?: return
        viewModelScope.launch {
            runCatching { agent.setPersonality(wanted, name) }
                .onSuccess { active -> _state.update { s -> s.copy(detail = s.detail?.copy(personalities = before.copy(active = active)), settingPersonality = null) } }
                .onFailure { e -> _state.update { s -> s.copy(detail = s.detail?.copy(personalities = before), settingPersonality = null, problem = e.message ?: "The node did not answer.") } }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (demo) {
            _state.update { s ->
                s.copy(bots = s.bots.copy(bots = s.bots.bots.map { if (it.name == name) NodeBots.switched(it, enabled) else it }, notice = NodeBotDetails.switched(name, dev.wckdboy.autobot.shared.invincible.BotSwitch(enabled, status = if (enabled) "running" else "stopped"))))
            }
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
        onPersonality = viewModel::setPersonality,
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
    onPersonality: (String?) -> Unit = {},
) {
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val bot = state.bot
    val large = isLargeText()
    Scaffold(
        topBar = {
            AppBar(
                title = "Bot · ${state.name}",
                onBack = onBack,
                actions = { IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            )
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            LazyColumn(width.fillMaxHeight().testTag("node-bot"), contentPadding = PaddingValues(bottom = 24.dp)) {
                state.problem?.let {
                    item(key = "problem") { NoticeCard(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), tone = NoticeTone.WARNING, action = "Retry", onAction = onRefresh) }
                }
                if (bot == null) {
                    item(key = "empty") {
                        when {
                            state.bots.loading || state.bots.supported == null && state.problem == null -> Loading()
                            else -> EmptyContent("There is no bot called ${state.name} on ${state.node}.", action = "Refresh", onAction = onRefresh)
                        }
                    }
                    return@LazyColumn
                }
                item(key = "head") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The name is in the bar; the head says who it is, after Invincible's pixel i (about the node), as on iOS.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NodeMark(size = 14.dp)
                            if (bot.persona.isNotEmpty()) Text(bot.persona, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(labelCase("on ${state.node} · ${NodeBots.meta(bot, state.now)}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "switch") {
                    val failed = bot.status == "error"
                    ListItem(
                        headlineContent = { Text("On") },
                        supportingContent = {
                            Text(
                                NodeBots.status(bot) + (bot.error?.takeIf { failed }?.let { ": $it ✗" } ?: ""),
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = { Switch(checked = bot.enabled, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = bot.enabled, role = Role.Switch) { on -> if (on) onEnabled(true) else confirmOff = true },
                    )
                    // What the node said it did ("ops is running.").
                    state.bots.notice?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item(key = "channels-label") { Subheader(labelCase("channels")) }
                if (bot.channels.isEmpty()) {
                    item(key = "no-channels") { Text("Only through the API.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(bot.channels.withIndex().toList(), key = { "c-${it.index}" }) { (_, c) ->
                    val stateText: @Composable () -> Unit = {
                        Text(
                            NodeBots.channelState(c),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.down) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ListItem(
                        headlineContent = { Text(c.kind) },
                        supportingContent = if (c.account.isEmpty() && !large) null else {
                            {
                                Column {
                                    if (c.account.isNotEmpty()) Text(c.account)
                                    // A large font puts the state under the account, where it has the row's width.
                                    if (large) stateText()
                                }
                            }
                        },
                        trailingContent = if (large) null else stateText,
                    )
                }
                item(key = "model") {
                    Subheader(labelCase("model and approvals"))
                    InfoItem(labelCase("model"), bot.model.ifEmpty { "auto" })
                    InfoItem(labelCase("approvals"), bot.approvals, supporting = NodeBots.approvals(bot.approvals))
                }
                botDetailItems(state.detail, bot.name, state.node, state.settingPersonality, onPersonality)
                if (state.approvals.isNotEmpty()) {
                    item(key = "waiting-label") { Subheader(labelCase("waiting for you · ${state.approvals.size}")) }
                    items(state.approvals, key = { "a-${it.id}" }) { a ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            QuestionCard(
                                AgentRow.Question("q-${a.id}", a.id, a.text, a.action, answer = state.answered[a.id], agent = a.agent),
                                onAnswer,
                                onInstead,
                            )
                        }
                    }
                }
                item(key = "sessions-label") { Subheader(labelCase("recent sessions")) }
                when (val sessions = state.sessions) {
                    null -> item(key = "sessions-loading") { Loading() }
                    else -> {
                        if (sessions.isEmpty()) {
                            item(key = "no-sessions") { Text("No sessions yet.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        items(sessions.take(MAX_SESSIONS), key = { "s-${it.id}" }) { s ->
                            ListItem(
                                headlineContent = { Text(s.id, style = AutobotTheme.styles.code, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingContent = { Text(NodeBots.ago(s.modified, state.now)) },
                                modifier = Modifier.clickable(role = Role.Button) { onOpenSession(s.id) },
                            )
                        }
                    }
                }
                item(key = "new") {
                    Button(onClick = onNewChat, enabled = bot.enabled, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("New chat with ${bot.name}") }
                }
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
                }) { Text("Turn off", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

private const val MAX_SESSIONS = 8
