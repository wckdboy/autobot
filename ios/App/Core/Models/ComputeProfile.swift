import Foundation
import Observation

/// One measured run of a model on a backend.
struct Benchmark: Codable, Identifiable, Equatable, Sendable {
    var id = UUID().uuidString
    var modelId: String
    /// Where it actually ran (an accelerator that fails falls back to the CPU).
    var backend: Backend
    /// Text models: prompt processing and generation speed in tokens per second.
    var promptTps: Double? = nil
    var generationTps: Double? = nil
    /// Image models: average time of one denoising step, in milliseconds.
    var stepMs: Double? = nil
    var loadMs: Int64? = nil
    var error: String? = nil
    var createdAt: Int64 = nowMillis()

    var ok: Bool { error == nil }
}

/**
 Which backend each model should run on: the user's explicit choice, otherwise the fastest one
 the benchmark measured, otherwise `.auto` (the engine decides). Port of `ComputeProfile.kt`.
 */
@MainActor
@Observable
final class ComputeProfile {
    private nonisolated static let collection = "benchmarks"

    private(set) var benchmarks: [Benchmark] = []
    /// The newest speculative decoding benchmark per model, for this session (not stored).
    private(set) var speculative: [String: SpeculativeResult] = [:]
    @ObservationIgnored private let store: EncryptedStore
    @ObservationIgnored private let library: ModelLibrary

    init(store: EncryptedStore, library: ModelLibrary) {
        self.store = store
        self.library = library
    }

    func load() { load(Self.read(store)) }

    nonisolated static func read(_ store: EncryptedStore) -> [Benchmark] {
        (try? store.all(collection, as: Benchmark.self)) ?? []
    }

    func load(_ rows: [Benchmark]) {
        benchmarks = rows.sorted { $0.createdAt > $1.createdAt }
    }

    func setBackend(_ modelId: String, _ backend: Backend) { library.setBackend(modelId, backend) }

    func record(_ result: Benchmark) {
        try? store.put(Self.collection, id: result.id, result)
        benchmarks.insert(result, at: 0)
    }

    func recordSpeculative(_ result: SpeculativeResult) { speculative[result.modelId] = result }

    func deleteFor(modelId: String) {
        speculative[modelId] = nil
        for b in benchmarks where b.modelId == modelId { try? store.delete(Self.collection, id: b.id) }
        benchmarks.removeAll { $0.modelId == modelId }
    }

    func resolve(_ model: InstalledModel) -> Backend {
        if model.backend != .auto { return model.backend }
        return Self.fastest(benchmarks.filter { $0.modelId == model.id }) ?? .auto
    }

    /// Fastest backend among the newest successful run per backend; nil if nothing was measured.
    nonisolated static func fastest(_ results: [Benchmark]) -> Backend? {
        let latest = Dictionary(grouping: results.filter { $0.ok && $0.backend != .auto }, by: \.backend)
            .compactMapValues { runs in runs.max { $0.createdAt < $1.createdAt } }
            .values
        if let byGeneration = latest.filter({ $0.generationTps != nil }).max(by: { $0.generationTps! < $1.generationTps! }) {
            return byGeneration.backend
        }
        return latest.filter { $0.stepMs != nil }.min { $0.stepMs! < $1.stepMs! }?.backend
    }
}
