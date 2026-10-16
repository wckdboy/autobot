package dev.wckdboy.autobot.feature.settings.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.component.InkBusyLabel
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.SecretKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.rememberBufferedText
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.settings.providers.ProviderDraft
import dev.wckdboy.autobot.feature.settings.providers.TestState

@Composable
fun McpServersRoute(onBack: () -> Unit, viewModel: McpServersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    McpServersScreen(
        state = state,
        onBack = onBack,
        onAdd = viewModel::startAdd,
        onEdit = viewModel::startEdit,
        onDelete = viewModel::delete,
        onEnabled = viewModel::setEnabled,
        onDraftChange = viewModel::updateDraft,
        onSave = viewModel::save,
        onTest = viewModel::testConnection,
        onDismissEditor = viewModel::dismissEditor,
    )
}

private val ProviderRouting.label: String
    get() = when (this) {
        ProviderRouting.INHERIT -> "Inherit"
        ProviderRouting.DIRECT -> "Direct"
        ProviderRouting.TOR -> "Tor"
        ProviderRouting.SOCKS5 -> "SOCKS5"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    state: McpServersUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onDraftChange: ((McpServerDraft) -> McpServerDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismissEditor: () -> Unit,
) {
    var deleting by remember { mutableStateOf<McpServerRow?>(null) }
    val dissolve = rememberInkDissolve()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add MCP server") }
        },
    ) { padding ->
        if (state.servers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No MCP servers yet. Tap + to add a Model Context Protocol server (Streamable HTTP).\n" +
                        "Its tools join agent sessions; every call asks first; a trusted server follows the permission preset instead.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                // A failed enable/disable from the list (an editor shows its own).
                if (state.editor == null) {
                    state.error?.let { item { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                }
                items(state.servers, key = { it.id }, contentType = { "mcp" }) { row ->
                    ListItem(
                        modifier = Modifier.inkDissolve(dissolve, row.id).clickable { onEdit(row.id) },
                        headlineContent = { Text(row.name) },
                        supportingContent = {
                            Column {
                                Text(
                                    listOfNotNull(
                                        row.host,
                                        row.routing.label,
                                        if (row.trusted) "trusted · follows preset" else "asks each call",
                                        "token ✓".takeIf { row.hasToken },
                                    ).joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                row.status?.let {
                                    Text(
                                        it,
                                        color = if (row.unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        overlineContent = { Text(row.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = row.enabled, onCheckedChange = { onEnabled(row.id, it) })
                                IconButton(onClick = { deleting = row }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.name}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { draft ->
        McpServerEditorSheet(
            draft = draft,
            test = state.test,
            saving = state.saving,
            error = state.error,
            onDraftChange = onDraftChange,
            onSave = onSave,
            onTest = onTest,
            onDismiss = onDismissEditor,
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${row.name}?") },
            text = { Text("Its tools leave agent sessions" + if (row.hasToken) " and its token is deleted from this phone." else ".") },
            confirmButton = {
                TextButton(onClick = {
                    dissolve.dissolve(row.id) { onDelete(row.id) }
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpServerEditorSheet(
    draft: McpServerDraft,
    test: TestState,
    saving: Boolean,
    error: String?,
    onDraftChange: ((McpServerDraft) -> McpServerDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        NoPersonalizedLearning {
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (draft.isNew) "Add MCP server" else "Edit MCP server", style = MaterialTheme.typography.titleLarge)

                // The draft comes back through the ViewModel's combined flow; buffer so typing is not lost.
                val (name, onName) = rememberBufferedText(draft.name) { v -> onDraftChange { it.copy(name = v) } }
                val (url, onUrl) = rememberBufferedText(draft.url) { v -> onDraftChange { it.copy(url = v) } }
                val (token, onToken) = rememberBufferedText(draft.tokenInput) { v -> onDraftChange { it.copy(tokenInput = v, clearStoredToken = false) } }
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text("Name") },
                    supportingText = { Text("Tools appear to the agent as mcp__<name>__<tool>.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrl,
                    label = { Text("URL") },
                    supportingText = {
                        Text(ProviderDraft.cleartextProblem(draft.url) ?: "The server's MCP endpoint, e.g. https://example.com/mcp. Cleartext http is only allowed for 127.0.0.1/localhost.")
                    },
                    isError = draft.url.isNotBlank() && (!draft.urlValid || ProviderDraft.cleartextProblem(draft.url) != null),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )

                OutlinedTextField(
                    value = token,
                    onValueChange = onToken,
                    label = { Text("Bearer token (optional)") },
                    placeholder = { if (draft.hasStoredToken && !draft.clearStoredToken) Text("•••••••• saved — type to replace") },
                    supportingText = { Text("Encrypted with Android Keystore. Sent only to this server's host. Never logged or displayed.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = SecretKeyboardOptions,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.hasStoredToken && draft.tokenInput.isEmpty()) {
                    TextButton(onClick = { onDraftChange { it.copy(clearStoredToken = !it.clearStoredToken) } }) {
                        Text(if (draft.clearStoredToken) "Keep saved token" else "Remove saved token")
                    }
                }

                Text("Routing", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderRouting.entries.forEach { routing ->
                        FilterChip(
                            selected = draft.routing == routing,
                            onClick = { onDraftChange { it.copy(routing = routing) } },
                            label = { Text(routing.label) },
                        )
                    }
                }
                Text(
                    "Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ToggleRow("Enabled", "Agent sessions get this server's tools", draft.enabled) { v -> onDraftChange { it.copy(enabled = v) } }
                ToggleRow(
                    "Trusted",
                    if (draft.trusted) {
                        "Calls follow the permission preset instead of always asking (like web fetch: full-access runs them, the other presets ask)"
                    } else {
                        "Every tool call asks for approval first"
                    },
                    draft.trusted,
                ) { v -> onDraftChange { it.copy(trusted = v) } }

                TestResult(test)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (test != TestState.Running) onTest() }, enabled = draft.urlValid) {
                        InkBusyLabel(test == TestState.Running) { Text("Test connection") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onSave, enabled = draft.canSave && !saving) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        // The test button carries the ink loader while it runs.
        TestState.Running -> Unit
        is TestState.Success -> Text(test.message, color = AutobotColors.Green, style = MaterialTheme.typography.bodySmall)
        is TestState.Failure -> Text(test.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun McpServersScreenPreview() {
    AutobotTheme {
        McpServersScreen(
            state = McpServersUiState(
                servers = listOf(
                    McpServerRow("1", "Notes", "https://notes.example.com/mcp", "notes.example.com", ProviderRouting.TOR, true, false, true),
                    McpServerRow("2", "Local tools", "http://127.0.0.1:8000/mcp", "127.0.0.1", ProviderRouting.INHERIT, false, true, false),
                ),
            ),
            onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onEnabled = { _, _ -> }, onDraftChange = {},
            onSave = {}, onTest = {}, onDismissEditor = {},
        )
    }
}
