package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.RouteChip
import dev.wckdboy.autobot.core.designsystem.component.Subheader
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.models.AccountState
import dev.wckdboy.autobot.core.models.ModelAccounts
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.feature.home.node.BotRow
import dev.wckdboy.autobot.feature.home.node.BotsDemo
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.invincible.NodeBotsState
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** A paired node in Remote: its agent row, and its bots when it has bots mode. */
@Immutable
data class RemoteNode(val id: String, val name: String)

@Immutable
data class RemoteUiState(
    val providers: List<Provider> = emptyList(),
    val backends: List<DiffusionBackend> = emptyList(),
    val accounts: AccountState = AccountState(),
    val network: PrivacyStatus = PrivacyStatus.OFFLINE,
    /** The paired nodes. */
    val nodes: List<RemoteNode> = emptyList(),
    /** Each node's bots, by provider id; a node not asked yet, or too old for bots, shows no list. */
    val bots: Map<String, NodeBotsState> = emptyMap(),
    /** Unix seconds, for the rows' "active 5m ago". */
    val now: Long = 0,
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    private val providers: ProviderRepository,
    backends: DiffusionBackendRepository,
    accounts: ModelAccounts,
    policy: NetworkPolicy,
    private val agents: NodeAgents,
) : ViewModel() {
    private val bots = MutableStateFlow<Map<String, NodeBotsState>>(emptyMap())

    private val base = combine(providers.observeProviders(), backends.backends, accounts.state, policy.mode) { p, b, a, m ->
        val nodes = p.filter { it.kind == ProviderKind.INVINCIBLE_NODE }.map { RemoteNode(it.id, it.displayName) }
        RemoteUiState(
            providers = p.filter { it.kind != ProviderKind.LOCAL },
            backends = b.filter { it.kind != BackendKind.ON_DEVICE },
            accounts = a,
            network = when (m) {
                NetworkMode.Offline -> PrivacyStatus.OFFLINE
                NetworkMode.Direct -> PrivacyStatus.DIRECT
                is NetworkMode.Tor -> PrivacyStatus.TOR
                is NetworkMode.Socks5 -> PrivacyStatus.PROXY
            },
            nodes = nodes,
        )
    }

    val uiState: StateFlow<RemoteUiState> = combine(base, bots) { s, b -> s.copy(bots = b, now = BotsDemo.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())

    /** Asks each paired node for its bots (an older node answers 404: no list, today's agent row). */
    fun refreshBots() {
        viewModelScope.launch {
            providers.observeProviders().first().filter { it.kind == ProviderKind.INVINCIBLE_NODE }.forEach { p ->
                launch {
                    // Debug builds' demo node (`--ez demo true`): the three sample bots.
                    if (BotsDemo.on(p.id)) return@launch bots.update { it + (p.id to BotsDemo.state()) }
                    val state = runCatching { agents.bots(p)?.refresh() }.getOrNull() ?: return@launch
                    bots.update { it + (p.id to state) }
                }
            }
        }
    }

    /** Asks node [providerId] to bring its bots in line with their configuration now; what it did shows under the list. */
    fun reloadBots(providerId: String) {
        viewModelScope.launch {
            if (BotsDemo.on(providerId)) return@launch bots.update { it + (providerId to BotsDemo.state().copy(notice = "Nothing to change.")) }
            val provider = providers.observeProviders().first().firstOrNull { it.id == providerId } ?: return@launch
            bots.update { m -> m + (providerId to (m[providerId] ?: NodeBotsState()).copy(loading = true, notice = null)) }
            val before = bots.value[providerId]
            val state = runCatching { agents.bots(provider)?.reload() }.getOrElse { e -> NodeBotsState(problem = e.message ?: "The node did not answer.") } ?: return@launch
            // A node that did not answer keeps the list it showed, with why.
            val shown = if (state.bots.isEmpty() && state.problem != null && before != null) before.copy(loading = false, problem = state.problem, notice = null) else state
            bots.update { it + (providerId to shown) }
        }
    }
}

/** The Remote tab: everything that leaves the phone — APIs, PCs on the LAN, hub accounts, the route. */
@Composable
fun RemoteRoute(
    onOpenProviders: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenNodeAgent: (providerId: String) -> Unit = {},
    onPairNode: () -> Unit = onOpenProviders,
    onOpenBot: (providerId: String, bot: String) -> Unit = { _, _ -> },
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Each time the tab shows: a bot's state changes on the node, not here.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBots()
        onPauseOrDispose {}
    }
    RemoteScreen(state, onOpenProviders, onOpenBackends, onOpenAccounts, onOpenPrivacy, onOpenNodeAgent, onPairNode, onOpenBot, onReloadBots = viewModel::reloadBots)
}

@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onOpenProviders: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenNodeAgent: (providerId: String) -> Unit = {},
    onPairNode: () -> Unit = onOpenProviders,
    onOpenBot: (providerId: String, bot: String) -> Unit = { _, _ -> },
    onReloadBots: (providerId: String) -> Unit = {},
) {
    val nodes = state.nodes
    Scaffold(
        topBar = {
            AppBar(
                title = "Remote",
                onBack = null,
                // The network route, and the way into Privacy Center.
                actions = { NetworkChip(state.network, onOpenPrivacy, Modifier.padding(end = 8.dp)) },
            )
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            LazyColumn(width.fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                // The Autobot Agent on each paired node: tasks run there, approvals come here.
                item { Subheader(labelCase("agents · my node")) }
                nodes.forEach { n ->
                    item(key = "a-${n.id}") {
                        RemoteItem(
                            "Agent on ${n.name}",
                            "runs there · you approve",
                            onClick = { onOpenNodeAgent(n.id) },
                            // The node's own mark: Invincible's pixel i.
                            leading = { NodeMark() },
                            route = Route.PC_LAN,
                        )
                    }
                    // Bots mode: the node's bots, one row each (an older node has no list).
                    val bots = state.bots[n.id]
                    if (bots?.showList == true) {
                        item(key = "bl-${n.id}") {
                            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Subheader(labelCase(if (nodes.size > 1) "bots · ${n.name}" else "bots"), Modifier.weight(1f))
                                // The node picks up a changed bot.toml or SOUL.md on its own within seconds; this is now.
                                TextButton(onClick = { onReloadBots(n.id) }) { Text("Reload") }
                            }
                        }
                        bots.problem?.let { problem ->
                            item(key = "bp-${n.id}") { Text("$problem ✗", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        bots.notice?.let { notice ->
                            item(key = "bn-${n.id}") { Text(notice, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        items(bots.bots, key = { "bot-${n.id}-${it.name}" }) { bot -> BotRow(bot, state.now, onClick = { onOpenBot(n.id, bot.name) }) }
                    }
                }
                if (nodes.isEmpty()) {
                    // Bringing a PC is a first-run path (the welcome screen's "Pair a PC"): one row away here too.
                    item { RemoteItem("Pair a PC", "your Invincible node · end to end encrypted", onClick = onPairNode, leading = { NodeMark() }, chevron = true) }
                }
                item { Subheader(labelCase("providers")) }
                items(state.providers, key = { "p-${it.id}" }) { p ->
                    RemoteItem(
                        p.displayName,
                        "${p.defaultModel} · ${p.baseUrl.substringAfter("://").substringBefore('/')}",
                        onClick = onOpenProviders,
                        route = if (Loopback.isLoopbackUrl(p.baseUrl) || p.kind == ProviderKind.OLLAMA || p.kind == ProviderKind.INVINCIBLE_NODE) Route.PC_LAN else Route.CLOUD_API,
                    )
                }
                item { RemoteItem(if (state.providers.isEmpty()) "Add an API key or server" else "Add or edit", null, onClick = onOpenProviders, chevron = true) }
                item { Subheader(labelCase("image backends")) }
                items(state.backends, key = { "b-${it.id}" }) { b ->
                    RemoteItem(b.name, "${b.kind.label} · ${b.baseUrl.substringAfter("://")}", onClick = onOpenBackends, route = if (b.isLoopback) Route.LOCAL else Route.PC_LAN)
                }
                item {
                    RemoteItem(if (state.backends.isEmpty()) "Add an image server" else "Add or edit", null, onClick = onOpenBackends, chevron = true)
                    Subheader(labelCase("accounts"))
                    RemoteItem("Hugging Face", state.accounts.huggingFaceUser?.let { "signed in · $it" } ?: "not signed in", onClick = onOpenAccounts, chevron = true)
                    RemoteItem(
                        "Civitai",
                        (state.accounts.civitaiUser?.let { "key · $it" } ?: "no API key") + " · ${state.accounts.civitaiHost.host}",
                        onClick = onOpenAccounts,
                        chevron = true,
                    )
                }
            }
        }
    }
}

/**
 * A Remote list item: [title], a [subtitle], and where it runs ([route]) or a chevron when it
 * opens a screen. A large font moves the route under the text, where it has the row's width.
 */
@Composable
private fun RemoteItem(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    route: Route? = null,
    chevron: Boolean = false,
) {
    val large = isLargeText()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle.isNullOrEmpty() && !(large && route != null)) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!subtitle.isNullOrEmpty()) Text(subtitle)
                    if (large && route != null) RouteChip(route)
                }
            }
        },
        leadingContent = leading,
        trailingContent = when {
            route != null && !large -> { { RouteChip(route) } }
            chevron -> { { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) } }
            else -> null
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

/** The network route as an assist chip ("Tor"), the way into Privacy Center. */
@Composable
private fun NetworkChip(status: PrivacyStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(status.label) },
        modifier = modifier.semantics { contentDescription = "Network: ${status.description}" },
    )
}
