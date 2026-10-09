package dev.wckdboy.autobot.core.network

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
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
)

/**
 * In-memory ring buffer of [AuditEntry]s, exposed as flows. Never persisted, so the log is gone
 * when the process dies (including after a panic wipe).
 */
@Singleton
class AuditLog(
    private val capacity: Int,
    private val clock: () -> Long,
) {
    @Inject
    constructor() : this(DEFAULT_CAPACITY, System::currentTimeMillis)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val ids = AtomicLong(0)
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
    ): AuditEntry {
        val entry = AuditEntry(
            id = ids.incrementAndGet(),
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
        )
        _entries.update { current ->
            val next = if (current.size >= capacity) current.drop(current.size - capacity + 1) else current
            next + entry
        }
        _events.tryEmit(entry)
        return entry
    }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
