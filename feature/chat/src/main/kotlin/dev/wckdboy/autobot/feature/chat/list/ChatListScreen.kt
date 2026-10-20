package dev.wckdboy.autobot.feature.chat.list

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.ActionBanner
import dev.wckdboy.autobot.core.designsystem.component.EmptyContent
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.TitleWithDot
import dev.wckdboy.autobot.core.designsystem.component.linesFor
import dev.wckdboy.autobot.core.designsystem.component.longPressAction
import dev.wckdboy.autobot.core.designsystem.component.readingWidth
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.chat.FolderChoice
import dev.wckdboy.autobot.feature.chat.FolderPickerDialog
import dev.wckdboy.autobot.feature.chat.NameDialog
import dev.wckdboy.autobot.shared.chats.ChatFilter
import dev.wckdboy.autobot.shared.chats.ChatListWords
import dev.wckdboy.autobot.shared.chats.ChatSort
import dev.wckdboy.autobot.shared.chats.HighlightSpan
import kotlinx.coroutines.delay

@Composable
fun ChatListRoute(
    onBack: (() -> Unit)? = null,
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
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
    // One instance: a new one per state change would make every row recompose.
    val actions = remember(viewModel) { viewModel.asActions() }
    ChatListScreen(
        state = state,
        actions = actions,
        onBack = onBack,
        onOpenChat = onOpenChat,
        onOpenProviders = onOpenProviders,
    )
}

/** What the list can do (the view model in the app, no-ops in previews). */
class ChatListActions(
    /** A new chat: incognito when true, the default (Settings › Incognito by default) when null. */
    val newChat: (Boolean?) -> Unit = {},
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
    val consumeMessage: () -> Unit = {},
)

private fun ChatListViewModel.asActions() = ChatListActions(
    newChat = ::newChat, setFilter = ::setFilter, setSort = ::setSort, setQuery = ::setQuery,
    toggleSelected = ::toggleSelected, selectAll = ::selectAll, clearSelection = ::clearSelection, rename = ::rename,
    setPinned = ::setPinned, setArchived = ::setArchived, move = ::move, delete = ::delete, undoDelete = ::undoDelete,
    commitDelete = ::commitDelete, folderProblem = ::folderProblem, createFolder = ::createFolder, renameFolder = ::renameFolder,
    deleteFolder = ::deleteFolder, consumeMessage = ::consumeMessage,
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
) {
    var dialog by remember { mutableStateOf<ListDialog?>(null) }
    val dissolve = rememberInkDissolve()
    val snackbar = remember { SnackbarHostState() }
    val pending = state.pendingDeletion
    LaunchedEffect(pending?.batch) {
        if (pending == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(pending.label, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) actions.undoDelete(pending.batch)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        actions.consumeMessage()
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
    }
    BackHandler(enabled = state.selecting) { actions.clearSelection() }
    // Row callbacks read these when tapped, so they need not change (and recompose rows) with them.
    val selecting by rememberUpdatedState(state.selecting)
    val openChat by rememberUpdatedState(onOpenChat)
    val minute = rememberMinuteClock()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                if (state.selecting) SelectionBar(state, actions) { dialog = it } else Header(state, actions, onBack)
                SearchField(state, actions)
                FilterChips(state, actions) { dialog = it }
            }
        },
        // The screen's create action: a new chat (the default kind; the lock in the bar starts an incognito one).
        floatingActionButton = {
            if (!state.selecting) {
                // The content overload: its label merges into the button (the icon/text one keeps the text apart).
                ExtendedFloatingActionButton(onClick = { actions.newChat(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(ChatListWords.NEW)
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.readingWidth().fillMaxSize()) {
                if (!state.loading && !state.hasProviders) {
                    ActionBanner(ChatListWords.NO_MODEL, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), action = ChatListWords.ADD_PROVIDER, onAction = onOpenProviders)
                }
                if (!state.loading && state.sections.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyContent(ChatListWords.empty(state.searching, state.filter)) }
                } else {
                    // Room at the end for the floating button.
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                        state.sections.forEach { section ->
                            if (section.label.isNotEmpty()) {
                                item(key = "header:${section.label}", contentType = "header") {
                                    Text(
                                        section.label,
                                        modifier = Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            items(section.items, key = { it.id }, contentType = { "conversation" }) { item ->
                                Box(Modifier.animateItem().inkDissolve(dissolve, item.id)) {
                                    SwipeableRow(
                                        item = item,
                                        selecting = state.selecting,
                                        selected = item.id in state.selection,
                                        now = minute.longValue,
                                        onClick = { if (selecting) actions.toggleSelected(item.id) else openChat(item.id) },
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
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Dialogs(dialog, state, actions) { dialog = it }
}

/** The title (with the dot on the tab root), what runs, and an incognito chat one tap away (unless every new chat already is one). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Header(state: ChatListUiState, actions: ChatListActions, onBack: (() -> Unit)?) {
    val live = ChatListWords.live(state.live)
    TopAppBar(
        title = { TitleWithDot(ChatListWords.TITLE, mark = onBack == null) },
        subtitle = { if (live != null) Text(live) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = {
            if (!state.incognito) {
                IconButton(onClick = { actions.newChat(true) }) { Icon(Icons.Filled.Lock, contentDescription = ChatListWords.NEW_INCOGNITO) }
            }
        },
    )
}

/** Multi-select: count, then pin / archive / move / delete (and rename for one chat). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(state: ChatListUiState, actions: ChatListActions, open: (ListDialog) -> Unit) {
    val ids = state.selection.toList()
    val chosen = state.conversations.filter { it.id in state.selection }
    val allPinned = chosen.isNotEmpty() && chosen.all { it.pinned }
    val allArchived = chosen.isNotEmpty() && chosen.all { it.archived }
    var more by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(ChatListWords.selected(ids.size)) },
        navigationIcon = { IconButton(onClick = actions.clearSelection) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") } },
        actions = {
            IconButton(onClick = { actions.setPinned(ids, !allPinned) }) { Icon(AutobotIcons.Pin, contentDescription = if (allPinned) "Unpin" else "Pin") }
            IconButton(onClick = { actions.delete(ids) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
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
        },
    )
}

/** The search field. It owns its text (no round trip through the view model per keystroke); the view model gets each change and debounces the search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(state: ChatListUiState, actions: ChatListActions) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(actions) { snapshotFlow { query }.collect { actions.setQuery(it) } }
    // The keyboard is asked not to learn from what is searched.
    NoPersonalizedLearning {
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text(ChatListWords.SEARCH, maxLines = linesFor(1, 2)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }),
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {}
    }
}

/** Sort · All · Pinned · Incognito · folders · Archive · New folder. Long-press a folder to rename or delete it. */
@Composable
private fun FilterChips(state: ChatListUiState, actions: ChatListActions, open: (ListDialog) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "sort") {
            Box {
                AssistChip(
                    onClick = { sortMenu = true },
                    label = { Text(state.sort.label) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.semantics { contentDescription = "Sort: ${state.sort.label}" },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    ChatSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            trailingIcon = { if (sort == state.sort) Icon(Icons.Filled.Check, contentDescription = null) },
                            onClick = {
                                sortMenu = false
                                actions.setSort(sort)
                            },
                        )
                    }
                }
            }
        }
        item(key = "all") { Chip("All", state.filter == ChatFilter.All) { actions.setFilter(ChatFilter.All) } }
        item(key = "pinned") { Chip("Pinned", state.filter == ChatFilter.Pinned) { actions.setFilter(ChatFilter.Pinned) } }
        if (state.showIncognitoChip) {
            item(key = "incognito") { Chip("Incognito", state.filter == ChatFilter.Incognito) { actions.setFilter(ChatFilter.Incognito) } }
        }
        items(state.folders, key = { "folder:${it.id}" }) { folder ->
            Chip(
                "${folder.name} ${folder.count}",
                selected = state.filter == ChatFilter.Folder(folder.id),
                modifier = Modifier.longPressAction("Folder actions") { open(ListDialog.FolderMenu(folder.id, folder.name)) },
            ) { actions.setFilter(ChatFilter.Folder(folder.id)) }
        }
        if (state.archivedCount > 0 || state.filter == ChatFilter.Archived) {
            item(key = "archived") { Chip("Archive ${state.archivedCount}", state.filter == ChatFilter.Archived) { actions.setFilter(ChatFilter.Archived) } }
        }
        item(key = "new-folder") {
            AssistChip(
                onClick = { open(ListDialog.NewFolder(emptyList())) },
                label = { Text("New folder") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        modifier = modifier,
        leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }) else null,
    )
}

/** Tests count recompositions through this (null in the app). */
@androidx.annotation.VisibleForTesting
internal var chatListProbe: ((String) -> Unit)? = null

private enum class RowAction { RENAME, PIN, MOVE, ARCHIVE, DELETE }

/** Swipe left to delete (undo in the snackbar), right to archive (or unarchive). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableRow(
    item: ConversationItem,
    selecting: Boolean,
    selected: Boolean,
    now: Long,
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
            // Nothing under a resting row: TalkBack does not read a hidden "archive" with every chat.
            if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toDelete = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier.fillMaxSize().background(if (toDelete) scheme.errorContainer else scheme.secondaryContainer).padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (toDelete) Alignment.End else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (toDelete) scheme.onErrorContainer else scheme.onSecondaryContainer
                if (toDelete) Icon(Icons.Filled.Delete, contentDescription = null, tint = ink)
                Text(if (toDelete) "Delete" else if (item.archived) "Unarchive" else "Archive", style = MaterialTheme.typography.labelLarge, color = ink)
            }
        },
    ) {
        ConversationRow(item, selecting, selected, now, onClick, onLongClick, onMenu)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationItem,
    selecting: Boolean,
    selected: Boolean,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: (RowAction) -> Unit,
) {
    chatListProbe?.invoke("row")
    var menu by remember { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
    val highlight = remember(highlightColor) { SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold) }
    val title = remember(item.title, item.titleSpans, highlight) { highlighted(item.title, item.titleSpans, highlight) }
    val snippet = remember(item.snippet, item.snippetSpans, highlight) { item.snippet?.let { highlighted(it, item.snippetSpans, highlight) } }
    val meta = remember(item.subtitle, item.updatedAt, now) {
        listOf(item.subtitle, (if (now - item.updatedAt < DateUtils.MINUTE_IN_MILLIS) "just now" else DateUtils.getRelativeTimeSpanString(item.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString())).filter { it.isNotBlank() }.joinToString(" · ")
    }
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select"),
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        // A plain row starts at the margin; the leading slot only appears to say something.
        leadingContent = when {
            selecting -> ({ Checkbox(checked = selected, onCheckedChange = null) })
            item.running -> ({ Badge(Modifier.semantics { contentDescription = "Running" }, containerColor = MaterialTheme.colorScheme.primary) })
            item.incognito -> ({ Icon(Icons.Filled.Lock, contentDescription = "Incognito", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            else -> null
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.pinned) Icon(AutobotIcons.Pin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                // A large font wraps the title and its line of detail rather than cutting their ends.
                Text(title, maxLines = linesFor(1, 3), overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                snippet?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = linesFor(1, 3), overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = if (selecting) null else ({
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Chat actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val pick = { a: RowAction -> menu = false; onMenu(a) }
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { pick(RowAction.RENAME) })
                    DropdownMenuItem(text = { Text(if (item.pinned) "Unpin" else "Pin to top") }, leadingIcon = { Icon(AutobotIcons.Pin, null) }, onClick = { pick(RowAction.PIN) })
                    DropdownMenuItem(text = { Text("Move to folder") }, onClick = { pick(RowAction.MOVE) })
                    DropdownMenuItem(text = { Text(if (item.archived) "Unarchive" else "Archive") }, onClick = { pick(RowAction.ARCHIVE) })
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { pick(RowAction.DELETE) },
                    )
                }
            }
        }),
    )
}

/** The current time, ticking at each minute boundary while on screen (relative times refresh with it). */
@Composable
private fun rememberMinuteClock(): MutableLongState {
    val clock = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(clock) {
        while (true) {
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
            clock.longValue = System.currentTimeMillis()
        }
    }
    return clock
}

private const val MINUTE_MS = 60_000L

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
            confirm = "Rename",
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
            confirm = "Create",
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
            confirmButton = { TextButton(onClick = { set(ListDialog.RenameFolder(dialog.id, dialog.name)) }) { Text("Rename") } },
            dismissButton = {
                TextButton(onClick = { set(ListDialog.DeleteFolder(dialog.id, dialog.name)) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
        is ListDialog.RenameFolder -> NameDialog(
            title = "Rename folder",
            initial = dialog.name,
            confirm = "Rename",
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
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { set(null) }) { Text("Cancel") } },
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
                loading = false,
            ),
            actions = ChatListActions(),
            onOpenChat = {}, onOpenProviders = {},
        )
    }
}
