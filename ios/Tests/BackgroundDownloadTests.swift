import CryptoKit
import Foundation
import Testing
@testable import Autobot

@MainActor private final class Flag { var on = false }

/// The download queue's rules, the background relaunch hand-off and force-quit recovery.
@Suite(.serialized)
@MainActor
struct BackgroundDownloadTests {
    private let dir: URL

    init() throws {
        FetchStub.reset()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("bg-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func model(_ id: String, _ status: ModelStatus, _ addedAt: Int64, error: String? = nil) -> InstalledModel {
        InstalledModel(
            id: id, kind: .chat, source: .catalog, title: id, subtitle: "", format: .gguf, engine: .llama, baseModel: nil, license: nil,
            nsfw: false, totalBytes: 4_000_000_000, downloadedBytes: 0, status: status, error: error,
            manifest: ModelManifest(files: []), addedAt: addedAt, updatedAt: 0,
        )
    }

    // MARK: Queue

    @Test func queueIsTheRegistryOldestFirst() {
        let rows = [model("c", .queued, 30), model("r", .ready, 1), model("a", .queued, 10), model("p", .paused, 5), model("b", .queued, 20)]
        #expect(DownloadQueue.queued(rows).map(\.id) == ["a", "b", "c"])
        #expect(DownloadQueue.queued(rows.reversed()).map(\.id) == ["a", "b", "c"])
        #expect(DownloadQueue.next(rows)?.id == "a")
    }

    @Test func interruptedDownloadsQueueAgainAfterARelaunch() {
        let rows = [model("running", .downloading, 10), model("next", .queued, 20), model("paused", .paused, 5)]
        let recovered = DownloadQueue.recover(rows)
        #expect(recovered.map(\.id) == ["running"])
        let after = rows.map { m in recovered.contains(m) ? { var q = m; q.status = .queued; return q }() : m }
        #expect(DownloadQueue.queued(after).map(\.id) == ["running", "next"])
        #expect(after.first { $0.id == "paused" }?.status == .paused)
    }

    @Test func blocksAndReasons() {
        #expect(DownloadQueue.block(networkOff: true, connected: false) == .killSwitch)
        #expect(DownloadQueue.block(networkOff: false, connected: false) == .noNetwork)
        #expect(DownloadQueue.block(networkOff: false, connected: true) == nil)
        let killed = model("k", .paused, 1, error: DownloadQueue.killSwitchError)
        #expect(DownloadQueue.pausedReason(killed) == .killSwitch)
        #expect(DownloadQueue.pausedReason(model("s", .paused, 1, error: "Not enough storage: needs 4 GB")) == .lowStorage)
        #expect(DownloadQueue.blockedByKillSwitch([killed, model("u", .paused, 2)]).map(\.id) == ["k"])
        let state = DownloadQueue.state([model("q", .queued, 1)], progress: nil, networkOff: false, connected: false)
        #expect(state.block == .noNetwork)
        #expect(DownloadQueue.state([], progress: nil, networkOff: true, connected: true).isEmpty)
    }

    @Test func etaAndSpeed() {
        #expect(DownloadQueue.eta(remaining: 101, bytesPerSecond: 10) == 11)
        #expect(DownloadQueue.eta(remaining: 100, bytesPerSecond: 0) == nil)
        #expect(DownloadQueue.formatEta(45) == "45 s")
        #expect(DownloadQueue.formatEta(61) == "2 min")
        #expect(DownloadQueue.formatEta(3900) == "1 h 05 min")
        #expect(DownloadQueue.smooth(100, 200) == 130)
        #expect(DownloadProgress(id: "a", title: "a", downloaded: 1000, total: 4000, bytesPerSecond: 100).etaSeconds == 30)
    }

    // MARK: Background relaunch

    @Test func completionHandlerWaitsForEventsAndTheDownloader() async {
        let events = BackgroundEvents()
        let settled = Flag()
        events.settled = { settled.on }
        var called = 0
        events.handle("ai.wckd.autobot.models.direct") { called += 1 }
        events.check()
        #expect(called == 0) // events not delivered yet
        events.didFinishEvents("ai.wckd.autobot.models.direct")
        #expect(called == 0) // the finished file is still being verified
        settled.on = true // the next file is transferring
        events.check()
        #expect(called == 1)
        events.check()
        #expect(called == 1)
        #expect(events.pending == 0)
    }

    @Test func completionHandlerIsCalledAtTheDeadlineAnyway() async throws {
        let events = BackgroundEvents()
        events.settled = { false }
        events.timeout = .milliseconds(50)
        var called = false
        events.handle("ai.wckd.autobot.models.direct") { called = true }
        try await Task.sleep(for: .milliseconds(300))
        #expect(called)
    }

    /// A body that finished while no fetch waited (the app was relaunched in the background, or
    /// the device was locked) is verified and stored by the next fetch, with no new transfer.
    @Test(.timeLimit(.minutes(1)))
    func bodyFinishedInTheBackgroundIsVerifiedAndStored() async throws {
        let content = Data((0 ..< 200_000).map { UInt8($0 % 251) })
        let sha = SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()
        let target = dir.appendingPathComponent("bg.gguf")
        let part = URL(fileURLWithPath: target.path + ".part")
        let resume = TransferResume.file(for: part)
        let body = dir.appendingPathComponent("transfer-body")
        try content.write(to: body)
        let url = "http://127.0.0.1/bg.gguf"
        #expect(CompletedTransfer.keep(body, record: CompletedTransfer(resource: TransferResume.resource(URL(string: url))!, range: nil, status: 200), at: resume))

        FetchStub.enqueue(.init(status: 200, headers: [:], body: content))
        // The session would stall forever: the kept body must be used instead.
        let transfer = BackgroundTransfer(makeConfiguration: { _ in
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StallStub.self]
            return config
        }, routes: nil)
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(capacity: 50, clock: { 0 }), protocolClasses: [FetchStub.self])
        let file = ModelFile(role: .model, name: "bg.gguf", url: url, sizeBytes: Int64(content.count), sha256: sha)
        let result = try await FileFetcher(clients: clients, bulk: transfer).fetch(file, to: target, credential: nil) { _, _ in }

        #expect(result.sha256 == sha)
        #expect(try Data(contentsOf: target) == content)
        #expect(!FileManager.default.fileExists(atPath: CompletedTransfer.body(resume).path))
        #expect(!FileManager.default.fileExists(atPath: CompletedTransfer.record(resume).path))
    }

    @Test func keptBodyOnlyAnswersTheSameResourceAndRange() throws {
        let resume = TransferResume.file(for: dir.appendingPathComponent("x.part"))
        let body = dir.appendingPathComponent("b1")
        try Data("x".utf8).write(to: body)
        let record = CompletedTransfer(resource: "https://cdn.example.org:443/x", range: "bytes=10-", status: 206)
        #expect(CompletedTransfer.keep(body, record: record, at: resume))
        var other = URLRequest(url: URL(string: "https://cdn.example.org/x?sig=2")!)
        other.setValue("bytes=20-", forHTTPHeaderField: "Range")
        // Another range: discarded (the part file moved on).
        #expect(CompletedTransfer.take(resume, matching: other) == nil)
        #expect(!FileManager.default.fileExists(atPath: CompletedTransfer.body(resume).path))

        try Data("x".utf8).write(to: body)
        #expect(CompletedTransfer.keep(body, record: record, at: resume))
        var same = URLRequest(url: URL(string: "https://cdn.example.org/x?sig=3")!)
        same.setValue("bytes=10-", forHTTPHeaderField: "Range")
        let taken = CompletedTransfer.take(resume, matching: same)
        #expect(taken?.1.statusCode == 206)
        if let file = taken?.0 { try? FileManager.default.removeItem(at: file) }
    }

    // MARK: Force quit

    @Test func forceQuitIsRecognisedAndExplainedOnce() throws {
        let defaults = try #require(UserDefaults(suiteName: "force-quit-\(UUID().uuidString)"))
        let error = NSError(domain: NSURLErrorDomain, code: NSURLErrorCancelled, userInfo: [
            NSURLErrorBackgroundTaskCancelledReasonKey: NSURLErrorCancelledReasonUserForceQuitApplication,
        ])
        #expect(ForceQuitNotice.isForceQuit(error))
        #expect(!ForceQuitNotice.isForceQuit(URLError(.cancelled)))
        #expect(!ForceQuitNotice.isForceQuit(nil))
        #expect(!ForceQuitNotice.shouldShow(defaults))
        ForceQuitNotice.record(defaults)
        #expect(ForceQuitNotice.shouldShow(defaults))
        ForceQuitNotice.dismiss(defaults)
        ForceQuitNotice.record(defaults)
        #expect(!ForceQuitNotice.shouldShow(defaults))
    }
}
