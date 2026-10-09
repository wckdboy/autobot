package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
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
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatusPill
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
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
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class RemoteUiState(
    val providers: List<Provider> = emptyList(),
    val backends: List<DiffusionBackend> = emptyList(),
    val accounts: AccountState = AccountState(),
    val network: PrivacyStatus = PrivacyStatus.OFFLINE,
)

@HiltViewModel
class RemoteViewModel @Inject constructor(
    providers: ProviderRepository,
    backends: DiffusionBackendRepository,
    accounts: ModelAccounts,
    policy: NetworkPolicy,
) : ViewModel() {
    val uiState: StateFlow<RemoteUiState> = combine(providers.observeProviders(), backends.backends, accounts.state, policy.mode) { p, b, a, m ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUiState())
}

/** REMOTE tab: everything that leaves the phone — APIs, PCs on the LAN, hub accounts, the route. */
@Composable
fun RemoteRoute(
    onOpenProviders: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenPrivacy: () -> Unit,
    viewModel: RemoteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(topBar = { AutobotTopBar(title = "Remote", actions = { PrivacyStatusPill(state.network, onClick = onOpenPrivacy) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item { SectionLabel("models · api & lan") }
            items(state.providers, key = { "p-${it.id}" }) { p ->
                RunRow(p.displayName, "${p.defaultModel} · ${p.baseUrl.substringAfter("://").substringBefore('/')}", onClick = onOpenProviders) {
                    RouteTag(if (Loopback.isLoopbackUrl(p.baseUrl) || p.kind == ProviderKind.OLLAMA) Route.PC_LAN else Route.CLOUD_API)
                }
            }
            item {
                RunRow("Add or edit providers", "deepseek · openrouter · ollama · any openai-compatible", onClick = onOpenProviders) { Tag("open") }
                Spacer(Modifier.height(18.dp))
                SectionLabel("image backends · pc & lan")
            }
            items(state.backends, key = { "b-${it.id}" }) { b ->
                RunRow(b.name, "${b.kind.label} · ${b.baseUrl.substringAfter("://")}", onClick = onOpenBackends) {
                    RouteTag(if (b.isLoopback) Route.LOCAL_CPU else Route.PC_LAN)
                }
            }
            item {
                RunRow("Add or edit backends", "a1111 / forge · local dream host · sse engines", onClick = onOpenBackends) { Tag("open") }
                Spacer(Modifier.height(18.dp))
                SectionLabel("accounts · model hubs")
                RunRow("Hugging Face", state.accounts.huggingFaceUser?.let { "signed in · $it" } ?: "not signed in", onClick = onOpenAccounts) { Tag("open") }
                RunRow("Civitai", (state.accounts.civitaiUser?.let { "key · $it" } ?: "no api key") + " · ${state.accounts.civitaiHost.host}", onClick = onOpenAccounts) { Tag("open") }
                Spacer(Modifier.height(18.dp))
                SectionLabel("privacy")
                RunRow("Privacy Center", "network mode · kill switch · audit log · panic wipe", onClick = onOpenPrivacy) { Tag("open") }
                Spacer(Modifier.height(12.dp))
                MicroLabel("on-device models never use the network; everything here goes through the kill switch")
            }
        }
    }
}
