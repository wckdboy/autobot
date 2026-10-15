package dev.wckdboy.autobot.feature.settings.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.MemoryRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.shared.memory.MemoryNote
import dev.wckdboy.autobot.shared.memory.MemoryRules
import dev.wckdboy.autobot.shared.memory.SaveResult
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class MemoryUiState(
    val enabled: Boolean = false,
    val query: String = "",
    val notes: List<MemoryNote> = emptyList(),
    val total: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<MemoryUiState> = combine(repository.observe(), query, settings.settings, message) { notes, q, s, m ->
        MemoryUiState(s.memoryEnabled, q, MemoryRules.filter(notes, q), notes.size, m)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launchSafely(::failed) { settings.setMemoryEnabled(enabled) }

    /** Saves a new note ([id] null) or an edit; the same rules as the agent's `memory_save`. */
    fun save(id: String?, text: String, tags: String) = viewModelScope.launchSafely(::failed) {
        message.value = when (val r = repository.memory.save(text, MemoryRules.parseTags(tags), id = id)) {
            is SaveResult.Duplicate -> "Already in memory (${r.note.id}); tags merged."
            SaveResult.Full -> "Memory is full (${MemoryRules.MAX_NOTES} notes). Delete some first."
            SaveResult.Empty -> "A note needs some text."
            SaveResult.NotFound -> "That note no longer exists."
            else -> null
        }
    }

    fun delete(id: String) = viewModelScope.launchSafely(::failed) { repository.delete(id) }

    fun deleteAll() = viewModelScope.launchSafely(::failed) { repository.deleteAll() }

    private fun failed(e: Throwable) {
        message.value = userMessage(e)
    }

    fun dismissMessage() {
        message.value = null
    }
}

@Composable
fun MemoryRoute(onBack: () -> Unit, viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MemoryScreen(
        state = state,
        onBack = onBack,
        onQuery = viewModel::setQuery,
        onEnabled = { viewModel.setEnabled(it) },
        onSave = { id, text, tags -> viewModel.save(id, text, tags) },
        onDelete = { viewModel.delete(it) },
        onDeleteAll = { viewModel.deleteAll() },
        onDismissMessage = viewModel::dismissMessage,
    )
}

/** Note being edited; [id] null for a new one. */
private data class Draft(val id: String?, val text: String, val tags: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    state: MemoryUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: (String?, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var draft by remember { mutableStateOf<Draft?>(null) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MEMORY", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.total > 0) {
                        TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { draft = Draft(null, "", "") }) { Icon(Icons.Filled.Add, contentDescription = "Add note") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ListItem(
                modifier = Modifier.clickable { onEnabled(!state.enabled) },
                headlineContent = { Text("Use memory in chats") },
                supportingContent = {
                    Text(
                        if (state.enabled) {
                            "The agent recalls relevant notes and can save new ones. Notes stay encrypted on this phone."
                        } else {
                            "Off: the agent neither sees nor saves notes."
                        },
                    )
                },
                trailingContent = { Switch(checked = state.enabled, onCheckedChange = onEnabled) },
            )
            NoPersonalizedLearning {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    singleLine = true,
                    label = { Text("Search notes") },
                )
            }
            Text(
                "${state.total} / ${MemoryRules.MAX_NOTES} notes",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.notes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.total == 0) "No notes yet. Tap + to write one, or let the agent save facts you ask it to remember." else "No notes match.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.notes, key = { it.id }, contentType = { "note" }) { note ->
                        val tagLine: (@Composable () -> Unit)? =
                            if (note.tags.isEmpty()) null else ({ Text(note.tags.joinToString(" ") { "#$it" }) })
                        ListItem(
                            modifier = Modifier.clickable { draft = Draft(note.id, note.text, note.tags.joinToString(", ")) },
                            overlineContent = { Text(note.id + " · " + dates.format(Date(note.updatedAt))) },
                            headlineContent = { Text(note.text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                            supportingContent = tagLine,
                            trailingContent = {
                                IconButton(onClick = { onDelete(note.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete note ${note.id}") }
                            },
                        )
                    }
                }
            }
        }
    }

    draft?.let { d ->
        var text by remember(d) { mutableStateOf(d.text) }
        var tags by remember(d) { mutableStateOf(d.tags) }
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(if (d.id == null) "New note" else "Edit note") },
            text = {
                NoPersonalizedLearning {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(MemoryRules.MAX_TEXT) },
                            label = { Text("Note") },
                            minLines = 3,
                            supportingText = { Text("${text.length} / ${MemoryRules.MAX_TEXT}") },
                        )
                        OutlinedTextField(value = tags, onValueChange = { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    onSave(d.id, text, tags)
                    draft = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Cancel") } },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all notes?") },
            text = { Text("All ${state.total} memory notes are deleted from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    confirmDeleteAll = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } },
        )
    }
}
