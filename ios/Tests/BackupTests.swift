import AutobotShared
import Foundation
import Testing
@testable import Autobot

private final class TestBundle {}

/// The `.autobot` export: Swift crypto against the Kotlin format, both directions.
@Suite(.serialized)
@MainActor
struct BackupTests {
    private func entries(_ path: String, _ passphrase: String) throws -> [String: Data] {
        final class Collect: NSObject, BackupEntryHandler, @unchecked Sendable {
            var out: [String: Data] = [:]
            func onEntry(name: String, size: Int64, path: String) { out[name] = try? Data(contentsOf: URL(fileURLWithPath: path)) }
        }
        let collect = Collect()
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("bk-\(UUID().uuidString)").path
        try BackupFileReader(path: path, crypto: SwiftBackupCrypto()).entries(passphrase: passphrase, scratchDir: scratch, handler: collect)
        return collect.out
    }

    @Test func decryptsTheKotlinTestVector() throws {
        let url = try #require(Bundle(for: TestBundle.self).url(forResource: "testvector", withExtension: "autobot"))
        let all = try entries(url.path, "autobot-test-vector")
        let manifest = try BackupManifests.shared.decode(bytes: BackupBytes.shared.fromData(data: try #require(all[BackupKt.MANIFEST])))
        #expect(manifest.settings["theme_mode"] == "LIGHT")
        // Written before local memory existed: imports with no notes.
        #expect(manifest.memory.isEmpty)
        let img = try #require(all["attachments/img1"])
        #expect(img == Data((0 ..< 3000).map { UInt8($0 % 251) }))
    }

    @Test func wrongPassphraseIsRejected() throws {
        let url = try #require(Bundle(for: TestBundle.self).url(forResource: "testvector", withExtension: "autobot"))
        #expect(throws: (any Error).self) { _ = try entries(url.path, "not the passphrase") }
    }

    @Test func cryptoPrimitivesMatchStandardVectors() throws {
        // PBKDF2-HMAC-SHA256("password", "salt", 1, 32).
        let key = SwiftBackupCrypto.deriveKey("password", salt: Data("salt".utf8), iterations: 1)
        #expect(key.map { String(format: "%02x", $0) }.joined() == "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b")
        let k = SwiftBackupCrypto.randomData(32), n = SwiftBackupCrypto.randomData(12)
        let sealed = try SwiftBackupCrypto.seal(key: k, nonce: n, aad: Data("aad".utf8), plaintext: Data("hello".utf8))
        #expect(sealed.count == 5 + 16)
        #expect(SwiftBackupCrypto.open(key: k, nonce: n, aad: Data("aad".utf8), sealed: sealed) == Data("hello".utf8))
        #expect(SwiftBackupCrypto.open(key: k, nonce: n, aad: Data("other".utf8), sealed: sealed) == nil)
    }

    @Test func swiftWriterRoundTrips() throws {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("rt-\(UUID().uuidString).autobot").path
        let blob = Data((0 ..< 2_500_000).map { UInt8($0 % 253) })
        let writer = try BackupFileWriter(path: path, crypto: SwiftBackupCrypto(), passphrase: "correct horse battery", app: "test", platform: "ios", createdAt: 1, iterations: 100_000)
        try writer.entry(name: BackupKt.MANIFEST, bytes: BackupManifest.companion.encode(manifest: BackupManifest(settings: ["amoled": "true"], providers: [], conversations: [], gallery: [], attachments: [], memory: [], folders: [], studioProjects: [], studioAssets: [], studioWorkflows: [], studioSnippets: [], usage: [], usagePlan: nil)))
        try writer.entry(name: "gallery/a.png", bytes: BackupBytes.shared.fromData(data: blob))
        try writer.finish()
        let all = try entries(path, "correct horse battery")
        #expect(all["gallery/a.png"] == blob)
    }

    /// Export from the app, remove the session, import: it comes back with its log; nothing is duplicated.
    @Test(.timeLimit(.minutes(3))) func serviceExportImportMergesWithoutOverwriting() async throws {
        let app = AppContainer()
        await app.unlockData()
        let c = app.conversations.create(incognito: false, providerId: nil, model: "m", title: "Backup round trip")
        let entry = LogEntry(seq: 0, time: 1, event: .userMessage(text: "hello export"))
        try app.sessions.append(c.id, [entry])
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("svc-\(UUID().uuidString).autobot")
        let service = BackupService(app: app, iterations: 100_000)
        try await service.export(to: url, passphrase: "correct horse battery")

        let again = try await service.import(from: url, passphrase: "correct horse battery")
        #expect(again.conversations == 0)

        app.conversations.delete(c.id)
        let restored = try await service.import(from: url, passphrase: "correct horse battery")
        #expect(restored.conversations >= 1)
        #expect(app.conversations.get(c.id)?.title == "Backup round trip")
        #expect(app.sessions.load(c.id).first?.event == .userMessage(text: "hello export"))
        app.conversations.delete(c.id)
    }

    /// Memory notes travel in the manifest and merge by id on import.
    @Test(.timeLimit(.minutes(3))) func memoryNotesExportAndMerge() async throws {
        let app = AppContainer()
        await app.unlockData()
        guard case .saved(let id) = app.memory.save(id: nil, text: "Backup memory \(UUID().uuidString)", tags: "backup") else {
            Issue.record("note not saved")
            return
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("mem-\(UUID().uuidString).autobot")
        let service = BackupService(app: app, iterations: 100_000)
        try await service.export(to: url, passphrase: "correct horse battery")
        let all = try entries(url.path, "correct horse battery")
        let manifest = try BackupManifests.shared.decode(bytes: BackupBytes.shared.fromData(data: try #require(all[BackupKt.MANIFEST])))
        #expect(manifest.memory.contains { $0.id == id && $0.tags == ["backup"] })

        #expect(try await service.import(from: url, passphrase: "correct horse battery").memories == 0)
        app.memory.delete(id)
        #expect(try await service.import(from: url, passphrase: "correct horse battery").memories >= 1)
        #expect(app.memory.notes.contains { $0.id == id })
        app.memory.delete(id)
    }

    @Test func settingsUseTheSharedSchema() {
        var s = AppSettings(values: [:])
        #expect(s.themeMode == .dark)
        #expect(s.mode == .offline)
        s.themeMode = .light
        s.lockTimeoutMillis = 300_000
        let values = s.values
        #expect(values["theme_mode"] == "LIGHT")
        #expect(values["lock_timeout_ms"] == "300000")
        #expect(values["network_mode"] == "offline")
        #expect(AppSettings(values: values) == s)
        #expect(values["memory_enabled"] == "false")
        #expect(values["search_provider"] == "NONE")
        var agent = s
        agent.memoryEnabled = true
        agent.searchProvider = "SEARXNG"
        agent.searchBaseUrl = "https://searx.example.org"
        agent.searchRoute = .tor
        #expect(AppSettings(values: agent.values) == agent)
        #expect(AppSettings(values: ["search_provider": "GOOGLE"]).searchProvider == "NONE")
        #expect(AppSettings(values: ["lock_timeout_ms": "99999999", "theme_mode": "PURPLE"]).lockTimeoutMillis == 900_000)
        #expect(lockTimeoutOptions.map(\.millis) == [0, 30_000, 60_000, 300_000, 900_000])
        #expect(lockTimeoutOptions.map(\.label) == ["Immediately", "30 s", "1 min", "5 min", "15 min"])
    }

    @Test func legacySettingsAreMigratedOnce() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("prefs-\(UUID().uuidString).json")
        let prefs = Preferences(url: url)
        // The legacy blob was a Codable AppSettings; write it the way 1.2.x did.
        var legacy = AppSettings(values: [:])
        legacy.themeMode = .system
        legacy.networkMode = "direct"
        legacy.onboarded = true
        prefs.set("settings", legacy)
        let store = SettingsStore(preferences: prefs, policy: NetworkPolicy(), lock: AppLockManager())
        #expect(store.settings.themeMode == .system)
        #expect(store.settings.mode == .direct)
        #expect(store.settings.onboarded)
        #expect(prefs.get("settings", as: AppSettings.self) == nil)
        #expect(prefs.get(SettingsStore.key, as: [String: String].self)?["theme_mode"] == "SYSTEM")
    }
}
