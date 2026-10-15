#if canImport(AutobotShared)
import AutobotShared
#endif
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Serves fixed bytes and records the request bodies that reached the "network".
final class BinaryStub: URLProtocol, @unchecked Sendable {
    static let payload = Data([0x89, 0x50, 0x4E, 0x47, 0x00, 0x0A, 0x0D, 0xFF])
    private static let bodies = Mutex<[Data]>([])

    static func reset() { bodies.withLock { $0 = [] } }
    static var received: [Data] { bodies.withLock { $0 } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buffer, maxLength: buffer.count)
                if n <= 0 { break }
                body.append(buffer, count: n)
            }
            stream.close()
        }
        Self.bodies.withLock { $0.append(body) }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Length": "\(Self.payload.count)", "Content-Type": "image/png"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.payload)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// The Studio's binary and WebSocket transports on iOS: same kill switch and audit as HTTP.
@Suite(.serialized)
struct StudioTransportTests {
    final class Bytes: NSObject, BinarySink, @unchecked Sendable {
        private let state = Mutex<(status: Int32, body: Data, blocked: Bool?, continuation: CheckedContinuation<Void, Never>?)>((0, Data(), nil, nil))
        var status: Int32 { state.withLock { $0.status } }
        var body: Data { state.withLock { $0.body } }
        var blocked: Bool? { state.withLock { $0.blocked } }

        func wait(_ c: CheckedContinuation<Void, Never>) { state.withLock { $0.continuation = c } }
        private func finish() { state.withLock { s in s.continuation?.resume(); s.continuation = nil } }

        func onResponse(status: Int32, headers: [String: String], body: KotlinByteArray) {
            let data = BackupBytes.shared.toData(bytes: body) as Data
            state.withLock { $0.status = status; $0.body = data }
            finish()
        }

        func onFailure(blocked: Bool, message: String) {
            state.withLock { $0.blocked = blocked }
            finish()
        }
    }

    final class Socket: NSObject, AutobotShared.WebSocketListener, @unchecked Sendable {
        private let state = Mutex<(events: [String], continuation: CheckedContinuation<Void, Never>?)>(([], nil))
        var events: [String] { state.withLock { $0.events } }

        func wait(_ c: CheckedContinuation<Void, Never>) { state.withLock { $0.continuation = c } }
        private func add(_ e: String, end: Bool = false) {
            state.withLock { s in
                s.events.append(e)
                if end { s.continuation?.resume(); s.continuation = nil }
            }
        }

        func onOpen() { add("open") }
        func onText(text: String) { add("text") }
        func onBinary(bytes: KotlinByteArray) { add("binary") }
        func onClosed(code: Int32, reason: String) { add("closed", end: true) }
        func onFailure(blocked: Bool, message: String) { add("failure:\(blocked)", end: true) }
    }

    private func connect(_ transport: URLSessionTransport, _ url: String) async -> Socket {
        let socket = Socket()
        await withCheckedContinuation { c in
            socket.wait(c)
            _ = transport.connect(spec: WebSocketSpec(url: url, headers: [:], tag: "studio:comfyui", route: "INHERIT", maxMessageBytes: 1 << 20, trace: nil), listener: socket)
        }
        return socket
    }

    @Test func binaryExchangeCarriesRawBytesBothWaysAndAuditsThem() async {
        BinaryStub.reset()
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let audit = AuditLog()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: policy, auditLog: audit, protocolClasses: [BinaryStub.self]))
        let upload = Data([0, 1, 2, 3, 0xFF])
        let sink = Bytes()
        await withCheckedContinuation { c in
            sink.wait(c)
            let spec = HttpRequestSpec(method: "POST", url: "https://pc.example.com/upload/image", headers: ["Content-Type": "multipart/form-data; boundary=x"], body: nil, tag: "studio:comfyui", route: "INHERIT", maxBytes: 1 << 20, trace: nil)
            _ = transport.exchange(request: spec, body: BackupBytes.shared.fromData(data: upload), sink: sink)
        }
        #expect(sink.status == 200)
        #expect(sink.body == BinaryStub.payload)
        #expect(BinaryStub.received == [upload])
        let entry = audit.entries.last!
        #expect(entry.tag == "studio:comfyui")
        #expect(entry.bytesSent == 5)
        #expect(entry.bytesReceived == Int64(BinaryStub.payload.count))
    }

    @Test func binaryExchangeIsBlockedOffline() async {
        BinaryStub.reset()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(), protocolClasses: [BinaryStub.self]))
        let sink = Bytes()
        await withCheckedContinuation { c in
            sink.wait(c)
            let spec = HttpRequestSpec(method: "GET", url: "https://pc.example.com/view?filename=a.png", headers: [:], body: nil, tag: "studio:comfyui", route: "INHERIT", maxBytes: 1 << 20, trace: nil)
            _ = transport.exchange(request: spec, body: nil, sink: sink)
        }
        #expect(sink.blocked == true)
        #expect(BinaryStub.received.isEmpty)
    }

    @Test func webSocketToARemoteHostIsBlockedOfflineAndAudited() async {
        let audit = AuditLog()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: NetworkPolicy(), auditLog: audit))
        let socket = await connect(transport, "wss://pc.example.invalid/ws?clientId=c1")
        #expect(socket.events == ["failure:true"])
        let entry = audit.entries.last!
        #expect(entry.blocked)
        #expect(entry.host == "pc.example.invalid")
        #expect(entry.path == "/ws")
        #expect(entry.tag == "studio:comfyui")
    }

    @Test func cleartextWebSocketToARemoteHostIsBlockedEvenWhenDirect() async {
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let audit = AuditLog()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: policy, auditLog: audit))
        let socket = await connect(transport, "ws://pc.example.invalid:8188/ws?clientId=c1")
        #expect(socket.events == ["failure:true"])
        #expect(audit.entries.last!.blocked)
    }

    @Test func loopbackWebSocketIsAdmittedOfflineAndFailsOnlyOnTheSocket() async {
        let audit = AuditLog()
        let transport = URLSessionTransport(clients: HttpClientFactory(policy: NetworkPolicy(), auditLog: audit))
        // Nothing listens on port 9: the kill switch admits loopback, the connection then fails.
        let socket = await connect(transport, "ws://127.0.0.1:9/ws?clientId=c1")
        #expect(socket.events.last == "failure:false")
        let entry = audit.entries.last!
        #expect(!entry.blocked)
        #expect(entry.route == "loopback")
    }
}
