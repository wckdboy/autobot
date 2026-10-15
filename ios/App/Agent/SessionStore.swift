import AutobotShared
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
    /**
     Searchable text per chat (prompts and replies, shared `ChatSearch`), built lazily from the log
     on the first search and kept current as entries are appended. Process memory only.
     */
    private let searchTexts = Mutex<[String: String]>([:])

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
        // Only a chat already indexed is extended; others are indexed from the whole log when searched.
        guard searchTexts.withLock({ $0[sessionId] != nil }) else { return }
        let added = ChatSearch.shared.textOfLines(lines: entries.compactMap { String(data: $0.json, encoding: .utf8) })
        guard !added.isEmpty else { return }
        searchTexts.withLock { texts in
            guard let existing = texts[sessionId], existing.count < Int(ChatSearch.shared.MAX_TEXT) else { return }
            texts[sessionId] = String((existing.isEmpty ? added : existing + "\n" + added).prefix(Int(ChatSearch.shared.MAX_TEXT)))
        }
    }

    /// The chat's searchable text (built from its log on first use).
    func searchText(_ sessionId: String) -> String {
        if let text = searchTexts.withLock({ $0[sessionId] }) { return text }
        let text = ChatSearch.shared.textOfLines(lines: loadJSON(sessionId).compactMap { String(data: $0, encoding: .utf8) })
        searchTexts.withLock { $0[sessionId] = text }
        return text
    }

    /// Attachment ids the chat's log references (deleted with the chat).
    func attachmentIds(_ sessionId: String) -> [String] {
        ChatSearch.shared.attachmentsOfLines(lines: loadJSON(sessionId).compactMap { String(data: $0, encoding: .utf8) })
    }

    func load(_ sessionId: String) -> [LogEntry] { loadJSON(sessionId).compactMap { try? SessionJSON.decode($0) } }

    func append(_ sessionId: String, _ entries: [LogEntry]) throws {
        try appendJSON(sessionId, entries.compactMap { e in (try? SessionJSON.encode(e)).map { (e.seq, $0) } })
    }

    func delete(_ sessionId: String) {
        _ = searchTexts.withLock { $0.removeValue(forKey: sessionId) }
        if isIncognito(sessionId) {
            _ = memory.withLock { $0.removeValue(forKey: sessionId) }
        } else {
            try? store.deleteEvents(sessionId: sessionId)
        }
    }

    /// Drops every incognito log (panic wipe).
    func clearIncognito() {
        memory.withLock { $0.removeAll() }
        searchTexts.withLock { $0 = $0.filter { !$0.key.hasPrefix(ConversationRepository.incognitoPrefix) } }
    }
}
