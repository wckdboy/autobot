package dev.wckdboy.autobot.core.network

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteResolverTest {

    private val fallback = NetworkMode.Socks5("10.1.1.1", 1080)

    @Test
    fun offlineHasNoProxy() {
        assertNull(RouteResolver.proxyFor(NetworkMode.Offline))
    }

    @Test
    fun directUsesNoProxy() {
        assertEquals(Proxy.NO_PROXY, RouteResolver.proxyFor(NetworkMode.Direct))
    }

    @Test
    fun torDefaultsToOrbotSocksPortWithUnresolvedAddress() {
        val proxy = RouteResolver.proxyFor(NetworkMode.Tor())!!
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(9050, address.port)
        assertTrue("SOCKS address must stay unresolved (remote DNS)", address.isUnresolved)
    }

    @Test
    fun socks5UsesConfiguredEndpoint() {
        val proxy = RouteResolver.proxyFor(NetworkMode.Socks5("proxy.lan", 1081))!!
        val address = proxy.address() as InetSocketAddress
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        assertEquals("proxy.lan", address.hostString)
        assertEquals(1081, address.port)
    }

    @Test
    fun loopbackHostsBypassProxy() {
        assertEquals(Proxy.NO_PROXY, RouteResolver.proxyForHost(NetworkMode.Tor(), "127.0.0.1"))
        assertEquals(Proxy.NO_PROXY, RouteResolver.proxyForHost(NetworkMode.Tor(), "localhost"))
        assertEquals(Proxy.Type.SOCKS, RouteResolver.proxyForHost(NetworkMode.Tor(), "api.deepseek.com")!!.type())
    }

    @Test
    fun loopbackAwareSelector() {
        val tor = RouteResolver.proxyFor(NetworkMode.Tor())!!
        val selector = LoopbackAwareProxySelector(tor)
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://127.0.0.1:11434/v1")))
        assertEquals(listOf(tor), selector.select(URI("https://openrouter.ai/api/v1")))
    }

    @Test
    fun offlineAlwaysWins() {
        RouteOverride.entries.forEach { override ->
            assertEquals(NetworkMode.Offline, RouteResolver.effectiveMode(NetworkMode.Offline, override, fallback))
        }
    }

    @Test
    fun inheritUsesGlobalMode() {
        assertEquals(NetworkMode.Direct, RouteResolver.effectiveMode(NetworkMode.Direct, RouteOverride.INHERIT, fallback))
        assertEquals(NetworkMode.Tor(), RouteResolver.effectiveMode(NetworkMode.Tor(), RouteOverride.INHERIT, fallback))
    }

    @Test
    fun torOverrideUpgradesDirect() {
        assertEquals(NetworkMode.Tor(), RouteResolver.effectiveMode(NetworkMode.Direct, RouteOverride.TOR, fallback))
        val customTor = NetworkMode.Tor("127.0.0.1", 9150)
        assertEquals(customTor, RouteResolver.effectiveMode(customTor, RouteOverride.TOR, fallback))
    }

    @Test
    fun directOverrideCannotDowngradeProxyModes() {
        assertEquals(NetworkMode.Tor(), RouteResolver.effectiveMode(NetworkMode.Tor(), RouteOverride.DIRECT, fallback))
        val socks = NetworkMode.Socks5("proxy", 1)
        assertEquals(socks, RouteResolver.effectiveMode(socks, RouteOverride.DIRECT, fallback))
    }

    @Test
    fun socksOverrideUsesFallbackWhenGlobalIsNotSocks() {
        assertEquals(fallback, RouteResolver.effectiveMode(NetworkMode.Direct, RouteOverride.SOCKS5, fallback))
        val socks = NetworkMode.Socks5("proxy", 1)
        assertEquals(socks, RouteResolver.effectiveMode(socks, RouteOverride.SOCKS5, fallback))
    }

    @Test
    fun policyRemembersLastSocksEndpoint() {
        val policy = NetworkPolicy()
        policy.setMode(NetworkMode.Socks5("proxy.lan", 9999))
        policy.setMode(NetworkMode.Direct)
        assertEquals(NetworkMode.Socks5("proxy.lan", 9999), policy.socksFallback.value)
    }

    @Test
    fun networkModeCodecRoundTrips() {
        listOf(
            NetworkMode.Offline,
            NetworkMode.Direct,
            NetworkMode.Tor(),
            NetworkMode.Tor("127.0.0.1", 9150),
            NetworkMode.Socks5("proxy.lan", 1080),
        ).forEach { assertEquals(it, NetworkMode.decode(NetworkMode.encode(it))) }
    }

    @Test
    fun malformedNetworkModeFailsClosed() {
        assertEquals(NetworkMode.Offline, NetworkMode.decode(null))
        assertEquals(NetworkMode.Offline, NetworkMode.decode("tor:"))
        assertEquals(NetworkMode.Offline, NetworkMode.decode("socks5:host:notaport"))
        assertEquals(NetworkMode.Offline, NetworkMode.decode("socks5:host:70000"))
        assertEquals(NetworkMode.Offline, NetworkMode.decode("teleport"))
    }

    @Test
    fun loopbackDetection() {
        listOf("localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "::1", "[::1]", "10.0.2.2", "api.localhost")
            .forEach { assertTrue(it, Loopback.isLoopback(it)) }
        listOf("127.0.0.1.nip.io", "128.0.0.1", "example.com", "10.0.2.3", "", null)
            .forEach { assertFalse(it.toString(), Loopback.isLoopback(it)) }
        assertTrue(Loopback.isLoopbackUrl("http://127.0.0.1:11434/v1"))
        assertTrue(Loopback.isLoopbackUrl("http://[::1]:8080/"))
        assertFalse(Loopback.isLoopbackUrl("https://api.deepseek.com"))
    }
}
