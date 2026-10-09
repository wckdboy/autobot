package dev.wckdboy.autobot.feature.settings.providers

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.SecretKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.ProviderPresets

@Composable
fun ProvidersRoute(onBack: () -> Unit, viewModel: ProvidersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProvidersScreen(
        state = state,
        onBack = onBack,
        onAdd = { viewModel.startAdd() },
        onEdit = viewModel::startEdit,
        onDelete = viewModel::delete,
        onKind = viewModel::changeKind,
        onDraftChange = viewModel::updateDraft,
        onSave = viewModel::save,
        onTest = viewModel::testConnection,
        onDismissEditor = viewModel::dismissEditor,
    )
}

private val ProviderKind.label: String get() = ProviderPresets.forKind(this).displayName

private val ProviderRouting.label: String
    get() = when (this) {
        ProviderRouting.INHERIT -> "Inherit"
        ProviderRouting.DIRECT -> "Direct"
        ProviderRouting.TOR -> "Tor"
        ProviderRouting.SOCKS5 -> "SOCKS5"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    state: ProvidersUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onKind: (ProviderKind) -> Unit,
    onDraftChange: ((ProviderDraft) -> ProviderDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismissEditor: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add provider") }
        },
    ) { padding ->
        if (state.providers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No providers yet. Tap + to add DeepSeek, OpenRouter, Ollama or any OpenAI-compatible endpoint.\nAPI keys are encrypted with a hardware-backed key.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(state.providers, key = { it.id }, contentType = { "provider" }) { row ->
                    ListItem(
                        modifier = Modifier.clickable { onEdit(row.id) },
                        headlineContent = { Text(row.name) },
                        supportingContent = {
                            Text(
                                "${row.kind.label} · ${row.model} · ${row.routing.label}" + if (row.hasKey) " · key ✓" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        overlineContent = { Text(row.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { onDelete(row.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.name}")
                            }
                        },
                    )
                }
            }
        }
    }

    state.editor?.let { draft ->
        ProviderEditorSheet(
            draft = draft,
            test = state.test,
            saving = state.saving,
            onKind = onKind,
            onDraftChange = onDraftChange,
            onSave = onSave,
            onTest = onTest,
            onDismiss = onDismissEditor,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorSheet(
    draft: ProviderDraft,
    test: TestState,
    saving: Boolean,
    onKind: (ProviderKind) -> Unit,
    onDraftChange: ((ProviderDraft) -> ProviderDraft) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val preset = ProviderPresets.forKind(draft.kind)
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
                Text(if (draft.isNew) "Add provider" else "Edit provider", style = MaterialTheme.typography.titleLarge)

                Text("Type", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderKind.entries.forEach { kind ->
                        FilterChip(selected = draft.kind == kind, onClick = { onKind(kind) }, label = { Text(kind.label) })
                    }
                }

                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = { v -> onDraftChange { it.copy(displayName = v) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = { v -> onDraftChange { it.copy(baseUrl = v) } },
                    label = { Text("Base URL") },
                    supportingText = { Text("Requests go to {base}/chat/completions. Cleartext http is only allowed for 127.0.0.1/localhost.") },
                    isError = draft.baseUrl.isNotBlank() && !draft.baseUrlValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = draft.defaultModel,
                    onValueChange = { v -> onDraftChange { it.copy(defaultModel = v) } },
                    label = { Text("Default model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                if (preset.suggestedModels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        preset.suggestedModels.forEach { m ->
                            FilterChip(
                                selected = draft.defaultModel == m,
                                onClick = { onDraftChange { it.copy(defaultModel = m) } },
                                label = { Text(m) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.apiKeyInput,
                    onValueChange = { v -> onDraftChange { it.copy(apiKeyInput = v, clearStoredKey = false) } },
                    label = { Text(if (preset.requiresApiKey) "API key" else "API key (optional)") },
                    placeholder = { if (draft.hasStoredKey && !draft.clearStoredKey) Text("•••••••• saved — type to replace") },
                    supportingText = { Text("Encrypted with Android Keystore. Never logged or displayed.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = SecretKeyboardOptions,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.hasStoredKey && draft.apiKeyInput.isEmpty()) {
                    TextButton(onClick = { onDraftChange { it.copy(clearStoredKey = !it.clearStoredKey) } }) {
                        Text(if (draft.clearStoredKey) "Keep saved key" else "Remove saved key")
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

                TestResult(test)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onTest, enabled = draft.baseUrlValid && test != TestState.Running) {
                        Text("Test connection")
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
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Testing…", style = MaterialTheme.typography.bodySmall)
        }
        is TestState.Success -> Text(test.message, color = AutobotColors.Green, style = MaterialTheme.typography.bodySmall)
        is TestState.Failure -> Text(test.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun ProvidersScreenPreview() {
    AutobotTheme {
        ProvidersScreen(
            state = ProvidersUiState(
                providers = listOf(
                    ProviderRow("1", "DeepSeek", ProviderKind.DEEPSEEK, "https://api.deepseek.com", "deepseek-chat", ProviderRouting.INHERIT, true),
                    ProviderRow("2", "Ollama", ProviderKind.OLLAMA, "http://127.0.0.1:11434/v1", "llama3.2", ProviderRouting.DIRECT, false),
                ),
            ),
            onBack = {}, onAdd = {}, onEdit = {}, onDelete = {}, onKind = {}, onDraftChange = {},
            onSave = {}, onTest = {}, onDismissEditor = {},
        )
    }
}
