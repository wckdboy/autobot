import Foundation
import ImageIO
import Synchronization
import Testing
import UIKit
@testable import Autobot

/// Which engines go before another loads, and when they are unloaded.
@Suite struct EngineMemoryTests {
    @Test func imageWorkRunsAlone() {
        let all = Set(ResidentEngine.allCases)
        #expect(EngineMemory.evictions(before: .diffusion, resident: all) == [.llm, .tts, .whisper, .upscaler, .audio])
        #expect(EngineMemory.evictions(before: .upscaler, resident: all) == [.llm, .tts, .whisper, .diffusion, .audio])
        #expect(EngineMemory.evictions(before: .audio, resident: all) == [.llm, .tts, .whisper, .diffusion, .upscaler])
        #expect(EngineMemory.evictions(before: .diffusion, resident: []) == [])
    }

    @Test func voiceModeEnginesShareMemory() {
        // Chat, dictation and the voice run together in voice mode; image work goes first.
        #expect(EngineMemory.evictions(before: .llm, resident: [.tts, .whisper, .diffusion]) == [.diffusion])
        #expect(EngineMemory.evictions(before: .tts, resident: [.llm, .whisper, .upscaler]) == [.upscaler])
        #expect(EngineMemory.evictions(before: .whisper, resident: [.llm, .tts]) == [])
    }

    private final class Log: Sendable {
        let unloaded = Mutex<[ResidentEngine]>([])
        var list: [ResidentEngine] { unloaded.withLock { $0 } }
    }

    private func coordinator(_ log: Log) -> EngineMemory {
        var unloaders: [ResidentEngine: @Sendable () async -> Void] = [:]
        for kind in ResidentEngine.allCases { unloaders[kind] = { log.unloaded.withLock { $0.append(kind) } } }
        return EngineMemory(unloaders: unloaders)
    }

    @Test func loadingTheImageModelUnloadsTheChatModel() async throws {
        let log = Log()
        let memory = coordinator(log)
        try await EngineMemory.using(.llm, on: memory) {}
        try await EngineMemory.using(.tts, on: memory) {}
        #expect(log.list.isEmpty)
        try await EngineMemory.using(.diffusion, on: memory) {}
        #expect(Set(log.list) == [.llm, .tts])
        #expect(await memory.resident == [.diffusion])
    }

    @Test func memoryWarningWaitsForBusyEngines() async throws {
        let log = Log()
        let memory = coordinator(log)
        try await EngineMemory.using(.whisper, on: memory) {}
        try await EngineMemory.using(.tts, on: memory) {
            await memory.releaseAll()
            // The voice is speaking: it stays until the piece is done; dictation is idle and goes.
            #expect(log.list == [.whisper])
        }
        #expect(log.list == [.whisper, .tts])
        #expect(await memory.resident.isEmpty)
    }

    @Test func busyVoiceIsReleasedAfterItsWorkWhenImagesStart() async throws {
        let log = Log()
        let memory = coordinator(log)
        await memory.begin(.tts)
        try await EngineMemory.using(.diffusion, on: memory) {
            #expect(!log.list.contains(.tts))
        }
        await memory.end(.tts)
        #expect(log.list.contains(.tts))
    }

    @Test func releaseOnlyTouchesResidentEngines() async {
        let log = Log()
        let memory = coordinator(log)
        await memory.release([.tts, .whisper])
        #expect(log.list.isEmpty)
    }
}

/// Preferences read before the first unlock must not overwrite the real file.
@Suite struct PreferencesLoadTests {
    @Test func unreadableFileIsNeverOverwrittenAndMergesLater() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("prefs-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("prefs.json")
        let original = Preferences(url: url)
        original.set("theme", "dark")
        original.set("kept", 7)
        let before = try Data(contentsOf: url)

        // As before the first unlock: the file exists but cannot be read.
        try FileManager.default.setAttributes([.posixPermissions: 0o000], ofItemAtPath: url.path)
        let early = Preferences(url: url)
        #expect(!early.isLoaded)
        #expect(early.get("theme", as: String.self) == nil)
        early.set("theme", "light")
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        #expect(try Data(contentsOf: url) == before)

        // Readable again: the file's values come back, the change made meanwhile wins.
        early.reloadIfNeeded()
        #expect(early.isLoaded)
        #expect(early.get("theme", as: String.self) == "light")
        #expect(early.get("kept", as: Int.self) == 7)
        let reread = Preferences(url: url)
        #expect(reread.get("theme", as: String.self) == "light")
        #expect(reread.get("kept", as: Int.self) == 7)
    }

    @Test func missingFileStartsEmptyAndWrites() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("prefs-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let prefs = Preferences(url: url)
        #expect(prefs.isLoaded)
        prefs.set("a", "b")
        #expect(Preferences(url: url).get("a", as: String.self) == "b")
    }
}

/// Transactions hold the shared connection from BEGIN to COMMIT.
@Suite struct EncryptedStoreTransactionTests {
    @Test func concurrentAppendsAndWritesAllLand() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tx-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let failures = Atomic<Int>(0)
        DispatchQueue.concurrentPerform(iterations: 40) { i in
            do {
                // Before: a second BEGIN on the shared connection failed, and other threads'
                // statements landed inside someone else's transaction.
                try store.appendEvents(sessionId: "s\(i % 4)", (0 ..< 5).map { (seq: Int64(i * 10 + $0), json: Data("{\"n\":\(i)}".utf8)) })
                try store.put("docs", id: "d\(i)", ["n": i])
            } catch {
                failures.add(1, ordering: .relaxed)
            }
        }
        #expect(failures.load(ordering: .relaxed) == 0)
        let events = try (0 ..< 4).map { try store.loadEvents(sessionId: "s\($0)").count }.reduce(0, +)
        #expect(events == 200)
        #expect(try store.all("docs", as: [String: Int].self).count == 40)
    }
}

/// Ids from session logs and imports never reach a file path (or a precondition).
@Suite struct StoredIdTests {
    @Test func validation() {
        #expect(StoredId.isValid("0f8fad5b-d9cb-469f-a165-70867728950e"))
        #expect(!StoredId.isValid("../../Library/x"))
        #expect(!StoredId.isValid(""))
        #expect(!StoredId.isValid(String(repeating: "a", count: 65)))
    }

    @Test func attachmentsRejectCraftedIdsWithoutTrapping() {
        let attachments = AttachmentRepository(keys: KeyManager())
        #expect(attachments.bytes("../secrets") == nil)
        #expect(throws: StoredId.Invalid.self) { try attachments.restore("a/b", Data([1])) }
        attachments.delete("../x")
    }

    @MainActor
    @Test func importDropsSessionLinesWithCraftedAttachmentIds() throws {
        let good = try SessionJSON.encode(LogEntry(seq: 0, time: 1, event: .userMessage(text: "ok", images: ["abc-123"])))
        let bad = try SessionJSON.encode(LogEntry(seq: 1, time: 2, event: .userMessage(text: "x", images: ["../../etc"])))
        let plain = try SessionJSON.encode(LogEntry(seq: 2, time: 3, event: .userMessage(text: "hi")))
        let text = [good, bad, plain, Data("not json".utf8)].map { String(decoding: $0, as: UTF8.self) }.joined(separator: "\n")
        #expect(BackupService.importableLines(text).map(\.seq) == [0, 2])
    }
}

/// Small regressions behind crash reports.
@Suite struct CrashGuardTests {
    @Test func repeatedKeysRenderOnce() {
        let items: [SessionItem] = [
            .user(key: "u1", text: "a", images: []),
            .notice(key: "n", text: "x", kind: .info),
            .notice(key: "n", text: "y", kind: .info),
        ]
        #expect(items.uniqueKeys.map(\.key) == ["u1", "n"])
        #expect(Array(items.prefix(2)).uniqueKeys.count == 2)
    }

    @Test func systemSpeechWaitIsBounded() {
        #expect(SystemSpeaker.timeout(for: "") == .seconds(10))
        #expect(SystemSpeaker.timeout(for: String(repeating: "a", count: 50)) == .seconds(20))
    }

    @Test func imageInputNeverDecodesTheFullImage() throws {
        let size = CGSize(width: 4000, height: 3000)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let photo = UIGraphicsImageRenderer(size: size, format: format).jpegData(withCompressionQuality: 0.5) { ctx in
            UIColor.red.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
        let encoded = try #require(ImageInput.encode(photo))
        let source = try #require(CGImageSourceCreateWithData(encoded as CFData, nil))
        let props = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        #expect(props[kCGImagePropertyPixelWidth] as? Int == 1344)
        #expect(props[kCGImagePropertyPixelHeight] as? Int == 1008)
        #expect(ImageInput.encode(Data("not an image".utf8)) == nil)
    }

    @MainActor
    @Test func wipeParticipantThatHangsIsLeftBehind() async {
        let started = ContinuousClock.now
        await PanicWipe.run({ try? await Task.sleep(for: .seconds(30)) }, timeout: .milliseconds(100))
        #expect(ContinuousClock.now - started < .seconds(5))
        var ran = false
        await PanicWipe.run({ ran = true }, timeout: .seconds(5))
        #expect(ran)
    }
}
