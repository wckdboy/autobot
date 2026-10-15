import AutobotShared
import CommonCrypto
import CryptoKit
import Foundation
import Security

/**
 The `.autobot` bundle's crypto on iOS: PBKDF2-HMAC-SHA256 (CommonCrypto), AES-256-GCM
 (CryptoKit, ciphertext ‖ 16-byte tag), random bytes from the system CSPRNG. The format itself
 (header, chunking, nonces, AAD) is the shared Kotlin code, so files open on Android too.
 */
final class SwiftBackupCrypto: NSObject, HostBackupCrypto, @unchecked Sendable {
    static func randomData(_ count: Int) -> Data {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return data
    }

    static func deriveKey(_ passphrase: String, salt: Data, iterations: Int) -> Data {
        var key = Data(count: 32)
        let password = Array(passphrase.utf8)
        let status = key.withUnsafeMutableBytes { k in
            salt.withUnsafeBytes { s in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2), password.map { CChar(bitPattern: $0) }, password.count,
                    s.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations),
                    k.bindMemory(to: UInt8.self).baseAddress, 32,
                )
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed")
        return key
    }

    static func seal(key: Data, nonce: Data, aad: Data, plaintext: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad)
        return box.ciphertext + box.tag
    }

    static func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard sealed.count >= 16, let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: sealed.dropLast(16), tag: sealed.suffix(16)) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // HostBackupCrypto (Kotlin calls these on the export / import thread).

    private func data(_ bytes: KotlinByteArray) -> Data { BackupBytes.shared.toData(bytes: bytes) as Data }
    private func bytes(_ data: Data) -> KotlinByteArray { BackupBytes.shared.fromData(data: data) }

    func randomBytes(count: Int32) -> KotlinByteArray { bytes(Self.randomData(Int(count))) }

    func deriveKey(passphrase: String, salt: KotlinByteArray, iterations: Int32) -> KotlinByteArray {
        bytes(Self.deriveKey(passphrase, salt: data(salt), iterations: Int(iterations)))
    }

    func seal(key: KotlinByteArray, nonce: KotlinByteArray, aad: KotlinByteArray, plaintext: KotlinByteArray) -> KotlinByteArray {
        // Sealing only fails on a malformed key or nonce, which the shared format never produces.
        bytes((try? Self.seal(key: data(key), nonce: data(nonce), aad: data(aad), plaintext: data(plaintext))) ?? Data())
    }

    func open(key: KotlinByteArray, nonce: KotlinByteArray, aad: KotlinByteArray, sealed: KotlinByteArray) -> KotlinByteArray? {
        Self.open(key: data(key), nonce: data(nonce), aad: data(aad), sealed: data(sealed)).map(bytes)
    }
}

/// What an import added (existing items with the same id are kept, not overwritten).
struct ImportSummary: Equatable, Sendable {
    var conversations = 0
    var providers = 0
    var images = 0
    var attachments = 0
    var memories = 0
}

struct BackupError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/**
 Passphrase-sealed export and import of everything the user owns, in the shared `.autobot`
 format, so a user can move between iPhone and Android (port of Android's `BackupService`).

 Not exported: API keys, incognito sessions, downloaded models (re-download them) and the app's
 own keys. On import, security settings (network mode, loopback, app lock, lock timeout) keep
 this device's values: a file must never switch the app online or unlock it. The same goes for
 the memory switch and the web search setup; memory notes themselves are merged.
 */
@MainActor
final class BackupService {
    static let minPassphrase = 10
    private static let safeId = try! NSRegularExpression(pattern: "^[A-Za-z0-9-]{1,64}$")

    private let app: AppContainer
    private let iterations: Int32

    init(app: AppContainer, iterations: Int32 = BackupKt.DEFAULT_ITERATIONS) {
        self.app = app
        self.iterations = iterations
    }

    /// Writes a bundle to [url]. Key derivation and sealing run off the main actor.
    func export(to url: URL, passphrase: String, onProgress: @escaping @MainActor (String) -> Void = { _ in }) async throws {
        let conversations = app.conversations.conversations.filter { !app.conversations.isIncognito($0.id) }
        let images = app.gallery.items
        let logs = conversations.map { c in (c.id, app.sessions.loadJSON(c.id)) }
        let attachmentIds = Self.attachmentIds(logs.flatMap(\.1))
        let manifest = BackupManifest(
            settings: app.settings.settings.values,
            providers: app.providers.providers.map {
                BackupProvider(
                    id: $0.id, kind: $0.kind.rawValue, displayName: $0.displayName, baseUrl: $0.baseUrl, defaultModel: $0.defaultModel,
                    routing: $0.routing.rawValue, presetId: $0.presetId, helperModel: $0.helperModel,
                )
            },
            conversations: conversations.map {
                BackupConversation(id: $0.id, title: $0.title, providerId: $0.providerId, model: $0.model, createdAt: $0.createdAt, updatedAt: $0.updatedAt)
            },
            gallery: images.map(Self.backupImage),
            attachments: attachmentIds,
            memory: app.memory.notes.map(\.shared),
        )
        let version = Bundle.main.infoDictionary
        let label = "Autobot \(version?["CFBundleShortVersionString"] as? String ?? "?") (\(version?["CFBundleVersion"] as? String ?? "0"))"
        let path = url.path
        let iterations = iterations
        onProgress("deriving key…")
        let box = try await Task.detached {
            WriterBox(try BackupFileWriter(path: path, crypto: SwiftBackupCrypto(), passphrase: passphrase, app: label, platform: "ios",
                                           createdAt: nowMillis(), iterations: iterations))
        }.value
        let writer = box.writer
        do {
            try writer.entry(name: BackupKt.MANIFEST, bytes: BackupManifest.companion.encode(manifest: manifest))
            for (id, lines) in logs {
                var jsonl = Data()
                for line in lines { jsonl.append(line); jsonl.append(0x0A) }
                try writer.entry(name: BackupManifest.companion.sessionEntry(conversationId: id), bytes: BackupBytes.shared.fromData(data: jsonl))
            }
            for (i, item) in images.enumerated() {
                onProgress("images \(i + 1)/\(images.count)")
                if let bytes = await app.gallery.image(item.id) {
                    let name = Self.imageEntry(item.id)
                    try await Task.detached { try box.writer.entry(name: name, bytes: BackupBytes.shared.fromData(data: bytes)) }.value
                }
            }
            for id in attachmentIds {
                if let bytes = app.attachments.bytes(id) {
                    try writer.entry(name: BackupManifest.companion.attachmentEntry(id: id), bytes: BackupBytes.shared.fromData(data: bytes))
                }
            }
            try await Task.detached { try box.writer.finish() }.value
        } catch {
            writer.abort()
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }

    /// Reads the bundle at [url] and merges it: nothing that already exists is overwritten.
    func `import`(from url: URL, passphrase: String, onProgress: @escaping @MainActor (String) -> Void = { _ in }) async throws -> ImportSummary {
        let staging = FileManager.default.temporaryDirectory.appendingPathComponent("autobot-import-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        defer { try? FileManager.default.removeItem(at: staging) }
        onProgress("deriving key…")
        let path = url.path
        // Decrypt every entry into the private staging folder, then apply on the main actor.
        let staged: [(name: String, file: URL)] = try await Task.detached {
            let reader = try BackupFileReader(path: path, crypto: SwiftBackupCrypto())
            let collector = EntryCollector(dir: staging)
            try reader.entries(passphrase: passphrase, scratchDir: staging.appendingPathComponent("scratch").path, handler: collector)
            return collector.entries
        }.value
        guard let first = staged.first, first.name == BackupKt.MANIFEST, let manifestData = try? Data(contentsOf: first.file) else {
            throw BackupError(message: "Damaged export: manifest missing")
        }
        let manifest = try BackupManifests.shared.decode(bytes: BackupBytes.shared.fromData(data: manifestData))
        var added = ImportSummary()
        applySettings(manifest.settings)
        for p in manifest.providers where app.providers.get(p.id) == nil {
            _ = try? app.providers.save(Provider(
                id: p.id, kind: ProviderKind(rawValue: p.kind) ?? .openAICompatible, displayName: p.displayName, baseUrl: p.baseUrl,
                defaultModel: p.defaultModel, routing: ProviderRouting(rawValue: p.routing) ?? .inherit, presetId: p.presetId,
                helperModel: p.helperModel.flatMap { $0.isEmpty ? nil : $0 },
            ))
            added.providers += 1
        }
        var newConversations = Set<String>()
        for c in manifest.conversations where app.conversations.get(c.id) == nil && !app.conversations.isIncognito(c.id) {
            app.conversations.update(Conversation(id: c.id, title: c.title, providerId: c.providerId, model: c.model, createdAt: c.createdAt, updatedAt: c.updatedAt))
            newConversations.insert(c.id)
        }
        added.conversations = newConversations.count
        added.memories = app.memory.merge(manifest.memory)
        for (name, file) in staged.dropFirst() {
            if name.hasPrefix("sessions/") {
                let id = String(name.dropFirst("sessions/".count).dropLast(".jsonl".count))
                guard newConversations.contains(id), let text = try? String(contentsOf: file, encoding: .utf8) else { continue }
                try? app.sessions.appendJSON(id, Self.importableLines(text))
            } else if name.hasPrefix("gallery/") {
                guard let item = manifest.gallery.first(where: { $0.file == name }), Self.isSafe(item.id), app.gallery.get(item.id) == nil,
                      let bytes = try? Data(contentsOf: file) else { continue }
                let thumb = await Task.detached { ImageCodec.thumbnail(bytes) }.value ?? bytes
                try await app.gallery.save(Self.galleryItem(item), image: bytes, thumbnail: thumb)
                added.images += 1
                onProgress("images \(added.images)/\(manifest.gallery.count)")
            } else if name.hasPrefix("attachments/") {
                let id = String(name.dropFirst("attachments/".count))
                guard Self.isSafe(id), app.attachments.bytes(id) == nil, let bytes = try? Data(contentsOf: file) else { continue }
                try app.attachments.restore(id, bytes)
                added.attachments += 1
            }
        }
        return added
    }

    /// Applies appearance and behaviour settings; security-relevant ones keep this device's values.
    private func applySettings(_ values: [String: String]) {
        guard !values.isEmpty else { return }
        let incoming = AppSettings(values: values)
        app.settings.setThemeMode(incoming.themeMode)
        app.settings.setAmoled(incoming.amoled)
        app.settings.setDynamicColor(incoming.dynamicColor)
        app.settings.setIncognitoByDefault(incoming.incognitoByDefault)
    }

    /// Attachment ids referenced by user messages in session log lines.
    static func attachmentIds(_ lines: [Data]) -> [String] {
        var seen = Set<String>(), out: [String] = []
        for line in lines {
            guard let entry = try? SessionJSON.decode(line), case .userMessage(_, _, _, let images) = entry.event else { continue }
            for id in images where seen.insert(id).inserted { out.append(id) }
        }
        return out
    }

    static func imageEntry(_ id: String) -> String { "gallery/\(id).png" }

    /// The readable entries of an imported session log. An entry that refers to an attachment by
    /// an id that is not a plain file name is dropped (it would point outside `attachments/`).
    static func importableLines(_ text: String) -> [(seq: Int64, json: Data)] {
        text.split(separator: "\n").compactMap { line -> (seq: Int64, json: Data)? in
            let data = Data(line.utf8)
            guard let entry = try? SessionJSON.decode(data), attachmentIds([data]).allSatisfy(isSafe) else { return nil }
            return (entry.seq, data)
        }
    }

    private static func isSafe(_ id: String) -> Bool { safeId.firstMatch(in: id, range: NSRange(id.startIndex..., in: id)) != nil }

    static func backupImage(_ g: GalleryItem) -> BackupImage {
        BackupImage(
            id: g.id, file: imageEntry(g.id), createdAt: g.createdAt, mode: g.mode, prompt: g.prompt, negativePrompt: g.negativePrompt,
            width: Int32(g.width), height: Int32(g.height), seed: g.seed, steps: Int32(g.steps), cfgScale: Float(g.cfgScale), sampler: g.sampler,
            engine: g.engine, model: g.model, durationMs: g.durationMs, paramsJson: g.paramsJson, favorite: g.favorite,
        )
    }

    static func galleryItem(_ b: BackupImage) -> GalleryItem {
        GalleryItem(
            id: b.id, createdAt: b.createdAt, mode: b.mode, prompt: b.prompt, negativePrompt: b.negativePrompt, width: Int(b.width), height: Int(b.height),
            seed: b.seed, steps: Int(b.steps), cfgScale: Double(b.cfgScale), sampler: b.sampler, engine: b.engine, model: b.model,
            durationMs: b.durationMs, paramsJson: b.paramsJson, favorite: b.favorite,
        )
    }
}

/// Moves each decrypted entry out of the reader's scratch file into the staging folder.
/// The Kotlin writer is used by one task at a time (strictly sequential awaits).
private final class WriterBox: @unchecked Sendable {
    let writer: BackupFileWriter
    init(_ writer: BackupFileWriter) { self.writer = writer }
}

private final class EntryCollector: NSObject, BackupEntryHandler, @unchecked Sendable {
    private let dir: URL
    private(set) var entries: [(name: String, file: URL)] = []

    init(dir: URL) { self.dir = dir }

    func onEntry(name: String, size: Int64, path: String) {
        let file = dir.appendingPathComponent("\(entries.count).entry")
        if (try? FileManager.default.moveItem(at: URL(fileURLWithPath: path), to: file)) != nil {
            entries.append((name, file))
        }
    }
}
