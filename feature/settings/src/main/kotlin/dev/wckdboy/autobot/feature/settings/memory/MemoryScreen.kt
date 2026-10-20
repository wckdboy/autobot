package dev.wckdboy.autobot.feature.settings.memory

import dev.wckdboy.autobot.core.designsystem.component.AddButton
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BackButton
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.EmptyState
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.SwitchRow
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.component.rememberLocalText
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.shared.memory.MemoryNote
import dev.wckdboy.autobot.shared.memory.MemoryRules
import dev.wckdboy.autobot.shared.memory.MemoryWords
import dev.wckdboy.autobot.shared.memory.SaveResult
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

@Immutable
data class MemoryUiState(
    val enabled: Boolean = false,
    val query: String = "",
    val notes: List<MemoryNote> = emptyList(),
    val total: Int = 0,
    val message: String? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val repository: MemoryRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    // Filtering follows the search text after a short pause (clearing applies at once), off the main thread.
    val uiState: StateFlow<MemoryUiState> = combine(repository.observe(), query.debounce { if (it.isBlank()) 0L else 150L }, settings.settings, message) { notes, q, s, m ->
        MemoryUiState(s.memoryEnabled, q, MemoryRules.filter(notes, q), notes.size, m)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

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
    val dissolve = rememberInkDissolve()
    Scaffold(
        topBar = {
            AutobotTopBar(title = "Memory", navigation = { BackButton(onBack) }) {
                if (state.total > 0) {
                    IconButton(onClick = { confirmDeleteAll = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = MemoryWords.DELETE_ALL, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                AddButton(MemoryWords.ADD) { draft = Draft(null, "", "") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SwitchRow(MemoryWords.USE_IN_CHATS, state.enabled, onEnabled, info = MemoryWords.USE_INFO)
            val query = rememberLocalText(state.query, onQuery)
            ConsoleTextField(
                value = query.text,
                onValueChange = query::edit,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = MemoryWords.SEARCH,
                singleLine = true,
                mono = true,
            )
            SectionLabel(MemoryWords.count(state.total), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (state.notes.isEmpty()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(MemoryWords.empty(state.total))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.notes, key = { it.id }, contentType = { "note" }) { note ->
                        NoteRow(
                            note = note,
                            date = dates.format(Date(note.updatedAt)),
                            modifier = Modifier.inkDissolve(dissolve, note.id),
                            onEdit = { draft = Draft(note.id, note.text, note.tags.joinToString(", ")) },
                            onDelete = { dissolve.dissolve(note.id) { onDelete(note.id) } },
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
                }) { ActionText("Save") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { ActionText("Cancel") } },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all notes?") },
            text = { Text("${if (state.total == 1) "The memory note is" else "All ${state.total} memory notes are"} deleted from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    confirmDeleteAll = false
                }) { ActionText("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { ActionText("Cancel") } },
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onDismissMessage) { ActionText("OK") } },
        )
    }
}

/** A note: its id and date, the text, its tags; tap to edit, the bin deletes (as on iOS). */
@Composable
private fun NoteRow(note: MemoryNote, date: String, modifier: Modifier, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MicroLabel("${note.id} · $date")
                Text(note.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (note.tags.isNotEmpty()) {
                    Text(note.tags.joinToString(" ") { "#$it" }, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete note ${note.id}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Hairline(Modifier.padding(start = 16.dp))
    }
}
