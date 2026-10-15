package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.shared.chats.ChatMatch
import dev.wckdboy.autobot.shared.chats.ChatSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Chats deleted moments ago, still restorable until the undo window closes. */
data class PendingDeletion(val batch: Long, val ids: List<String>, val label: String)

/**
 * Deleting and searching chats across the app (list, chat screen, settings).
 *
 * Deletion is soft for [UNDO_WINDOW_MS]: [trash] stops the chats' running turns at once and hides
 * them ([pending]); then the conversation, its session log, search text and attached images are
 * removed for good, unless [undo] came first. A process killed inside the window keeps the chats.
 */
@Singleton
class ChatLibrary @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val host: AgentHost,
    private val conversations: ConversationRepository,
    private val attachments: AttachmentRepository,
    private val store: RoutingSessionStore,
    private val encrypted: EncryptedSessionStore,
) {
    private val _pending = MutableStateFlow<List<PendingDeletion>>(emptyList())
    val pending: StateFlow<List<PendingDeletion>> = _pending.asStateFlow()
    private val timers = HashMap<Long, Job>()
    private val committing = HashSet<Long>()
    private val lock = Mutex()
    private var batches = 0L

    /** Hides [ids] now and deletes them after the undo window. Returns the batch to [undo]. */
    fun trash(ids: Collection<String>, label: String): Long {
        val list = ids.distinct()
        val batch = synchronized(timers) { ++batches }
        if (list.isEmpty()) return batch
        _pending.update { it + PendingDeletion(batch, list, label) }
        val job = scope.launch {
            // A running turn is cancelled first, so nothing writes to a chat being deleted.
            list.forEach { host.discard(it) }
            delay(UNDO_WINDOW_MS)
            commit(batch)
        }
        synchronized(timers) { timers[batch] = job }
        return batch
    }

    /** Restores [batch]; too late once its deletion has started. */
    fun undo(batch: Long) {
        synchronized(timers) {
            if (batch in committing) return
            timers.remove(batch)
        }?.cancel()
        _pending.update { list -> list.filterNot { it.batch == batch } }
    }

    /** Ends the undo window of [batch] now (the snackbar was dismissed). */
    fun commitNow(batch: Long) {
        synchronized(timers) { timers.remove(batch) }?.cancel()
        scope.launch { commit(batch) }
    }

    private suspend fun commit(batch: Long) {
        val entry = synchronized(timers) {
            timers.remove(batch)
            _pending.value.firstOrNull { it.batch == batch }?.takeIf { committing.add(batch) }
        } ?: return
        try {
            purge(entry.ids)
        } finally {
            // Hidden until gone from the database, so a deleted chat never flashes back.
            _pending.update { list -> list.filterNot { it.batch == batch } }
            synchronized(timers) { committing.remove(batch) }
        }
    }

    /** "Delete all chats": every chat, at once and for good (no undo). */
    suspend fun deleteAll() {
        val batches = synchronized(timers) { timers.values.toList().also { timers.clear() } }
        batches.forEach { it.cancel() }
        purge(conversations.allIds())
        _pending.value = emptyList()
    }

    private suspend fun purge(ids: List<String>) = lock.withLock {
        for (id in ids) {
            host.discard(id)
            val images = ChatSearch.attachments(store.load(id))
            conversations.deleteConversation(id) // cascades the log and the search text
            images.forEach { runCatching { attachments.delete(it) } }
        }
    }

    /**
     * Chats matching [query] in their title or messages, by id ([ChatSearch]). Chats without search
     * text yet are indexed first; incognito chats are searched in memory.
     */
    suspend fun search(query: String): Map<String, ChatMatch> {
        val terms = ChatSearch.terms(query)
        val prefilter = ChatSearch.prefilterTerm(terms) ?: return emptyMap()
        encrypted.indexMissing()
        val out = LinkedHashMap<String, ChatMatch>()
        conversations.searchCandidates(ChatSearch.likePattern(prefilter)).forEach { row ->
            ChatSearch.match(terms, row.title, row.text.orEmpty())?.let { out[row.id] = it }
        }
        conversations.observeConversations().first().filter { conversations.isIncognito(it.id) }.forEach { c ->
            ChatSearch.match(terms, c.title, ChatSearch.append("", store.load(c.id)))?.let { out[c.id] = it }
        }
        return out
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}
