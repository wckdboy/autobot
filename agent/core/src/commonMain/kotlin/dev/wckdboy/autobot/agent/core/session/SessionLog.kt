package dev.wckdboy.autobot.agent.core.session

import dev.wckdboy.autobot.agent.core.currentTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable backing for session logs. Implementations must preserve append order. */
interface SessionStore {
    suspend fun load(sessionId: String): List<LogEntry>
    suspend fun append(sessionId: String, entries: List<LogEntry>)

    /**
     * [load] plus the highest seq stored. A store that skips rows it cannot decode must count
     * them here, or the next append reuses their seq and conflicts with them.
     */
    suspend fun loadLog(sessionId: String): StoredLog = load(sessionId).let { StoredLog(it, it.maxOfOrNull { e -> e.seq } ?: 0L) }
}

/** A session's readable entries and the highest seq stored for it (readable or not). */
class StoredLog(val entries: List<LogEntry>, val lastSeq: Long)

/** Volatile store for tests and incognito sessions. */
class InMemorySessionStore : SessionStore {
    private val lock = dev.wckdboy.autobot.agent.core.Lock()
    private val data = HashMap<String, MutableList<LogEntry>>()

    override suspend fun load(sessionId: String): List<LogEntry> = lock.withLock { data[sessionId]?.toList().orEmpty() }

    override suspend fun append(sessionId: String, entries: List<LogEntry>) {
        lock.withLock { data.getOrPut(sessionId) { mutableListOf() }.addAll(entries) }
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
    storedSeq: Long,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow(initial)
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    /** Seqs up to this one are taken in the store: unreadable rows, or a failed append that may have landed. */
    private var seqFloor = storedSeq

    val lastSeq: Long get() = maxOf(_entries.value.lastOrNull()?.seq ?: 0L, seqFloor)

    suspend fun append(event: SessionEvent, surface: SurfaceOp = SurfaceOp.Append): LogEntry =
        appendAll(listOf(event), surface).single()

    suspend fun appendAll(events: List<SessionEvent>, surface: SurfaceOp = SurfaceOp.Append): List<LogEntry> = mutex.withLock {
        var seq = lastSeq
        val now = clock()
        val created = events.map { LogEntry(++seq, now, it, surface) }
        if (created.isNotEmpty()) {
            try {
                store.append(sessionId, created)
            } catch (t: Throwable) {
                // Part of the batch may be stored: the next append starts above it.
                seqFloor = seq
                throw t
            }
            _entries.value = _entries.value + created
        }
        created
    }

    inline fun <reified T : SessionEvent> latest(): T? = entries.value.asReversed().firstNotNullOfOrNull { it.event as? T }

    companion object {
        suspend fun open(sessionId: String, store: SessionStore, clock: () -> Long = ::currentTimeMillis): SessionLog {
            val stored = store.loadLog(sessionId)
            return SessionLog(sessionId, store, stored.entries.sortedBy { it.seq }, stored.lastSeq, clock)
        }
    }
}
