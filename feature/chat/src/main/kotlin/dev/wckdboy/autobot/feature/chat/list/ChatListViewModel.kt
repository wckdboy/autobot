package dev.wckdboy.autobot.feature.chat.list

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.agent.runtime.ChatLibrary
import dev.wckdboy.autobot.agent.runtime.PendingDeletion
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.ChatFolderRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.feature.chat.toPrivacyStatus
import dev.wckdboy.autobot.shared.chats.ChatEntry
import dev.wckdboy.autobot.shared.chats.ChatFilter
import dev.wckdboy.autobot.shared.chats.ChatMatch
import dev.wckdboy.autobot.shared.chats.ChatNames
import dev.wckdboy.autobot.shared.chats.ChatOrganizer
import dev.wckdboy.autobot.shared.chats.ChatSort
import dev.wckdboy.autobot.shared.chats.HighlightSpan
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ConversationItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val incognito: Boolean,
    val running: Boolean = false,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val folderId: String? = null,
    /** Search: highlighted parts of the title, and an excerpt of the matching message. */
    val titleSpans: List<HighlightSpan> = emptyList(),
    val snippet: String? = null,
    val snippetSpans: List<HighlightSpan> = emptyList(),
)

/** A heading ("Today", "Pinned", … or none) and its chats. */
@Immutable
data class ChatListSection(val label: String, val items: List<ConversationItem>)

@Immutable
data class FolderChip(val id: String, val name: String, val count: Int)

@Immutable
data class ChatListUiState(
    val sections: List<ChatListSection> = emptyList(),
    /** Chats outside the archive, and how many of them run a turn. */
    val total: Int = 0,
    val live: Int = 0,
    val folders: List<FolderChip> = emptyList(),
    val filter: ChatFilter = ChatFilter.All,
    val sort: ChatSort = ChatSort.RECENT,
    val showIncognitoChip: Boolean = false,
    val archivedCount: Int = 0,
    val query: String = "",
    val selection: Set<String> = emptySet(),
    /** The latest deletion that can still be undone. */
    val pendingDeletion: PendingDeletion? = null,
    val incognito: Boolean = false,
    val privacyStatus: PrivacyStatus = PrivacyStatus.OFFLINE,
    val hasProviders: Boolean = true,
    val loading: Boolean = true,
) {
    val conversations: List<ConversationItem> get() = sections.flatMap { it.items }
    val selecting: Boolean get() = selection.isNotEmpty()
    val searching: Boolean get() = query.isNotBlank()
}

sealed interface ChatListEvent {
    data class OpenChat(val conversationId: String) : ChatListEvent
}

/**
 * The chat list: filters (all, pinned, folders, incognito, archive), sorting, day groups and search
 * (shared `ChatOrganizer` / `ChatSearch`), multi-select actions, and deletion with undo
 * ([ChatLibrary]).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val host: AgentHost,
    private val chats: ChatLibrary,
    private val settingsRepository: SettingsRepository,
    private val savedState: SavedStateHandle,
    networkPolicy: NetworkPolicy,
) : ViewModel() {

    /** `null` until the user toggles; falls back to the persisted default. */
    private val incognitoOverride = MutableStateFlow<Boolean?>(null)
    private val filter = savedState.getStateFlow(KEY_FILTER, ChatFilter.All.key)
    private val query = savedState.getStateFlow(KEY_QUERY, "")
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val events = Channel<ChatListEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<ChatListEvent> = events.receiveAsFlow()

    private val inputs = combine(settingsRepository.settings, incognitoOverride, networkPolicy.mode) { s, o, m -> Triple(s, o, m) }

    /** Search matches by chat id; null while no search is active. */
    private val matches: Flow<Map<String, ChatMatch>?> = query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(null) else conversations.observeConversations().mapLatest { chats.search(q) }
        }
        .onStart { emit(null) }

    private data class View(val filter: ChatFilter, val sort: ChatSort, val selection: Set<String>, val matches: Map<String, ChatMatch>?, val query: String)

    private val view = combine(filter, settingsRepository.chatSort, selection, matches, query) { f, s, sel, m, q -> View(ChatFilter.parse(f), s, sel, m, q) }

    private data class Data(val list: List<Conversation>, val folders: List<ChatFolderRow>, val pending: List<PendingDeletion>, val running: Set<String>)

    private val data = combine(conversations.observeConversations(), conversations.observeFolders(), chats.pending, host.running, ::Data)

    val uiState: StateFlow<ChatListUiState> = combine(data, providers.observeProviders(), view, inputs) { d, providerList, v, (settings, override, mode) ->
        val providerNames = providerList.associate { it.id to it.displayName }
        val hidden = d.pending.flatMapTo(HashSet()) { it.ids }
        val visible = d.list.filter { it.id !in hidden }
        val byId = visible.associateBy { it.id }
        val entries = visible.map { c ->
            ChatEntry(c.id, c.title, c.createdAt, c.updatedAt, c.pinned, c.archived, c.folderId, conversations.isIncognito(c.id))
        }
        val filterFolder = (v.filter as? ChatFilter.Folder)?.id
        // A folder that was deleted (or a stale incognito filter) falls back to everything.
        val activeFilter = when {
            filterFolder != null && d.folders.none { it.id == filterFolder } -> ChatFilter.All
            else -> v.filter
        }
        val searched = v.matches?.let { m -> entries.filter { it.id in m } } ?: entries
        val sections = ChatOrganizer.sections(searched, activeFilter, v.sort, startOfToday()).map { section ->
            ChatListSection(
                section.group.label,
                section.entries.map { e ->
                    val c = byId.getValue(e.id)
                    val match = v.matches?.get(e.id)
                    ConversationItem(
                        id = c.id,
                        title = c.title,
                        subtitle = listOfNotNull(c.providerId?.let(providerNames::get), c.model).joinToString(" · "),
                        updatedAt = c.updatedAt,
                        incognito = e.incognito,
                        running = c.id in d.running,
                        pinned = c.pinned,
                        archived = c.archived,
                        folderId = c.folderId,
                        titleSpans = match?.titleSpans.orEmpty(),
                        snippet = match?.snippet,
                        snippetSpans = match?.snippetSpans.orEmpty(),
                    )
                },
            )
        }
        val counts = ChatOrganizer.folderCounts(entries)
        val shown = sections.flatMapTo(HashSet()) { s -> s.items.map { it.id } }
        ChatListUiState(
            sections = sections,
            total = entries.count { !it.archived },
            live = entries.count { !it.archived && it.id in d.running },
            folders = d.folders.map { FolderChip(it.id, it.name, counts[it.id] ?: 0) },
            filter = activeFilter,
            sort = v.sort,
            showIncognitoChip = ChatOrganizer.showsIncognitoChip(entries) || activeFilter == ChatFilter.Incognito,
            archivedCount = entries.count { it.archived },
            query = v.query,
            selection = v.selection.filterTo(LinkedHashSet()) { it in shown },
            pendingDeletion = d.pending.lastOrNull(),
            incognito = override ?: settings.incognitoByDefault,
            privacyStatus = mode.toPrivacyStatus(),
            hasProviders = providerList.isNotEmpty(),
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    fun setIncognito(enabled: Boolean) {
        incognitoOverride.value = enabled
    }

    fun newChat() {
        viewModelScope.launch {
            val provider = providers.observeProviders().first().firstOrNull()
            val conversation = conversations.createConversation(
                incognito = uiState.value.incognito,
                providerId = provider?.id,
                model = provider?.defaultModel,
            )
            events.send(ChatListEvent.OpenChat(conversation.id))
        }
    }

    // View

    fun setFilter(filter: ChatFilter) {
        savedState[KEY_FILTER] = filter.key
        selection.value = emptySet()
    }

    fun setSort(sort: ChatSort) {
        viewModelScope.launch { settingsRepository.setChatSort(sort) }
    }

    fun setQuery(text: String) {
        savedState[KEY_QUERY] = text
    }

    // Selection

    fun toggleSelected(id: String) = selection.update { if (id in it) it - id else it + id }

    fun selectAll() {
        selection.value = uiState.value.conversations.mapTo(LinkedHashSet()) { it.id }
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // Chat actions ([ids]: the selection, or one chat from a swipe or the chat menu)

    fun rename(id: String, title: String) {
        viewModelScope.launch { conversations.rename(id, title) }
    }

    fun setPinned(ids: Collection<String>, pinned: Boolean) = act { conversations.setPinned(ids, pinned) }

    fun setArchived(ids: Collection<String>, archived: Boolean) = act { conversations.setArchived(ids, archived) }

    fun move(ids: Collection<String>, folderId: String?) = act { conversations.moveToFolder(ids, folderId) }

    /** Hides [ids] at once (running turns are cancelled) and deletes them unless undone in time. */
    fun delete(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val titles = uiState.value.conversations.filter { it.id in ids }.map { it.title }
        val label = if (ids.size == 1) titles.firstOrNull()?.let { "Deleted “$it”" } ?: "Chat deleted" else "Deleted ${ids.size} chats"
        chats.trash(ids, label)
        selection.value = emptySet()
    }

    fun undoDelete(batch: Long) = chats.undo(batch)

    fun commitDelete(batch: Long) = chats.commitNow(batch)

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
        selection.value = emptySet()
    }

    // Folders

    /** Why [name] cannot name a folder (null: it can); [except] is the folder being renamed. */
    fun folderProblem(name: String, except: String? = null): String? =
        ChatNames.folderProblem(name, uiState.value.folders.filter { it.id != except }.map { it.name })

    /** Creates a folder and moves [moving] into it (none: shows the new folder). */
    fun createFolder(name: String, moving: Collection<String> = emptyList()) {
        viewModelScope.launch {
            val folder = conversations.createFolder(name) ?: return@launch
            if (moving.isEmpty()) setFilter(ChatFilter.Folder(folder.id)) else conversations.moveToFolder(moving, folder.id)
        }
        selection.value = emptySet()
    }

    fun renameFolder(id: String, name: String) {
        viewModelScope.launch { conversations.renameFolder(id, name) }
    }

    /** Deletes the folder; its chats stay, outside any folder. */
    fun deleteFolder(id: String) {
        if (uiState.value.filter == ChatFilter.Folder(id)) setFilter(ChatFilter.All)
        viewModelScope.launch { conversations.deleteFolder(id) }
    }

    private fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private companion object {
        const val KEY_FILTER = "filter"
        const val KEY_QUERY = "query"
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}
