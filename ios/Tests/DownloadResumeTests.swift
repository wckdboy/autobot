import CryptoKit
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// A [BulkTransfer] that hands back canned bodies and records what it was asked for.
private final class CannedBulk: BulkTransfer, @unchecked Sendable {
    let body: Data
    let status: Int
    let asked = Mutex<[(request: URLRequest, resume: URL?)]>([])

    init(body: Data, status: Int) {
        self.body = body
        self.status = status
    }

    func download(
        _ request: URLRequest,
        proxy _: Proxy,
        resume: URL?,
        vetHop _: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse) {
        asked.withLock { $0.append((request, resume)) }
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("bulk-\(UUID().uuidString)")
        try body.write(to: tmp)
        progress(Int64(body.count))
        return (tmp, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!)
    }
}

/// Serves a response head and part of a body, then never finishes (a transfer to pause).
final class StallStub: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "1000000"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(repeating: 7, count: 1000))
    }

    override func stopLoading() {}
}

/// Pausing keeps what a background transfer received; the next one continues from it.
@Suite(.serialized)
struct DownloadResumeTests {
    private let dir: URL

    init() throws {
        FetchStub.reset()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("resume-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func request(_ url: String, range: String? = "bytes=100-", auth: Bool = false) -> URLRequest {
        var r = URLRequest(url: URL(string: url)!)
        r.setValue(range, forHTTPHeaderField: "Range")
        if auth { r.setValue("Bearer hf_secret", forHTTPHeaderField: "Authorization") }
        return r
    }

    /// Resume data as the system writes it (a property list with the received byte count).
    private func resumeData(bytes: Int64) throws -> Data {
        try PropertyListSerialization.data(fromPropertyList: ["NSURLSessionResumeBytesReceived": bytes, "NSURLSessionResumeInfoVersion": 2], format: .binary, options: 0)
    }

    @Test func bulkBodiesAppendOnlyAsTheContinuationOfThePartFile() {
        #expect(FileFetcher.bulkAppends(status: 206, have: 100) == true)
        // A resumed transfer from 0 reports 206 for its last leg: it is still the whole file.
        #expect(FileFetcher.bulkAppends(status: 206, have: 0) == false)
        // The range was ignored: the body is the whole file.
        #expect(FileFetcher.bulkAppends(status: 200, have: 100) == false)
        #expect(FileFetcher.bulkAppends(status: 403, have: 100) == nil)
        #expect(FileFetcher.bulkAppends(status: 416, have: 100) == nil)
    }

    @Test func resumeDataIsKeptBesideThePartFileAndMatchesTheResourceNotItsSignature() throws {
        let part = dir.appendingPathComponent("m.gguf.part")
        let file = TransferResume.file(for: part)
        TransferResume.keep(try resumeData(bytes: 4096), url: URL(string: "https://cdn.example.org/blob?sig=old"), range: "bytes=100-", at: file, persist: true)
        #expect(FileManager.default.fileExists(atPath: file.path))
        #expect(TransferResume.bytes(under: dir) == 4096)

        // Re-resolved: a new signature, the same resource and range.
        let taken = TransferResume.take(file, matching: request("https://cdn.example.org/blob?sig=new"))
        #expect(taken?.bytes == 4096)
        #expect(taken?.url == "https://cdn.example.org/blob?sig=old")
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(TransferResume.bytes(under: dir) == 0)
    }

    @Test func staleResumeDataIsDropped() throws {
        let file = TransferResume.file(for: dir.appendingPathComponent("a.part"))
        TransferResume.keep(try resumeData(bytes: 10), url: URL(string: "https://cdn.example.org/blob"), range: "bytes=100-", at: file, persist: true)
        // The part file grew since: another range.
        #expect(TransferResume.take(file, matching: request("https://cdn.example.org/blob", range: "bytes=200-")) == nil)
        TransferResume.keep(try resumeData(bytes: 10), url: URL(string: "https://cdn.example.org/blob"), range: "bytes=100-", at: file, persist: true)
        #expect(TransferResume.take(file, matching: request("https://other.example.org/blob")) == nil)
        #expect(TransferResume.bytes(under: dir) == 0)
    }

    @Test func resumeDataOfACredentialedRequestStaysInMemory() throws {
        let file = TransferResume.file(for: dir.appendingPathComponent("b.part"))
        TransferResume.keep(try resumeData(bytes: 77), url: URL(string: "https://huggingface.co/a/resolve/main/b"), range: nil, at: file, persist: false)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(TransferResume.bytes(under: dir) == 77)
        #expect(TransferResume.take(file, matching: request("https://huggingface.co/a/resolve/main/b", range: nil))?.bytes == 77)
    }

    @Test func noResumeDataKeepsNothing() {
        let file = TransferResume.file(for: dir.appendingPathComponent("c.part"))
        TransferResume.keep(nil, url: URL(string: "https://cdn.example.org/c"), range: nil, at: file, persist: true)
        #expect(TransferResume.bytes(under: dir) == 0)
        #expect(TransferResume.receivedBytes(Data("not a plist".utf8)) == 0)
    }

    @Test func pausedModelReportsTheBytesItKeeps() {
        var model = InstalledModel(
            id: "m", kind: .chat, source: .catalog, title: "M", subtitle: "", format: .gguf, engine: .llama, baseModel: nil, license: nil,
            nsfw: false, totalBytes: 4_000_000_000, downloadedBytes: 1_200_000_000, status: .paused, error: nil,
            manifest: ModelManifest(files: []), addedAt: 0, updatedAt: 0,
        )
        #expect(model.pausedLabel?.hasPrefix("Paused at ") == true)
        #expect(model.pausedLabel?.contains(" of ") == true)
        model.status = .downloading
        #expect(model.pausedLabel == nil)
    }

    @Test func routesRoundTrip() {
        #expect(BackgroundTransfer.proxy(route: BackgroundTransfer.route(.none)) == Proxy.none)
        let tor = Proxy.socks(host: "127.0.0.1", port: 9050)
        #expect(BackgroundTransfer.proxy(route: BackgroundTransfer.route(tor)) == tor)
        let named = Proxy.socks(host: "my-proxy.lan", port: 1080)
        #expect(BackgroundTransfer.proxy(route: BackgroundTransfer.route(named)) == named)
        #expect(BackgroundTransfer.proxy(route: "socks--1") == nil)
        #expect(BackgroundTransfer.proxy(route: "garbage") == nil)
    }

    @Test func bulkTransferContinuesThePartFileAndVerifies() async throws {
        let content = Data((0 ..< 300_000).map { UInt8($0 % 251) })
        let sha = SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined()
        let target = dir.appendingPathComponent("out.gguf")
        let part = URL(fileURLWithPath: target.path + ".part")
        try content.prefix(100_000).write(to: part)
        FetchStub.enqueue(.init(status: 206, headers: ["Content-Range": "bytes 100000-299999/300000"], body: content.suffix(from: 100_000)))
        let bulk = CannedBulk(body: content.suffix(from: 100_000), status: 206)
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(capacity: 50, clock: { 0 }), protocolClasses: [FetchStub.self])
        let fetcher = FileFetcher(clients: clients, bulk: bulk)

        let file = ModelFile(role: .model, name: "m.gguf", url: "http://127.0.0.1/m.gguf", sizeBytes: Int64(content.count), sha256: sha)
        let result = try await fetcher.fetch(file, to: target, credential: nil) { _, _ in }

        #expect(result.sha256 == sha)
        #expect(try Data(contentsOf: target) == content)
        let asked = bulk.asked.withLock { $0 }
        #expect(asked.count == 1)
        #expect(asked.first?.request.value(forHTTPHeaderField: "Range") == "bytes=100000-")
        #expect(asked.first?.resume == TransferResume.file(for: part))
    }

    @Test(.timeLimit(.minutes(1)))
    func pausingABackgroundTransferEndsItWithoutLosingThePartFile() async throws {
        let transfer = BackgroundTransfer(makeConfiguration: { _ in
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [StallStub.self]
            return config
        }, routes: nil)
        let part = dir.appendingPathComponent("p.gguf.part")
        try Data(repeating: 1, count: 100).write(to: part)
        let job = Task {
            try await transfer.download(request("http://127.0.0.1/p.gguf"), proxy: .none, resume: TransferResume.file(for: part), vetHop: { $0 }) { _ in }
        }
        try await Task.sleep(for: .milliseconds(300))
        job.cancel()
        let ended = await job.result
        #expect(throws: (any Error).self) { try ended.get() }
        #expect(FileFetcher.size(part) == 100)
    }
}
