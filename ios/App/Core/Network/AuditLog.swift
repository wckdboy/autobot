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
 In-memory ring buffer of [AuditEntry]s. Never persisted, so the log is gone when the process
 dies (including after a panic wipe).
 */
final class AuditLog: Sendable {
    static let defaultCapacity = 500

    private struct State {
        var nextId: Int64 = 0
        var entries: [AuditEntry] = []
        var observers: [UUID: AsyncStream<[AuditEntry]>.Continuation] = [:]
    }

    private let capacity: Int
    private let clock: @Sendable () -> Int64
    private let state = Mutex(State())

    init(capacity: Int = AuditLog.defaultCapacity, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        precondition(capacity > 0, "capacity must be positive")
        self.capacity = capacity
        self.clock = clock
    }

    /// Snapshot of the buffer, oldest first.
    var entries: [AuditEntry] { state.withLock { $0.entries } }

    /// Emits the current snapshot, then every change.
    func updates() -> AsyncStream<[AuditEntry]> {
        let (stream, continuation) = AsyncStream<[AuditEntry]>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        let snapshot = state.withLock { s in
            s.observers[id] = continuation
            return s.entries
        }
        continuation.yield(snapshot)
        continuation.onTermination = { [weak self] _ in
            _ = self?.state.withLock { $0.observers.removeValue(forKey: id) }
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
        let cleanPath = String(path.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            .split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let now = clock()
        let (entry, snapshot, observers) = state.withLock { s in
            s.nextId += 1
            let entry = AuditEntry(
                id: s.nextId,
                timeMillis: now,
                method: method,
                host: host,
                path: cleanPath,
                bytesSent: bytesSent.flatMap { $0 >= 0 ? $0 : nil },
                bytesReceived: bytesReceived.flatMap { $0 >= 0 ? $0 : nil },
                status: status,
                tag: tag,
                route: route,
                blocked: blocked,
                error: error,
            )
            if s.entries.count >= capacity { s.entries.removeFirst(s.entries.count - capacity + 1) }
            s.entries.append(entry)
            return (entry, s.entries, Array(s.observers.values))
        }
        observers.forEach { $0.yield(snapshot) }
        return entry
    }

    func clear() {
        let observers = state.withLock { s in
            s.entries = []
            return Array(s.observers.values)
        }
        observers.forEach { $0.yield([]) }
    }
}
