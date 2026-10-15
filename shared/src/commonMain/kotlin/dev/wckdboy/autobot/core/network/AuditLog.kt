package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.currentTimeMillis
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One outbound request attempt.
 *
 * Deliberately minimal: no headers, no query string, no request or response bodies, so the log
 * itself can never contain credentials or prompt content.
 *
 * @property path URL path only (query and fragment stripped).
 * @property bytesSent request body length if known.
 * @property bytesReceived response `Content-Length` if known (streams usually report `null`).
 * @property tag caller-supplied label, e.g. the provider name.
 * @property route effective route label (`direct`, `tor`, `socks5`, `offline`).
 * @property blocked `true` if the kill switch refused the request before any I/O.
 * @property session the agent session whose turn made the request ([TurnTrace]), if any.
 * @property turn that session's turn number.
 * @property count requests this entry stands for: repeated identical requests (a progress poll)
 *   are collapsed into one entry, with their bytes summed (see [AuditLog.record]).
 */
data class AuditEntry(
    val id: Long,
    val timeMillis: Long,
    val method: String,
    val host: String,
    val path: String,
    val bytesSent: Long?,
    val bytesReceived: Long?,
    val status: Int?,
    val tag: String?,
    val route: String,
    val blocked: Boolean,
    val error: String? = null,
    val session: String? = null,
    val turn: Int? = null,
    val count: Int = 1,
)

/**
 * In-memory ring buffer of [AuditEntry]s, exposed as flows. Never persisted, so the log is gone
 * when the process dies (including after a panic wipe). Shared so both apps record the same
 * fields with the same redaction.
 *
 * Entries tied to an agent turn ([AuditEntry.session] / [AuditEntry.turn]) are also kept per
 * turn until that turn's privacy receipt took them ([release]), so a busy turn (or other traffic
 * meanwhile) can't push them out of the ring before the receipt is written. At most
 * [MAX_OPEN_TURNS] turns (oldest dropped) of up to [MAX_TURN_ENTRIES] entries each are kept.
 *
 * Polls collapse: a request identical to one of the last [COLLAPSE_WINDOW] entries (method, host,
 * path, status, route, tag, blocked, no error, same session and turn; untraced ones only within
 * [COLLAPSE_MS]) updates that entry instead of adding one: [AuditEntry.count] grows, bytes add
 * up, it moves to the end with the new time. A progress poll then can't push useful entries out
 * of the ring, and receipts still count every request.
 */
class AuditLog(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = ::currentTimeMillis,
) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val ids = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<AuditEntry>>(emptyList())

    /** Snapshot of the buffer, oldest first. */
    val entries: StateFlow<List<AuditEntry>> = _entries.asStateFlow()

    private val _events = MutableSharedFlow<AuditEntry>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of new entries as they are recorded. */
    val events: SharedFlow<AuditEntry> = _events.asSharedFlow()

    private val turnLock = Lock()
    /** Entries of turns whose receipt is not written yet, by [turnKey]; insertion order = age. */
    private val turns = LinkedHashMap<String, MutableList<AuditEntry>>()
    /** Entry id → [turnKey], for [received] updates of kept entries. */
    private val turnOf = HashMap<Long, String>()

    private fun turnKey(session: String, turn: Int) = "$session\u0000$turn"

    private fun keep(entry: AuditEntry) {
        val session = entry.session ?: return
        val turn = entry.turn ?: return
        val key = turnKey(session, turn)
        turnLock.withLock {
            val list = turns.getOrPut(key) { ArrayList() }
            if (list.size >= MAX_TURN_ENTRIES) return
            list += entry
            turnOf[entry.id] = key
            while (turns.size > MAX_OPEN_TURNS) {
                val eldest = turns.keys.first()
                turns.remove(eldest)?.forEach { turnOf.remove(it.id) }
            }
        }
    }

    fun record(
        method: String,
        host: String,
        path: String,
        bytesSent: Long? = null,
        bytesReceived: Long? = null,
        status: Int? = null,
        tag: String? = null,
        route: String,
        blocked: Boolean,
        error: String? = null,
        session: String? = null,
        turn: Int? = null,
    ): AuditEntry {
        val entry = AuditEntry(
            id = ids.incrementAndFetch(),
            timeMillis = clock(),
            method = method,
            host = host,
            path = path.substringBefore('?').substringBefore('#'),
            bytesSent = bytesSent?.takeIf { it >= 0 },
            bytesReceived = bytesReceived?.takeIf { it >= 0 },
            status = status,
            tag = tag,
            route = route,
            blocked = blocked,
            error = error,
            session = session,
            turn = turn,
        )
        val recorded = turnLock.withLock {
            val current = _entries.value
            val start = (current.size - COLLAPSE_WINDOW).coerceAtLeast(0)
            var match = -1
            for (i in current.lastIndex downTo start) {
                if (collapses(current[i], entry)) {
                    match = i
                    break
                }
            }
            if (match >= 0) {
                val old = current[match]
                val merged = old.copy(
                    timeMillis = entry.timeMillis,
                    bytesSent = sum(old.bytesSent, entry.bytesSent),
                    bytesReceived = sum(old.bytesReceived, entry.bytesReceived),
                    count = old.count + 1,
                )
                // [received] replaces the latest request's own part of the sum.
                latestReceived[old.id] = entry.bytesReceived ?: 0L
                _entries.value = current.toMutableList().apply {
                    removeAt(match)
                    add(merged)
                }
                turnOf[old.id]?.let { turns[it] }?.let { list ->
                    val i = list.indexOfLast { it.id == old.id }
                    if (i >= 0) list[i] = merged
                }
                merged
            } else {
                _entries.value = (if (current.size >= capacity) current.drop(current.size - capacity + 1) else current) + entry
                if (latestReceived.size > MAX_TURN_ENTRIES) latestReceived.clear()
                latestReceived[entry.id] = entry.bytesReceived ?: 0L
                null
            }
        }
        if (recorded == null) keep(entry)
        val out = recorded ?: entry
        _events.tryEmit(out)
        return out
    }

    /** The request's own bytes received so far, per entry id (for [received] on a collapsed entry). */
    private val latestReceived = HashMap<Long, Long>()

    private fun collapses(old: AuditEntry, new: AuditEntry): Boolean =
        old.method == new.method && old.host == new.host && old.path == new.path && old.status == new.status &&
            old.route == new.route && old.tag == new.tag && old.blocked == new.blocked && old.error == null && new.error == null &&
            old.session == new.session && old.turn == new.turn &&
            (old.session != null || new.timeMillis - old.timeMillis in 0..COLLAPSE_MS)

    private fun sum(a: Long?, b: Long?): Long? = if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)

    /**
     * Sets the body bytes actually received for entry [id], once its body has been read or
     * closed (streams have no `Content-Length` when they start). No-op if the entry is gone.
     */
    fun received(id: Long, bytes: Long) {
        if (bytes < 0) return
        turnLock.withLock {
            // A collapsed entry sums its requests: swap the latest request's part for [bytes].
            val previous = latestReceived.put(id, bytes) ?: 0L
            fun updated(e: AuditEntry) = e.copy(bytesReceived = if (e.count > 1) (e.bytesReceived ?: 0L) - previous + bytes else bytes)
            val list = turnOf[id]?.let { turns[it] }
            val index = list?.indexOfLast { it.id == id } ?: -1
            if (list != null && index >= 0) list[index] = updated(list[index])
            val current = _entries.value
            val at = current.indexOfLast { it.id == id }
            if (at >= 0) {
                val next = updated(current[at])
                if (next != current[at]) _entries.value = current.toMutableList().also { it[at] = next }
            }
        }
    }

    /** Entries made for [sessionId]'s [turn], oldest first (kept until [release]). */
    fun forTurn(sessionId: String, turn: Int): List<AuditEntry> {
        turnLock.withLock { turns[turnKey(sessionId, turn)]?.toList() }?.let { return it }
        return _entries.value.filter { it.session == sessionId && it.turn == turn }
    }

    /** The receipt of [sessionId]'s [turn] is written: its entries need no longer be kept apart from the ring. */
    fun release(sessionId: String, turn: Int) {
        turnLock.withLock { turns.remove(turnKey(sessionId, turn))?.forEach { turnOf.remove(it.id) } }
    }

    fun clear() {
        _entries.value = emptyList()
        turnLock.withLock {
            turns.clear()
            turnOf.clear()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 500

        /** Turns whose entries are kept for their receipt at the same time (sessions run in parallel). */
        const val MAX_OPEN_TURNS = 32

        /** Entries kept per turn; a turn with more lists the first ones. */
        const val MAX_TURN_ENTRIES = 2_000

        /** Recent entries a new request may collapse into. */
        const val COLLAPSE_WINDOW = 16

        /** Untraced identical requests collapse only this close together. */
        const val COLLAPSE_MS = 60_000L
    }
}
