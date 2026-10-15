import AutobotShared
import AVFoundation
import CryptoKit
import Foundation
import Observation
import UIKit

/// One Studio record in the encrypted store: the shared `StudioCodec` JSON, sealed like every row.
private struct StudioRow: Codable {
    let json: String
}

/**
 The shared Studio's records (`StudioStoreBridge`) in the encrypted store: projects, assets,
 saved workflows, the prompt library and the queue's jobs, one collection each. Kotlin calls it on
 its I/O threads; every change is also reported to [onChange] (the library mirrors it on main).
 */
final class StudioStoreHost: NSObject, StudioStoreBridge, @unchecked Sendable {
    static func collection(_ kind: String) -> String { "studio_\(kind)" }

    private let store: EncryptedStore
    /// (kind, id, json or nil when removed); called on the writing thread.
    let onChange: @Sendable (String, String, String?) -> Void

    init(store: EncryptedStore, onChange: @escaping @Sendable (String, String, String?) -> Void) {
        self.store = store
        self.onChange = onChange
    }

    func records(kind: String) -> [String] {
        ((try? store.all(Self.collection(kind), as: StudioRow.self)) ?? []).map(\.json)
    }

    func put(kind: String, id: String, json: String) {
        do {
            try store.put(Self.collection(kind), id: id, StudioRow(json: json))
            onChange(kind, id, json)
        } catch {
            // Locked device or a closed store (panic wipe): the record is dropped like other writes.
        }
    }

    func remove(kind: String, id: String) {
        try? store.delete(Self.collection(kind), id: id)
        onChange(kind, id, nil)
    }
}

/**
 Studio asset bytes, AES-256-GCM sealed under `studio/` with the media key (the id and suffix are
 associated data), like the gallery: `<id>.bin` the file as generated, `<id>.thumb` a small JPEG
 for images and videos, `<id>.wave` an audio file's waveform. Nothing is written in the clear;
 players and engines that need a file get a short-lived copy in the plain cache ([openPlain]).
 */
final class StudioFilesHost: NSObject, StudioFiles, @unchecked Sendable {
    static let data = ".bin"
    static let thumb = ".thumb"
    static let wave = ".wave"

    private let keys: KeyManager
    private let root: URL

    init(keys: KeyManager, root: URL? = nil) {
        self.keys = keys
        self.root = root ?? AppPaths.directory("studio")
    }

    // StudioFiles (Kotlin: the queue's output sink, on an I/O thread).

    func write(assetId: String, mimeType: String, bytes: KotlinByteArray) {
        let data = BackupBytes.shared.toData(bytes: bytes) as Data
        try? save(assetId, data, mimeType: mimeType)
    }

    func read(assetId: String) -> KotlinByteArray? {
        bytes(assetId).map { BackupBytes.shared.fromData(data: $0) }
    }

    /// Stores [data] and, for images, its thumbnail.
    func save(_ id: String, _ data: Data, mimeType: String) throws {
        try put(id, Self.data, data)
        if mimeType.hasPrefix("image/"), let thumb = ImageCodec.thumbnail(data) { try? put(id, Self.thumb, thumb) }
    }

    func bytes(_ id: String) -> Data? { get(id, Self.data) }

    func thumbnail(_ id: String) -> Data? { get(id, Self.thumb) }

    func putThumbnail(_ id: String, _ data: Data) { try? put(id, Self.thumb, data) }

    /// The cached waveform peaks (0…1), or nil.
    func waveform(_ id: String) -> [Float]? {
        guard let data = get(id, Self.wave) else { return nil }
        return data.map { Float($0) / 255 }
    }

    func putWaveform(_ id: String, _ peaks: [Float]) {
        try? put(id, Self.wave, Data(peaks.map { UInt8(max(0, min(255, ($0 * 255).rounded()))) }))
    }

    func delete(_ id: String) {
        for suffix in [Self.data, Self.thumb, Self.wave] {
            if let url = file(id, suffix) { try? FileManager.default.removeItem(at: url) }
        }
    }

    /// Decrypts [id] into a private temporary file (players, AVFoundation); delete it after use.
    func openPlain(_ id: String, ext: String) -> URL? {
        guard let data = bytes(id) else { return nil }
        let url = AppPaths.plainCache.appendingPathComponent("\(UUID().uuidString).\(ext)")
        return (try? AppPaths.write(data, to: url)) != nil ? url : nil
    }

    private func file(_ id: String, _ suffix: String) -> URL? {
        guard StoredId.isValid(id) else { return nil }
        return root.appendingPathComponent(id + suffix)
    }

    private func aad(_ id: String, _ suffix: String) -> Data { Data("studio:\(id)\(suffix)".utf8) }

    private func put(_ id: String, _ suffix: String, _ data: Data) throws {
        guard let url = file(id, suffix) else { throw StoredId.Invalid() }
        try AppPaths.write(try AesGcmEnvelope.seal(keys.mediaKey(), data, aad: aad(id, suffix)), to: url)
    }

    private func get(_ id: String, _ suffix: String) -> Data? {
        guard let url = file(id, suffix), let sealed = try? Data(contentsOf: url), let key = try? keys.mediaKey() else { return nil }
        return try? AesGcmEnvelope.open(key, sealed, aad: aad(id, suffix))
    }
}

/**
 The Studio's records as the screens see them (main actor): projects, assets (newest first), saved
 workflows and the prompt library, mirrored from [StudioStoreHost] as they change, wherever the
 change came from (a screen, the queue's sink on a Kotlin thread, an import).
 */
@MainActor
@Observable
final class StudioLibrary {
    private(set) var projects: [StudioProject] = []
    private(set) var assets: [StudioAsset] = []
    private(set) var workflows: [StudioWorkflow] = []
    private(set) var snippets: [PromptSnippet] = []
    private(set) var loaded = false

    /// Rows as read off the main thread.
    struct Rows: @unchecked Sendable {
        var projects: [String] = []
        var assets: [String] = []
        var workflows: [String] = []
        var snippets: [String] = []
    }

    nonisolated static func read(_ host: StudioStoreHost) -> Rows {
        Rows(
            projects: host.records(kind: StudioStoreBridgeCompanion.shared.PROJECTS),
            assets: host.records(kind: StudioStoreBridgeCompanion.shared.ASSETS),
            workflows: host.records(kind: StudioStoreBridgeCompanion.shared.WORKFLOWS),
            snippets: host.records(kind: StudioStoreBridgeCompanion.shared.SNIPPETS),
        )
    }

    func load(_ rows: Rows) {
        let codec = StudioCodec.shared
        projects = Self.sorted(rows.projects.compactMap { try? codec.decodeProject(json: $0) })
        assets = rows.assets.compactMap { try? codec.decodeAsset(json: $0) }.sorted { $0.createdAt > $1.createdAt }
        workflows = rows.workflows.compactMap { try? codec.decodeWorkflow(json: $0) }.sorted { $0.name.lowercased() < $1.name.lowercased() }
        snippets = rows.snippets.compactMap { try? codec.decodeSnippet(json: $0) }.sorted { $0.name.lowercased() < $1.name.lowercased() }
        loaded = true
    }

    /// Mirrors one record change (idempotent).
    func apply(kind: String, id: String, json: String?) {
        let codec = StudioCodec.shared
        let k = StudioStoreBridgeCompanion.shared
        switch kind {
        case k.PROJECTS:
            projects.removeAll { $0.id == id }
            if let json, let p = try? codec.decodeProject(json: json) { projects = Self.sorted(projects + [p]) }
        case k.ASSETS:
            if let json, let a = try? codec.decodeAsset(json: json) {
                if let i = assets.firstIndex(where: { $0.id == id }) { assets[i] = a } else { assets = ([a] + assets).sorted { $0.createdAt > $1.createdAt } }
            } else {
                assets.removeAll { $0.id == id }
            }
        case k.WORKFLOWS:
            workflows.removeAll { $0.id == id }
            if let json, let w = try? codec.decodeWorkflow(json: json) { workflows = (workflows + [w]).sorted { $0.name.lowercased() < $1.name.lowercased() } }
        case k.SNIPPETS:
            snippets.removeAll { $0.id == id }
            if let json, let s = try? codec.decodeSnippet(json: json) { snippets = (snippets + [s]).sorted { $0.name.lowercased() < $1.name.lowercased() } }
        default:
            break
        }
    }

    func asset(_ id: String) -> StudioAsset? { assets.first { $0.id == id } }

    func project(_ id: String?) -> StudioProject? { id.flatMap { id in projects.first { $0.id == id } } }

    func workflow(_ id: String?) -> StudioWorkflow? { id.flatMap { id in workflows.first { $0.id == id } } }

    func assets(in projectId: String?, types: Set<AssetType>, favoritesOnly: Bool) -> [StudioAsset] {
        assets.filter { a in (projectId == nil || a.projectId == projectId) && types.contains(a.type) && (!favoritesOnly || a.favorite) }
    }

    private static func sorted(_ list: [StudioProject]) -> [StudioProject] { list.sorted { $0.updatedAt > $1.updatedAt } }
}

// MARK: - Audio helpers

/// WAV encoding and waveform peaks for Studio audio (Kokoro's mono PCM, any decodable file).
enum StudioAudio {
    /// 16-bit PCM WAV of mono float samples.
    static func wav(mono samples: [Float], sampleRate: Int) -> Data {
        var d = Data(capacity: 44 + samples.count * 2)
        func le32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func le16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); le32(36 + bytes)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); le32(16); le16(1); le16(1)
        le32(UInt32(sampleRate)); le32(UInt32(sampleRate * 2)); le16(2); le16(16)
        d.append(contentsOf: Array("data".utf8)); le32(bytes)
        for s in samples {
            let v = Int16(max(-1, min(1, s)) * Float(Int16.max))
            le16(UInt16(bitPattern: v))
        }
        return d
    }

    /// [count] peak levels (0…1) of an audio file's first channel, or nil when it cannot be read.
    static func peaks(file: URL, count: Int = 96) -> [Float]? {
        guard let audio = try? AVAudioFile(forReading: file), audio.length > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: AVAudioFrameCount(audio.length)),
              (try? audio.read(into: buffer)) != nil, let channel = buffer.floatChannelData?[0] else { return nil }
        return peaks(Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength))), count: count)
    }

    static func peaks(_ samples: [Float], count: Int) -> [Float] {
        guard !samples.isEmpty, count > 0 else { return Array(repeating: 0, count: max(count, 0)) }
        let per = max(1, samples.count / count)
        var out: [Float] = []
        out.reserveCapacity(count)
        for i in 0 ..< count {
            let start = i * samples.count / count
            let end = min(samples.count, start + per)
            var peak: Float = 0
            if start < end { for j in start ..< end { peak = max(peak, abs(samples[j])) } }
            out.append(min(peak, 1))
        }
        let top = out.max() ?? 0
        return top > 0 ? out.map { $0 / top } : out
    }

    /// The part of an audio file from [start] to [end] seconds as 16-bit WAV (trim), or nil.
    static func trim(file: URL, start: Double, end: Double) -> (data: Data, durationMs: Int64)? {
        guard let audio = try? AVAudioFile(forReading: file) else { return nil }
        let rate = audio.processingFormat.sampleRate
        let from = AVAudioFramePosition(max(0, start) * rate)
        let to = min(audio.length, AVAudioFramePosition(end * rate))
        guard to > from else { return nil }
        let frames = AVAudioFrameCount(to - from)
        audio.framePosition = from
        guard let buffer = AVAudioPCMBuffer(pcmFormat: audio.processingFormat, frameCapacity: frames), (try? audio.read(into: buffer, frameCount: frames)) != nil,
              let data = buffer.floatChannelData else { return nil }
        let channels = Int(buffer.format.channelCount)
        let n = Int(buffer.frameLength)
        var d = Data(capacity: 44 + n * channels * 2)
        func le32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func le16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(n * channels * 2)
        d.append(contentsOf: Array("RIFF".utf8)); le32(36 + bytes)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); le32(16); le16(1); le16(UInt16(channels))
        le32(UInt32(rate)); le32(UInt32(Int(rate) * channels * 2)); le16(UInt16(channels * 2)); le16(16)
        d.append(contentsOf: Array("data".utf8)); le32(bytes)
        for i in 0 ..< n {
            for ch in 0 ..< channels {
                le16(UInt16(bitPattern: Int16(max(-1, min(1, data[ch][i])) * Float(Int16.max))))
            }
        }
        return (d, Int64(Double(n) / rate * 1000))
    }
}
