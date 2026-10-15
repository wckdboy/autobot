import AutobotShared
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// The private vault on iOS: the sealed store the shared vault writes through, indexing and search
/// through `VaultHost`, and reading picked files.
@Suite(.serialized)
struct VaultTests {
    private func store() -> EncryptedStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("vault-\(UUID().uuidString).sqlite")
        return EncryptedStore(url: url, keys: KeyManager())
    }

    /// Hashed bag of words (16 dimensions): passages sharing words with the query rank first.
    private final class WordEmbedder: NSObject, EmbedderBridge, @unchecked Sendable {
        func modelId() -> String? { "test-embed" }
        func lastError() -> String? { nil }

        func embed(texts: [String]) -> [String]? {
            texts.map { text in
                var v = [Float](repeating: 0, count: 16)
                for word in text.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }) {
                    v[Int(word.utf8.reduce(UInt32(7)) { ($0 &* 31) &+ UInt32($1) } % 16)] += 1
                }
                let norm = max(sqrt(v.reduce(0) { $0 + $1 * $1 }), 1e-6)
                return v.map { $0 / norm }.withUnsafeBufferPointer { Data(buffer: $0) }.base64EncodedString()
            }
        }
    }

    @Test func storeSealsAndRemovesAllThreeRecords() {
        let host = VaultStoreHost(store: store())
        host.put(id: "d-1", document: "{\"id\":\"d-1\"}", chunks: "[]", vectors: "AAAA")
        host.put(id: "d-2", document: "{\"id\":\"d-2\"}", chunks: "[1]", vectors: "BBBB")
        #expect(Set(host.documents()) == ["{\"id\":\"d-1\"}", "{\"id\":\"d-2\"}"])
        #expect(host.chunks(id: "d-2") == "[1]")
        #expect(host.vectors(id: "d-1") == "AAAA")
        #expect(host.remove(id: "d-1"))
        #expect(!host.remove(id: "d-1"))
        #expect(host.chunks(id: "d-1") == nil && host.vectors(id: "d-1") == nil)
        host.removeAll()
        #expect(host.documents().isEmpty && host.chunks(id: "d-2") == nil)
    }

    @Test func hostIndexesSearchesAndRemoves() async {
        let vault = VaultHost(store: VaultStoreHost(store: store()), embedder: WordEmbedder())
        let progress = Mutex<[Int]>([])
        let problems: [String] = await withCheckedContinuation { done in
            vault.add(
                names: ["lease.md", "recipes.txt", "empty.txt"],
                types: ["MARKDOWN", "TEXT", "TEXT"],
                sizes: [KotlinLong(value: 10), KotlinLong(value: 10), KotlinLong(value: 0)],
                sources: ["lease.md", "recipes.txt", "empty.txt"],
                pages: [["The monthly rent is 950 euros."], ["Pancakes need flour, eggs and milk."], ["   "]],
                progress: Listener { done, _ in progress.withLock { $0.append(done) } },
                done: { done.resume(returning: $0) },
            )
        }
        #expect(problems.count == 1 && problems[0].hasPrefix("empty.txt"))
        #expect(!progress.withLock { $0 }.isEmpty)

        let (docs, model, stale): ([Doc], String?, Int) = await withCheckedContinuation { done in
            vault.documents { d, m, s in done.resume(returning: (d.map { Doc(id: $0.id, name: $0.name, current: $0.current) }, m, Int(truncating: s))) }
        }
        #expect(Set(docs.map(\.name)) == ["lease.md", "recipes.txt"])
        #expect(model == "test-embed" && stale == 0 && docs.allSatisfy(\.current))

        let hits: [[String]] = await withCheckedContinuation { done in
            vault.search(query: "monthly rent", limit: 2) { h, _ in done.resume(returning: h.map { [$0.name, $0.position, $0.snippet] }) }
        }
        #expect(hits.first == ["lease.md", "lease.md · passage 1/1", "The monthly rent is 950 euros."])

        let lease = docs.first { $0.name == "lease.md" }!
        await withCheckedContinuation { done in vault.remove(id: lease.id) { done.resume() } }
        let after: [String] = await withCheckedContinuation { done in vault.documents { d, _, _ in done.resume(returning: d.map(\.name)) } }
        #expect(after == ["recipes.txt"])
    }

    @Test func readsTextAndMarkdownAndRejectsUnknownFiles() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("vault-files-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let md = dir.appendingPathComponent("notes.md")
        try Data("# Budget\n\nRent: 950 €".utf8).write(to: md)
        let bin = dir.appendingPathComponent("blob.xyz123")
        try Data([0, 1, 2]).write(to: bin)

        #expect(VaultImport.type(of: md) == "MARKDOWN")
        #expect(VaultImport.type(of: dir.appendingPathComponent("a.pdf")) == "PDF")
        #expect(VaultImport.type(of: dir.appendingPathComponent("a.txt")) == "TEXT")
        #expect(VaultImport.type(of: dir.appendingPathComponent("a.jpg")) == "IMAGE")
        guard case .success(let file) = await VaultImport.read(md) else {
            Issue.record("markdown not read")
            return
        }
        #expect(file.name == "notes.md" && file.type == "MARKDOWN" && file.pages == ["# Budget\n\nRent: 950 €"])
        guard case .failure = await VaultImport.read(bin) else {
            Issue.record("unknown file accepted")
            return
        }
    }

    @Test func catalogOffersThePinnedEmbeddingModel() {
        let plan = Catalog.embedding.first
        #expect(plan?.id == "catalog:qwen3-embedding-0.6b-q8_0")
        #expect(plan?.engine == .llama && plan?.kind == .embedding)
        #expect(plan?.manifest.files.first?.sha256 == "06507c7b42688469c4e7298b0a1e16deff06caf291cf0a5b278c308249c3e439")
        #expect(!Catalog.chat.contains { $0.kind == .embedding })
    }
}

private struct Doc: Sendable {
    let id: String
    let name: String
    let current: Bool
}

private final class Listener: NSObject, VaultProgressListener, @unchecked Sendable {
    let handler: @Sendable (Int, Int) -> Void
    init(_ handler: @escaping @Sendable (Int, Int) -> Void) { self.handler = handler }
    func onProgress(name: String, done: Int32, total: Int32) { handler(Int(done), Int(total)) }
}
