package dev.wckdboy.autobot.feature.settings

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
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.security.AppLockManager
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(appLockAvailable = lockAvailable))

    fun setThemeMode(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun setAmoled(enabled: Boolean) = launch { settings.setAmoled(enabled) }
    fun setDynamicColor(enabled: Boolean) = launch { settings.setDynamicColor(enabled) }
    fun setAppLock(enabled: Boolean) = launch { settings.setAppLockEnabled(enabled) }
    fun setLockTimeout(millis: Long) = launch { settings.setLockTimeoutMillis(millis) }
    fun setIncognitoByDefault(enabled: Boolean) = launch { settings.setIncognitoByDefault(enabled) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

val LockTimeoutOptions: List<Pair<Long, String>> = listOf(
    0L to "Immediately",
    30_000L to "30 s",
    60_000L to "1 min",
    5 * 60_000L to "5 min",
    15 * 60_000L to "15 min",
)

@Composable
fun SettingsRoute(
    onBack: (() -> Unit)?,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenImageBackends: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenProviders = onOpenProviders,
        onOpenPrivacyCenter = onOpenPrivacyCenter,
        onOpenImageBackends = onOpenImageBackends,
        onThemeMode = viewModel::setThemeMode,
        onAmoled = viewModel::setAmoled,
        onDynamicColor = viewModel::setDynamicColor,
        onAppLock = viewModel::setAppLock,
        onLockTimeout = viewModel::setLockTimeout,
        onIncognitoByDefault = viewModel::setIncognitoByDefault,
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
            NavigationRow("Image backends", "Diffusion servers: on-device sidecar, Local Dream host, A1111/Forge", onOpenImageBackends)
            NavigationRow("Privacy Center", "Network mode, audit log, panic wipe", onOpenPrivacyCenter)
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
