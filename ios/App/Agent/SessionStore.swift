import AutobotShared
import Foundation
import Synchronization

/**
 Persistence of agent session logs as event JSON lines (the contract the KMP bridge's
 `SessionStoreBridge` implements). Incognito sessions stay in process memory; everything else is
 sealed in the [EncryptedStore]. Unreadable rows are skipped.

 Each persisted chat also keeps its searchable text (prompts and replies, shared `ChatSearch`)
 as sealed rows next to the log: a base row (the text when the chat was first indexed) and one
 row per appended batch. A search reads those small rows instead of decrypting every log, and
 nothing of it stays in memory between searches.
 */
final class SessionStore: Sendable {
    /// Searchable text rows: `<session>:base`, `<session>:<first seq of the batch>`.
    static let searchCollection = "chat_search"

    private enum Indexed: Sendable { case yes, no }

    private let store: EncryptedStore
    private let memory = Mutex<[String: [Data]]>([:])
    /// Searchable text of incognito chats (memory only, never on disk).
    private let incognitoTexts = Mutex<[String: String]>([:])
    /// Whether a chat has search rows (a base row), learned once from the row ids.
    private let indexed = Mutex<[String: Indexed]?>(nil)

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
            guard incognitoTexts.withLock({ $0[sessionId] != nil }) else { return }
            let added = Self.text(entries.map(\.json))
            guard !added.isEmpty else { return }
            incognitoTexts.withLock { texts in
                guard let existing = texts[sessionId] else { return }
                texts[sessionId] = Self.joined(existing, added)
            }
            return
        }
        try store.appendEvents(sessionId: sessionId, entries)
        indexAppend(sessionId, entries)
    }

    /// Extends an indexed chat's search rows with [entries]. A chat whose log starts with them
    /// is indexed from here on; an older, unindexed one is indexed from its log when first searched.
    private func indexAppend(_ sessionId: String, _ entries: [(seq: Int64, json: Data)]) {
        let first = entries.map(\.seq).min() ?? 0
        let state = indexState(sessionId) ?? {
            let fresh = (try? store.hasEvents(sessionId: sessionId, before: first)) == false
            if fresh { try? store.put(Self.searchCollection, id: Self.baseRow(sessionId), "") }
            let state: Indexed = fresh ? .yes : .no
            indexed.withLock { $0?[sessionId] = state }
            return state
        }()
        guard state == .yes else { return }
        let added = Self.text(entries.map(\.json))
        guard !added.isEmpty else { return }
        try? store.put(Self.searchCollection, id: Self.batchRow(sessionId, first), added)
    }

    /// Whether [sessionId] has search rows (nil: unknown, decide on this append).
    private func indexState(_ sessionId: String) -> Indexed? {
        indexed.withLock { map in
            if map == nil {
                let bases = (try? store.ids(Self.searchCollection))?.filter { $0.hasSuffix(":base") } ?? []
                map = Dictionary(bases.map { (String($0.dropLast(5)), Indexed.yes) }, uniquingKeysWith: { a, _ in a })
            }
            return map?[sessionId]
        }
    }

    /// The chat's searchable text.
    func searchText(_ sessionId: String) -> String { searchTexts([sessionId])[sessionId] ?? "" }

    /**
     Searchable text of [sessionIds]: one read of the search rows; a chat without them is indexed
     from its log now (once: the rows are kept).
     */
    func searchTexts(_ sessionIds: [String]) -> [String: String] {
        var out: [String: String] = [:]
        let (incognito, stored) = (sessionIds.filter(isIncognito), sessionIds.filter { !isIncognito($0) })
        for id in incognito {
            out[id] = incognitoTexts.withLock { $0[id] } ?? {
                let text = Self.text(loadJSON(id))
                incognitoTexts.withLock { $0[id] = text }
                return text
            }()
        }
        guard !stored.isEmpty else { return out }
        let wanted = Set(stored)
        var rows: [String: [String]] = [:]
        var based = Set<String>()
        // One query for all of them (a chat's rows are in id order: base first, then by seq).
        let entries = stored.count == 1
            ? (try? store.entries(Self.searchCollection, idPrefix: stored[0] + ":", as: String.self)) ?? []
            : (try? store.entries(Self.searchCollection, as: String.self)) ?? []
        for (rowId, text) in entries {
            guard let colon = rowId.lastIndex(of: ":") else { continue }
            let session = String(rowId[..<colon])
            guard wanted.contains(session) else { continue }
            if rowId[rowId.index(after: colon)...] == "base" { based.insert(session) }
            if !text.isEmpty { rows[session, default: []].append(text) }
        }
        for id in stored {
            if based.contains(id) {
                out[id] = String((rows[id] ?? []).joined(separator: "\n").prefix(Int(ChatSearch.shared.MAX_TEXT)))
            } else {
                let text = Self.text(loadJSON(id))
                // Rows of appends before the base (an index started on another path) are dropped.
                try? store.delete(Self.searchCollection, idPrefix: id + ":")
                try? store.put(Self.searchCollection, id: Self.baseRow(id), text)
                indexed.withLock { $0?[id] = .yes }
                out[id] = text
            }
        }
        return out
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
        if isIncognito(sessionId) {
            _ = memory.withLock { $0.removeValue(forKey: sessionId) }
            _ = incognitoTexts.withLock { $0.removeValue(forKey: sessionId) }
        } else {
            try? store.deleteEvents(sessionId: sessionId)
            try? store.delete(Self.searchCollection, idPrefix: sessionId + ":")
            indexed.withLock { _ = $0?.removeValue(forKey: sessionId) }
        }
    }

    /// Drops every incognito log (panic wipe).
    func clearIncognito() {
        memory.withLock { $0.removeAll() }
        incognitoTexts.withLock { $0.removeAll() }
    }

    // MARK: Search text

    private static func baseRow(_ sessionId: String) -> String { sessionId + ":base" }

    /// Zero-padded so rows sort by seq.
    private static func batchRow(_ sessionId: String, _ seq: Int64) -> String { sessionId + ":" + String(format: "%019lld", max(seq, 0)) }

    private static func text(_ lines: [Data]) -> String {
        ChatSearch.shared.textOfLines(lines: lines.compactMap { String(data: $0, encoding: .utf8) })
    }

    /// [existing] + [added] on a new line, capped at `ChatSearch.MAX_TEXT` characters.
    private static func joined(_ existing: String, _ added: String) -> String {
        let max = Int(ChatSearch.shared.MAX_TEXT)
        if existing.count >= max || added.isEmpty { return existing }
        return String((existing.isEmpty ? added : existing + "\n" + added).prefix(max))
    }
}
