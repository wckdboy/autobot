import AutobotShared
import Foundation
import Synchronization

/**
 One outbound request attempt.

 Deliberately minimal: no headers, no query string, no request or response bodies, so the log
 itself can never contain credentials or prompt content.
 */
struct AuditEntry: Identifiable, Equatable, Sendable {
    let id: Int64
    let timeMillis: Int64
    let method: String
    let host: String
    /// URL path only (query and fragment stripped).
    let path: String
    /// Request body length if known.
    let bytesSent: Int64?
    /// Response `Content-Length` if known (streams usually report nil).
    let bytesReceived: Int64?
    let status: Int?
    /// Caller-supplied label, e.g. the provider name.
    let tag: String?
    /// Effective route label (`direct`, `tor`, `socks5`, `offline`, `loopback`).
    let route: String
    /// `true` if the kill switch refused the request before any I/O.
    let blocked: Bool
    var error: String? = nil
}

/**
 In-memory ring buffer of [AuditEntry]s: the shared Kotlin `AuditLog` (same capacity, fields and
 path redaction as Android), with change notifications for SwiftUI. Never persisted, so the log is
 gone when the process dies (including after a panic wipe).
 */
final class AuditLog: Sendable {
    static let defaultCapacity = 500

    /// Thread-safe on the Kotlin side (atomic ids, `MutableStateFlow.update`).
    nonisolated(unsafe) private let log: AutobotShared.AuditLog
    private let observers = Mutex<[UUID: AsyncStream<[AuditEntry]>.Continuation]>([:])

    init(capacity: Int = AuditLog.defaultCapacity, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        precondition(capacity > 0, "capacity must be positive")
        log = AutobotShared.AuditLog(capacity: Int32(capacity), clock: { KotlinLong(value: clock()) })
    }

    /// Snapshot of the buffer, oldest first.
    var entries: [AuditEntry] {
        ((log.entries.value as? [AutobotShared.AuditEntry]) ?? []).map(AuditEntry.init)
    }

    /// Emits the current snapshot, then every change.
    func updates() -> AsyncStream<[AuditEntry]> {
        let (stream, continuation) = AsyncStream<[AuditEntry]>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        observers.withLock { $0[id] = continuation }
        continuation.yield(entries)
        continuation.onTermination = { [weak self] _ in
            _ = self?.observers.withLock { $0.removeValue(forKey: id) }
        }
        return stream
    }

    @discardableResult
    func record(
        method: String,
        host: String,
        path: String,
        bytesSent: Int64? = nil,
        bytesReceived: Int64? = nil,
        status: Int? = nil,
        tag: String? = nil,
        route: String,
        blocked: Bool,
        error: String? = nil,
    ) -> AuditEntry {
        let entry = AuditEntry(log.record(
            method: method,
            host: host,
            path: path,
            bytesSent: bytesSent.map { KotlinLong(value: $0) },
            bytesReceived: bytesReceived.map { KotlinLong(value: $0) },
            status: status.map { KotlinInt(value: Int32(clamping: $0)) },
            tag: tag,
            route: route,
            blocked: blocked,
            error: error,
        ))
        notify()
        return entry
    }

    func clear() {
        log.clear()
        notify()
    }

    private func notify() {
        let snapshot = entries
        observers.withLock { Array($0.values) }.forEach { $0.yield(snapshot) }
    }
}

extension AuditEntry {
    init(_ e: AutobotShared.AuditEntry) {
        self.init(
            id: e.id,
            timeMillis: e.timeMillis,
            method: e.method,
            host: e.host,
            path: e.path,
            bytesSent: e.bytesSent?.int64Value,
            bytesReceived: e.bytesReceived?.int64Value,
            status: e.status.map { Int($0.int32Value) },
            tag: e.tag,
            route: e.route,
            blocked: e.blocked,
            error: e.error,
        )
    }
}
