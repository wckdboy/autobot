package dev.wckdboy.autobot.feature.settings

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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.SettingsSchema
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.feature.settings.backup.BackupDialog
import dev.wckdboy.autobot.feature.settings.search.WebSearchDialog
import dev.wckdboy.autobot.shared.search.SearchProvider
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    val quotaFallback: Boolean = false,
    val searchProvider: SearchProvider = SearchProvider.NONE,
    val offline: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val chats: ChatLibrary,
    appLockManager: AppLockManager,
) : ViewModel() {
    private val lockAvailable = appLockManager.canAuthenticate()

    val uiState: StateFlow<SettingsUiState> = settings.settings.map {
        SettingsUiState(
            themeMode = it.themeMode,
            amoled = it.amoled,
            dynamicColor = it.dynamicColor,
            appLockEnabled = it.appLockEnabled,
            appLockAvailable = lockAvailable,
            lockTimeoutMillis = it.lockTimeoutMillis,
            incognitoByDefault = it.incognitoByDefault,
            memoryEnabled = it.memoryEnabled,
            quotaFallback = it.quotaFallback,
            searchProvider = it.searchProvider,
            offline = it.networkMode == NetworkMode.Offline,
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

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
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
            text = { Text("Every session, its encrypted history, tool calls, approvals and attached images is removed from this phone. Folders, memory notes and workspace files stay. This cannot be undone.") },
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
        onOpenWebSearch = { showSearch = true },
        onQuotaFallback = viewModel::setQuotaFallback,
        onDeleteAllChats = { confirmDeleteChats = true },
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
    onOpenWebSearch: () -> Unit = {},
    onQuotaFallback: (Boolean) -> Unit = {},
    onDeleteAllChats: () -> Unit = {},
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
            NavigationRow("Model providers", "Chat endpoints and API keys", onOpenProviders)
            NavigationRow("MCP servers", "Remote tools for agent sessions, each call approved", onOpenMcpServers)
            NavigationRow("Image backends", "Diffusion servers: on-device sidecar, Local Dream host, A1111/Forge", onOpenImageBackends)
            NavigationRow("Privacy Center", "Network mode, audit log, panic wipe", onOpenPrivacyCenter)
            NavigationRow("Export & import", "Move sessions, gallery and memory between phones, sealed with a passphrase", onOpenBackup)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Agent")
            SwitchRow(
                "Memory",
                "Let the agent recall and save notes, kept encrypted on this phone",
                state.memoryEnabled,
                onMemoryEnabled,
            )
            NavigationRow("Memory notes", "View, search, edit and delete what the agent remembers", onOpenMemory)
            SwitchRow(
                "On limit, fall back",
                "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so",
                state.quotaFallback,
                onQuotaFallback,
            )
            NavigationRow(
                "Web search",
                if (state.searchProvider == SearchProvider.NONE) "Off · SearXNG or Brave Search" else "${state.searchProvider.label} · queries only, through your route",
                onOpenWebSearch,
            )
            Text(
                "Used in sessions with agent tools on (not plain chat)",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.searchProvider != SearchProvider.NONE && state.offline) {
                Text(
                    "Offline mode blocks searches",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                    ) {
                        Text(mode.name.lowercase().replaceFirstChar(Char::titlecase))
                    }
                }
            }
            SwitchRow("AMOLED black", "Pure black backgrounds in dark theme", state.amoled, onAmoled, enabled = state.themeMode != ThemeMode.LIGHT)
            SwitchRow("Dynamic color", "Use wallpaper colors instead of the underground palette", state.dynamicColor, onDynamicColor)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionHeader("Security")
            SwitchRow(
                title = "App lock",
                subtitle = if (state.appLockAvailable) {
                    "Require biometrics or device credential"
                } else {
                    "Set up a screen lock on this device to enable"
                },
                checked = state.appLockEnabled && state.appLockAvailable,
                onChange = onAppLock,
                enabled = state.appLockAvailable,
            )
            if (state.appLockEnabled && state.appLockAvailable) {
                Text(
                    "Lock after leaving the app",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
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
            SwitchRow(
                "Incognito by default",
                "New chats are kept in memory only",
                state.incognitoByDefault,
                onIncognitoByDefault,
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDeleteAllChats).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column {
                    Text("Delete all chats", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Remove every session from this phone; folders and memory stay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
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
