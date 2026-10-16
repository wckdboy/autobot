package dev.wckdboy.autobot.feature.settings

import android.util.Log
import dev.wckdboy.autobot.agent.runtime.ChatLibrary
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.SettingsSchema
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NavRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.SwitchRow
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.feature.settings.backup.BackupDialog
import dev.wckdboy.autobot.feature.settings.search.WebSearchDialog
import dev.wckdboy.autobot.shared.search.SearchProvider
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val appLockEnabled: Boolean = true,
    val appLockAvailable: Boolean = true,
    val lockTimeoutMillis: Long = AppSettings.DEFAULT_LOCK_TIMEOUT_MILLIS,
    val incognitoByDefault: Boolean = false,
    val memoryEnabled: Boolean = false,
    val vaultEnabled: Boolean = false,
    val quotaFallback: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.NONE,
    val offline: Boolean = false,
    /** "Offline", "Direct", "Tor", "SOCKS5": the Privacy Center row's value. */
    val networkLabel: String = "Direct",
    val providerCount: Int = 0,
)

/** The network mode as one word, as on iOS. */
fun networkLabel(mode: NetworkMode): String = when (mode) {
    NetworkMode.Offline -> "Offline"
    NetworkMode.Direct -> "Direct"
    is NetworkMode.Tor -> "Tor"
    is NetworkMode.Socks5 -> "SOCKS5"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val chats: ChatLibrary,
    providers: ProviderRepository,
    appLockManager: AppLockManager,
) : ViewModel() {
    private val lockAvailable = appLockManager.canAuthenticate()

    val uiState: StateFlow<SettingsUiState> = combine(settings.settings, providers.observeProviders()) { it, list ->
        SettingsUiState(
            themeMode = it.themeMode,
            amoled = it.amoled,
            dynamicColor = it.dynamicColor,
            appLockEnabled = it.appLockEnabled,
            appLockAvailable = lockAvailable,
            lockTimeoutMillis = it.lockTimeoutMillis,
            incognitoByDefault = it.incognitoByDefault,
            memoryEnabled = it.memoryEnabled,
            vaultEnabled = it.vaultEnabled,
            quotaFallback = it.quotaFallback,
            searchProvider = it.searchProvider,
            offline = it.networkMode == NetworkMode.Offline,
            networkLabel = networkLabel(it.networkMode),
            providerCount = list.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appLockAvailable = lockAvailable))

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setAmoled(enabled: Boolean) = launch { settings.setAmoled(enabled) }
    fun setDynamicColor(enabled: Boolean) = launch { settings.setDynamicColor(enabled) }
    fun setAppLock(enabled: Boolean) = launch { settings.setAppLockEnabled(enabled) }
    fun setLockTimeout(millis: Long) = launch { settings.setLockTimeoutMillis(millis) }
    fun setIncognitoByDefault(enabled: Boolean) = launch { settings.setIncognitoByDefault(enabled) }
    fun setMemoryEnabled(enabled: Boolean) = launch { settings.setMemoryEnabled(enabled) }
    fun setQuotaFallback(enabled: Boolean) = launch { settings.setQuotaFallback(enabled) }

    /** Deletes every chat, stored and incognito, for good (running turns are stopped first). */
    fun deleteAllChats() = launch { chats.deleteAll() }

    /** A setting (or the deletion) that cannot be stored (disk full, I/O error) stays as it was instead of crashing. */
    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launchSafely({ Log.w("Settings", "cannot save a setting", it) }) { block() }
    }
}

val LockTimeoutOptions: List<Pair<Long, String>> =
    SettingsSchema.LOCK_TIMEOUTS_MILLIS.zip(listOf("Immediately", "30 s", "1 min", "5 min", "15 min"))

@Composable
fun SettingsRoute(
    onBack: (() -> Unit)?,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenImageBackends: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    version: String = "",
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteChats by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteChats) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChats = false },
            title = { Text("Delete all chats?") },
            text = { Text("Every session is removed from this phone. Folders, memory and files stay.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllChats()
                    confirmDeleteChats = false
                }) { Text("DELETE ALL", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChats = false }) { Text("CANCEL") } },
        )
    }
    if (showBackup) BackupDialog(onDismiss = { showBackup = false })
    if (showSearch) WebSearchDialog(onDismiss = { showSearch = false })
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenProviders = onOpenProviders,
        onOpenPrivacyCenter = onOpenPrivacyCenter,
        onOpenImageBackends = onOpenImageBackends,
        onOpenMcpServers = onOpenMcpServers,
        onThemeMode = viewModel::setThemeMode,
        onAmoled = viewModel::setAmoled,
        onDynamicColor = viewModel::setDynamicColor,
        onAppLock = viewModel::setAppLock,
        onLockTimeout = viewModel::setLockTimeout,
        onIncognitoByDefault = viewModel::setIncognitoByDefault,
        onOpenBackup = { showBackup = true },
        onMemoryEnabled = viewModel::setMemoryEnabled,
        onOpenMemory = onOpenMemory,
        onOpenVault = onOpenVault,
        onOpenWebSearch = { showSearch = true },
        onQuotaFallback = viewModel::setQuotaFallback,
        onDeleteAllChats = { confirmDeleteChats = true },
        version = version,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: (() -> Unit)?,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenImageBackends: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAmoled: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onLockTimeout: (Long) -> Unit,
    onIncognitoByDefault: (Boolean) -> Unit,
    onOpenBackup: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    onMemoryEnabled: (Boolean) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onQuotaFallback: (Boolean) -> Unit = {},
    onDeleteAllChats: () -> Unit = {},
    version: String = "",
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SYSTEM", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Connections")
            NavRow("Model providers", onOpenProviders, value = state.providerCount.takeIf { it > 0 }?.toString())
            NavRow("MCP servers", onOpenMcpServers)
            NavRow("Image backends", onOpenImageBackends)
            NavRow("Privacy Center", onOpenPrivacyCenter, value = state.networkLabel)
            Divider()

            SectionHeader("Agent")
            SwitchRow("Memory", state.memoryEnabled, onMemoryEnabled, info = "The agent recalls and saves notes, kept encrypted on this phone.")
            NavRow("Memory notes", onOpenMemory)
            NavRow("Vault", onOpenVault, value = if (state.vaultEnabled) "On" else "Off")
            SwitchRow(
                "Fallback on limit",
                state.quotaFallback,
                onQuotaFallback,
                info = "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so.",
            )
            NavRow(
                "Web search",
                onOpenWebSearch,
                value = if (state.searchProvider == SearchProvider.NONE) "Off" else state.searchProvider.label,
                info = "Used by sessions with agent tools on. Only the query is sent, through your network route.",
            )
            if (state.searchProvider != SearchProvider.NONE && state.offline) Note("Offline mode blocks searches", MaterialTheme.colorScheme.error)
            Divider()

            SectionHeader("Appearance")
            // The ink segmented control: its selection block slides and leaves a registration ghost.
            Segmented(
                ThemeMode.entries,
                state.themeMode,
                onThemeMode,
                { it.name.lowercase() },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchRow("AMOLED black", state.amoled, onAmoled, enabled = state.themeMode != ThemeMode.LIGHT)
            SwitchRow("Dynamic color", state.dynamicColor, onDynamicColor, info = "Use the wallpaper's colors instead of the ink palette.")
            Divider()

            SectionHeader("Security")
            SwitchRow(
                "App lock",
                state.appLockEnabled && state.appLockAvailable,
                onAppLock,
                state = if (state.appLockAvailable) null else "Needs a screen lock",
                info = if (state.appLockAvailable) "Require biometrics or the device credential." else null,
                enabled = state.appLockAvailable,
            )
            if (state.appLockEnabled && state.appLockAvailable) {
                Text(
                    "Lock after",
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LockTimeoutOptions.forEach { (millis, label) ->
                        FilterChip(
                            selected = state.lockTimeoutMillis == millis,
                            onClick = { onLockTimeout(millis) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            SwitchRow("Incognito by default", state.incognitoByDefault, onIncognitoByDefault, info = "New chats are kept in memory only, never written to disk.")
            Divider()

            SectionHeader("Data")
            NavRow("Export & import", onOpenBackup, info = "Move sessions, gallery and memory between phones, sealed with a passphrase.")
            NavRow("Delete all chats", onDeleteAllChats, titleColor = MaterialTheme.colorScheme.error, chevron = false)
            Divider()

            SectionHeader("About")
            MicroLabel(
                if (version.isBlank()) "autobot · no analytics" else "autobot $version · no analytics",
                Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = color)

/** `// CONNECTIONS`: the app's section label (as on iOS). */
@Composable
internal fun SectionHeader(text: String) {
    SectionLabel(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun SettingsScreenPreview() {
    AutobotTheme {
        SettingsScreen(
            state = SettingsUiState(),
            onBack = null, onOpenProviders = {}, onOpenPrivacyCenter = {}, onOpenImageBackends = {}, onThemeMode = {}, onAmoled = {},
            onDynamicColor = {}, onAppLock = {}, onLockTimeout = {}, onIncognitoByDefault = {},
        )
    }
}
