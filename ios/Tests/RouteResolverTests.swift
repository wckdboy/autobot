import Foundation
import Testing
@testable import Autobot

/// Port of `RouteResolverTest.kt`.
struct RouteResolverTests {
    private let fallback = NetworkMode.socks5(host: "10.1.1.1", port: 1080)

    @Test func offlineHasNoProxy() {
        #expect(RouteResolver.proxy(for: .offline) == nil)
    }

    @Test func directUsesNoProxy() {
        #expect(RouteResolver.proxy(for: .direct) == Proxy.none)
    }

    @Test func torDefaultsToOrbotSocksPortWithHostName() {
        #expect(RouteResolver.proxy(for: .tor()) == .socks(host: "127.0.0.1", port: 9050))
    }

    @Test func socks5UsesConfiguredEndpoint() {
        #expect(RouteResolver.proxy(for: .socks5(host: "proxy.lan", port: 1081)) == .socks(host: "proxy.lan", port: 1081))
    }

    @Test func proxyConfigurationsDisableFailover() {
        #expect(RouteResolver.proxyConfigurations(Proxy.none).isEmpty)
        let configs = RouteResolver.proxyConfigurations(.socks(host: "127.0.0.1", port: 9050))
        #expect(configs.count == 1)
        #expect(configs.first?.allowFailover == false)
    }

    @Test func loopbackHostsBypassProxy() {
        #expect(RouteResolver.proxy(for: .tor(), host: "127.0.0.1") == Proxy.none)
        #expect(RouteResolver.proxy(for: .tor(), host: "localhost") == Proxy.none)
        #expect(RouteResolver.proxy(for: .tor(), host: "api.deepseek.com") == .socks(host: "127.0.0.1", port: 9050))
    }

    @Test func offlineAlwaysWins() {
        for override in RouteOverride.allCases {
            #expect(RouteResolver.effectiveMode(.offline, override, socksFallback: fallback) == .offline)
        }
    }

    @Test func inheritUsesGlobalMode() {
        #expect(RouteResolver.effectiveMode(.direct, .inherit, socksFallback: fallback) == .direct)
        #expect(RouteResolver.effectiveMode(.tor(), .inherit, socksFallback: fallback) == .tor())
    }

    @Test func torOverrideUpgradesDirect() {
        #expect(RouteResolver.effectiveMode(.direct, .tor, socksFallback: fallback) == .tor())
        let custom = NetworkMode.tor(host: "127.0.0.1", port: 9150)
        #expect(RouteResolver.effectiveMode(custom, .tor, socksFallback: fallback) == custom)
    }

    @Test func directOverrideCannotDowngradeProxyModes() {
        #expect(RouteResolver.effectiveMode(.tor(), .direct, socksFallback: fallback) == .tor())
        let socks = NetworkMode.socks5(host: "proxy", port: 1)
        #expect(RouteResolver.effectiveMode(socks, .direct, socksFallback: fallback) == socks)
    }

    @Test func socksOverrideUsesFallbackWhenGlobalIsNotSocks() {
        #expect(RouteResolver.effectiveMode(.direct, .socks5, socksFallback: fallback) == fallback)
        let socks = NetworkMode.socks5(host: "proxy", port: 1)
        #expect(RouteResolver.effectiveMode(socks, .socks5, socksFallback: fallback) == socks)
    }

    @Test func policyRemembersLastSocksEndpoint() {
        let policy = NetworkPolicy()
        policy.setMode(.socks5(host: "proxy.lan", port: 9999))
        policy.setMode(.direct)
        #expect(policy.socksFallback == .socks5(host: "proxy.lan", port: 9999))
    }

    @Test func networkModeCodecRoundTrips() {
        let modes: [NetworkMode] = [.offline, .direct, .tor(), .tor(host: "127.0.0.1", port: 9150), .socks5(host: "proxy.lan", port: 1080)]
        for mode in modes { #expect(NetworkMode.decode(NetworkMode.encode(mode)) == mode) }
    }

    @Test func malformedNetworkModeFailsClosed() {
        #expect(NetworkMode.decode(nil) == .offline)
        #expect(NetworkMode.decode("tor:") == .offline)
        #expect(NetworkMode.decode("socks5:host:notaport") == .offline)
        #expect(NetworkMode.decode("socks5:host:70000") == .offline)
        #expect(NetworkMode.decode("teleport") == .offline)
    }

    @Test func loopbackDetection() {
        for host in ["localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "10.0.2.2", "api.localhost"] {
            #expect(Loopback.isLoopback(host), "\(host)")
        }
        for host in ["127.0.0.1.nip.io", "128.0.0.1", "example.com", "10.0.2.3", "", nil] as [String?] {
            #expect(!Loopback.isLoopback(host), "\(host ?? "nil")")
        }
        #expect(Loopback.isLoopbackURL("http://127.0.0.1:11434/v1"))
        #expect(Loopback.isLoopbackURL("http://[::1]:8080/"))
        #expect(!Loopback.isLoopbackURL("https://api.deepseek.com"))
    }
}
