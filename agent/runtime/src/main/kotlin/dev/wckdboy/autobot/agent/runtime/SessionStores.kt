package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SessionStore
import dev.wckdboy.autobot.core.data.IncognitoSession
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException

/** Session logs in the SQLCipher database (`session_events`). Unreadable rows are skipped. */
@Singleton
class EncryptedSessionStore @Inject constructor(
    private val rows: SessionEventRepository,
) : SessionStore {
    override suspend fun load(sessionId: String): List<LogEntry> = rows.load(sessionId).mapNotNull { row ->
        try {
            SessionJson.decodeFromString(LogEntry.serializer(), row.json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) = rows.append(
        entries.map { SessionEventRow(sessionId, it.seq, it.time, SessionJson.encodeToString(LogEntry.serializer(), it)) },
    )
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
