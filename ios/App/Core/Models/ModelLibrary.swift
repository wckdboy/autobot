import Foundation
import Observation

/**
 Registry of installed and in-progress models (the iOS counterpart of `ModelLibrary.kt`). Rows
 live in the encrypted store; files live in app-private storage under `models/<id>/…` (never in
 the photo library or Files), so deleting the app or a panic wipe removes them.
 */
@MainActor
@Observable
final class ModelLibrary {
    private nonisolated static let collection = "models"

    /// Newest first.
    private(set) var models: [InstalledModel] = []
    @ObservationIgnored private let store: EncryptedStore
    /// Called after every change (local provider sync, the agent bridge's model snapshot).
    @ObservationIgnored var onChange: (() -> Void)?

    init(store: EncryptedStore) {
        self.store = store
    }

    func load() { load(Self.read(store)) }

    nonisolated static func read(_ store: EncryptedStore) -> [InstalledModel] {
        (try? store.all(collection, as: InstalledModel.self)) ?? []
    }

    func load(_ rows: [InstalledModel]) {
        models = rows.sorted { $0.addedAt > $1.addedAt }
        onChange?()
    }

    func get(_ id: String) -> InstalledModel? { models.first { $0.id == id } }

    /// Registers [plan] as queued (keeps progress if it already exists and is not failed).
    @discardableResult
    func add(_ plan: ModelPlan) -> InstalledModel {
        let existing = get(plan.id)
        let now = nowMillis()
        let model = InstalledModel(
            id: plan.id,
            kind: plan.kind,
            source: plan.source,
            title: plan.title,
            subtitle: plan.subtitle,
            format: plan.format,
            engine: plan.engine,
            baseModel: plan.baseModel,
            license: plan.license,
            nsfw: plan.nsfw,
            totalBytes: plan.totalBytes,
            downloadedBytes: existing?.downloadedBytes ?? 0,
            status: existing?.status == .ready ? .ready : .queued,
            error: nil,
            manifest: plan.manifest,
            addedAt: existing?.addedAt ?? now,
            updatedAt: now,
            backend: existing?.backend ?? .auto,
        )
        put(model)
        return model
    }

    func update(_ id: String, _ change: (inout InstalledModel) -> Void) {
        guard var model = get(id) else { return }
        change(&model)
        model.updatedAt = nowMillis()
        put(model)
    }

    func progress(_ id: String, downloaded: Int64, status: ModelStatus, error: String? = nil) {
        update(id) {
            $0.downloadedBytes = downloaded
            $0.status = status
            $0.error = error
        }
    }

    func setBackend(_ id: String, _ backend: Backend) { update(id) { $0.backend = backend } }

    /// Deletes the files and the registry entry.
    func remove(_ id: String) {
        try? FileManager.default.removeItem(at: Self.dir(of: id))
        try? store.delete(Self.collection, id: id)
        models.removeAll { $0.id == id }
        onChange?()
    }

    /// Absolute paths of finished files by role (empty until ready).
    func paths(_ model: InstalledModel) -> [FileRole: URL] {
        guard model.isReady else { return [:] }
        var out: [FileRole: URL] = [:]
        for file in model.manifest.files where out[file.role] == nil { out[file.role] = Self.file(of: model.id, file) }
        return out
    }

    /// Directory of a model (Core ML resources are loaded from here).
    nonisolated static func dir(of id: String) -> URL { AppPaths.models.appendingPathComponent(safeName(id), isDirectory: true) }

    nonisolated static func file(of id: String, _ file: ModelFile) -> URL {
        let name = file.keepName ? safeRelativePath(file.name) : "\(file.role.rawValue.lowercased())-\(safeName(file.name))"
        return dir(of: id).appendingPathComponent(name)
    }

    /// Bytes on disk for [id] (finished files plus partial downloads).
    nonisolated static func bytesOnDisk(_ id: String) -> Int64 {
        guard let walker = FileManager.default.enumerator(at: dir(of: id), includingPropertiesForKeys: [.fileSizeKey]) else { return 0 }
        var total: Int64 = 0
        for case let url as URL in walker { total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        return total
    }

    private func put(_ model: InstalledModel) {
        try? store.put(Self.collection, id: model.id, model)
        let old: InstalledModel?
        if let i = models.firstIndex(where: { $0.id == model.id }) {
            old = models[i]
            models[i] = model
        } else {
            old = nil
            models.insert(model, at: 0)
        }
        // Byte counts alone change nothing the app derives from the registry (providers, the
        // bridge's model snapshot, the vault's embedder): only a real change fans out.
        if !Self.sameExceptProgress(old, model) { onChange?() }
    }

    /// Whether [a] and [b] differ in downloaded bytes and timestamps only.
    nonisolated static func sameExceptProgress(_ a: InstalledModel?, _ b: InstalledModel) -> Bool {
        guard var a else { return false }
        a.downloadedBytes = b.downloadedBytes
        a.updatedAt = b.updatedAt
        return a == b
    }

    /// File-system-safe, collision-resistant name for an id.
    nonisolated static func safeName(_ id: String) -> String {
        let cleaned = String(id.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? $0 : "_" }.prefix(80))
        var hash: UInt32 = 5381
        for b in id.utf8 { hash = (hash &* 33) ^ UInt32(b) }
        return "\(cleaned)_\(String(hash, radix: 16))"
    }

    /// A relative path whose components are plain names (no `..`, no leading dots).
    nonisolated static func safeRelativePath(_ path: String) -> String {
        path.split(separator: "/").map { component in
            let base = String(component.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? $0 : "_" }.prefix(120))
            let trimmed = String(base.drop(while: { $0 == "." }))
            return trimmed.isEmpty ? "file" : trimmed
        }.joined(separator: "/")
    }
}
