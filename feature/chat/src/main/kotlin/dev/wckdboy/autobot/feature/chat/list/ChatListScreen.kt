package dev.wckdboy.autobot.feature.chat.list

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatusPill
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.chat.FolderChoice
import dev.wckdboy.autobot.feature.chat.FolderPickerDialog
import dev.wckdboy.autobot.feature.chat.NameDialog
import dev.wckdboy.autobot.shared.chats.ChatFilter
import dev.wckdboy.autobot.shared.chats.ChatSort
import dev.wckdboy.autobot.shared.chats.HighlightSpan

@Composable
fun ChatListRoute(
    onBack: (() -> Unit)? = null,
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is ChatListEvent.OpenChat -> onOpenChat(event.conversationId)
            }
        }
    }
    ChatListScreen(
        state = state,
        actions = viewModel.asActions(),
        onBack = onBack,
        onOpenChat = onOpenChat,
        onOpenProviders = onOpenProviders,
        onOpenPrivacyCenter = onOpenPrivacyCenter,
    )
}

/** What the list can do (the view model in the app, no-ops in previews). */
class ChatListActions(
    val newChat: () -> Unit = {},
    val setIncognito: (Boolean) -> Unit = {},
    val setFilter: (ChatFilter) -> Unit = {},
    val setSort: (ChatSort) -> Unit = {},
    val setQuery: (String) -> Unit = {},
    val toggleSelected: (String) -> Unit = {},
    val selectAll: () -> Unit = {},
    val clearSelection: () -> Unit = {},
    val rename: (String, String) -> Unit = { _, _ -> },
    val setPinned: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    val setArchived: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    val move: (Collection<String>, String?) -> Unit = { _, _ -> },
    val delete: (Collection<String>) -> Unit = {},
    val undoDelete: (Long) -> Unit = {},
    val commitDelete: (Long) -> Unit = {},
    val folderProblem: (String, String?) -> String? = { _, _ -> null },
    val createFolder: (String, Collection<String>) -> Unit = { _, _ -> },
    val renameFolder: (String, String) -> Unit = { _, _ -> },
    val deleteFolder: (String) -> Unit = {},
)

private fun ChatListViewModel.asActions() = ChatListActions(
    newChat = ::newChat, setIncognito = ::setIncognito, setFilter = ::setFilter, setSort = ::setSort, setQuery = ::setQuery,
    toggleSelected = ::toggleSelected, selectAll = ::selectAll, clearSelection = ::clearSelection, rename = ::rename,
    setPinned = ::setPinned, setArchived = ::setArchived, move = ::move, delete = ::delete, undoDelete = ::undoDelete,
    commitDelete = ::commitDelete, folderProblem = ::folderProblem, createFolder = ::createFolder, renameFolder = ::renameFolder,
    deleteFolder = ::deleteFolder,
)

/** Which dialog of the list is open. */
private sealed interface ListDialog {
    data class Rename(val id: String, val title: String) : ListDialog
    data class Move(val ids: List<String>, val current: String?) : ListDialog
    data class NewFolder(val moving: List<String>) : ListDialog
    data class FolderMenu(val id: String, val name: String) : ListDialog
    data class RenameFolder(val id: String, val name: String) : ListDialog
    data class DeleteFolder(val id: String, val name: String) : ListDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    state: ChatListUiState,
    actions: ChatListActions,
    onBack: (() -> Unit)? = null,
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
) {
    var dialog by remember { mutableStateOf<ListDialog?>(null) }
    val dissolve = rememberInkDissolve()
    val snackbar = remember { SnackbarHostState() }
    val pending = state.pendingDeletion
    LaunchedEffect(pending?.batch) {
        if (pending == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(pending.label, actionLabel = "UNDO", withDismissAction = true, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) actions.undoDelete(pending.batch)
    }
    BackHandler(enabled = state.selecting) { actions.clearSelection() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding().hairlineEdge()) {
                    if (state.selecting) {
                        SelectionBar(state, actions) { dialog = it }
                    } else {
                        Header(state, actions, onBack, onOpenPrivacyCenter)
                    }
                    SearchAndSort(state, actions)
                    FilterChips(state, actions) { dialog = it }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && !state.hasProviders) {
                Panel(Modifier.fillMaxWidth().padding(12.dp), title = "No model provider", accent = AutobotColors.Amber) {
                    Text(
                        "Add DeepSeek, OpenRouter, a local Ollama server or any OpenAI-compatible endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenProviders) { Text("ADD PROVIDER") }
                }
            }
            if (!state.loading && state.sections.isEmpty()) {
                EmptyList(state)
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    state.sections.forEach { section ->
                        if (section.label.isNotEmpty()) {
                            item(key = "header:${section.label}", contentType = "header") {
                                MicroLabel(
                                    section.label,
                                    modifier = Modifier.animateItem().padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        items(section.items, key = { it.id }, contentType = { "conversation" }) { item ->
                            Column(Modifier.animateItem().inkDissolve(dissolve, item.id)) {
                                SwipeableRow(
                                    item = item,
                                    selecting = state.selecting,
                                    selected = item.id in state.selection,
                                    onClick = { if (state.selecting) actions.toggleSelected(item.id) else onOpenChat(item.id) },
                                    onLongClick = { actions.toggleSelected(item.id) },
                                    onDelete = { actions.delete(listOf(item.id)) },
                                    onArchive = { actions.setArchived(listOf(item.id), !item.archived) },
                                    onMenu = { action ->
                                        when (action) {
                                            RowAction.RENAME -> dialog = ListDialog.Rename(item.id, item.title)
                                            RowAction.PIN -> actions.setPinned(listOf(item.id), !item.pinned)
                                            RowAction.MOVE -> dialog = ListDialog.Move(listOf(item.id), item.folderId)
                                            RowAction.ARCHIVE -> actions.setArchived(listOf(item.id), !item.archived)
                                            // Deleted from the menu: the row dissolves first (a swipe already moved it away).
                                            RowAction.DELETE -> dissolve.dissolve(item.id) { actions.delete(listOf(item.id)) }
                                        }
                                    },
                                )
                                Hairline(Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    Dialogs(dialog, state, actions) { dialog = it }
}

@Composable
private fun Header(state: ChatListUiState, actions: ChatListActions, onBack: (() -> Unit)?, onOpenPrivacyCenter: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Column(Modifier.weight(1f)) {
            Text("SESSIONS", style = MaterialTheme.typography.titleLarge)
            MicroLabel("${state.total} sessions · ${state.live} live")
        }
        PrivacyStatusPill(state.privacyStatus, onClick = onOpenPrivacyCenter)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (state.incognito) AutobotColors.Uv else MaterialTheme.colorScheme.outline)
        Column(Modifier.weight(1f)) {
            MicroLabel("incognito", color = if (state.incognito) AutobotColors.Uv else MaterialTheme.colorScheme.onSurfaceVariant)
            MicroLabel("memory only · never written to disk", color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = state.incognito, onCheckedChange = actions.setIncognito)
        Button(
            onClick = actions.newChat,
            shape = MaterialTheme.shapes.small,
            colors = if (state.incognito) ButtonDefaults.buttonColors(containerColor = AutobotColors.Uv) else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            Icon(AutobotIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("NEW")
        }
    }
}

/** Multi-select: count, then pin / archive / move / delete (and rename for one chat). */
@Composable
private fun SelectionBar(state: ChatListUiState, actions: ChatListActions, open: (ListDialog) -> Unit) {
    val ids = state.selection.toList()
    val chosen = state.conversations.filter { it.id in state.selection }
    val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
    val allArchived = chosen.isNotEmpty() && chosen.all { it.archived }
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
        Column(Modifier.weight(1f)) {
            Text("${ids.size} SELECTED", style = MaterialTheme.typography.titleLarge)
            MicroLabel("tap to add or remove")
        }
        IconButton(onClick = { actions.setPinned(ids, !allPinned) }) {
            Icon(AutobotIcons.Pin, contentDescription = if (allPinned) "Unpin" else "Pin", tint = if (allPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = { actions.delete(ids) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (chosen.size == 1) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { more = false; open(ListDialog.Rename(chosen[0].id, chosen[0].title)) })
                }
                DropdownMenuItem(text = { Text("Move to folder") }, onClick = { more = false; open(ListDialog.Move(ids, chosen.map { it.folderId }.distinct().singleOrNull())) })
                DropdownMenuItem(text = { Text(if (allArchived) "Unarchive" else "Archive") }, onClick = { more = false; actions.setArchived(ids, !allArchived) })
                DropdownMenuItem(text = { Text("Select all") }, onClick = { more = false; actions.selectAll() })
            }
        }
    }
}

@Composable
private fun SearchAndSort(state: ChatListUiState, actions: ChatListActions) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConsoleTextField(
            value = state.query,
            onValueChange = actions.setQuery,
            modifier = Modifier.weight(1f),
            placeholder = "Search titles and messages",
            singleLine = true,
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { actions.setQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp)) }
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
            },
        )
        Box {
            Tag("${state.sort.label} ▾", onClick = { sortMenu = true })
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                ChatSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sort.label) },
                        trailingIcon = { if (sort == state.sort) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            sortMenu = false
                            actions.setSort(sort)
                        },
                    )
                }
            }
        }
    }
}

/** All · Pinned · Incognito · folders · Archive · + folder. Long-press a folder to rename or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterChips(state: ChatListUiState, actions: ChatListActions, open: (ListDialog) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "all") { Chip("All", state.filter == ChatFilter.All) { actions.setFilter(ChatFilter.All) } }
        item(key = "pinned") { Chip("Pinned", state.filter == ChatFilter.Pinned) { actions.setFilter(ChatFilter.Pinned) } }
        if (state.showIncognitoChip) {
            item(key = "incognito") { Chip("Incognito", state.filter == ChatFilter.Incognito, AutobotColors.Uv) { actions.setFilter(ChatFilter.Incognito) } }
        }
        items(state.folders, key = { "folder:${it.id}" }) { folder ->
            val selected = state.filter == ChatFilter.Folder(folder.id)
            Tag(
                "${folder.name} ${folder.count}",
                modifier = Modifier.combinedClickable(
                    onClick = { actions.setFilter(ChatFilter.Folder(folder.id)) },
                    onLongClick = { open(ListDialog.FolderMenu(folder.id, folder.name)) },
                ),
                accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                filled = selected,
            )
        }
        if (state.archivedCount > 0 || state.filter == ChatFilter.Archived) {
            item(key = "archived") { Chip("Archive ${state.archivedCount}", state.filter == ChatFilter.Archived) { actions.setFilter(ChatFilter.Archived) } }
        }
        item(key = "new-folder") { Tag("+ Folder", accent = MaterialTheme.colorScheme.outline, onClick = { open(ListDialog.NewFolder(emptyList())) }) }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, accent: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Tag(text, accent = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, filled = selected, onClick = onClick)
}

@Composable
private fun EmptyList(state: ChatListUiState) {
    val (title, detail) = when {
        state.searching -> "no matches" to "titles and messages on this device were searched"
        state.filter == ChatFilter.Archived -> "archive is empty" to "archived chats are kept here, out of the list"
        state.filter == ChatFilter.Pinned -> "nothing pinned" to "pin a chat to keep it on top"
        state.filter is ChatFilter.Folder -> "empty folder" to "long-press a chat, then move it here"
        else -> "no sessions" to "everything stays encrypted on this device"
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(AutobotIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(28.dp))
            MicroLabel(title)
            MicroLabel(detail, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private enum class RowAction { RENAME, PIN, MOVE, ARCHIVE, DELETE }

/** Swipe left to delete (undo in the snackbar), right to archive (or unarchive). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    item: ConversationItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
    onMenu: (RowAction) -> Unit,
) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.StartToEnd -> onArchive()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = {
            val toDelete = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                Modifier.fillMaxSize().background(if (toDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 20.dp),
                contentAlignment = if (toDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                MicroLabel(
                    if (toDelete) "delete" else if (item.archived) "unarchive" else "archive",
                    color = if (toDelete) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        ConversationRow(item, selecting, selected, onClick, onLongClick, onMenu)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: (RowAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), fontWeight = FontWeight.SemiBold)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select")
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(if (selecting) 18.dp else 8.dp), contentAlignment = Alignment.Center) {
            when {
                selecting -> SelectBox(selected)
                item.running -> StatusDot(AutobotColors.Acid, live = true)
                item.incognito -> StatusDot(AutobotColors.Uv)
                else -> Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.pinned) Icon(AutobotIcons.Pin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Text(highlighted(item.title, item.titleSpans, highlight), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            item.snippet?.let {
                Text(
                    highlighted(it, item.snippetSpans, highlight),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOf(item.subtitle, DateUtils.getRelativeTimeSpanString(item.updatedAt).toString()).filter { it.isNotBlank() }.joinToString(" · "),
                style = AutobotTheme.styles.codeBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selecting) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val pick = { a: RowAction -> menu = false; onMenu(a) }
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)) }, onClick = { pick(RowAction.RENAME) })
                    DropdownMenuItem(text = { Text(if (item.pinned) "Unpin" else "Pin to top") }, leadingIcon = { Icon(AutobotIcons.Pin, null, Modifier.size(16.dp)) }, onClick = { pick(RowAction.PIN) })
                    DropdownMenuItem(text = { Text("Move to folder") }, onClick = { pick(RowAction.MOVE) })
                    DropdownMenuItem(text = { Text(if (item.archived) "Unarchive" else "Archive") }, onClick = { pick(RowAction.ARCHIVE) })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) },
                        onClick = { pick(RowAction.DELETE) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectBox(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(16.dp).then(if (selected) Modifier.background(color, MaterialTheme.shapes.extraSmall) else Modifier.border(1.dp, color, MaterialTheme.shapes.extraSmall)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
    }
}

private fun highlighted(text: String, spans: List<HighlightSpan>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { if (it.start in 0..it.end && it.end <= text.length) addStyle(style, it.start, it.end) }
}

@Composable
private fun Dialogs(dialog: ListDialog?, state: ChatListUiState, actions: ChatListActions, set: (ListDialog?) -> Unit) {
    when (dialog) {
        null -> Unit
        is ListDialog.Rename -> NameDialog(
            title = "Rename chat",
            initial = dialog.title,
            confirm = "RENAME",
            onDismiss = { set(null) },
            onConfirm = {
                actions.rename(dialog.id, it)
                actions.clearSelection()
                set(null)
            },
        )
        is ListDialog.Move -> FolderPickerDialog(
            folders = state.folders.map { FolderChoice(it.id, it.name) },
            current = dialog.current,
            onDismiss = { set(null) },
            onPick = {
                actions.move(dialog.ids, it)
                set(null)
            },
            onNewFolder = { set(ListDialog.NewFolder(dialog.ids)) },
        )
        is ListDialog.NewFolder -> NameDialog(
            title = "New folder",
            initial = "",
            confirm = "CREATE",
            onDismiss = { set(null) },
            onConfirm = {
                actions.createFolder(it, dialog.moving)
                set(null)
            },
            problem = { actions.folderProblem(it, null) },
        )
        is ListDialog.FolderMenu -> AlertDialog(
            onDismissRequest = { set(null) },
            title = { Text(dialog.name) },
            text = { Text("Deleting a folder keeps its chats; they move out of it.") },
            confirmButton = { TextButton(onClick = { set(ListDialog.RenameFolder(dialog.id, dialog.name)) }) { Text("RENAME") } },
            dismissButton = {
                TextButton(onClick = { set(ListDialog.DeleteFolder(dialog.id, dialog.name)) }) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            },
        )
        is ListDialog.RenameFolder -> NameDialog(
            title = "Rename folder",
            initial = dialog.name,
            confirm = "RENAME",
            onDismiss = { set(null) },
            onConfirm = {
                actions.renameFolder(dialog.id, it)
                set(null)
            },
            problem = { actions.folderProblem(it, dialog.id) },
        )
        is ListDialog.DeleteFolder -> AlertDialog(
            onDismissRequest = { set(null) },
            title = { Text("Delete “${dialog.name}”?") },
            text = { Text("Its chats are kept and move out of the folder.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteFolder(dialog.id)
                    set(null)
                }) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { set(null) }) { Text("CANCEL") } },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF09090B)
@Composable
private fun ChatListScreenPreview() {
    AutobotTheme {
        ChatListScreen(
            state = ChatListUiState(
                sections = listOf(
                    ChatListSection("Pinned", listOf(ConversationItem("1", "Refactor notes into a plan", "DeepSeek · deepseek-chat", 0L, false, running = true, pinned = true))),
                    ChatListSection("Today", listOf(ConversationItem("2", "Private question", "Ollama · qwen3", 0L, true))),
                ),
                folders = listOf(FolderChip("f", "Work", 3)),
                privacyStatus = PrivacyStatus.OFFLINE,
                loading = false,
            ),
            actions = ChatListActions(),
            onOpenChat = {}, onOpenProviders = {}, onOpenPrivacyCenter = {},
        )
    }
}
