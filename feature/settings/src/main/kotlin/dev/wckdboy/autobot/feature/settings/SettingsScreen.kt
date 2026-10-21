package dev.wckdboy.autobot.feature.settings

import dev.wckdboy.autobot.shared.cascade.CascadePolicy
import dev.wckdboy.autobot.shared.settings.SettingsWords
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.NavListItem
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.Subheader
import dev.wckdboy.autobot.core.designsystem.component.SwitchItem
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.ChatLibrary
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.settings.AppSettings
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.SettingsSchema
import dev.wckdboy.autobot.core.data.settings.ThemeMode
import dev.wckdboy.autobot.core.designsystem.component.InfoButton
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.feature.settings.backup.BackupDialog
import dev.wckdboy.autobot.core.security.panic.PinLock
import dev.wckdboy.autobot.feature.settings.privacy.PanicActions
import dev.wckdboy.autobot.feature.settings.privacy.PanicDialog
import dev.wckdboy.autobot.feature.settings.privacy.PanicDialogs
import dev.wckdboy.autobot.feature.settings.privacy.PanicRows
import dev.wckdboy.autobot.feature.settings.privacy.PanicUiState
import dev.wckdboy.autobot.feature.settings.privacy.PanicWipeDialog
import dev.wckdboy.autobot.feature.settings.privacy.PrivacyCenterViewModel
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
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val appLockEnabled: Boolean = true,
    val appLockAvailable: Boolean = true,
    val lockTimeoutMillis: Long = AppSettings.DEFAULT_LOCK_TIMEOUT_MILLIS,
    val incognitoByDefault: Boolean = false,
    val memoryEnabled: Boolean = false,
    val vaultEnabled: Boolean = false,
    val quotaFallback: Boolean = false,
    val redactCloud: Boolean = true,
    val cascade: CascadePolicy = CascadePolicy.DEFAULT,
    val searchProvider: SearchProvider = SearchProvider.NONE,
    val offline: Boolean = false,
    /** "Offline", "Direct", "Tor", "SOCKS5": the Privacy Center row's value. */
    val networkLabel: String = "Direct",
    val panic: PanicUiState = PanicUiState(),
)

/** The network mode as one word, as on iOS. */
fun networkLabel(mode: NetworkMode): String = when (mode) {
    NetworkMode.Offline -> "Offline"
    NetworkMode.Direct -> "Direct"
    is NetworkMode.Tor -> "Tor"
    is NetworkMode.Socks5 -> "SOCKS5"
}

/**
 * The Settings tab's sections, in order. Providers, image backends and hub accounts live on the
 * Remote tab (one home per thing), so there is no "Connections" section here.
 */
val SettingsSections: List<String> = listOf("Privacy & security", "Agent", "Appearance", "Data", "About")

/** The About row: `Autobot 1.6.4 (16)`. */
fun aboutRowTitle(versionName: String, versionCode: Long): String = "Autobot $versionName ($versionCode)"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val chats: ChatLibrary,
    appLockManager: AppLockManager,
    private val pinLock: PinLock,
) : ViewModel(), PanicActions {
    private val lockAvailable = appLockManager.canAuthenticate()

    val uiState: StateFlow<SettingsUiState> = combine(settings.settings, pinLock.record) { it, lock ->
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
            redactCloud = it.redactCloud,
            cascade = it.cascade,
            searchProvider = it.searchProvider,
            offline = it.networkMode == NetworkMode.Offline,
            networkLabel = networkLabel(it.networkMode),
            panic = PanicUiState.of(lock),
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
    fun setRedactCloud(enabled: Boolean) = launch { settings.setRedactCloud(enabled) }
    fun setCascade(policy: CascadePolicy) = launch { settings.setCascade(policy) }

    override fun savePin(current: String?, pin: String, confirm: String, done: (String?) -> Unit) =
        answer(done) { if (current == null) pinLock.setPin(pin, confirm) else pinLock.changePin(current, pin, confirm) }

    override fun removePin(current: String, done: (String?) -> Unit) = answer(done) { pinLock.removePin(current) }

    override fun setWipeAfter(attempts: Int, current: String?, done: (String?) -> Unit) = answer(done) { pinLock.setWipeAfter(attempts, current) }

    override fun setDuress(current: String, duress: String, confirm: String, done: (String?) -> Unit) =
        answer(done) { pinLock.setDuress(current, duress, confirm) }

    override fun clearDuress() = launch { pinLock.clearDuress() }

    fun setWipeModels(enabled: Boolean) = launch { pinLock.setWipeModels(enabled) }
    fun setPinBiometrics(enabled: Boolean) = launch { pinLock.setBiometrics(enabled) }

    /** Runs a lock change and reports its problem (or null) on the main thread; a failed write is a problem too. */
    private fun answer(done: (String?) -> Unit, block: suspend () -> String?) {
        viewModelScope.launch {
            done(runCatching { block() }.getOrElse { "Could not save. Try again." })
        }
    }

    /** Deletes every chat, stored and incognito, for good (running turns are stopped first). */
    fun deleteAllChats() = launch { chats.deleteAll() }

    /** A setting (or the deletion) that cannot be stored (disk full, I/O error) stays as it was instead of crashing. */
    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launchSafely({ Log.w("Settings", "cannot save a setting", it) }) { block() }
    }
}

val LockTimeoutOptions: List<Pair<Long, String>> =
    SettingsSchema.LOCK_TIMEOUTS_MILLIS.zip(listOf("Immediately", "30 s", "1 min", "5 min", "15 min"))

/**
 * @param about the About row's title, e.g. `Autobot 1.6.4 (16)` ([aboutRowTitle]).
 */
@Composable
fun SettingsRoute(
    onBack: (() -> Unit)?,
    onOpenPrivacyCenter: () -> Unit,
    onOpenMcpServers: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenRecipes: () -> Unit = {},
    about: String = "About",
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showWipe by rememberSaveable { mutableStateOf(false) }
    var panicDialog by rememberSaveable { mutableStateOf<PanicDialog?>(null) }
    PanicDialogs(panicDialog, state.panic, viewModel) { panicDialog = null }
    var confirmDeleteChats by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteChats) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChats = false },
            title = { Text(SettingsWords.DELETE_ALL_TITLE) },
            text = { Text(SettingsWords.DELETE_ALL_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAllChats()
                        confirmDeleteChats = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(SettingsWords.DELETE_ALL_CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChats = false }) { Text("Cancel") } },
        )
    }
    if (showWipe) {
        // Created only when asked for: the wipe itself lives with the Privacy Center.
        val privacy: PrivacyCenterViewModel = hiltViewModel()
        PanicWipeDialog(
            onDismiss = { showWipe = false },
            onConfirm = { typed ->
                showWipe = false
                privacy.panicWipe(typed)
            },
        )
    }
    if (showBackup) BackupDialog(onDismiss = { showBackup = false })
    if (showSearch) WebSearchDialog(onDismiss = { showSearch = false })
    SettingsScreen(
        state = state,
        onBack = onBack,
        onOpenPrivacyCenter = onOpenPrivacyCenter,
        onOpenMcpServers = onOpenMcpServers,
        onThemeMode = viewModel::setThemeMode,
        onAmoled = viewModel::setAmoled,
        onDynamicColor = viewModel::setDynamicColor,
        onAppLock = viewModel::setAppLock,
        onLockTimeout = viewModel::setLockTimeout,
        onIncognitoByDefault = viewModel::setIncognitoByDefault,
        onPanicWipe = { showWipe = true },
        onOpenPanic = { panicDialog = it },
        onPinBiometrics = viewModel::setPinBiometrics,
        onWipeModels = viewModel::setWipeModels,
        onOpenBackup = { showBackup = true },
        onMemoryEnabled = viewModel::setMemoryEnabled,
        onOpenMemory = onOpenMemory,
        onOpenVault = onOpenVault,
        onOpenWebSearch = { showSearch = true },
        onQuotaFallback = viewModel::setQuotaFallback,
        onRedactCloud = viewModel::setRedactCloud,
        onCascade = viewModel::setCascade,
        onOpenUsage = onOpenUsage,
        onOpenRecipes = onOpenRecipes,
        onDeleteAllChats = { confirmDeleteChats = true },
        onOpenAbout = onOpenAbout,
        about = about,
    )
}

/** Settings: a Material list under a top app bar, sections in [SettingsSections] order. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: (() -> Unit)?,
    onOpenPrivacyCenter: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAmoled: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onLockTimeout: (Long) -> Unit,
    onIncognitoByDefault: (Boolean) -> Unit,
    onPanicWipe: () -> Unit = {},
    onOpenPanic: (PanicDialog) -> Unit = {},
    onPinBiometrics: (Boolean) -> Unit = {},
    onWipeModels: (Boolean) -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenMcpServers: () -> Unit = {},
    onMemoryEnabled: (Boolean) -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onQuotaFallback: (Boolean) -> Unit = {},
    onRedactCloud: (Boolean) -> Unit = {},
    onCascade: (CascadePolicy) -> Unit = {},
    onOpenUsage: () -> Unit = {},
    onOpenRecipes: () -> Unit = {},
    onDeleteAllChats: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    about: String = "About",
) {
    val (privacy, agent, appearance, data, aboutSection) = SettingsSections
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { AppBar("Settings", onBack, scrollBehavior = scroll) },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            Column(width.fillMaxSize().verticalScroll(rememberScrollState())) {
                Subheader(privacy)
                NavListItem("Privacy Center", onOpenPrivacyCenter, value = state.networkLabel)
                // An app PIN always locks: the wipe and the duress PIN depend on it.
                val pinLocks = state.panic.hasPin
                SwitchItem(
                    "App lock",
                    pinLocks || (state.appLockEnabled && state.appLockAvailable),
                    onAppLock,
                    state = if (state.appLockAvailable || pinLocks) null else "Needs a screen lock",
                    info = if (state.appLockAvailable || pinLocks) "Require biometrics or the device credential." else null,
                    enabled = state.appLockAvailable && !pinLocks,
                )
                if (pinLocks || (state.appLockEnabled && state.appLockAvailable)) {
                    ChoiceTitle("Lock after")
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp).selectableGroup(),
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
                PanicRows(state.panic, onOpenPanic, onPinBiometrics, onWipeModels)
                SwitchItem("Incognito by default", state.incognitoByDefault, onIncognitoByDefault, info = SettingsWords.INCOGNITO)
                SwitchItem("Redact before cloud", state.redactCloud, onRedactCloud, info = SettingsWords.REDACT)
                ChoiceTitle("Phone first", info = SettingsWords.cascade)
                Choices(CascadePolicy.entries, state.cascade, onCascade, ChoicesModifier) { it.title }
                NavListItem(
                    "Panic wipe",
                    onPanicWipe,
                    titleColor = MaterialTheme.colorScheme.error,
                    info = SettingsWords.PANIC_WIPE,
                    chevron = false,
                )

                Subheader(agent)
                SwitchItem("Memory", state.memoryEnabled, onMemoryEnabled, info = SettingsWords.MEMORY)
                NavListItem("Memory notes", onOpenMemory)
                NavListItem("Vault", onOpenVault, value = if (state.vaultEnabled) "On" else "Off")
                NavListItem(
                    "Web search",
                    onOpenWebSearch,
                    value = if (state.searchProvider == SearchProvider.NONE) "Off" else state.searchProvider.label,
                    info = SettingsWords.WEB_SEARCH,
                )
                if (state.searchProvider != SearchProvider.NONE && state.offline) {
                    NoticeCard(SettingsWords.OFFLINE_BLOCKS_SEARCH, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), tone = NoticeTone.ERROR)
                }
                SwitchItem("Fallback on limit", state.quotaFallback, onQuotaFallback, info = SettingsWords.QUOTA_FALLBACK)
                NavListItem("Usage", onOpenUsage)
                NavListItem("MCP servers", onOpenMcpServers)
                NavListItem("Recipes", onOpenRecipes)

                Subheader(appearance)
                ChoiceTitle("Theme")
                Choices(ThemeMode.entries, state.themeMode, onThemeMode, ChoicesModifier) { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) }
                SwitchItem("AMOLED black", state.amoled, onAmoled, enabled = state.themeMode != ThemeMode.LIGHT)
                SwitchItem("Dynamic color", state.dynamicColor, onDynamicColor, info = "Use the wallpaper's colors instead of the brand's.")

                Subheader(data)
                NavListItem("Export & import", onOpenBackup, info = "Move sessions, gallery and memory between phones, sealed with a passphrase.")
                NavListItem("Delete all chats", onDeleteAllChats, titleColor = MaterialTheme.colorScheme.error, chevron = false)

                Subheader(aboutSection)
                NavListItem(about, onOpenAbout)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The title over a choice (Lock after, Phone first, Theme): a list headline without an action, its ⓘ after it. */
private val ChoicesModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)

@Composable
private fun ChoiceTitle(text: String, info: String? = null) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = if (info == null) 12.dp else 0.dp, bottom = if (info == null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (info != null) InfoButton(text, info)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun SettingsScreenPreview() {
    AutobotTheme {
        SettingsScreen(
            state = SettingsUiState(),
            onBack = null, onOpenPrivacyCenter = {}, onThemeMode = {}, onAmoled = {},
            onDynamicColor = {}, onAppLock = {}, onLockTimeout = {}, onIncognitoByDefault = {},
            about = aboutRowTitle("1.6.4", 16),
        )
    }
}
