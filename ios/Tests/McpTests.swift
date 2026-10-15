#if canImport(AutobotShared)
import AutobotShared
#endif
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// A scripted MCP server behind URLSession: answers by JSON-RPC method and records requests.
final class McpStub: URLProtocol, @unchecked Sendable {
    struct Recorded: Sendable {
        let method: String
        let url: URL?
        let headers: [String: String]
        let body: String
    }

    private static let state = Mutex<[Recorded]>([])

    static func reset() { state.withLock { $0 = [] } }
    static var requests: [Recorded] { state.withLock { $0 } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let body = Self.bodyOf(request)
        Self.state.withLock { $0.append(Recorded(method: request.httpMethod ?? "GET", url: request.url, headers: request.allHTTPHeaderFields ?? [:], body: body)) }
        let json = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any]
        let id = json?["id"].map { "\($0)" } ?? "null"
        let (status, headers, payload): (Int, [String: String], String) = switch json?["method"] as? String {
        case "initialize":
            (200, ["Content-Type": "application/json", "Mcp-Session-Id": "ios-sess"],
             #"{"jsonrpc":"2.0","id":\#(id),"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"stub","version":"1"}}}"#)
        case "tools/list":
            (200, ["Content-Type": "text/event-stream"],
             "id: 1\r\ndata:\r\n\r\nevent: message\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":\(id),\"result\":{\"tools\":[{\"name\":\"a\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"b\",\"inputSchema\":{\"type\":\"object\"}}]}}\r\n\r\n")
        case .some:
            (202, [:], "")
        case nil:
            (request.httpMethod == "DELETE" ? 200 : 405, [:], "")
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers.merging(["Content-Length": "\(payload.utf8.count)"]) { a, _ in a })!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(payload.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func bodyOf(_ request: URLRequest) -> String {
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return String(decoding: data, as: UTF8.self)
    }
}

/// MCP server configurations: encrypted rows, tokens only in the Keychain.
@MainActor
struct McpServerRepositoryTests {
    private func repository(_ dir: URL, clock: @escaping () -> Int64 = { 1_000 }) -> (McpServerRepository, EncryptedStore, SecretStore) {
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let secrets = SecretStore()
        return (McpServerRepository(store: store, secrets: secrets, clock: clock), store, secrets)
    }

    private func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mcp-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test func savesReloadsAndKeepsTheTokenInTheKeychain() throws {
        let dir = tempDir()
        let (repo, store, secrets) = repository(dir)
        var changes: [[McpServer]] = []
        repo.onChange = { changes.append($0) }
        let server = McpServer(id: "s1", name: "Notes", url: "https://notes.example.com/mcp", routing: .tor, enabled: true, trusted: false)
        let stored = try repo.save(server, token: .set(Secret("tok-ios")))
        defer { stored.tokenRef.map(secrets.delete) }

        #expect(stored.tokenRef != nil)
        #expect(repo.token(stored) == Secret("tok-ios"))
        #expect(changes.count == 1)
        // The sealed row never holds the token.
        let row = try #require(try store.get("mcp_servers", id: "s1", as: McpServer.self))
        #expect(row.tokenRef == stored.tokenRef)
        #expect(!String(describing: row).contains("tok-ios"))

        let (reloaded, _, _) = repository(dir)
        reloaded.load()
        #expect(reloaded.servers == [stored])
        #expect(reloaded.servers.first?.routing == .tor)
    }

    @Test func keepReplaceClearAndDelete() throws {
        var now: Int64 = 5
        let (repo, _, secrets) = repository(tempDir(), clock: { now })
        let first = try repo.save(McpServer(id: "s1", name: "a", url: "https://a.example.com/mcp"), token: .set(Secret("one")))
        let ref = try #require(first.tokenRef)
        defer { secrets.delete(ref) }

        var edited = first
        edited.name = "renamed"
        edited.tokenRef = nil
        let kept = try repo.save(edited)
        #expect(kept.tokenRef == ref)
        #expect(kept.updatedAt > first.updatedAt)
        #expect(repo.token(kept) == Secret("one"))

        now = 50
        let replaced = try repo.save(kept, token: .set(Secret("two")))
        #expect(replaced.tokenRef == ref)
        #expect(replaced.updatedAt == 50)
        #expect(repo.token(replaced) == Secret("two"))

        let cleared = try repo.save(replaced, token: .clear)
        #expect(cleared.tokenRef == nil)
        #expect(secrets.get(ref) == nil)

        _ = try repo.save(cleared, token: .set(Secret("three")))
        let newRef = try #require(repo.get("s1")?.tokenRef)
        repo.delete("s1")
        #expect(repo.servers.isEmpty)
        #expect(secrets.get(newRef) == nil)
    }

    #if canImport(AutobotShared)
    @Test func mapsToTheSharedSpec() {
        let spec = SharedMapping.mcpSpec(McpServer(id: "s", name: "N", url: "https://n.example.com/mcp", routing: .socks5, enabled: false, trusted: true, updatedAt: 9))
        #expect(spec.id == "s")
        #expect(spec.route == "SOCKS5")
        #expect(!spec.enabled)
        #expect(spec.trusted)
        #expect(spec.revision == 9)
    }
    #endif
}

#if canImport(AutobotShared)
/// The Swift `HttpTransport` the shared MCP client runs on: kill switch, headers both ways, SSE lines.
@Suite(.serialized)
struct McpTransportTests {
    private final class Sink: NSObject, HttpSink, @unchecked Sendable {
        private let state = Mutex<(status: Int32, headers: [String: String], lines: [String], done: Bool, blocked: Bool?)>((0, [:], [], false, nil))
        private let waiter = Mutex<CheckedContinuation<Void, Never>?>(nil)

        var status: Int32 { state.withLock { $0.status } }
        var headers: [String: String] { state.withLock { $0.headers } }
        var lines: [String] { state.withLock { $0.lines } }
        var blocked: Bool? { state.withLock { $0.blocked } }
        var completed: Bool { state.withLock { $0.done } }

        func wait(_ continuation: CheckedContinuation<Void, Never>) { waiter.withLock { $0 = continuation } }

        private func finish() { waiter.withLock { c in c?.resume(); c = nil } }

        func onResponse(status: Int32, headers: [String: String], errorBody: String?) {
            state.withLock { $0.status = status; $0.headers = headers }
            if !(200 ..< 300).contains(Int(status)) { finish() }
        }
        func onLine(line: String) { state.withLock { $0.lines.append(line) } }
        func onComplete() { state.withLock { $0.done = true }; finish() }
        func onFailure(blocked: Bool, message: String) { state.withLock { $0.blocked = blocked }; finish() }
    }

    /// Sends [spec] and waits for the response to finish (the transport always answers asynchronously).
    private func send(_ transport: URLSessionTransport, _ spec: HttpRequestSpec) async -> Sink {
        let sink = Sink()
        await withCheckedContinuation { continuation in
            sink.wait(continuation)
            _ = transport.send(request: spec, sink: sink)
        }
        return sink
    }

    private func spec(_ body: String, method: String = "POST", headers: [String: String] = [:]) -> HttpRequestSpec {
        HttpRequestSpec(method: method, url: "https://mcp.example.com/mcp", headers: headers, body: body, tag: "mcp:Stub", route: "INHERIT", maxBytes: 1 << 20)
    }

    @Test func offlineBlocksBeforeAnyRequest() async {
        McpStub.reset()
        let factory = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(), protocolClasses: [McpStub.self])
        let sink = await send(URLSessionTransport(clients: factory), spec(#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#))
        #expect(sink.blocked == true)
        #expect(McpStub.requests.isEmpty)
    }

    @Test func headersPassThroughAndSseArrivesLineByLine() async {
        McpStub.reset()
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let audit = AuditLog()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: policy, auditLog: audit, protocolClasses: [McpStub.self]))

        let init_ = await send(transport, spec(#"{"jsonrpc":"2.0","id":1,"method":"initialize"}"#, headers: ["Authorization": "Bearer t", "Accept": "application/json, text/event-stream"]))
        #expect(init_.status == 200)
        #expect(init_.headers["mcp-session-id"] == "ios-sess")
        #expect(init_.completed)
        let sent = McpStub.requests[0]
        #expect(sent.headers["Authorization"] == "Bearer t")
        #expect(sent.headers["Accept"] == "application/json, text/event-stream")
        #expect(sent.body.contains("initialize"))

        let list = await send(transport, spec(#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#, headers: ["Mcp-Session-Id": "ios-sess", "MCP-Protocol-Version": "2025-06-18"]))
        #expect(McpStub.requests[1].headers["Mcp-Session-Id"] == "ios-sess")
        #expect(McpStub.requests[1].headers["MCP-Protocol-Version"] == "2025-06-18")
        #expect(list.headers["content-type"] == "text/event-stream")
        #expect(list.lines.first == "id: 1")
        #expect(list.lines.contains { $0.hasPrefix("data: {") && $0.contains("\"tools\"") })
        #expect(!list.lines.contains { $0.hasSuffix("\r") })
        #expect(audit.entries.allSatisfy { $0.tag == "mcp:Stub" && $0.host == "mcp.example.com" })
    }

    @Test func sharedClientCountsToolsThroughTheSwiftTransport() async throws {
        McpStub.reset()
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let factory = HttpClientFactory(policy: policy, auditLog: AuditLog(), protocolClasses: [McpStub.self])
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mcp-bridge-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let snapshots = HostSnapshots()
        let bridge = AgentBridge(
            workspaceDir: dir.path,
            providers: ProviderDirectoryHost(snapshots: snapshots, secrets: SecretStore()),
            transport: URLSessionTransport(clients: factory),
            local: LocalEngineHost(snapshots: snapshots, llm: LocalLlm()),
            store: SessionStoreHost(store: SessionStore(store: store)),
            images: nil,
            mcpTokens: McpTokenSourceHost(snapshots: snapshots, secrets: SecretStore()),
        )
        let server = McpServer(id: "s", name: "Stub", url: "https://mcp.example.com/mcp")
        let (ok, message) = await withCheckedContinuation { continuation in
            bridge.testMcpServer(spec: SharedMapping.mcpSpec(server), token: "tok") { ok, message in
                continuation.resume(returning: (ok.boolValue, message))
            }
        }
        #expect(ok, "\(message)")
        #expect(message == "2 tools")
        let methods = McpStub.requests.map { r in r.method == "POST" ? ((try? JSONSerialization.jsonObject(with: Data(r.body.utf8))) as? [String: Any])?["method"] as? String ?? "?" : r.method }
        #expect(methods == ["initialize", "notifications/initialized", "tools/list", "DELETE"])
        #expect(McpStub.requests.allSatisfy { $0.headers["Authorization"] == "Bearer tok" && $0.url?.host() == "mcp.example.com" })
        #expect(McpStub.requests[2].headers["Mcp-Session-Id"] == "ios-sess")
        #expect(McpStub.requests[2].headers["MCP-Protocol-Version"] == "2025-06-18")
        bridge.shutdown {}
    }
}
#endif
