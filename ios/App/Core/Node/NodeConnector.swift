import AutobotShared
import Foundation
import Network

/**
 Connections to paired Invincible nodes on iOS: the counterpart of Android's `JvmNodeConnector`.
 The shared `NodeEgress` decides first (the same kill switch and route rules as every HTTP request,
 with a Noise channel to a pinned key counting as secure), then an `NWConnection` goes out directly
 or through the SOCKS5 proxy (Tor's or another) with the node's host name unresolved, so the proxy
 does the lookup. Every attempt, refused ones included, goes in the audit log, for the agent turn it
 was made for, with the bytes each way once the connection closes.
 */
final class SwiftNodeConnector: NSObject, HostNodeConnector, @unchecked Sendable {
    private let policy: NetworkPolicy
    private let audit: AuditLog
    private let tag: String
    private let timeout: TimeInterval

    init(policy: NetworkPolicy, audit: AuditLog, tag: String = "node", timeout: TimeInterval = 10) {
        (self.policy, self.audit, self.tag, self.timeout) = (policy, audit, tag, timeout)
    }

    func open(addresses: [String], route: String, session: String?, turn: Int32, done: @escaping (HostNodeLink?, KotlinBoolean, String?) -> Void) {
        // Kotlin's callback may be called from any thread, once.
        let reply = Unchecked(done)
        let request = OpenRequest(addresses: addresses, override: RouteOverride(rawValue: route) ?? .inherit, session: session, turn: turn >= 0 ? Int(turn) : nil)
        Task { [self] in
            let result = await connect(request)
            reply.value(result.link, KotlinBoolean(value: result.blocked), result.message)
        }
    }

    private struct OpenRequest: Sendable {
        let addresses: [String]
        let override: RouteOverride
        let session: String?
        let turn: Int?
    }

    private struct OpenResult: @unchecked Sendable {
        var link: NWNodeLink?
        var blocked = false
        var message: String?
    }

    /// Tries the addresses in order: the first that connects, or why none did.
    private func connect(_ request: OpenRequest) async -> OpenResult {
        var last = "the node has no address"
        for text in request.addresses {
            guard let address = NodeAddress.companion.parse(text: text) else { continue }
            let plan = NodeEgress.shared.plan(
                global: policy.mode.shared,
                override: request.override.shared,
                socksFallback: socksFallback(),
                allowLoopbackWhenOffline: policy.allowLoopbackWhenOffline,
                host: address.host,
            )
            if let blocked = plan as? NodeRouteBlocked {
                audit.record(method: "NOISE", host: address.host, path: "/", tag: tag, route: plan.label, blocked: true, error: blocked.reason, session: request.session, turn: request.turn)
                return OpenResult(link: nil, blocked: true, message: blocked.reason)
            }
            let proxy = Self.proxy(for: plan)
            do {
                let connection = try await Self.connect(host: address.host, port: Int(address.port), proxy: proxy, timeout: timeout)
                let entry = audit.record(method: "NOISE", host: address.host, path: "/", tag: tag, route: plan.label, blocked: false, session: request.session, turn: request.turn)
                let audit = self.audit
                let link = NWNodeLink(connection) { sent, received in
                    audit.shared.sent(id: entry.id, bytes: sent)
                    audit.received(id: entry.id, bytes: received)
                }
                return OpenResult(link: link)
            } catch {
                let message = Self.describe(error, proxy: proxy)
                audit.record(method: "NOISE", host: address.host, path: "/", tag: tag, route: plan.label, blocked: false, error: message, session: request.session, turn: request.turn)
                last = message
            }
        }
        return OpenResult(link: nil, blocked: false, message: last)
    }

    private static func proxy(for plan: NodeRoute) -> Proxy {
        guard let socks = plan as? NodeRouteSocks else { return .none }
        return .socks(host: socks.proxyHost, port: Int(socks.proxyPort))
    }

    private func socksFallback() -> NetworkModeSocks5 {
        if case .socks5(let host, let port) = policy.socksFallback {
            return NetworkModeSocks5(host: host, port: Int32(clamping: port))
        }
        return NetworkModeSocks5(host: NetworkPolicy.defaultSocksHost, port: Int32(NetworkPolicy.defaultSocksPort))
    }

    /// A TCP connection to [host]:[port], through [proxy] when set (no failover to a direct
    /// connection), ready to use; throws when it fails or [timeout] passes first.
    static func connect(host: String, port: Int, proxy: Proxy, timeout: TimeInterval) async throws -> NWConnection {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { throw NodeConnectError("bad port \(port)") }
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        let configurations = RouteResolver.proxyConfigurations(proxy)
        if !configurations.isEmpty {
            let context = NWParameters.PrivacyContext(description: "node")
            context.proxyConfigurations = configurations
            parameters.setPrivacyContext(context)
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: parameters)
        let queue = DispatchQueue(label: "ai.wckd.autobot.node")
        return try await withCheckedThrowingContinuation { continuation in
            let once = Once()
            let finish: @Sendable (Error?) -> Void = { error in
                guard once.first() else { return }
                connection.stateUpdateHandler = nil
                if let error {
                    connection.cancel()
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: connection)
                }
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(nil)
                case .failed(let error): finish(error)
                // Refused or unreachable: NWConnection would wait for the network to change; a node
                // address that does not answer now is skipped for the next one.
                case .waiting(let error): finish(error)
                case .cancelled: finish(NodeConnectError("cancelled"))
                default: break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout) { finish(NodeConnectError("no answer within \(Int(timeout)) s")) }
        }
    }

    private static func describe(_ error: Error, proxy: Proxy) -> String {
        if case .socks(let host, let port) = proxy, error is NWError {
            return "Tor/SOCKS proxy at \(host):\(port) isn't reachable, or it could not reach the node"
        }
        return (error as? NodeConnectError)?.message ?? error.localizedDescription
    }
}

struct NodeConnectError: Error, Sendable {
    let message: String
    init(_ message: String) { self.message = message }
}

/// True only for the first caller (a continuation resumes once).
private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func first() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

/**
 A node byte stream over an `NWConnection`: reads exactly what is asked, writes in order, and on
 close reports the bytes each way once (for the audit log and the turn's privacy receipt).
 */
final class NWNodeLink: NSObject, HostNodeLink, @unchecked Sendable {
    private let connection: NWConnection
    private let report: (Int64, Int64) -> Void
    private let lock = NSLock()
    private var sent: Int64 = 0
    private var received: Int64 = 0
    private var closed = false

    init(_ connection: NWConnection, report: @escaping (Int64, Int64) -> Void) {
        (self.connection, self.report) = (connection, report)
    }

    func read(count: Int32, done: @escaping (KotlinByteArray?, String?) -> Void) {
        let wanted = Int(count)
        let reply = Unchecked(done)
        guard wanted > 0 else { return reply.value(BackupBytes.shared.fromData(data: Data()), nil) }
        receive(wanted, into: Data(), reply: reply)
    }

    private func receive(_ wanted: Int, into buffer: Data, reply: Unchecked<(KotlinByteArray?, String?) -> Void>) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: wanted - buffer.count) { [self] data, _, complete, error in
            var next = buffer
            if let data { next.append(data) }
            addReceived(data?.count ?? 0)
            if next.count == wanted {
                reply.value(BackupBytes.shared.fromData(data: next), nil)
            } else if let error {
                reply.value(nil, error.localizedDescription)
            } else if complete {
                reply.value(nil, "the node closed the connection")
            } else {
                receive(wanted, into: next, reply: reply)
            }
        }
    }

    func write(bytes: KotlinByteArray, done: @escaping (String?) -> Void) {
        let data = BackupBytes.shared.toData(bytes: bytes) as Data
        let reply = Unchecked(done)
        let count = data.count
        connection.send(content: data, completion: .contentProcessed { [self] error in
            if error == nil { addSent(count) }
            reply.value(error?.localizedDescription)
        })
    }

    private func addReceived(_ n: Int) {
        lock.lock()
        received += Int64(n)
        lock.unlock()
    }

    private func addSent(_ n: Int) {
        lock.lock()
        sent += Int64(n)
        lock.unlock()
    }

    func close() {
        lock.lock()
        let first = !closed
        closed = true
        let counts = (sent, received)
        lock.unlock()
        guard first else { return }
        connection.cancel()
        report(counts.0, counts.1)
    }
}
