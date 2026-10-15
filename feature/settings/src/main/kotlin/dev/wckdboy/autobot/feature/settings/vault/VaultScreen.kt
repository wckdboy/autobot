package dev.wckdboy.autobot.feature.settings.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.models.formatBytes
import dev.wckdboy.autobot.providers.remote.vault.IndexState
import dev.wckdboy.autobot.providers.remote.vault.OnDeviceVault
import dev.wckdboy.autobot.providers.remote.vault.VaultImporter
import dev.wckdboy.autobot.shared.vault.Vault
import dev.wckdboy.autobot.shared.vault.VaultDocument
import dev.wckdboy.autobot.shared.vault.VaultRules
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One search result as the screen shows it. */
@Immutable
data class VaultResult(val position: String, val snippet: String, val score: Double)

@Immutable
data class VaultUiState(
    val enabled: Boolean = false,
    /** Title and id of the ready embedding model; null when none is installed. */
    val model: String? = null,
    val modelId: String? = null,
    val documents: List<VaultDocument> = emptyList(),
    val index: IndexState = IndexState(),
    val query: String = "",
    val searching: Boolean = false,
    val results: List<VaultResult>? = null,
) {
    /** Documents indexed with another embedding model (not searched until re-indexed). */
    val stale: Int get() = if (modelId == null) 0 else documents.count { it.model != modelId }
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val onDevice: OnDeviceVault,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val search = MutableStateFlow(Triple("", false, null as List<VaultResult>?))

    val uiState: StateFlow<VaultUiState> = combine(
        settings.settings, onDevice.model, onDevice.repository.observeDocuments(), onDevice.state, search,
    ) { s, model, docs, index, (query, searching, results) ->
        VaultUiState(s.vaultEnabled, model?.title, model?.id, docs, index, query, searching, results)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setVaultEnabled(enabled) }

    fun setQuery(value: String) = search.value.let { search.value = Triple(value, it.second, if (value.isBlank()) null else it.third) }

    fun runSearch() {
        val query = search.value.first.trim()
        if (query.isEmpty()) return
        search.value = Triple(query, true, search.value.third)
        viewModelScope.launch {
            val hits = runCatching { onDevice.vault.search(query, Vault.SEARCH_MAX) }.getOrDefault(emptyList())
            val results = hits.map { VaultResult(Vault.position(it), VaultRules.snippet(it.chunk.text, 400), it.score) }
            search.value = Triple(search.value.first, false, results)
        }
    }

    fun add(uris: List<Uri>) = onDevice.add(uris)

    fun reindex() = onDevice.reindex()

    fun cancel() = onDevice.cancel()

    fun dismissProblems() = onDevice.dismissProblems()

    fun delete(id: String) = viewModelScope.launch { onDevice.vault.remove(id) }

    fun deleteAll() = viewModelScope.launch { onDevice.vault.clear() }
}

@Composable
fun VaultRoute(onBack: () -> Unit, onOpenModels: () -> Unit, viewModel: VaultViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.add(uris)
    }
    VaultScreen(
        state = state,
        onBack = onBack,
        onOpenModels = onOpenModels,
        onEnabled = { viewModel.setEnabled(it) },
        onAdd = { picker.launch(VaultImporter.MIME_TYPES) },
        onReindex = viewModel::reindex,
        onCancel = viewModel::cancel,
        onQuery = viewModel::setQuery,
        onSearch = viewModel::runSearch,
        onDelete = { viewModel.delete(it) },
        onDeleteAll = { viewModel.deleteAll() },
        onDismissProblems = viewModel::dismissProblems,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: VaultUiState,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onReindex: () -> Unit,
    onCancel: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onDismissProblems: () -> Unit,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VAULT", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.documents.isNotEmpty()) TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                },
            )
        },
        floatingActionButton = {
            if (state.model != null && !state.index.running) {
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add files") }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "toggle") {
                ListItem(
                    modifier = Modifier.clickable { onEnabled(!state.enabled) },
                    headlineContent = { Text("Let the agent search the vault") },
                    supportingContent = {
                        Text(if (state.enabled) "The agent can look up passages with vault_search." else "Off: the agent neither sees nor searches your files.")
                    },
                    trailingContent = { Switch(checked = state.enabled, onCheckedChange = onEnabled) },
                )
            }
            item(key = "copy") {
                Text(
                    "Indexed on this phone, encrypted, never uploaded.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item(key = "model") {
                ListItem(
                    overlineContent = { Text("Embedding model") },
                    headlineContent = { Text(state.model ?: "None installed") },
                    supportingContent = {
                        Text(
                            if (state.model == null) "Download an embedding model to add files." else "Text, Markdown and PDF (Android 15+) files.",
                            color = muted,
                        )
                    },
                    trailingContent = { TextButton(onClick = onOpenModels) { Text("Models") } },
                )
            }
            if (state.index.running) {
                item(key = "progress") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.index.name?.let { "Indexing $it · ${state.index.done}/${state.index.total}" } ?: "Reading files…",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onCancel) { Text("Stop") }
                        }
                        InkProgressBar(state.index.fraction, Modifier.fillMaxWidth().height(3.dp))
                    }
                }
            } else if (state.stale > 0) {
                item(key = "stale") {
                    ListItem(
                        headlineContent = { Text("${state.stale} document(s) use another embedding model") },
                        supportingContent = { Text("They are not searched until re-indexed (from the stored text, no files needed).", color = muted) },
                        trailingContent = { OutlinedButton(onClick = onReindex) { Text("Re-index") } },
                    )
                }
            }
            if (state.documents.isNotEmpty()) {
                item(key = "search") {
                    NoPersonalizedLearning {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onQuery,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            singleLine = true,
                            label = { Text("Search your files") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        )
                    }
                }
            }
            if (state.searching) {
                item(key = "searching") {
                    InkProgressBar(0.3f, Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp))
                }
            }
            state.results?.let { results ->
                if (results.isEmpty() && !state.searching) {
                    item(key = "noresults") { Text("No passages match.", Modifier.padding(16.dp), color = muted) }
                }
                items(results, key = { "r-" + it.position }) { r ->
                    ListItem(
                        overlineContent = { Text(r.position + " · " + "%.2f".format(r.score)) },
                        headlineContent = { Text(r.snippet, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) },
                    )
                }
            }
            item(key = "docs-h") {
                Text(
                    "${state.documents.size} documents · ${state.documents.sumOf { it.chunks }} / ${VaultRules.MAX_CHUNKS} passages",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            if (state.documents.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No files yet. Tap + to add notes, Markdown or PDFs: they are split into passages and embedded on this phone.",
                        modifier = Modifier.padding(32.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                }
            }
            items(state.documents, key = { it.id }, contentType = { "doc" }) { doc ->
                ListItem(
                    headlineContent = { Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(doc.type.label).append(" · ").append(formatBytes(doc.sizeBytes))
                                if (doc.pages > 0) append(" · ").append(doc.pages).append(" pages")
                                append(" · ").append(doc.chunks).append(" passages")
                            },
                            color = muted,
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { onDelete(doc.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${doc.name}") }
                    },
                )
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Empty the vault?") },
            text = { Text("All ${state.documents.size} documents and their index are deleted from this phone. Your original files are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    confirmDeleteAll = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }

    if (state.index.problems.isNotEmpty() && !state.index.running) {
        AlertDialog(
            onDismissRequest = onDismissProblems,
            title = { Text("Some files were not added") },
            text = { Text(state.index.problems.joinToString("\n")) },
            confirmButton = { TextButton(onClick = onDismissProblems) { Text("OK") } },
        )
    }
}
