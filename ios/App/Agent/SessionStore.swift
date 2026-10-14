import Foundation
import Synchronization

/**
 Persistence of agent session logs as event JSON lines (the contract the KMP bridge's
 `SessionStoreBridge` implements). Incognito sessions stay in process memory; everything else is
 sealed in the [EncryptedStore]. Unreadable rows are skipped.
 */
final class SessionStore: Sendable {
    private let store: EncryptedStore
    private let memory = Mutex<[String: [Data]]>([:])

    init(store: EncryptedStore) { self.store = store }

    private func isIncognito(_ id: String) -> Bool { id.hasPrefix(ConversationRepository.incognitoPrefix) }

    /// Raw JSON lines of [sessionId], in order.
    func loadJSON(_ sessionId: String) -> [Data] {
        if isIncognito(sessionId) { return memory.withLock { $0[sessionId] ?? [] } }
        return (try? store.loadEvents(sessionId: sessionId)) ?? []
    }

    func appendJSON(_ sessionId: String, _ entries: [(seq: Int64, json: Data)]) throws {
        guard !entries.isEmpty else { return }
        if isIncognito(sessionId) {
            memory.withLock { $0[sessionId, default: []].append(contentsOf: entries.map(\.json)) }
        } else {
            try store.appendEvents(sessionId: sessionId, entries)
        }
    }

    func load(_ sessionId: String) -> [LogEntry] { loadJSON(sessionId).compactMap { try? SessionJSON.decode($0) } }

    func append(_ sessionId: String, _ entries: [LogEntry]) throws {
        try appendJSON(sessionId, entries.compactMap { e in (try? SessionJSON.encode(e)).map { (e.seq, $0) } })
    }

    func delete(_ sessionId: String) {
        if isIncognito(sessionId) {
            _ = memory.withLock { $0.removeValue(forKey: sessionId) }
        } else {
            try? store.deleteEvents(sessionId: sessionId)
        }
    }

    /// Drops every incognito log (panic wipe).
    func clearIncognito() { memory.withLock { $0.removeAll() } }
}
