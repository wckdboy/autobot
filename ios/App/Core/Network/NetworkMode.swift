import Foundation

/**
 Global egress policy of the app.

 `.offline` is the default: nothing leaves the device (loopback endpoints such as a local Ollama
 server may be allowed, see [NetworkPolicy.allowLoopbackWhenOffline]).
 */
enum NetworkMode: Equatable, Hashable, Sendable {
    /// All non-loopback traffic is blocked by the kill switch.
    case offline
    /// Direct connections without a proxy.
    case direct
    /// Route through a Tor SOCKS port (e.g. Orbot for iOS at `127.0.0.1:9050`).
    case tor(host: String = NetworkMode.defaultTorHost, port: Int = NetworkMode.defaultTorPort)
    /// Route through an arbitrary SOCKS5 proxy.
    case socks5(host: String, port: Int)

    static let defaultTorHost = "127.0.0.1"
    static let defaultTorPort = 9050

    /// Serializes a mode to a stable string for persistence (e.g. `tor:127.0.0.1:9050`).
    static func encode(_ mode: NetworkMode) -> String {
        switch mode {
        case .offline: "offline"
        case .direct: "direct"
        case .tor(let host, let port): "tor:\(host):\(port)"
        case .socks5(let host, let port): "socks5:\(host):\(port)"
        }
    }

    /// Parses [encode]'s output. Anything unknown or malformed falls back to `.offline`.
    static func decode(_ value: String?) -> NetworkMode {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return .offline }
        let kind = value.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let rest = value.firstIndex(of: ":").map { String(value[value.index(after: $0)...]) } ?? ""
        func endpoint() -> (String, Int)? {
            guard let colon = rest.lastIndex(of: ":") else { return nil }
            let host = String(rest[..<colon])
            guard !host.trimmingCharacters(in: .whitespaces).isEmpty,
                  let port = Int(rest[rest.index(after: colon)...]), (1 ... 65535).contains(port) else { return nil }
            return (host, port)
        }
        switch kind {
        case "offline": return .offline
        case "direct": return .direct
        case "tor": return endpoint().map { .tor(host: $0.0, port: $0.1) } ?? .offline
        case "socks5": return endpoint().map { .socks5(host: $0.0, port: $0.1) } ?? .offline
        default: return .offline
        }
    }

    var isProxy: Bool {
        switch self {
        case .tor, .socks5: true
        default: false
        }
    }
}

/// Per-client routing override (mirrors a provider's routing choice). Resolved by [RouteResolver].
enum RouteOverride: String, CaseIterable, Codable, Sendable {
    case inherit = "INHERIT", direct = "DIRECT", tor = "TOR", socks5 = "SOCKS5"
}

/// Thrown for any request the kill switch refuses, before any I/O.
struct NetworkBlockedError: LocalizedError, Equatable, Sendable {
    let message: String
    var errorDescription: String? { message }
}
