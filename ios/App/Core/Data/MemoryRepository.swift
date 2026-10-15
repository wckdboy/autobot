import AutobotShared
import Foundation
import Observation
import Synchronization

/// A local memory note as stored (the shared `MemoryNote`, Codable for the [EncryptedStore]).
struct MemoryRecord: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var text: String
    var tags: [String]
    var createdAt: Int64
    var updatedAt: Int64
    /// The conversation the agent saved it from; nil for notes the user wrote or imported.
    var sessionId: String?

    init(id: String, text: String, tags: [String], createdAt: Int64, updatedAt: Int64, sessionId: String? = nil) {
        self.id = id
        self.text = text
        self.tags = tags
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.sessionId = sessionId
    }

    init(_ note: MemoryNote) {
        self.init(id: note.id, text: note.text, tags: note.tags, createdAt: note.createdAt, updatedAt: note.updatedAt, sessionId: note.sessionId)
    }

    var shared: MemoryNote {
        MemoryNote(id: id, text: text, tags: tags, createdAt: createdAt, updatedAt: updatedAt, sessionId: sessionId)
    }
}

/**
 Memory notes sealed in the [EncryptedStore] (collection `memory`). The agent bridge calls this on
 its background threads (`MemoryStoreBridge`); the Memory screen reads it through
 [MemoryRepository]. Every change is reported through [onChange].
 */
final class MemoryStoreHost: NSObject, MemoryStoreBridge, @unchecked Sendable {
    static let collection = "memory"
    private let store: EncryptedStore
    private let listener = Mutex<(@Sendable () -> Void)?>(nil)

    init(store: EncryptedStore) { self.store = store }

    /// Called after every change, on the thread that made it.
    func setOnChange(_ handler: (@Sendable () -> Void)?) { listener.withLock { $0 = handler } }

    private func onChange() { listener.withLock { $0 }?() }

    /// Newest first; undecryptable rows are skipped.
    func records() -> [MemoryRecord] {
        ((try? store.all(Self.collection, as: MemoryRecord.self)) ?? []).sorted { $0.updatedAt > $1.updatedAt }
    }

    func record(_ id: String) -> MemoryRecord? { try? store.get(Self.collection, id: id, as: MemoryRecord.self) }

    func write(_ record: MemoryRecord) throws {
        try store.put(Self.collection, id: record.id, record)
        onChange()
    }

    // MemoryStoreBridge

    func all() -> [MemoryNote] { records().map(\.shared) }

    func put(note: MemoryNote) { try? write(MemoryRecord(note)) }

    func remove(id: String) -> Bool {
        guard record(id) != nil else { return false }
        try? store.delete(Self.collection, id: id)
        onChange()
        return true
    }

    func removeAll() {
        for r in records() { try? store.delete(Self.collection, id: r.id) }
        onChange()
    }
}

/**
 The Memory screen's view of the notes (port of Android's `MemoryRepository` + `MemoryViewModel`):
 the same shared `MemoryRules` normalise, de-duplicate and cap what the user types, exactly as
 for the agent's `memory_save`.
 */
@MainActor
@Observable
final class MemoryRepository {
    /// Newest first.
    private(set) var notes: [MemoryRecord] = []
    @ObservationIgnored let host: MemoryStoreHost

    init(store: EncryptedStore) {
        host = MemoryStoreHost(store: store)
        // Saves by the agent (background threads) refresh the screen.
        host.setOnChange { [weak self] in Task { @MainActor in self?.load() } }
    }

    func load() { load(host.records()) }

    func load(_ records: [MemoryRecord]) { notes = records }

    /// Notes matching the search field (text or `#tag`), newest first.
    func filtered(_ query: String) -> [MemoryRecord] {
        let byId = Dictionary(notes.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return MemoryRules.shared.filter(notes: notes.map(\.shared), query: query).compactMap { byId[$0.id] }
    }

    enum SaveOutcome: Equatable {
        case saved(String)
        case duplicate(String)
        case empty, full, notFound
    }

    /// Creates a note ([id] nil) or replaces one; [tags] as typed (comma or space separated).
    @discardableResult
    func save(id: String?, text: String, tags: String) -> SaveOutcome {
        let rules = MemoryRules.shared
        guard let clean = rules.normalizeText(text: text) else { return .empty }
        let cleanTags = rules.parseTags(input: tags)
        let all = host.records()
        let now = nowMillis()
        if let id {
            guard var existing = all.first(where: { $0.id == id }) else { return .notFound }
            existing.text = clean
            existing.tags = cleanTags
            existing.updatedAt = now
            try? host.write(existing)
            load()
            return .saved(id)
        }
        if let dup = rules.findDuplicate(text: clean, notes: all.map(\.shared), ignoreId: nil), var existing = all.first(where: { $0.id == dup.id }) {
            let merged = rules.normalizeTags(tags: existing.tags + cleanTags)
            if merged != existing.tags {
                existing.tags = merged
                existing.updatedAt = now
                try? host.write(existing)
                load()
            }
            return .duplicate(existing.id)
        }
        guard all.count < Int(MemoryRules.shared.MAX_NOTES) else { return .full }
        let ids = Set(all.map(\.id))
        var newId = Self.newId()
        while ids.contains(newId) { newId = Self.newId() }
        try? host.write(MemoryRecord(id: newId, text: clean, tags: cleanTags, createdAt: now, updatedAt: now))
        load()
        return .saved(newId)
    }

    func delete(_ id: String) {
        _ = host.remove(id: id)
        load()
    }

    func deleteAll() {
        host.removeAll()
        load()
    }

    /**
     Adds imported notes whose id is new, up to the cap; existing notes are kept as they are.
     Returns how many were added.
     */
    func merge(_ incoming: [MemoryNote]) -> Int {
        var ids = Set(host.records().map(\.id))
        var count = ids.count
        var added = 0
        for note in incoming {
            guard let clean = MemoryRules.shared.sanitize(note: note), !ids.contains(clean.id), count < Int(MemoryRules.shared.MAX_NOTES) else { continue }
            try? host.write(MemoryRecord(clean))
            ids.insert(clean.id)
            count += 1
            added += 1
        }
        load()
        return added
    }

    /// `m-` and ten hex digits, the same shape as the shared `MemoryRules.newId`.
    nonisolated static func newId() -> String {
        "m-" + String((0 ..< 10).map { _ in "0123456789abcdef".randomElement()! })
    }
}
