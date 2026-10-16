import CryptoKit
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Serves model files by URL path (Range-aware), fails or stalls on request; no sockets. Paths are
/// unique per test, so suites running in parallel never take each other's answers.
final class ServeStub: URLProtocol, @unchecked Sendable {
    enum Reply: Sendable {
        case body(status: Int, headers: [String: String], data: Data)
        /// Sends the head and [data], then never finishes (a transfer to pause or cancel).
        case stall(Data)
        case fail(URLError.Code)
    }

    typealias Handler = @Sendable (URLRequest) -> Reply

    nonisolated(unsafe) private static let state = Mutex<(routes: [String: Handler], requests: [URLRequest])>(([:], []))

    static func on(_ path: String, _ handler: @escaping Handler) { state.withLock { $0.routes[path] = handler } }
    static func requests(_ path: String) -> [URLRequest] { state.withLock { $0.requests.filter { $0.url?.path() == path } } }
    static func requests(prefix: String) -> [URLRequest] { state.withLock { $0.requests.filter { $0.url?.path().hasPrefix(prefix) == true } } }

    /// [content] honouring `Range` (206) unless [ignoreRange] (always the whole file, 200).
    static func file(_ content: Data, ignoreRange: Bool = false, headers: [String: String] = [:]) -> Handler {
        { request in
            if !ignoreRange, let range = request.value(forHTTPHeaderField: "Range"), range.hasPrefix("bytes="),
               let from = Int(range.dropFirst(6).split(separator: "-").first ?? ""), from > 0, from < content.count {
                return .body(status: 206, headers: headers.merging(["Content-Range": "bytes \(from)-\(content.count - 1)/\(content.count)"]) { a, _ in a },
                             data: content.suffix(from: from))
            }
            return .body(status: 200, headers: headers, data: content)
        }
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let handler = Self.state.withLock { s -> Handler? in
            s.requests.append(request)
            return request.url.flatMap { s.routes[$0.path()] }
        }
        guard let url = request.url, let handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        switch handler(request) {
        case .body(let status, let headers, let data):
            var h = headers
            h["Content-Length"] = "\(data.count)"
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: h)!, cacheStoragePolicy: .notAllowed)
            if !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
            client?.urlProtocolDidFinishLoading(self)
        case .stall(let data):
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "10000000"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
        case .fail(let code):
            client?.urlProtocol(self, didFailWithError: URLError(code))
        }
    }

    override func stopLoading() {}
}

/// Polls [condition] on the main actor until it holds or [timeout] passes.
@MainActor
func eventually(_ timeout: Duration = .seconds(15), _ condition: () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let end = clock.now + timeout
    while clock.now < end {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(20))
    }
    return condition()
}

func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

/**
 The download queue end to end (registry, downloader, fetcher, stubbed server): pause and resume,
 a server that ignores `Range`, a corrupted body, a full disk, a multi-file package where one file
 fails, cancel and pause surviving a relaunch, the background session finishing while the app was
 gone, and Offline mode refusing with an audit entry.
 */
@Suite(.serialized, .timeLimit(.minutes(2)))
@MainActor
struct DownloadReliabilityTests {
    private let dir: URL
    private let store: EncryptedStore
    private let policy = NetworkPolicy()
    private let audit = AuditLog(capacity: 200, clock: { 0 })
    private let clients: HttpClientFactory
    private let accounts: ModelAccounts
    /// Unique per test: the stub's paths and the models' ids.
    private let run = UUID().uuidString.prefix(8).lowercased()

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("dl-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        clients = HttpClientFactory(policy: policy, auditLog: audit, protocolClasses: [ServeStub.self])
        accounts = ModelAccounts(preferences: Preferences(url: dir.appendingPathComponent("prefs.json")), secrets: SecretStore())
    }

    private func content(_ size: Int, seed: Int = 0) -> Data { Data((0 ..< size).map { UInt8(($0 + seed) % 251) }) }

    private func plan(_ name: String, _ files: [ModelFile]) -> ModelPlan {
        ModelPlan(id: "test:\(run)-\(name)", kind: .chat, source: .huggingFace, title: name, subtitle: "", format: .gguf, engine: .llama,
                  manifest: ModelManifest(files: files))
    }

    private func file(_ name: String, _ data: Data, sha: Bool = true, host: String = "127.0.0.1") -> ModelFile {
        ModelFile(role: name.hasPrefix("mmproj") ? .mmproj : .model, name: name, url: "http://\(host)/\(run)/\(name)",
                  sizeBytes: Int64(data.count), sha256: sha ? sha256Hex(data) : nil)
    }

    private func path(_ name: String) -> String { "/\(run)/\(name)" }

    private func makeDownloader(_ library: ModelLibrary) -> ModelDownloader {
        let d = ModelDownloader(library: library, clients: clients, accounts: accounts)
        d.useBackgroundSession = false
        d.freeSpace = { 1 << 40 }
        return d
    }

    private func settled(_ library: ModelLibrary, _ id: String, _ downloader: ModelDownloader) async -> InstalledModel? {
        _ = await eventually { !downloader.busy && (library.get(id).map { [.ready, .paused, .failed].contains($0.status) } ?? false) }
        return library.get(id)
    }

    // MARK: Pause, resume, Range

    @Test func pauseKeepsThePartFileAndResumeContinuesWithRange() async throws {
        let data = content(600_000)
        let f = file("m.gguf", data)
        let stalled = Mutex(true)
        ServeStub.on(path("m.gguf")) { request in
            if stalled.withLock({ $0 }) { return .stall(Data(data.prefix(1000))) }
            return ServeStub.file(data)(request)
        }
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        let p = plan("pause", [f])
        downloader.install(p)
        #expect(await eventually { library.get(p.id)?.status == .downloading && !ServeStub.requests(path("m.gguf")).isEmpty })
        try await Task.sleep(for: .milliseconds(200))

        downloader.pause(p.id)
        let paused = await settled(library, p.id, downloader)
        #expect(paused?.status == .paused)
        #expect(paused?.error == nil)
        let part = URL(fileURLWithPath: ModelLibrary.file(of: p.id, f).path + ".part")
        #expect(FileFetcher.size(part) == 1000)
        #expect(paused?.downloadedBytes == 1000)

        stalled.withLock { $0 = false }
        downloader.resume(p.id)
        let done = await settled(library, p.id, downloader)
        #expect(done?.status == .ready)
        #expect(ServeStub.requests(path("m.gguf")).last?.value(forHTTPHeaderField: "Range") == "bytes=1000-")
        #expect(try Data(contentsOf: ModelLibrary.file(of: p.id, f)) == data)
        library.remove(p.id)
    }

    @Test func serverIgnoringRangeRestartsTheFileInsteadOfAppending() async throws {
        let data = content(300_000, seed: 3)
        let f = file("whole.gguf", data)
        ServeStub.on(path("whole.gguf"), ServeStub.file(data, ignoreRange: true))
        let target = dir.appendingPathComponent("whole.gguf")
        // A part file from an earlier attempt: appending a full body to it would corrupt the model.
        try data.prefix(120_000).write(to: URL(fileURLWithPath: target.path + ".part"))

        let result = try await FileFetcher(clients: clients).fetch(f, to: target, credential: nil) { _, _ in }

        #expect(ServeStub.requests(path("whole.gguf")).first?.value(forHTTPHeaderField: "Range") == "bytes=120000-")
        #expect(result.sha256 == sha256Hex(data))
        #expect(try Data(contentsOf: target) == data)
    }

    @Test func corruptedBodyIsDeletedAndTheModelWaitsForARetry() async throws {
        let data = content(200_000, seed: 5)
        var corrupted = data
        corrupted[1234] ^= 0xFF
        let bad = corrupted
        let f = file("bad.gguf", data)
        ServeStub.on(path("bad.gguf")) { _ in .body(status: 200, headers: [:], data: bad) }
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        let p = plan("hash", [f])
        downloader.install(p)
        let model = await settled(library, p.id, downloader)

        #expect(model?.status == .paused)
        #expect(model?.error?.contains("Checksum mismatch") == true)
        let target = ModelLibrary.file(of: p.id, f)
        #expect(!FileManager.default.fileExists(atPath: target.path))
        #expect(!FileManager.default.fileExists(atPath: target.path + ".part"))

        ServeStub.on(path("bad.gguf"), ServeStub.file(data))
        downloader.resume(p.id)
        #expect(await settled(library, p.id, downloader)?.status == .ready)
        library.remove(p.id)
    }

    // MARK: Disk full

    @Test func notEnoughSpaceStopsBeforeAnyRequestWithTheStorageReason() async throws {
        let data = content(50_000)
        let f = ModelFile(role: .model, name: "big.gguf", url: "http://127.0.0.1\(path("big.gguf"))", sizeBytes: 4_000_000_000, sha256: sha256Hex(data))
        ServeStub.on(path("big.gguf"), ServeStub.file(data))
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        downloader.freeSpace = { 300_000_000 }
        let p = plan("full", [f])
        downloader.install(p)
        let model = await settled(library, p.id, downloader)

        #expect(model?.status == .paused)
        #expect(model.flatMap(DownloadQueue.pausedReason) == .lowStorage)
        #expect(ServeStub.requests(path("big.gguf")).isEmpty)
        let state = DownloadQueue.state(library.models.filter { $0.id == p.id }, progress: nil, networkOff: false, connected: true)
        #expect(state.blocks == [.lowStorage])
        library.remove(p.id)
    }

    @Test func aDiskFullWriteIsReportedAsStorage() {
        #expect(ModelDownloader.isOutOfSpace(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError)))
        #expect(ModelDownloader.isOutOfSpace(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))))
        let wrapped = NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError, userInfo: [NSUnderlyingErrorKey: NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))])
        #expect(ModelDownloader.isOutOfSpace(wrapped))
        #expect(!ModelDownloader.isOutOfSpace(URLError(.timedOut)))
    }

    // MARK: Multi-file packages

    @Test func aFailingFileOfAPackageKeepsTheFinishedOnesForTheResume() async throws {
        let weights = content(400_000, seed: 1)
        let projector = content(90_000, seed: 2)
        // No hash up front for the first file: the Hub's `X-Linked-ETag` gives it, and it is kept at
        // once so the resume after the second file failed does not fetch the first again.
        let a = file("weights.gguf", weights, sha: false)
        let b = file("mmproj.gguf", projector)
        ServeStub.on(path("weights.gguf"), ServeStub.file(weights, headers: ["X-Linked-ETag": "\"\(sha256Hex(weights))\""]))
        let failing = Mutex(true)
        ServeStub.on(path("mmproj.gguf")) { request in
            failing.withLock { $0 } ? .body(status: 503, headers: [:], data: Data("busy".utf8)) : ServeStub.file(projector)(request)
        }
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        let p = plan("pkg", [a, b])
        downloader.install(p)
        let failed = await settled(library, p.id, downloader)

        #expect(failed?.status == .paused)
        #expect(failed?.error != nil)
        #expect(FileManager.default.fileExists(atPath: ModelLibrary.file(of: p.id, a).path))
        #expect(failed?.manifest.files.first?.sha256 == sha256Hex(weights))
        #expect(ServeStub.requests(path("weights.gguf")).count == 1)

        failing.withLock { $0 = false }
        downloader.resume(p.id)
        let done = await settled(library, p.id, downloader)
        #expect(done?.status == .ready)
        #expect(ServeStub.requests(path("weights.gguf")).count == 1)
        #expect(done?.manifest.files.first?.sha256 == sha256Hex(weights))
        #expect(done?.totalBytes == Int64(weights.count + projector.count))
        #expect(try Data(contentsOf: ModelLibrary.file(of: p.id, b)) == projector)
        library.remove(p.id)
    }

    // MARK: Relaunch

    @Test func cancelAndPauseSurviveARelaunch() async throws {
        let data = content(300_000, seed: 9)
        let fc = file("cancel.gguf", data)
        let fp = file("paused.gguf", data)
        ServeStub.on(path("cancel.gguf")) { _ in .stall(Data(data.prefix(500))) }
        ServeStub.on(path("paused.gguf")) { _ in .stall(Data(data.prefix(700))) }
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        let cancelled = plan("cancelled", [fc])
        let paused = plan("paused", [fp])
        downloader.install(cancelled)
        #expect(await eventually { library.get(cancelled.id)?.status == .downloading })
        downloader.remove(cancelled.id)
        downloader.install(paused)
        #expect(await eventually { library.get(paused.id)?.status == .downloading && !ServeStub.requests(path("paused.gguf")).isEmpty })
        try await Task.sleep(for: .milliseconds(150))
        downloader.pause(paused.id)
        _ = await settled(library, paused.id, downloader)
        #expect(!FileManager.default.fileExists(atPath: ModelLibrary.dir(of: cancelled.id).path))

        // A new process: the registry from the store, a fresh downloader recovering.
        let relaunched = ModelLibrary(store: store)
        relaunched.load()
        let after = makeDownloader(relaunched)
        after.recoverAfterRestart()
        try await Task.sleep(for: .milliseconds(300))
        #expect(relaunched.get(cancelled.id) == nil)
        #expect(!FileManager.default.fileExists(atPath: ModelLibrary.dir(of: cancelled.id).path))
        #expect(relaunched.get(paused.id)?.status == .paused)
        #expect(relaunched.get(paused.id)?.downloadedBytes == 700)
        #expect(!after.busy)

        ServeStub.on(path("paused.gguf"), ServeStub.file(data))
        after.resume(paused.id)
        #expect(await settled(relaunched, paused.id, after)?.status == .ready)
        #expect(ServeStub.requests(path("paused.gguf")).last?.value(forHTTPHeaderField: "Range") == "bytes=700-")
        relaunched.remove(paused.id)
    }

    @Test func aDownloadRunningAtTheKillQueuesAgainAndResumes() async throws {
        let data = content(250_000, seed: 4)
        let f = file("crash.gguf", data)
        ServeStub.on(path("crash.gguf"), ServeStub.file(data))
        let library = ModelLibrary(store: store)
        let p = plan("crash", [f])
        library.add(p)
        library.progress(p.id, downloaded: 0, status: .downloading)
        let part = URL(fileURLWithPath: ModelLibrary.file(of: p.id, f).path + ".part")
        try FileManager.default.createDirectory(at: part.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.prefix(80_000).write(to: part)

        let relaunched = ModelLibrary(store: store)
        relaunched.load()
        let downloader = makeDownloader(relaunched)
        downloader.recoverAfterRestart()
        let done = await settled(relaunched, p.id, downloader)
        #expect(done?.status == .ready)
        #expect(ServeStub.requests(path("crash.gguf")).first?.value(forHTTPHeaderField: "Range") == "bytes=80000-")
        relaunched.remove(p.id)
    }

    // MARK: Background session

    /// A download task as the system hands it to the delegate after relaunching the app.
    private final class RelaunchedTask: URLSessionDownloadTask {
        private let request: URLRequest
        private let reply: HTTPURLResponse
        private let identifier: Int

        init(request: URLRequest, reply: HTTPURLResponse, identifier: Int, tag: String) {
            self.request = request
            self.reply = reply
            self.identifier = identifier
            super.init()
            taskDescription = tag
        }

        override var originalRequest: URLRequest? { request }
        override var currentRequest: URLRequest? { request }
        override var response: URLResponse? { reply }
        override var taskIdentifier: Int { identifier }
        override var state: URLSessionTask.State { .completed }
        override func cancel() {}
        override func resume() {}
    }

    @Test func aBodyFinishedWhileTheAppWasGoneIsStoredByTheNextLaunch() async throws {
        let data = content(220_000, seed: 7)
        let f = file("bg.gguf", data)
        let target = dir.appendingPathComponent("bg.gguf")
        let part = URL(fileURLWithPath: target.path + ".part")
        let resume = TransferResume.file(for: part)
        let url = URL(string: f.url)!
        // Delivered to a process with no fetch waiting: the earlier one was terminated.
        let transfer = BackgroundTransfer(makeConfiguration: { _ in
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StallStub.self]
            return config
        }, routes: nil)
        let session = URLSession(configuration: .ephemeral)
        let task = RelaunchedTask(request: URLRequest(url: url), reply: HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!,
                                  identifier: 41, tag: TransferResume.tag(resume))
        let delivered = dir.appendingPathComponent("system-tmp")
        try data.write(to: delivered)
        transfer.urlSession(session, downloadTask: task, didFinishDownloadingTo: delivered)
        transfer.urlSession(session, task: task, didCompleteWithError: nil)
        #expect(FileManager.default.fileExists(atPath: CompletedTransfer.body(resume).path))

        // The completion handler goes back to iOS only once the downloader settled.
        let events = BackgroundEvents()
        events.timeout = .seconds(30)
        let settled = Mutex(false)
        events.settled = { settled.withLock { $0 } }
        let called = Mutex(0)
        events.handle("ai.wckd.autobot.models.direct") { called.withLock { $0 += 1 } }
        events.didFinishEvents("ai.wckd.autobot.models.direct")
        #expect(called.withLock { $0 } == 0)

        // The next fetch of that part file takes the kept body: no new transfer, verified and stored.
        ServeStub.on(path("bg.gguf"), ServeStub.file(data))
        let result = try await FileFetcher(clients: clients, bulk: transfer).fetch(f, to: target, credential: nil) { _, _ in }
        #expect(result.sha256 == sha256Hex(data))
        #expect(try Data(contentsOf: target) == data)
        #expect(!FileManager.default.fileExists(atPath: CompletedTransfer.body(resume).path))

        settled.withLock { $0 = true }
        events.check()
        #expect(called.withLock { $0 } == 1)
    }

    @Test func aTransferCancelledBeforeItStartsReturnsAtOnce() async throws {
        let transfer = BackgroundTransfer(makeConfiguration: { _ in
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StallStub.self]
            return config
        }, routes: nil)
        for _ in 0 ..< 20 {
            let job = Task {
                try await transfer.download(URLRequest(url: URL(string: "http://127.0.0.1/never")!), proxy: .none, resume: nil, vetHop: { $0 }) { _ in }
            }
            job.cancel()
            await #expect(throws: (any Error).self) { try await job.value }
        }
        #expect(!transfer.hasActiveTransfer)
    }

    // MARK: Offline mode

    @Test func offlineModeBlocksTheDownloadWithAnAuditEntryAndResumesWhenAllowed() async throws {
        let data = content(120_000, seed: 8)
        let f = ModelFile(role: .model, name: "hub.gguf", url: "https://huggingface.co/\(run)/hub.gguf", sizeBytes: Int64(data.count), sha256: sha256Hex(data))
        ServeStub.on("/\(run)/hub.gguf", ServeStub.file(data))
        #expect(policy.mode == .offline)
        let library = ModelLibrary(store: store)
        let downloader = makeDownloader(library)
        let p = plan("offline", [f])
        downloader.install(p)
        let blocked = await settled(library, p.id, downloader)

        #expect(blocked?.status == .paused)
        #expect(blocked?.error == DownloadQueue.killSwitchError)
        #expect(blocked.flatMap(DownloadQueue.pausedReason) == .killSwitch)
        #expect(ServeStub.requests(prefix: "/\(run)/hub").isEmpty)
        let entry = audit.entries.last { $0.host == "huggingface.co" }
        #expect(entry?.blocked == true)
        #expect(entry?.tag == "models:download")
        #expect(entry?.path == "/\(run)/hub.gguf")
        let state = DownloadQueue.state(library.models.filter { $0.id == p.id }, progress: nil, networkOff: true, connected: true)
        #expect(state.blocks == [.killSwitch])

        policy.setMode(.direct)
        downloader.networkChanged(offline: false)
        #expect(await settled(library, p.id, downloader)?.status == .ready)
        #expect(audit.entries.contains { $0.host == "huggingface.co" && !$0.blocked && $0.status == 200 })
        library.remove(p.id)
    }
}
