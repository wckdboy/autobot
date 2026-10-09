package dev.wckdboy.autobot.agent.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable backing for session logs. Implementations must preserve append order. */
interface SessionStore {
    suspend fun load(sessionId: String): List<LogEntry>
    suspend fun append(sessionId: String, entries: List<LogEntry>)
}

/** Volatile store for tests and incognito sessions. */
class InMemorySessionStore : SessionStore {
    private val data = HashMap<String, MutableList<LogEntry>>()

    override suspend fun load(sessionId: String): List<LogEntry> = synchronized(data) { data[sessionId]?.toList().orEmpty() }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) {
        synchronized(data) { data.getOrPut(sessionId) { mutableListOf() }.addAll(entries) }
    }
}

/**
 * Append-only, single-writer session log. Every append is written through to the [store]
 * before it becomes visible in [entries], so what the UI shows has been persisted.
 */
class SessionLog private constructor(
    val sessionId: String,
    private val store: SessionStore,
    initial: List<LogEntry>,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(initial)
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    val lastSeq: Long get() = _entries.value.lastOrNull()?.seq ?: 0L

    suspend fun append(event: SessionEvent, surface: SurfaceOp = SurfaceOp.Append): LogEntry =
        appendAll(listOf(event), surface).single()

    suspend fun appendAll(events: List<SessionEvent>, surface: SurfaceOp = SurfaceOp.Append): List<LogEntry> = mutex.withLock {
        var seq = lastSeq
        val now = clock()
        val created = events.map { LogEntry(++seq, now, it, surface) }
        if (created.isNotEmpty()) {
            store.append(sessionId, created)
            _entries.value = _entries.value + created
        }
        created
    }

    inline fun <reified T : SessionEvent> latest(): T? = entries.value.asReversed().firstNotNullOfOrNull { it.event as? T }

    companion object {
        suspend fun open(sessionId: String, store: SessionStore, clock: () -> Long = System::currentTimeMillis): SessionLog =
            SessionLog(sessionId, store, store.load(sessionId).sortedBy { it.seq }, clock)
    }
}
