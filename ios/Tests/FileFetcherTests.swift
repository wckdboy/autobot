import CryptoKit
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Canned HTTP responses for [FileFetcher] (no sockets; loopback host so Offline allows it).
final class FetchStub: URLProtocol, @unchecked Sendable {
    struct Canned: Sendable {
        let status: Int
        var headers: [String: String] = [:]
        var body = Data()
    }

    nonisolated(unsafe) private static let state = Mutex<(queue: [Canned], requests: [URLRequest])>(([], []))

    static func reset() { state.withLock { $0 = ([], []) } }
    static func enqueue(_ c: Canned) { state.withLock { $0.queue.append(c) } }
    static var requests: [URLRequest] { state.withLock { $0.requests } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let canned = Self.state.withLock { s -> Canned? in
            s.requests.append(request)
            return s.queue.isEmpty ? nil : s.queue.removeFirst()
        }
        guard let canned, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        var headers = canned.headers
        headers["Content-Length"] = "\(canned.body.count)"
        let response = HTTPURLResponse(url: url, statusCode: canned.status, httpVersion: "HTTP/1.1", headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if !canned.body.isEmpty { client?.urlProtocol(self, didLoad: canned.body) }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Port of `FileFetcherTest.kt`.
@Suite(.serialized)
struct FileFetcherTests {
    private let content = Data((0 ..< 300_000).map { UInt8($0 % 251) })
    private var sha: String { SHA256.hash(data: content).map { String(format: "%02x", $0) }.joined() }
    private let dir: URL
    private let fetcher: FileFetcher

    init() throws {
        FetchStub.reset()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("fetch-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        // Offline policy: loopback is allowed, which is all the test needs.
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(capacity: 50, clock: { 0 }), protocolClasses: [FetchStub.self])
        fetcher = FileFetcher(clients: clients)
    }

    private func file(sha256: String? = nil, auth: AuthHost? = nil, size: Int64 = 0) -> ModelFile {
        ModelFile(role: .model, name: "m.gguf", url: "http://127.0.0.1/resolve/main/m.gguf", sizeBytes: size, sha256: sha256, auth: auth)
    }

    @Test(.timeLimit(.minutes(3))) func followsRedirectAdoptsHubHashAndVerifies() async throws {
        FetchStub.enqueue(.init(status: 302, headers: ["Location": "/cdn/blob?sig=1", "X-Linked-ETag": "\"\(sha)\"", "X-Linked-Size": "\(content.count)"]))
        FetchStub.enqueue(.init(status: 200, body: content))
        let target = dir.appendingPathComponent("out.gguf")

        let result = try await fetcher.fetch(file(auth: .huggingFace), to: target, credential: Secret("hf_secret")) { _, _ in }

        #expect(result.sha256 == sha)
        #expect(result.sizeBytes == Int64(content.count))
        #expect(try Data(contentsOf: target) == content)
        #expect(!FileManager.default.fileExists(atPath: target.path + ".part"))
        let requests = FetchStub.requests
        #expect(requests.count == 2)
        // 127.0.0.1 is not huggingface.co: the token must never be sent there.
        #expect(requests[0].value(forHTTPHeaderField: "Authorization") == nil)
        #expect(requests[1].url?.path() == "/cdn/blob")
        #expect(requests[1].url?.query() == "sig=1")
        #expect(requests[1].value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test(.timeLimit(.minutes(3))) func resumesWithRangeFromPartialFile() async throws {
        let target = dir.appendingPathComponent("out.gguf")
        try content.prefix(100_000).write(to: URL(fileURLWithPath: target.path + ".part"))
        FetchStub.enqueue(.init(status: 206, headers: ["Content-Range": "bytes 100000-\(content.count - 1)/\(content.count)"], body: content.suffix(from: 100_000)))

        let result = try await fetcher.fetch(file(sha256: sha, size: Int64(content.count)), to: target, credential: nil) { _, _ in }

        #expect(FetchStub.requests.first?.value(forHTTPHeaderField: "Range") == "bytes=100000-")
        #expect(result.sha256 == sha)
        #expect(try Data(contentsOf: target) == content)
    }

    @Test(.timeLimit(.minutes(3))) func checksumMismatchDeletesTheDownload() async throws {
        FetchStub.enqueue(.init(status: 200, body: content))
        let target = dir.appendingPathComponent("out.gguf")
        do {
            _ = try await fetcher.fetch(file(sha256: String(repeating: "0", count: 64)), to: target, credential: nil) { _, _ in }
            Issue.record("expected a checksum failure")
        } catch let e as DownloadError {
            #expect(e.message.contains("Checksum mismatch"))
        }
        #expect(!FileManager.default.fileExists(atPath: target.path))
        #expect(!FileManager.default.fileExists(atPath: target.path + ".part"))
    }

    @Test(.timeLimit(.minutes(3))) func gatedRepoIsNotRetryable() async throws {
        FetchStub.enqueue(.init(status: 401, headers: ["X-Error-Code": "GatedRepo"], body: Data("restricted".utf8)))
        do {
            _ = try await fetcher.fetch(file(), to: dir.appendingPathComponent("x"), credential: nil) { _, _ in }
            Issue.record("expected a gated failure")
        } catch let e as DownloadError {
            #expect(!e.retryable)
            #expect(e.message.contains("Gated"))
        }
    }

    @Test func tokenGoesOnlyToItsHub() {
        let hub = FileFetcher.request(URL(string: "https://huggingface.co/a/b/resolve/main/x.gguf")!, offset: 0, credential: Secret("hf_x"), auth: .huggingFace)
        #expect(hub.value(forHTTPHeaderField: "Authorization") == "Bearer hf_x")
        let cdn = FileFetcher.request(URL(string: "https://cdn-lfs.huggingface.co/x")!, offset: 0, credential: Secret("hf_x"), auth: .huggingFace)
        #expect(cdn.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func hubHostsAreExact() {
        #expect(FileFetcher.isHubHost("huggingface.co", .huggingFace))
        #expect(!FileFetcher.isHubHost("cdn-lfs.huggingface.co", .huggingFace))
        #expect(!FileFetcher.isHubHost("evilhuggingface.co", .huggingFace))
        #expect(FileFetcher.isHubHost("civitai.red", .civitai))
        #expect(!FileFetcher.isHubHost("civitai-delivery-worker-prod.r2.cloudflarestorage.com", .civitai))
    }

    @Test(.timeLimit(.minutes(3))) func offlineBlocksRemoteDownloads() async throws {
        let remote = ModelFile(role: .model, name: "m.gguf", url: "https://huggingface.co/a/b/resolve/main/m.gguf")
        await #expect(throws: NetworkBlockedError.self) {
            _ = try await fetcher.fetch(remote, to: dir.appendingPathComponent("y"), credential: nil) { _, _ in }
        }
        #expect(FetchStub.requests.isEmpty)
    }
}
