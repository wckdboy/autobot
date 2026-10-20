package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatusPill
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.component.Tag
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
            val state = runCatching { agents.bots(provider)?.reload() }.getOrNull() ?: return@launch
            bots.update { it + (providerId to state) }
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
            AutobotTopBar(
                title = "Remote",
                mark = true,
                // The network route, and the way into Privacy Center. 8 dp here + the bar's 8 dp = a 16 dp edge margin.
                actions = { PrivacyStatusPill(state.network, Modifier.padding(end = 8.dp), onClick = onOpenPrivacy) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (nodes.isNotEmpty()) {
                // The Autobot Agent on each paired node: tasks run there, approvals come here.
                item { SectionLabel("agents · my node") }
                nodes.forEach { n ->
                    item(key = "a-${n.id}") {
                        RunRow(
                            "Agent on ${n.name}",
                            "runs there · you approve",
                            // The node's own mark: Invincible's pixel i.
                            leading = { NodeMark() },
                            onClick = { onOpenNodeAgent(n.id) },
                        ) {
                            RouteTag(Route.PC_LAN)
                        }
                    }
                    // Bots mode: the node's bots, one row each (an older node has no list).
                    val bots = state.bots[n.id]
                    if (bots?.showList == true) {
                        item(key = "bl-${n.id}") {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel(if (nodes.size > 1) "bots · ${n.name}" else "bots", Modifier.weight(1f))
                                // The node picks up a changed bot.toml or SOUL.md on its own within seconds; this is now.
                                Tag("Reload", onClick = { onReloadBots(n.id) })
                            }
                        }
                        bots.notice?.let { notice -> item(key = "bn-${n.id}") { Text(notice, Modifier.padding(bottom = 4.dp), style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        items(bots.bots, key = { "bot-${n.id}-${it.name}" }) { bot -> BotRow(bot, state.now, onClick = { onOpenBot(n.id, bot.name) }) }
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
            if (nodes.isEmpty()) {
                // Bringing a PC is a first-run path (the welcome screen's "Pair a PC"): one row away here too.
                item {
                    SectionLabel("agents · my node")
                    RunRow("Pair a PC", "your Invincible node · end to end encrypted", leading = { NodeMark() }, onClick = onPairNode) { Chevron() }
                    Spacer(Modifier.height(18.dp))
                }
            }
            item { SectionLabel("providers") }
            items(state.providers, key = { "p-${it.id}" }) { p ->
                RunRow(p.displayName, "${p.defaultModel} · ${p.baseUrl.substringAfter("://").substringBefore('/')}", onClick = onOpenProviders) {
                    RouteTag(if (Loopback.isLoopbackUrl(p.baseUrl) || p.kind == ProviderKind.OLLAMA || p.kind == ProviderKind.INVINCIBLE_NODE) Route.PC_LAN else Route.CLOUD_API)
                }
            }
            item {
                RunRow(if (state.providers.isEmpty()) "Add an API key or server" else "Add or edit", "", onClick = onOpenProviders) { Chevron() }
                Spacer(Modifier.height(18.dp))
                SectionLabel("image backends")
            }
            items(state.backends, key = { "b-${it.id}" }) { b ->
                RunRow(b.name, "${b.kind.label} · ${b.baseUrl.substringAfter("://")}", onClick = onOpenBackends) {
                    RouteTag(if (b.isLoopback) Route.LOCAL else Route.PC_LAN)
                }
            }
            item {
                RunRow(if (state.backends.isEmpty()) "Add an image server" else "Add or edit", "", onClick = onOpenBackends) { Chevron() }
                Spacer(Modifier.height(18.dp))
                SectionLabel("accounts")
                RunRow("Hugging Face", state.accounts.huggingFaceUser?.let { "signed in · $it" } ?: "not signed in", onClick = onOpenAccounts) { Chevron() }
                RunRow("Civitai", (state.accounts.civitaiUser?.let { "key · $it" } ?: "no API key") + " · ${state.accounts.civitaiHost.host}", onClick = onOpenAccounts) { Chevron() }
            }
        }
    }
}

/** A row that opens a screen: the chevron, as in Settings (and on iOS). */
@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
