import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Answers every request with a canned response and records what reached the "network".
final class KillSwitchStub: URLProtocol, @unchecked Sendable {
    struct Recorded: Sendable {
        let url: URL?
        let headers: [String: String]
    }

    private static let state = Mutex<(requests: [Recorded], status: Int, body: Data)>(([], 200, Data("ok".utf8)))

    static func reset(status: Int = 200, body: String = "ok") { state.withLock { $0 = ([], status, Data(body.utf8)) } }
    static var requests: [Recorded] { state.withLock { $0.requests } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let (status, body) = Self.state.withLock { s -> (Int, Data) in
            s.requests.append(Recorded(url: request.url, headers: request.allHTTPHeaderFields ?? [:]))
            return (s.status, s.body)
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: (300 ..< 400).contains(status) ? ["Location": "/next", "Content-Length": "0"] : ["Content-Length": "\(body.count)"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Port of `KillSwitchInterceptorTest.kt` against the URLSession client.
@Suite(.serialized)
struct KillSwitchTests {
    private let policy = NetworkPolicy()
    private let auditLog = AuditLog(capacity: 50, clock: { 1000 })
    private let factory: HttpClientFactory

    init() {
        KillSwitchStub.reset()
        factory = HttpClientFactory(policy: policy, auditLog: auditLog, protocolClasses: [KillSwitchStub.self])
    }

    private func get(_ client: HttpClient, _ url: String) async throws -> HttpResponse {
        try await client.send(URLRequest(url: URL(string: url)!))
    }

    @Test func defaultModeIsOffline() {
        #expect(policy.mode == .offline)
        #expect(policy.allowLoopbackWhenOffline)
    }

    @Test(.timeLimit(.minutes(3))) func offlineBlocksRemoteHostsBeforeAnyIo() async {
        let client = factory.create(tag: "test")
        await #expect(throws: NetworkBlockedError.self) {
            _ = try await get(client, "https://api.example.invalid/v1/chat/completions?api_key=secret")
        }
        #expect(KillSwitchStub.requests.isEmpty)
    }

    @Test(.timeLimit(.minutes(3))) func blockedMessageNamesOffline() async {
        let client = factory.create(tag: "test")
        do {
            _ = try await get(client, "https://api.example.invalid/")
            Issue.record("expected a block")
        } catch let blocked as NetworkBlockedError {
            #expect(blocked.message.contains("Offline"))
        } catch {
            Issue.record("unexpected \(error)")
        }
    }

    @Test(.timeLimit(.minutes(3))) func blockedRequestIsAuditedWithoutQuery() async {
        let client = factory.create(tag: "deepseek")
        _ = try? await get(client, "https://api.example.invalid/v1/chat/completions?api_key=secret#frag")
        let entry = auditLog.entries.last!
        #expect(auditLog.entries.count == 1)
        #expect(entry.blocked)
        #expect(entry.method == "GET")
        #expect(entry.host == "api.example.invalid")
        #expect(entry.path == "/v1/chat/completions")
        #expect(entry.tag == "deepseek")
        #expect(entry.route == "offline")
        #expect(entry.status == nil)
        #expect(!"\(entry)".contains("secret"))
    }

    @Test(.timeLimit(.minutes(3))) func offlineAllowsLoopbackByDefault() async throws {
        let client = factory.create(tag: "ollama")
        let response = try await get(client, "http://127.0.0.1:11434/v1/models")
        #expect(response.status == 200)
        #expect(KillSwitchStub.requests.count == 1)
        let entry = try #require(auditLog.entries.last)
        #expect(!entry.blocked)
        #expect(entry.status == 200)
        #expect(entry.route == "loopback")
    }

    @Test(.timeLimit(.minutes(3))) func offlineBlocksLoopbackWhenDisallowed() async {
        policy.setAllowLoopbackWhenOffline(false)
        let client = factory.create(tag: "ollama")
        await #expect(throws: NetworkBlockedError.self) { _ = try await get(client, "http://127.0.0.1:11434/v1/models") }
        #expect(KillSwitchStub.requests.isEmpty)
        #expect(auditLog.entries.last?.blocked == true)
    }

    @Test(.timeLimit(.minutes(3))) func cleartextToRemoteHostIsBlockedEvenWhenDirect() async {
        policy.setMode(.direct)
        let client = factory.create(tag: "test")
        await #expect(throws: NetworkBlockedError.self) { _ = try await get(client, "http://example.invalid/") }
        #expect(KillSwitchStub.requests.isEmpty)
    }

    @Test(.timeLimit(.minutes(3))) func routeChangeAfterClientCreationIsBlocked() async {
        policy.setMode(.direct)
        let client = factory.create(tag: "test")
        policy.setMode(.tor())
        do {
            _ = try await get(client, "https://api.example.invalid/")
            Issue.record("expected a block")
        } catch let blocked as NetworkBlockedError {
            #expect(blocked.message.contains("route changed"))
        } catch {
            Issue.record("unexpected \(error)")
        }
        #expect(KillSwitchStub.requests.isEmpty)
    }

    @Test(.timeLimit(.minutes(3))) func switchingToOfflineBlocksExistingClients() async {
        policy.setMode(.direct)
        let client = factory.create(tag: "test")
        policy.setMode(.offline)
        policy.setAllowLoopbackWhenOffline(false)
        await #expect(throws: NetworkBlockedError.self) { _ = try await get(client, "http://127.0.0.1/") }
        #expect(KillSwitchStub.requests.isEmpty)
    }

    @Test(.timeLimit(.minutes(3))) func auditRecordsBytesSentStatusAndTag() async throws {
        policy.setMode(.direct)
        KillSwitchStub.reset(status: 201, body: "created")
        let client = factory.create(tag: "default-tag")
        var request = URLRequest(url: URL(string: "http://127.0.0.1/upload?token=abc")!)
        request.httpMethod = "POST"
        request.httpBody = Data("12345".utf8)
        let response = try await client.send(request, tag: "per-request")
        #expect(response.status == 201)
        let entry = try #require(auditLog.entries.last)
        #expect(entry.method == "POST")
        #expect(entry.path == "/upload")
        #expect(entry.bytesSent == 5)
        #expect(entry.bytesReceived == 7)
        #expect(entry.tag == "per-request")
    }

    @Test(.timeLimit(.minutes(3))) func headerScrubSetsUserAgentAndRemovesReferer() async throws {
        let client = factory.create(tag: "test")
        var request = URLRequest(url: URL(string: "http://127.0.0.1/")!)
        request.setValue("https://tracker.example", forHTTPHeaderField: "Referer")
        request.setValue("CFNetwork/1.0 Darwin iPhone", forHTTPHeaderField: "User-Agent")
        request.setValue("1", forHTTPHeaderField: "X-Device-Id")
        _ = try await client.send(request)
        let recorded = try #require(KillSwitchStub.requests.last)
        #expect(recorded.headers["User-Agent"] == HeaderScrub.userAgent)
        #expect(recorded.headers["User-Agent"] == "Autobot")
        #expect(recorded.headers["Referer"] == nil)
        #expect(recorded.headers["X-Device-Id"] == nil)
        #expect(recorded.headers["Accept-Language"] == "en")
    }

    @Test(.timeLimit(.minutes(3))) func redirectsAreNotFollowed() async throws {
        KillSwitchStub.reset(status: 302, body: "")
        let client = factory.create(tag: "test")
        let response = try await get(client, "http://127.0.0.1/start")
        #expect(response.status == 302)
        #expect(KillSwitchStub.requests.count == 1)
    }
}
