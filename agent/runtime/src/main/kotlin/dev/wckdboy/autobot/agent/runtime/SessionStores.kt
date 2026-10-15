package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.IncognitoSession
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.shared.chats.ChatSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * Session logs in the SQLCipher database (`session_events`). Unreadable rows are skipped. Appended
 * prompts and replies also go into the chat's searchable text (`conversation_text`, `ChatSearch`).
 */
@Singleton
class EncryptedSessionStore @Inject constructor(
    private val rows: SessionEventRepository,
    private val conversations: ConversationRepository,
) : SessionStore {
    private val indexing = Mutex()
    override suspend fun load(sessionId: String): List<LogEntry> = rows.load(sessionId).mapNotNull { row ->
        try {
            SessionJson.decodeFromString(LogEntry.serializer(), row.json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) {
        rows.append(entries.map { SessionEventRow(sessionId, it.seq, it.time, SessionJson.encodeToString(LogEntry.serializer(), it)) })
        if (entries.any { ChatSearch.textOf(it.event) != null }) {
            // Search is a convenience: a failed index update never fails the turn.
            try {
                index(sessionId, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Adds [appended] to the chat's search text; a chat without one is indexed from its whole log. */
    private suspend fun index(sessionId: String, appended: List<LogEntry>) = indexing.withLock {
        val existing = conversations.searchText(sessionId)
        val text = if (existing == null) ChatSearch.append("", load(sessionId)) else ChatSearch.append(existing, appended)
        if (text != existing) conversations.putSearchText(sessionId, text)
    }

    /** Indexes chats that have no search text yet (from before search, or imported). */
    suspend fun indexMissing() {
        for (id in conversations.unindexedConversations()) {
            indexing.withLock {
                if (conversations.searchText(id) == null) conversations.putSearchText(id, ChatSearch.append("", load(id)))
            }
        }
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

    override suspend fun append(sessionId: String, entries: List<LogEntry>) = route(sessionId).append(sessionId, entries)

    /** Drops every incognito log (panic wipe). */
    fun clearIncognito() {
        memory = InMemorySessionStore()
    }
}
