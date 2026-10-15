import AutobotShared
import Foundation

// The model logic lives in `:shared` (`dev.wckdboy.autobot.core.models`): catalog, fit rules,
// hub requests and parsing, the download state machine. The Swift value types in Models.swift are
// its mirrors — Codable for the encrypted store, Sendable for SwiftUI — and convert field by field
// here (enums by their Kotlin name, which is also their raw value). Nothing is decoded from JSON.

/// The Kotlin enum entry named [name] (Swift raw values are the Kotlin names).
private func entry<E: AnyObject>(_ entries: [E], _ name: String) -> E {
    entries.first { ($0 as? KotlinEnum<AnyObject>)?.name == name } ?? entries[0]
}

extension ModelKind {
    init(_ k: AutobotShared.ModelKind) { self = ModelKind(rawValue: k.name) ?? .other }
    var kotlin: AutobotShared.ModelKind { entry(AutobotShared.ModelKind.entries, rawValue) }
}

extension ModelFormat {
    init(_ k: AutobotShared.ModelFormat) { self = ModelFormat(rawValue: k.name) ?? .other }
    var kotlin: AutobotShared.ModelFormat { entry(AutobotShared.ModelFormat.entries, rawValue) }
}

extension EngineKind {
    init(_ k: AutobotShared.EngineKind) { self = EngineKind(rawValue: k.name) ?? .none }
    var kotlin: AutobotShared.EngineKind { entry(AutobotShared.EngineKind.entries, rawValue) }
}

extension ModelSource {
    init(_ k: AutobotShared.ModelSource) { self = ModelSource(rawValue: k.name) ?? .catalog }
}

extension FileRole {
    init(_ k: AutobotShared.FileRole) { self = FileRole(rawValue: k.name) ?? .other }
    var kotlin: AutobotShared.FileRole { entry(AutobotShared.FileRole.entries, rawValue) }
}

extension AuthHost {
    init?(_ k: AutobotShared.AuthHost?) {
        guard let k, let host = AuthHost(rawValue: k.name) else { return nil }
        self = host
    }

    var kotlin: AutobotShared.AuthHost { entry(AutobotShared.AuthHost.entries, rawValue) }
}

extension CivitaiHost {
    var kotlin: AutobotShared.CivitaiHost { entry(AutobotShared.CivitaiHost.entries, rawValue) }
}

extension Fit {
    init(_ k: AutobotShared.Fit) {
        self = switch k {
        case .good: .good
        case .tight: .tight
        case .tooBig: .tooBig
        default: .unsupported
        }
    }
}

extension ZipEntryRef {
    init(_ k: AutobotShared.ZipEntryRef) {
        self.init(entry: k.entry, dataOffset: k.dataOffset, compressedSize: k.compressedSize, method: Int(k.method), crc32: k.crc32)
    }

    var kotlin: AutobotShared.ZipEntryRef {
        AutobotShared.ZipEntryRef(entry: entry, dataOffset: dataOffset, compressedSize: compressedSize, method: Int32(method), crc32: crc32)
    }
}

extension ModelFile {
    init(_ k: AutobotShared.ModelFile) {
        self.init(role: FileRole(k.role), name: k.name, url: k.url, sizeBytes: k.sizeBytes, sha256: k.sha256, auth: AuthHost(k.auth),
                  zipEntry: k.zipEntry.map(ZipEntryRef.init), keepName: k.keepName)
    }

    var kotlin: AutobotShared.ModelFile {
        AutobotShared.ModelFile(role: role.kotlin, name: name, url: url, sizeBytes: sizeBytes, sha256: sha256, auth: auth?.kotlin,
                                zipEntry: zipEntry?.kotlin, keepName: keepName)
    }
}

extension Recommended {
    init(_ k: AutobotShared.Recommended) {
        self.init(steps: k.steps.map { Int($0.int32Value) }, cfg: k.cfg.map { Double($0.floatValue) }, sampler: k.sampler, scheduler: k.scheduler,
                  width: k.width.map { Int($0.int32Value) }, height: k.height.map { Int($0.int32Value) },
                  contextLength: k.contextLength.map { Int($0.int32Value) }, negativePrompt: k.negativePrompt)
    }
}

extension ModelManifest {
    init(_ k: AutobotShared.ModelManifest) {
        self.init(
            files: k.files.map(ModelFile.init), pageUrl: k.pageUrl, recommended: k.recommended.map(Recommended.init), trainedWords: k.trainedWords,
            gated: k.gated, notes: k.notes, npu: k.npu.map { NpuPackage(arch: $0.arch, runtime: $0.runtime, vpred: $0.vpred, htpArch: $0.htpArch) },
        )
    }
}

extension ModelPlan {
    init(_ k: AutobotShared.ModelPlan) {
        self.init(id: k.id, kind: ModelKind(k.kind), source: ModelSource(k.source), title: k.title, subtitle: k.subtitle, format: ModelFormat(k.format),
                  engine: EngineKind(k.engine), baseModel: k.baseModel, license: k.license, nsfw: k.nsfw, manifest: ModelManifest(k.manifest))
    }
}

extension HfFile {
    init(_ k: AutobotShared.HfFile) { self.init(path: k.path, sizeBytes: k.sizeBytes, sha256: k.sha256) }
    var kotlin: AutobotShared.HfFile { AutobotShared.HfFile(path: path, sizeBytes: sizeBytes, sha256: sha256) }
}

extension HfRepo {
    init(_ k: AutobotShared.HfRepo) { self.init(id: k.id, commit: k.commit, gated: k.gated, license: k.license, files: k.files.map(HfFile.init)) }
    var kotlin: AutobotShared.HfRepo { AutobotShared.HfRepo(id: id, commit: commit, gated: gated, license: license, files: files.map(\.kotlin)) }
}

extension HfModelSummary {
    init(_ k: AutobotShared.HfModelSummary) {
        self.init(id: k.id, downloads: k.downloads, likes: k.likes, license: k.license, gated: k.gated, tags: k.tags)
    }
}

extension CivitaiModel {
    init(_ k: AutobotShared.CivitaiModel) {
        self.init(
            id: k.id, name: k.name, type: k.type, nsfw: k.nsfw, creator: k.creator, downloads: k.downloads, allowCommercialUse: k.allowCommercialUse,
            versions: k.versions.map { v in
                CivitaiVersion(
                    id: v.id, name: v.name, baseModel: v.baseModel, trainedWords: v.trainedWords,
                    files: v.files.map { CivitaiFile(name: $0.name, sizeBytes: $0.sizeBytes, sha256: $0.sha256, format: $0.format, primary: $0.primary, downloadUrl: $0.downloadUrl) },
                    previewUrl: v.previewUrl, previewMature: v.previewMature, earlyAccess: v.earlyAccess,
                )
            },
        )
    }

    var kotlin: AutobotShared.CivitaiModel {
        AutobotShared.CivitaiModel(id: id, name: name, type: type, nsfw: nsfw, creator: creator, downloads: downloads,
                                   allowCommercialUse: allowCommercialUse, versions: versions.map(\.kotlin))
    }
}

extension CivitaiVersion {
    var kotlin: AutobotShared.CivitaiVersion {
        AutobotShared.CivitaiVersion(
            id: id, name: name, baseModel: baseModel, trainedWords: trainedWords,
            files: files.map { AutobotShared.CivitaiFile(name: $0.name, sizeBytes: $0.sizeBytes, sha256: $0.sha256, format: $0.format, primary: $0.primary, downloadUrl: $0.downloadUrl) },
            previewUrl: previewUrl, previewMature: previewMature, earlyAccess: earlyAccess,
        )
    }
}

/// The Kotlin exception behind an error thrown by a shared `@Throws` function, if any.
func kotlinException(_ error: Error) -> KotlinThrowable? {
    (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
}

extension DownloadError {
    init(_ e: DownloadException) { self.init(message: e.message ?? "Download failed", retryable: e.retryable) }

    /// Runs a shared call that throws `DownloadException`, as a [DownloadError].
    static func rethrowing<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch {
            if let e = kotlinException(error) as? DownloadException { throw DownloadError(e) }
            throw error
        }
    }
}

extension HubError {
    init(_ e: HubException) {
        let code: Code = switch e.code {
        case .blocked: .blocked
        case .unauthorized: .unauthorized
        case .gated: .gated
        case .notFound: .notFound
        case .rateLimited: .rateLimited
        default: .failed
        }
        self.init(message: e.message ?? "Request failed", code: code)
    }

    /// Runs a shared parser that throws `HubException`, as a [HubError].
    static func rethrowing<T>(_ body: () throws -> T) throws -> T {
        do {
            return try body()
        } catch {
            if let e = kotlinException(error) as? HubException { throw HubError(e) }
            throw HubError(message: error.localizedDescription)
        }
    }
}
