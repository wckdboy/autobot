import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Local memory on iOS: the encrypted store, the bridge the Kotlin tools use, and the screen's rules.
@Suite(.serialized)
@MainActor
struct MemoryTests {
    private func repository() -> MemoryRepository {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("memory-\(UUID().uuidString).sqlite")
        return MemoryRepository(store: EncryptedStore(url: url, keys: KeyManager()))
    }

    @Test func savesNormalisesDeduplicatesAndEdits() throws {
        let memory = repository()
        guard case .saved(let id) = memory.save(id: nil, text: "  Likes   hiking ", tags: "#Outdoor, weekend") else {
            Issue.record("not saved")
            return
        }
        #expect(MemoryRules.shared.isValidId(id: id))
        let note = try #require(memory.notes.first)
        #expect(note.text == "Likes hiking")
        #expect(note.tags == ["outdoor", "weekend"])
        #expect(note.sessionId == nil)

        #expect(memory.save(id: nil, text: "likes hiking!", tags: "trips") == .duplicate(id))
        #expect(memory.notes.count == 1)
        #expect(memory.notes[0].tags == ["outdoor", "weekend", "trips"])

        #expect(memory.save(id: id, text: "Likes hiking and climbing", tags: "") == .saved(id))
        #expect(memory.notes[0].text == "Likes hiking and climbing")
        #expect(memory.notes[0].tags.isEmpty)

        #expect(memory.save(id: nil, text: "   ", tags: "") == .empty)
        #expect(memory.save(id: "m-missing", text: "x", tags: "") == .notFound)
    }

    @Test func searchDeleteAndDeleteAll() {
        let memory = repository()
        memory.save(id: nil, text: "Coffee black", tags: "food")
        memory.save(id: nil, text: "Tea green", tags: "food")
        #expect(memory.filtered("#food").count == 2)
        #expect(memory.filtered("coffee").map(\.text) == ["Coffee black"])
        #expect(memory.filtered("").count == 2)
        memory.delete(memory.filtered("tea")[0].id)
        #expect(memory.notes.map(\.text) == ["Coffee black"])
        memory.deleteAll()
        #expect(memory.notes.isEmpty)
    }

    /// What the shared agent loop calls (`MemoryStoreBridge`) sees the same sealed rows.
    @Test func bridgeReadsAndWritesTheSameNotes() {
        let memory = repository()
        memory.save(id: nil, text: "Prefers metric units", tags: "units")
        let host = memory.host
        #expect(host.all().map(\.text) == ["Prefers metric units"])
        host.put(note: MemoryNote(id: "m-0000000001", text: "From the agent", tags: [], createdAt: 1, updatedAt: nowMillis(), sessionId: "s1"))
        memory.load()
        #expect(memory.notes.first?.sessionId == "s1")
        #expect(host.remove(id: "m-0000000001"))
        #expect(!host.remove(id: "m-0000000001"))
        host.removeAll()
        #expect(host.all().isEmpty)
    }

    @Test func importMergesNewIdsOnly() {
        let memory = repository()
        memory.save(id: nil, text: "Existing note", tags: "")
        let existing = memory.notes[0].shared
        let added = memory.merge([
            existing,
            MemoryNote(id: "m-aaaaaaaaaa", text: " Imported ", tags: ["#A"], createdAt: 1, updatedAt: 2, sessionId: nil),
            MemoryNote(id: "../bad", text: "x", tags: [], createdAt: 1, updatedAt: 2, sessionId: nil),
            MemoryNote(id: "m-bbbbbbbbbb", text: "  ", tags: [], createdAt: 1, updatedAt: 2, sessionId: nil),
        ])
        #expect(added == 1)
        #expect(memory.notes.count == 2)
        #expect(memory.notes.contains { $0.id == "m-aaaaaaaaaa" && $0.text == "Imported" && $0.tags == ["a"] })
    }

    @Test func newIdsMatchTheSharedShape() {
        let id = MemoryRepository.newId()
        #expect(id.count == 12 && id.hasPrefix("m-"))
        #expect(MemoryRules.shared.isValidId(id: id))
    }
}
