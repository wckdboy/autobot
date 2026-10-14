import Foundation
import Synchronization

/**
 Single in-memory source of truth for the current egress policy.

 The persisted value lives in the settings store; it is mirrored here at startup. Until that
 happens the policy is `.offline`, so the app fails closed.
 */
final class NetworkPolicy: Sendable {
    static let defaultSocksHost = "127.0.0.1"
    static let defaultSocksPort = 1080

    private struct State {
        var mode: NetworkMode = .offline
        var allowLoopbackWhenOffline = true
        var socksFallback: NetworkMode = .socks5(host: NetworkPolicy.defaultSocksHost, port: NetworkPolicy.defaultSocksPort)
        var observers: [UUID: @Sendable (NetworkMode) -> Void] = [:]
    }

    private let state = Mutex(State())

    init() {}

    /// The global network mode. Defaults to `.offline`.
    var mode: NetworkMode { state.withLock { $0.mode } }

    /**
     When `true` (default) requests to loopback hosts (`localhost`, `127.0.0.0/8`, `::1`) are
     allowed while offline, so on-device servers like Ollama keep working.
     */
    var allowLoopbackWhenOffline: Bool { state.withLock { $0.allowLoopbackWhenOffline } }

    /**
     SOCKS5 endpoint used for providers that force SOCKS5 routing while the global mode is not
     SOCKS5. Updated whenever a SOCKS5 global mode is applied.
     */
    var socksFallback: NetworkMode { state.withLock { $0.socksFallback } }

    func setMode(_ mode: NetworkMode) {
        let observers = state.withLock { s -> [@Sendable (NetworkMode) -> Void] in
            if case .socks5 = mode { s.socksFallback = mode }
            guard s.mode != mode else { return [] }
            s.mode = mode
            return Array(s.observers.values)
        }
        observers.forEach { $0(mode) }
    }

    func setAllowLoopbackWhenOffline(_ allow: Bool) {
        state.withLock { $0.allowLoopbackWhenOffline = allow }
    }

    func setSocksFallback(host: String, port: Int) {
        state.withLock { $0.socksFallback = .socks5(host: host, port: port) }
    }

    /// Calls [observer] after every mode change. Returns a token for [removeObserver].
    @discardableResult
    func addObserver(_ observer: @escaping @Sendable (NetworkMode) -> Void) -> UUID {
        let id = UUID()
        state.withLock { $0.observers[id] = observer }
        return id
    }

    func removeObserver(_ id: UUID) {
        _ = state.withLock { $0.observers.removeValue(forKey: id) }
    }
}
