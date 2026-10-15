import AutobotShared
import Foundation
import Observation
import Synchronization

/// One sealed vault record: a document, its passages (JSON from the shared code) or its vectors (base64).
struct VaultBlob: Codable, Equatable, Sendable {
    var id: String
    var value: String
}

/**
 The private vault's records sealed in the [EncryptedStore] (collections `vault-docs`,
 `vault-chunks`, `vault-vectors`, one row each per document). The shared `Vault` reads and writes
 them on its background threads (`VaultStoreBridge`); every change is reported through [onChange].
 */
final class VaultStoreHost: NSObject, VaultStoreBridge, @unchecked Sendable {
    static let documentsCollection = "vault-docs"
    static let chunksCollection = "vault-chunks"
    static let vectorsCollection = "vault-vectors"
    private let store: EncryptedStore
    private let listener = Mutex<(@Sendable () -> Void)?>(nil)

    init(store: EncryptedStore) { self.store = store }

    func setOnChange(_ handler: (@Sendable () -> Void)?) { listener.withLock { $0 = handler } }

    private func onChange() { listener.withLock { $0 }?() }

    private func read(_ collection: String, _ id: String) -> String? {
        (try? store.get(collection, id: id, as: VaultBlob.self))?.value
    }

    // VaultStoreBridge

    func documents() -> [String] {
        ((try? store.all(Self.documentsCollection, as: VaultBlob.self)) ?? []).map(\.value)
    }

    func chunks(id: String) -> String? { read(Self.chunksCollection, id) }

    func vectors(id: String) -> String? { read(Self.vectorsCollection, id) }

    /// Passages and vectors first, the document last: a partial write never shows up as a document.
    func put(id: String, document: String, chunks: String, vectors: String) {
        try? store.put(Self.chunksCollection, id: id, VaultBlob(id: id, value: chunks))
        try? store.put(Self.vectorsCollection, id: id, VaultBlob(id: id, value: vectors))
        try? store.put(Self.documentsCollection, id: id, VaultBlob(id: id, value: document))
        onChange()
    }

    func remove(id: String) -> Bool {
        guard read(Self.documentsCollection, id) != nil else { return false }
        for collection in [Self.documentsCollection, Self.chunksCollection, Self.vectorsCollection] {
            try? store.delete(collection, id: id)
        }
        onChange()
        return true
    }

    func removeAll() {
        for collection in [Self.documentsCollection, Self.chunksCollection, Self.vectorsCollection] {
            for blob in (try? store.all(collection, as: VaultBlob.self)) ?? [] { try? store.delete(collection, id: blob.id) }
        }
        onChange()
    }
}

/**
 llama.cpp embeddings for the shared `Vault` (`EmbedderBridge`): the newest ready embedding model
 from the library, run by AutobotEngines on this phone. Called on the bridge's background
 threads, so [embed] blocks until the engine answers.
 */
final class VaultEmbedderHost: NSObject, EmbedderBridge, @unchecked Sendable {
    private struct Current: Sendable {
        let id: String
        let path: String
    }

    private final class Outcome: @unchecked Sendable {
        var result: Result<[[Float]], Error> = .success([])
    }

    private let llm: LocalLlm
    private let current = Mutex<Current?>(nil)
    private let error = Mutex<String?>(nil)

    init(llm: LocalLlm) { self.llm = llm }

    /// Picks the embedding model from the library (after every change to it).
    @MainActor
    func update(_ library: ModelLibrary) {
        let model = library.models.filter { $0.isReady && $0.kind == .embedding && $0.engine == .llama }.max { $0.addedAt < $1.addedAt }
        let path = model.flatMap { library.paths($0)[.model]?.path }
        current.withLock { $0 = model.flatMap { m in path.map { Current(id: m.id, path: $0) } } }
    }

    func modelId() -> String? { current.withLock { $0?.id } }

    func embed(texts: [String]) -> [String]? {
        guard let model = current.withLock({ $0 }) else {
            error.withLock { $0 = Vault.companion.NO_MODEL }
            return nil
        }
        let outcome = Outcome()
        let done = DispatchSemaphore(value: 0)
        let llm = llm
        Task.detached {
            do {
                outcome.result = .success(try await llm.embed(modelPath: model.path, texts: texts))
            } catch {
                outcome.result = .failure(error)
            }
            done.signal()
        }
        done.wait()
        switch outcome.result {
        case .success(let vectors):
            return vectors.map { v in v.withUnsafeBufferPointer { Data(buffer: $0) }.base64EncodedString() }
        case .failure(let e):
            error.withLock { $0 = e.localizedDescription }
            return nil
        }
    }

    func lastError() -> String? { error.withLock { $0 } }
}

/// A picked file's text, ready for the vault (or why it was skipped).
struct VaultFile: Sendable {
    var name: String
    /// `VaultFileType` name.
    var type: String
    var size: Int64
    var pages: [String]
}

/**
 The Vault screen's view of the shared vault (port of Android's `VaultViewModel` +
 `OnDeviceVault`): documents, indexing progress, search. Indexing keeps running when the screen
 closes.
 */
@MainActor
@Observable
final class VaultRepository {
    struct Progress: Equatable {
        var name: String?
        var done = 0
        var total = 0
        var fraction: Double { total <= 0 ? 0 : Double(done) / Double(total) }
    }

    private(set) var documents: [VaultDocumentInfo] = []
    private(set) var modelId: String?
    private(set) var stale = 0
    private(set) var progress: Progress?
    var problems: [String] = []
    private(set) var results: [VaultHitInfo]?
    private(set) var searching = false

    @ObservationIgnored let store: VaultStoreHost
    @ObservationIgnored let embedder: VaultEmbedderHost
    @ObservationIgnored let host: VaultHost

    init(store: EncryptedStore, llm: LocalLlm) {
        self.store = VaultStoreHost(store: store)
        embedder = VaultEmbedderHost(llm: llm)
        host = VaultHost(store: self.store, embedder: embedder)
        // Writes by the agent's vault or an indexing run refresh the screen.
        self.store.setOnChange { [weak self] in Task { @MainActor in self?.load() } }
    }

    var running: Bool { progress != nil }

    func load() {
        host.documents(callback: Self.onMain { [weak self] (docs: [VaultDocumentInfo], model: String?, stale: KotlinInt) in
            self?.documents = docs
            self?.modelId = model
            self?.stale = Int(truncating: stale)
        })
    }

    /// Reads the picked files (text, Markdown, PDF, photos with OCR) and indexes them one after another.
    func add(_ urls: [URL]) {
        guard !running else { return }
        progress = Progress()
        problems = []
        Task {
            var files: [VaultFile] = []
            var skipped: [String] = []
            for url in urls {
                switch await VaultImport.read(url) {
                case .success(let file): files.append(file)
                case .failure(let reason): skipped.append("\(url.lastPathComponent): \(reason.message)")
                }
            }
            guard !files.isEmpty else {
                finish(skipped)
                return
            }
            host.add(
                names: files.map(\.name),
                types: files.map(\.type),
                sizes: files.map { KotlinLong(value: $0.size) },
                sources: files.map(\.name),
                pages: files.map(\.pages),
                progress: ProgressSink { [weak self] name, done, total in
                    Task { @MainActor in self?.progress = Progress(name: name, done: done, total: total) }
                },
                done: Self.onMain { [weak self] (problems: [String]) in self?.finish(skipped + problems) },
            )
        }
    }

    /// Re-embeds documents indexed with an earlier embedding model (from their stored passages).
    func reindex() {
        guard !running else { return }
        progress = Progress()
        host.reindex(
            progress: ProgressSink { [weak self] name, done, total in
                Task { @MainActor in self?.progress = Progress(name: name, done: done, total: total) }
            },
            done: Self.onMain { [weak self] (_: KotlinInt) in self?.finish([]) },
        )
    }

    func cancel() {
        host.cancelWork()
        finish(problems)
    }

    private func finish(_ problems: [String]) {
        progress = nil
        self.problems = problems
        load()
    }

    func search(_ query: String) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            results = nil
            return
        }
        searching = true
        host.search(query: q, limit: Vault.companion.SEARCH_MAX, callback: Self.onMain { [weak self] (hits: [VaultHitInfo], _: String?) in
            self?.results = hits
            self?.searching = false
        })
    }

    func clearResults() { results = nil }

    func remove(_ id: String) { host.remove(id: id, done: Self.onMain { [weak self] in self?.load() }) }

    func clear() { host.clear(done: Self.onMain { [weak self] in self?.load() }) }

    // The shared vault calls back on its own threads: these wrap a main-actor handler in a
    // nonisolated callback that hops to the main actor (Kotlin values are not Sendable).

    private nonisolated static func onMain(_ apply: @escaping @MainActor () -> Void) -> () -> Void {
        { Task { @MainActor in apply() } }
    }

    private nonisolated static func onMain<A>(_ apply: @escaping @MainActor (A) -> Void) -> (A) -> Void {
        { a in
            let box = Unchecked((a, ()))
            Task { @MainActor in apply(box.value.0) }
        }
    }

    private nonisolated static func onMain<A, B>(_ apply: @escaping @MainActor (A, B) -> Void) -> (A, B) -> Void {
        { a, b in
            let box = Unchecked((a, b))
            Task { @MainActor in apply(box.value.0, box.value.1) }
        }
    }

    private nonisolated static func onMain<A, B, C>(_ apply: @escaping @MainActor (A, B, C) -> Void) -> (A, B, C) -> Void {
        { a, b, c in
            let box = Unchecked((a, b, c))
            Task { @MainActor in apply(box.value.0, box.value.1, box.value.2) }
        }
    }
}

private struct Unchecked<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}

/// Indexing progress from the shared vault (any thread).
private final class ProgressSink: NSObject, VaultProgressListener, @unchecked Sendable {
    let handler: @Sendable (String, Int, Int) -> Void

    init(_ handler: @escaping @Sendable (String, Int, Int) -> Void) { self.handler = handler }

    func onProgress(name: String, done: Int32, total: Int32) { handler(name, Int(done), Int(total)) }
}
