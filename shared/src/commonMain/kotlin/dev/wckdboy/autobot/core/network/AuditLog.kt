package dev.wckdboy.autobot.core.network

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
)

/**
 * In-memory ring buffer of [AuditEntry]s, exposed as flows. Never persisted, so the log is gone
 * when the process dies (including after a panic wipe). Shared so both apps record the same
 * fields with the same redaction.
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
        _entries.update { current ->
            val next = if (current.size >= capacity) current.drop(current.size - capacity + 1) else current
            next + entry
        }
        _events.tryEmit(entry)
        return entry
    }

    /**
     * Sets the body bytes actually received for entry [id], once its body has been read or
     * closed (streams have no `Content-Length` when they start). No-op if the entry is gone.
     */
    fun received(id: Long, bytes: Long) {
        if (bytes < 0) return
        _entries.update { current ->
            val index = current.indexOfLast { it.id == id }
            if (index < 0 || current[index].bytesReceived == bytes) current
            else current.toMutableList().also { it[index] = it[index].copy(bytesReceived = bytes) }
        }
    }

    /** Entries made for [sessionId]'s [turn], oldest first. */
    fun forTurn(sessionId: String, turn: Int): List<AuditEntry> =
        _entries.value.filter { it.session == sessionId && it.turn == turn }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
