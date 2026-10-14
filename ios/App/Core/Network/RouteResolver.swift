import AutobotShared
import Foundation
import Network

/// How a request leaves the device: directly or through a SOCKS5 endpoint. `nil` = blocked.
enum Proxy: Equatable, Sendable {
    case none
    /// Host and port are kept as given (unresolved) so the proxy resolves the name (remote DNS).
    case socks(host: String, port: Int)
}

/**
 Routing: given the global [NetworkMode] and a per-client [RouteOverride], decides the effective
 egress route and the corresponding [Proxy]. The rules are the shared Kotlin `RouteResolver` and
 `KillSwitch` (`:shared`), which Android runs too; this file only maps types and builds the
 URLSession proxy configuration.

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
    private static var rules: AutobotShared.RouteResolver { AutobotShared.RouteResolver.shared }

    static func effectiveMode(_ global: NetworkMode, _ override: RouteOverride, socksFallback: NetworkMode) -> NetworkMode {
        let fallback: NetworkModeSocks5 = if case .socks5(let host, let port) = socksFallback {
            NetworkModeSocks5(host: host, port: Int32(clamping: port))
        } else {
            NetworkModeSocks5(host: NetworkPolicy.defaultSocksHost, port: Int32(NetworkPolicy.defaultSocksPort))
        }
        return NetworkMode(shared: rules.effectiveMode(global: global.shared, override: override.shared, socksFallback: fallback))
    }

    /// Proxy for a non-loopback host under [mode]. `nil` means blocked (offline).
    static func proxy(for mode: NetworkMode) -> Proxy? { proxy(rules.route(mode: mode.shared)) }

    /// Proxy to use for a request to [host] under [mode]. Loopback is always direct.
    static func proxy(for mode: NetworkMode, host: String) -> Proxy? { proxy(rules.routeForHost(mode: mode.shared, host: host)) }

    /// Route label shown in the audit log (`direct`, `tor`, `socks5`, `offline`).
    static func routeLabel(_ mode: NetworkMode) -> String { rules.label(mode: mode.shared) }

    /**
     The kill switch's decision before any DNS lookup or socket (shared `KillSwitch`): the reason
     to refuse the request, or nil. Schemes other than HTTP(S) are refused too.
     */
    static func blockReason(global: NetworkMode, effective: NetworkMode, allowLoopbackWhenOffline: Bool, url: URL?, expectedRoute: NetworkMode?) -> String? {
        let scheme = url?.scheme?.lowercased()
        let reason = KillSwitch.shared.blockReason(
            global: global.shared,
            effective: effective.shared,
            allowLoopbackWhenOffline: allowLoopbackWhenOffline,
            host: url?.host() ?? "",
            https: scheme == "https",
            expectedRoute: expectedRoute?.shared,
        )
        if reason == nil && scheme != "https" && scheme != "http" { return "Cleartext HTTP is only allowed to loopback hosts" }
        return reason
    }

    private static func proxy(_ route: any AutobotShared.Route) -> Proxy? {
        switch route {
        case let socks as RouteSocks: .socks(host: socks.host, port: Int(socks.port))
        case is RouteDirect: Proxy.none
        default: nil
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
