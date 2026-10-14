import Foundation
import Network

/// How a request leaves the device: directly or through a SOCKS5 endpoint. `nil` = blocked.
enum Proxy: Equatable, Sendable {
    case none
    /// Host and port are kept as given (unresolved) so the proxy resolves the name (remote DNS).
    case socks(host: String, port: Int)
}

/**
 Pure routing logic: given the global [NetworkMode] and a per-client [RouteOverride], decides
 the effective egress route and the corresponding [Proxy].

 Rules:
 - `.offline` always wins; the kill switch blocks the request regardless of overrides.
 - `.inherit` uses the global mode.
 - `.tor` / `.socks5` force a proxy even if the global mode is Direct.
 - `.direct` cannot *downgrade* a global proxy mode: if the user switched the whole app to Tor or
   SOCKS5, a provider configured as Direct still goes through that proxy.
 - Loopback hosts never use a proxy (a SOCKS proxy cannot reach the device's own loopback).

 Proxies carry host names, never resolved addresses, so lookups happen at the proxy (no DNS leak
 outside Tor).
 */
enum RouteResolver {
    static func effectiveMode(_ global: NetworkMode, _ override: RouteOverride, socksFallback: NetworkMode) -> NetworkMode {
        if global == .offline { return .offline }
        switch override {
        case .inherit, .direct:
            return global
        case .tor:
            if case .tor = global { return global }
            return .tor()
        case .socks5:
            if case .socks5 = global { return global }
            return socksFallback
        }
    }

    /// Proxy for a non-loopback host under [mode]. `nil` means blocked (offline).
    static func proxy(for mode: NetworkMode) -> Proxy? {
        switch mode {
        case .offline: nil
        case .direct: Proxy.none
        case .tor(let host, let port), .socks5(let host, let port): .socks(host: host, port: port)
        }
    }

    /// Proxy to use for a request to [host] under [mode]. Loopback is always direct.
    static func proxy(for mode: NetworkMode, host: String) -> Proxy? {
        Loopback.isLoopback(host) ? Proxy.none : proxy(for: mode)
    }

    /// Route label shown in the audit log (`direct`, `tor`, `socks5`, `offline`).
    static func routeLabel(_ mode: NetworkMode) -> String {
        switch mode {
        case .offline: "offline"
        case .direct: "direct"
        case .tor: "tor"
        case .socks5: "socks5"
        }
    }

    /// URLSession proxy configuration for [proxy] (iOS 17+ Network framework SOCKSv5). Failover
    /// is off: if the proxy is unreachable the request fails instead of going out directly.
    static func proxyConfigurations(_ proxy: Proxy) -> [ProxyConfiguration] {
        switch proxy {
        case .none:
            return []
        case .socks(let host, let port):
            let endpoint = NWEndpoint.hostPort(host: NWEndpoint.Host(host), port: NWEndpoint.Port(integerLiteral: UInt16(clamping: port)))
            var configuration = ProxyConfiguration(socksv5Proxy: endpoint)
            configuration.allowFailover = false
            return [configuration]
        }
    }
}
