package dev.wckdboy.autobot.feature.settings.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.CodeFontFamily
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.feature.settings.SwitchRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivacyCenterRoute(onBack: () -> Unit, viewModel: PrivacyCenterViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PrivacyCenterScreen(
        state = state,
        onBack = onBack,
        onModeChange = viewModel::setMode,
        onAllowLoopback = viewModel::setAllowLoopback,
        onClearAudit = viewModel::clearAudit,
        onPanicWipe = viewModel::panicWipe,
    )
}

private enum class ModeKind(val label: String, val description: String) {
    OFFLINE("Offline", "Kill switch on. Nothing leaves the device."),
    DIRECT("Direct", "Connect to providers directly."),
    TOR("Tor", "Route through Orbot's SOCKS port (remote DNS)."),
    SOCKS5("SOCKS5", "Route through a custom SOCKS5 proxy."),
}

private fun NetworkMode.kind() = when (this) {
    NetworkMode.Offline -> ModeKind.OFFLINE
    NetworkMode.Direct -> ModeKind.DIRECT
    is NetworkMode.Tor -> ModeKind.TOR
    is NetworkMode.Socks5 -> ModeKind.SOCKS5
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyCenterScreen(
    state: PrivacyCenterUiState,
    onBack: () -> Unit,
    onModeChange: (NetworkMode) -> Unit,
    onAllowLoopback: (Boolean) -> Unit,
    onClearAudit: () -> Unit,
    onPanicWipe: (String) -> Unit,
) {
    var showWipeDialog by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy Center") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item(key = "mode-header", contentType = "header") { Header("Network mode") }
            item(key = "mode-selector", contentType = "mode") {
                NetworkModeSelector(state.mode, onModeChange)
            }
            item(key = "loopback", contentType = "switch") {
                SwitchRow(
                    title = "Allow loopback while offline",
                    subtitle = "Lets on-device servers (e.g. Ollama at 127.0.0.1) work in Offline mode",
                    checked = state.allowLoopbackWhenOffline,
                    onChange = onAllowLoopback,
                )
            }
            item(key = "audit-header", contentType = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Header("Live audit log (${state.audit.size})", Modifier.weight(1f))
                    TextButton(onClick = onClearAudit, modifier = Modifier.padding(end = 8.dp)) { Text("Clear") }
                }
            }
            item(key = "audit-note", contentType = "note") {
                Text(
                    "Every outbound request attempt, kept in memory only. Headers, queries and bodies are never recorded.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.audit.isEmpty()) {
                item(key = "audit-empty", contentType = "note") {
                    Text(
                        "No requests yet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(state.audit, key = { it.id }, contentType = { "audit" }) { row -> AuditRowView(row) }
            item(key = "wipe-divider", contentType = "divider") { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
            item(key = "wipe", contentType = "wipe") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Panic wipe", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Destroys encryption keys, conversations, providers, API keys and settings, then closes the app. This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showWipeDialog = true },
                        enabled = !state.wiping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(if (state.wiping) "Wiping…" else "Wipe everything") }
                }
            }
        }
    }

    if (showWipeDialog) {
        PanicWipeDialog(
            onDismiss = { showWipeDialog = false },
            onConfirm = { typed ->
                showWipeDialog = false
                onPanicWipe(typed)
            },
        )
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun NetworkModeSelector(mode: NetworkMode, onModeChange: (NetworkMode) -> Unit) {
    val current = mode.kind()
    var torHost by remember(mode) { mutableStateOf((mode as? NetworkMode.Tor)?.host ?: NetworkMode.DEFAULT_TOR_HOST) }
    var torPort by remember(mode) { mutableStateOf(((mode as? NetworkMode.Tor)?.port ?: NetworkMode.DEFAULT_TOR_PORT).toString()) }
    var socksHost by remember(mode) { mutableStateOf((mode as? NetworkMode.Socks5)?.host ?: NetworkPolicy.DEFAULT_SOCKS_HOST) }
    var socksPort by remember(mode) { mutableStateOf(((mode as? NetworkMode.Socks5)?.port ?: NetworkPolicy.DEFAULT_SOCKS_PORT).toString()) }

    fun build(kind: ModeKind): NetworkMode? = when (kind) {
        ModeKind.OFFLINE -> NetworkMode.Offline
        ModeKind.DIRECT -> NetworkMode.Direct
        ModeKind.TOR -> torPort.toIntOrNull()?.takeIf { it in 1..65535 && torHost.isNotBlank() }?.let { NetworkMode.Tor(torHost.trim(), it) }
        ModeKind.SOCKS5 -> socksPort.toIntOrNull()?.takeIf { it in 1..65535 && socksHost.isNotBlank() }?.let { NetworkMode.Socks5(socksHost.trim(), it) }
    }

    Column {
        ModeKind.entries.forEach { kind ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = kind == current, role = Role.RadioButton) { build(kind)?.let(onModeChange) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = kind == current, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    Text(kind.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind == current && (kind == ModeKind.TOR || kind == ModeKind.SOCKS5)) {
                val isTor = kind == ModeKind.TOR
                EndpointEditor(
                    host = if (isTor) torHost else socksHost,
                    port = if (isTor) torPort else socksPort,
                    onHost = { if (isTor) torHost = it else socksHost = it },
                    onPort = { if (isTor) torPort = it.filter(Char::isDigit) else socksPort = it.filter(Char::isDigit) },
                    onApply = { build(kind)?.let(onModeChange) },
                    canApply = build(kind) != null && build(kind) != mode,
                )
            }
        }
    }
}

@Composable
private fun EndpointEditor(
    host: String,
    port: String,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onApply: () -> Unit,
    canApply: Boolean,
) {
    NoPersonalizedLearning {
        Row(
            Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = onHost,
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPort,
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.width(96.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedButton(onClick = onApply, enabled = canApply) { Text("Apply") }
        }
    }
}

private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

@Composable
private fun AuditRowView(row: AuditRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = (if (row.blocked) MaterialTheme.colorScheme.error else AutobotColors.Green).copy(alpha = 0.15f),
            contentColor = if (row.blocked) MaterialTheme.colorScheme.error else AutobotColors.Green,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Text(
                if (row.blocked) "BLOCKED" else row.method,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.host + row.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                timeFormat.get()!!.format(Date(row.timeMillis)) + " · " + row.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PanicWipeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val matches = typed == PrivacyCenterViewModel.WIPE_CONFIRMATION
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wipe everything?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("All keys and data will be destroyed and the app will close. Type WIPE to confirm.")
                NoPersonalizedLearning {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        isError = typed.isNotEmpty() && !matches,
                        label = { Text("Confirmation") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(typed) }, enabled = matches) {
                Text("Wipe", color = if (matches) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun PrivacyCenterPreview() {
    AutobotTheme {
        PrivacyCenterScreen(
            state = PrivacyCenterUiState(
                mode = NetworkMode.Tor(),
                audit = listOf(
                    AuditRow(1, 0, "POST", "api.deepseek.com", "/chat/completions", "offline · DeepSeek", blocked = true),
                    AuditRow(2, 0, "GET", "127.0.0.1", "/v1/models", "loopback · Ollama · HTTP 200", blocked = false),
                ),
            ),
            onBack = {}, onModeChange = {}, onAllowLoopback = {}, onClearAudit = {}, onPanicWipe = {},
        )
    }
}
