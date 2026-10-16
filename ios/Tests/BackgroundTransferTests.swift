import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Serves `/start` as a 302 to [target] (through URLSession's redirect machinery, so the task
/// delegate is asked) and anything else as a small body.
final class RedirectStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) private static let state = Mutex<(target: String, seen: [URLRequest])>(("", []))

    static func reset(target: String) { state.withLock { $0 = (target, []) } }
    static var seen: [URLRequest] { state.withLock { $0.seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let target = Self.state.withLock { s -> String in
            s.seen.append(request)
            return s.target
        }
        if url.path() == "/start", let next = URL(string: target) {
            let response = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": target])!
            var redirected = request
            redirected.url = next
            client?.urlProtocol(self, wasRedirectedTo: redirected, redirectResponse: response)
            return
        }
        let body = Data("model-bytes".utf8)
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "\(body.count)"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Every redirect a handed-off (background) transfer meets passes the kill switch, the route
/// check and the audit log, and hub credentials never reach another host.
@Suite(.serialized)
struct BackgroundTransferTests {
    private func transfer() -> BackgroundTransfer {
        BackgroundTransfer { _ in
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [RedirectStub.self]
            return config
        }
    }

    private func start(_ client: HttpClient, auth: AuthHost?) throws -> (URLRequest, Proxy) {
        var request = URLRequest(url: URL(string: "http://127.0.0.1/start")!)
        request.setValue("Bearer hf_secret", forHTTPHeaderField: "Authorization")
        return try client.admitForTransfer(request)
    }

    @Test(.timeLimit(.minutes(3))) func allowedHopIsAuditedAndDropsCredentialsForAnotherHost() async throws {
        RedirectStub.reset(target: "http://localhost/cdn/blob")
        let audit = AuditLog(capacity: 50, clock: { 0 })
        let client = HttpClientFactory(policy: NetworkPolicy(), auditLog: audit).create(tag: "models:download")
        let (request, proxy) = try start(client, auth: .huggingFace)
        let original = request.url!

        let (file, response) = try await transfer().download(request, proxy: proxy, vetHop: { hop in
            try vetTransferHop(hop, client: client, proxy: proxy, original: original, auth: .huggingFace)
        }) { _ in }
        defer { try? FileManager.default.removeItem(at: file) }

        #expect(response.statusCode == 200)
        #expect(try Data(contentsOf: file) == Data("model-bytes".utf8))
        let hop = RedirectStub.seen.last
        #expect(hop?.url?.host() == "localhost")
        #expect(hop?.value(forHTTPHeaderField: "Authorization") == nil)
        #expect(hop?.value(forHTTPHeaderField: "User-Agent") == HeaderScrub.userAgent)
        let logged = audit.entries.filter { $0.host == "localhost" }
        #expect(logged.count == 1)
        #expect(logged.first?.blocked == false)
        #expect(logged.first?.path == "/cdn/blob")
    }

    @Test(.timeLimit(.minutes(3))) func hopToRemoteHostIsBlockedWhileOffline() async throws {
        RedirectStub.reset(target: "https://cdn.example.invalid/blob?sig=1")
        let audit = AuditLog(capacity: 50, clock: { 0 })
        let client = HttpClientFactory(policy: NetworkPolicy(), auditLog: audit).create(tag: "models:download")
        let (request, proxy) = try start(client, auth: nil)
        let original = request.url!

        await #expect(throws: NetworkBlockedError.self) {
            _ = try await transfer().download(request, proxy: proxy, vetHop: { hop in
                try vetTransferHop(hop, client: client, proxy: proxy, original: original, auth: nil)
            }) { _ in }
        }
        // The stub never saw the remote host: the hop was refused before any I/O.
        #expect(RedirectStub.seen.allSatisfy { $0.url?.host() == "127.0.0.1" })
        let blocked = audit.entries.first { $0.host == "cdn.example.invalid" }
        #expect(blocked?.blocked == true)
        #expect(blocked?.path == "/blob")
    }

    @Test func credentialStaysOnlyOnItsOwnHubHost() throws {
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let client = HttpClientFactory(policy: policy, auditLog: AuditLog(capacity: 10, clock: { 0 })).create(tag: "t")
        let original = URL(string: "https://huggingface.co/a/resolve/main/m.gguf")!
        func hop(_ to: String) -> URLRequest {
            var r = URLRequest(url: URL(string: to)!)
            r.setValue("Bearer hf_secret", forHTTPHeaderField: "Authorization")
            return r
        }
        let same = try vetTransferHop(hop("https://huggingface.co/b/resolve/main/m.gguf"), client: client, proxy: .none, original: original, auth: .huggingFace)
        #expect(same.value(forHTTPHeaderField: "Authorization") == "Bearer hf_secret")
        let cdn = try vetTransferHop(hop("https://cdn-lfs.huggingface.co/x"), client: client, proxy: .none, original: original, auth: .huggingFace)
        #expect(cdn.value(forHTTPHeaderField: "Authorization") == nil)
        let otherHub = try vetTransferHop(hop("https://civitai.com/x"), client: client, proxy: .none, original: original, auth: .civitai)
        #expect(otherHub.value(forHTTPHeaderField: "Authorization") == nil)
    }

    @Test func hopNeedingAnotherRouteIsBlocked() throws {
        let policy = NetworkPolicy()
        policy.setMode(.tor())
        let client = HttpClientFactory(policy: policy, auditLog: AuditLog(capacity: 10, clock: { 0 })).create(tag: "t")
        // The session was built for loopback (no proxy); a remote hop would need Tor.
        #expect(throws: NetworkBlockedError.self) {
            _ = try vetTransferHop(URLRequest(url: URL(string: "https://example.org/x")!), client: client, proxy: .none,
                                   original: URL(string: "http://127.0.0.1/x")!, auth: nil)
        }
    }
}
