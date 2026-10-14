import AutobotShared
import Foundation

/**
 Global egress policy of the app. The rules behind it (codec, routing, kill switch, audit) are the
 shared Kotlin ones in `:shared` (`dev.wckdboy.autobot.core.network`), so both apps decide alike;
 this enum is the Swift-friendly face of `NetworkMode`.

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

    /// Serializes a mode for persistence (e.g. `tor:127.0.0.1:9050`), with the shared codec, so
    /// the stored value reads the same on Android.
    static func encode(_ mode: NetworkMode) -> String {
        NetworkModeCompanion.shared.encode(mode: mode.shared)
    }

    /// Parses [encode]'s output (shared codec). Anything unknown or malformed falls back to `.offline`.
    static func decode(_ value: String?) -> NetworkMode {
        NetworkMode(shared: NetworkModeCompanion.shared.decode(value: value))
    }

    /// The shared Kotlin value the routing rules take.
    var shared: any AutobotShared.NetworkMode {
        switch self {
        case .offline: NetworkModeOffline.shared
        case .direct: NetworkModeDirect.shared
        case .tor(let host, let port): NetworkModeTor(host: host, port: Int32(clamping: port))
        case .socks5(let host, let port): NetworkModeSocks5(host: host, port: Int32(clamping: port))
        }
    }

    init(shared mode: any AutobotShared.NetworkMode) {
        switch mode {
        case let tor as NetworkModeTor: self = .tor(host: tor.host, port: Int(tor.port))
        case let socks as NetworkModeSocks5: self = .socks5(host: socks.host, port: Int(socks.port))
        case is NetworkModeDirect: self = .direct
        default: self = .offline
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

    var shared: AutobotShared.RouteOverride {
        switch self {
        case .inherit: .inherit
        case .direct: .direct
        case .tor: .tor
        case .socks5: .socks5
        }
    }
}

/// Thrown for any request the kill switch refuses, before any I/O.
struct NetworkBlockedError: LocalizedError, Equatable, Sendable {
    let message: String
    var errorDescription: String? { message }
}
