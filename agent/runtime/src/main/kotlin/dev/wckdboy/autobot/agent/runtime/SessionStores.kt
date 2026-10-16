package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.agent.core.session.StoredLog
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.IncognitoSession
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.shared.chats.ChatSearch
import javax.inject.Inject
import javax.inject.Singleton
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * Keeps each chat's searchable text (`ChatSearch`) up to date as messages are appended, off the
 * appending (agent) coroutine: appends are queued per chat and one worker per chat drains them,
 * so a burst of messages costs one read-modify-write, not one per message. The latest text of
 * recently indexed chats stays in memory, so the stored text is read once per chat, not per
 * message. [read]/[write] are the stored text, [loadLog] a chat's whole log (for a chat without
 * text yet). Search is a convenience: failures are dropped, never surfaced to the turn.
 */
internal class SearchIndexer(
    private val scope: CoroutineScope,
    private val read: suspend (String) -> String?,
    private val write: suspend (String, String) -> Unit,
    private val loadLog: suspend (String) -> List<LogEntry>,
) {
    /** Serializes writers of the stored text (queue workers and [indexWhole]); never held by appenders. */
    private val writing = Mutex()
    private val queued = HashMap<String, MutableList<LogEntry>>()
    private val working = HashSet<String>()
    private val texts = object : LinkedHashMap<String, Indexed>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Indexed>?) = size > CACHED_CHATS
    }

    /** Queues [entries] of [sessionId] for indexing and returns at once. */
    fun enqueue(sessionId: String, entries: List<LogEntry>) {
        if (entries.none { ChatSearch.textOf(it.event) != null }) return
        val start = synchronized(queued) {
            queued.getOrPut(sessionId) { ArrayList() } += entries
            working.add(sessionId)
        }
        if (start) scope.launch { drain(sessionId) }
    }

    private suspend fun drain(sessionId: String) {
        while (true) {
            val batch = synchronized(queued) {
                queued.remove(sessionId) ?: run {
                    working.remove(sessionId)
                    null
                }
            } ?: return
            try {
                writing.withLock { appendNow(sessionId, batch) }
            } catch (e: CancellationException) {
                synchronized(queued) { working.remove(sessionId) }
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun appendNow(sessionId: String, appended: List<LogEntry>) {
        val known = synchronized(texts) { texts[sessionId] } ?: read(sessionId)?.let { Indexed(it, -1L) }
        val next = if (known == null) {
            // A chat without text yet is indexed from its whole log (which holds [appended] already).
            fromLog(sessionId)
        } else {
            // Entries the whole-log pass already covered are not added twice.
            val fresh = appended.filter { it.seq > known.seq }
            Indexed(ChatSearch.append(known.text, fresh), maxOf(known.seq, fresh.maxOfOrNull { it.seq } ?: -1L))
        }
        if (next.text != known?.text) write(sessionId, next.text)
        synchronized(texts) { texts[sessionId] = next }
    }

    private suspend fun fromLog(sessionId: String): Indexed {
        val log = loadLog(sessionId)
        return Indexed(ChatSearch.append("", log), log.maxOfOrNull { it.seq } ?: -1L)
    }

    /** Indexes [sessionId] from its whole log if it has no text yet. */
    suspend fun indexWhole(sessionId: String) = writing.withLock {
        if (synchronized(texts) { texts[sessionId] } == null && read(sessionId) == null) {
            val next = fromLog(sessionId)
            write(sessionId, next.text)
            synchronized(texts) { texts[sessionId] = next }
        }
    }

    /** A chat's search text and the last log seq in it (-1: unknown, read back from storage). */
    private class Indexed(val text: String, val seq: Long)

    /** Whether appends are still waiting to be indexed (tests). */
    val busy: Boolean get() = synchronized(queued) { working.isNotEmpty() }

    private companion object {
        const val CACHED_CHATS = 8
    }
}

/**
 * Session logs in the SQLCipher database (`session_events`). Unreadable rows are skipped, but
 * their seq still counts, so the next append goes above them. Appended prompts and replies also
 * go into the chat's searchable text (`conversation_text`, `ChatSearch`), in the background
 * ([SearchIndexer]).
 */
@Singleton
class EncryptedSessionStore @Inject constructor(
    private val rows: SessionEventRepository,
    private val conversations: ConversationRepository,
    @ApplicationScope scope: CoroutineScope,
) : SessionStore {
    private val indexer = SearchIndexer(scope, conversations::searchText, conversations::putSearchText, ::load)

    override suspend fun load(sessionId: String): List<LogEntry> = loadLog(sessionId).entries

    override suspend fun loadLog(sessionId: String): StoredLog {
        val stored = rows.load(sessionId)
        // A long chat is thousands of rows: decode them on a worker, not on the caller's thread
        // (opening a chat calls this from the screen's main-thread scope).
        return withContext(Dispatchers.Default) {
            val entries = stored.mapNotNull { row ->
                try {
                    SessionJson.decodeFromString(LogEntry.serializer(), row.json)
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            StoredLog(entries, stored.maxOfOrNull { it.seq } ?: 0L)
        }
    }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) {
        rows.append(entries.map { SessionEventRow(sessionId, it.seq, it.time, SessionJson.encodeToString(LogEntry.serializer(), it)) })
        indexer.enqueue(sessionId, entries)
    }

    /** Indexes chats that have no search text yet (from before search, or imported). */
    suspend fun indexMissing() {
        for (id in conversations.unindexedConversations()) indexer.indexWhole(id)
    }
}

/**
 * Routes incognito sessions to process memory and everything else to [EncryptedSessionStore],
 * mirroring `ConversationRepository`.
 */
@Singleton
class RoutingSessionStore @Inject constructor(
    private val encrypted: EncryptedSessionStore,
    private val incognito: IncognitoSession,
) : SessionStore {
    @Volatile
    private var memory = InMemorySessionStore()

    private fun route(sessionId: String): SessionStore = if (incognito.isIncognito(sessionId)) memory else encrypted

    override suspend fun load(sessionId: String) = route(sessionId).load(sessionId)

    override suspend fun loadLog(sessionId: String) = route(sessionId).loadLog(sessionId)

    override suspend fun append(sessionId: String, entries: List<LogEntry>) = route(sessionId).append(sessionId, entries)

    /** Drops every incognito log (panic wipe). */
    fun clearIncognito() {
        memory = InMemorySessionStore()
    }
}
