package dev.wckdboy.autobot.feature.settings.vault

import dev.wckdboy.autobot.shared.vault.VaultWords
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import dev.wckdboy.autobot.feature.settings.ui.AppBar
import dev.wckdboy.autobot.feature.settings.ui.Centered
import dev.wckdboy.autobot.feature.settings.ui.EmptyMessage
import dev.wckdboy.autobot.feature.settings.ui.NavItem
import dev.wckdboy.autobot.feature.settings.ui.NoticeCard
import dev.wckdboy.autobot.feature.settings.ui.SearchField
import dev.wckdboy.autobot.feature.settings.ui.Subheader
import dev.wckdboy.autobot.feature.settings.ui.SwitchItem
import dev.wckdboy.autobot.feature.settings.ui.Tone
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.counted
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** Why the last search failed (the embedding model would not load, …), instead of "no match". */
    val searchError: String? = null,
) {
    /** Documents indexed with another embedding model (not searched until re-indexed). */
    val stale: Int get() = if (modelId == null) 0 else documents.count { it.model != modelId }
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val onDevice: OnDeviceVault,
    private val settings: SettingsRepository,
) : ViewModel() {
    private data class Search(val query: String = "", val searching: Boolean = false, val results: List<VaultResult>? = null, val error: String? = null)

    private val search = MutableStateFlow(Search())

    val uiState: StateFlow<VaultUiState> = combine(
        settings.settings, onDevice.model, onDevice.repository.observeDocuments(), onDevice.state, search,
    ) { s, model, docs, index, q ->
        VaultUiState(s.vaultEnabled, model?.title, model?.id, docs, index, q.query, q.searching, q.results, q.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setVaultEnabled(enabled) }

    fun setQuery(value: String) = search.update { it.copy(query = value, results = if (value.isBlank()) null else it.results, error = null) }

    fun runSearch() {
        val query = search.value.query.trim()
        if (query.isEmpty()) return
        search.update { it.copy(query = query, searching = true, error = null) }
        viewModelScope.launch {
            val found = try {
                onDevice.vault.search(query, Vault.SEARCH_MAX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                search.update { it.copy(searching = false, results = null, error = "Search failed: ${userMessage(e)}") }
                return@launch
            }
            val results = found.map { VaultResult(Vault.position(it), VaultRules.snippet(it.chunk.text, 400), it.score) }
            search.update { it.copy(searching = false, results = results) }
        }
    }

    fun add(uris: List<Uri>) = onDevice.add(uris)

    fun reindex() = onDevice.reindex()

    fun cancel() = onDevice.cancel()

    fun dismissProblems() = onDevice.dismissProblems()

    fun delete(id: String) = viewModelScope.launchSafely({ e -> search.update { it.copy(error = "Couldn't delete: ${userMessage(e)}") } }) { onDevice.vault.remove(id) }

    fun deleteAll() = viewModelScope.launchSafely({ e -> search.update { it.copy(error = "Couldn't delete: ${userMessage(e)}") } }) { onDevice.vault.clear() }
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
            AppBar("Vault", onBack) {
                if (state.documents.isNotEmpty()) {
                    IconButton(onClick = { confirmDeleteAll = true }) { Icon(Icons.Filled.Delete, contentDescription = VaultWords.DELETE_ALL) }
                }
            }
        },
        floatingActionButton = {
            if (state.model != null && !state.index.running) {
                FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = VaultWords.ADD) }
            }
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
        LazyColumn(width.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item(key = "toggle") {
                SwitchItem(VaultWords.AGENT_SEARCH, state.enabled, onEnabled, info = VaultWords.AGENT_SEARCH_INFO)
            }
            item(key = "model") {
                NavItem(VaultWords.EMBEDDING_MODEL, onOpenModels, value = state.model ?: VaultWords.NO_MODEL)
            }
            if (state.index.running) {
                item(key = "progress") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.index.name?.let { "Indexing $it · ${state.index.done}/${state.index.total}" } ?: "Reading files…",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onCancel) { Text("Stop") }
                        }
                        LinearWavyProgressIndicator(progress = { state.index.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else if (state.stale > 0) {
                item(key = "stale") {
                    NoticeCard(VaultWords.stale(state.stale), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), tone = Tone.WARNING, action = VaultWords.REINDEX, onAction = onReindex)
                }
            }
            if (state.documents.isNotEmpty()) {
                item(key = "search") {
                    SearchField(
                        value = state.query,
                        onValueChange = onQuery,
                        placeholder = VaultWords.SEARCH,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        onSearch = onSearch,
                    )
                }
            }
            if (state.searching) {
                item(key = "searching") {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
            state.searchError?.let { error ->
                item(key = "searcherror") {
                    NoticeCard(error, Modifier.padding(16.dp), tone = Tone.ERROR, action = "Retry", onAction = onSearch)
                }
            }
            state.results?.let { results ->
                if (results.isEmpty() && !state.searching) {
                    item(key = "noresults") { Text(VaultWords.NO_MATCH, Modifier.padding(16.dp), color = muted, style = MaterialTheme.typography.bodyMedium) }
                }
                items(results, key = { "r-" + it.position }) { r ->
                    ListItem(
                        overlineContent = { Text(r.position + " · " + "%.2f".format(r.score)) },
                        headlineContent = { Text(r.snippet, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                    )
                    HorizontalDivider()
                }
            }
            item(key = "docs-h") {
                Subheader(VaultWords.count(state.documents.size, state.documents.sumOf { it.chunks }))
            }
            if (state.documents.isEmpty()) {
                item(key = "empty") {
                    if (state.model == null) {
                        EmptyMessage(VaultWords.empty(hasModel = false), action = "Models", onAction = onOpenModels)
                    } else {
                        EmptyMessage(VaultWords.empty(hasModel = true))
                    }
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
                        )
                    },
                    trailingContent = { IconButton(onClick = { onDelete(doc.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${doc.name}") } },
                )
            }
        }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Empty the vault?") },
            text = { Text("${if (state.documents.size == 1) "The document and its index are" else "All ${state.documents.size} documents and their index are"} deleted from this phone. Your original files are not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        confirmDeleteAll = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete all") }
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
