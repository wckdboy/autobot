package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.RouteOverride
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Node connections under the network policy: kill switch, routes, remote DNS, audit log. */
class NodeEgressTest {
    private val fallback = NetworkMode.Socks5("127.0.0.1", 1080)
    private fun plan(global: NetworkMode, host: String, override: RouteOverride = RouteOverride.INHERIT, loopbackOffline: Boolean = true) =
        NodeEgress.plan(global, override, fallback, loopbackOffline, host)

    @Test
    fun theKillSwitchAndRoutesApply() {
        assertTrue(plan(NetworkMode.Offline, "192.168.1.20") is NodeRoute.Blocked)
        assertEquals(NodeRoute.Loopback, plan(NetworkMode.Offline, "127.0.0.1"))
        assertTrue(plan(NetworkMode.Offline, "127.0.0.1", loopbackOffline = false) is NodeRoute.Blocked)
        // Encrypted and authenticated: not refused as cleartext, unlike plain HTTP to a LAN host.
        assertEquals(NodeRoute.Direct, plan(NetworkMode.Direct, "192.168.1.20"))
        assertEquals(NodeRoute.Socks("127.0.0.1", 9050), plan(NetworkMode.Tor(), "node.example"))
        // A Direct override cannot downgrade a global Tor mode.
        assertEquals(NodeRoute.Socks("127.0.0.1", 9050), plan(NetworkMode.Tor(), "node.example", RouteOverride.DIRECT))
        assertEquals(NodeRoute.Socks("127.0.0.1", 1080), plan(NetworkMode.Direct, "node.example", RouteOverride.SOCKS5))
    }

    @Test
    fun addressesParse() {
        assertEquals(NodeAddress("192.168.1.20", 7778), NodeAddress.parse("192.168.1.20:7778"))
        assertEquals(NodeAddress("fd00::1", 7778), NodeAddress.parse("[fd00::1]:7778"))
        assertEquals(null, NodeAddress.parse("host"))
        assertEquals(null, NodeAddress.parse("host:0"))
        assertEquals(null, NodeAddress.parse("[fd00::1]7778"))
    }

    /** An echo server standing in for a node. */
    private fun echoServer(): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
    }

    /**
     * A minimal SOCKS5 proxy (no auth, CONNECT) that records the address type and host each
     * request named, then relays to [target].
     */
    private fun socksProxy(target: Int, seen: MutableList<String>): ServerSocket = ServerSocket(0).also { server ->
        thread(isDaemon = true) {
            val client = server.accept()
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()
            input.readByte(); val methods = input.readByte().toInt(); repeat(methods) { input.readByte() }
            output.write(byteArrayOf(5, 0))
            input.readByte(); input.readByte(); input.readByte()
            val type = input.readByte().toInt()
            val host = when (type) {
                3 -> ByteArray(input.readByte().toInt()).also { input.readFully(it) }.decodeToString()
                1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                else -> "type $type"
            }
            input.readShort()
            seen += "type=$type host=$host"
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            val upstream = Socket("127.0.0.1", target)
            thread(isDaemon = true) { runCatching { upstream.getInputStream().copyTo(output) } }
            runCatching { input.copyTo(upstream.getOutputStream()) }
        }
    }

    private fun connector(global: NetworkMode, audit: AuditLog) =
        JvmNodeConnector({ NodePolicy(global, fallback, allowLoopbackWhenOffline = true) }, audit)

    @Test
    fun aDirectConnectionIsAuditedWithItsBytes() = runBlocking {
        val echo = echoServer()
        val audit = AuditLog()
        val link = connector(NetworkMode.Direct, audit).open(listOf("127.0.0.1:${echo.localPort}"))
        link.write("hello".encodeToByteArray())
        assertArrayEquals("hello".encodeToByteArray(), link.readExactly(5))
        link.close()
        val entry = audit.entries.value.single()
        assertEquals("NOISE", entry.method)
        assertEquals("loopback", entry.route)
        assertEquals(5L, entry.bytesSent)
        assertEquals(5L, entry.bytesReceived)
    }

    @Test
    fun throughTorTheProxyGetsTheHostNameNotAnAddress() = runBlocking {
        val echo = echoServer()
        val seen = mutableListOf<String>()
        val proxy = socksProxy(echo.localPort, seen)
        val audit = AuditLog()
        val link = connector(NetworkMode.Tor("127.0.0.1", proxy.localPort), audit).open(listOf("mynode.example:7778"))
        link.write("ping".encodeToByteArray())
        assertArrayEquals("ping".encodeToByteArray(), link.readExactly(4))
        link.close()
        // ATYP 3: a domain name, resolved by the proxy (remote DNS), not on the phone.
        assertEquals(listOf("type=3 host=mynode.example"), seen)
        assertEquals("socks", audit.entries.value.single().route)
    }

    @Test
    fun offlineRefusesBeforeAnySocketAndIsAudited() = runBlocking {
        val audit = AuditLog()
        val error = runCatching { connector(NetworkMode.Offline, audit).open(listOf("192.168.1.20:7778")) }.exceptionOrNull()
        assertTrue("$error", error is NodeBlockedException)
        val entry = audit.entries.value.single()
        assertTrue(entry.blocked)
        assertEquals("192.168.1.20", entry.host)
    }

    @Test
    fun aConnectionMadeForATurnIsAuditedForThatTurn() = runBlocking {
        val echo = echoServer()
        val audit = AuditLog()
        kotlinx.coroutines.withContext(dev.wckdboy.autobot.agent.core.TurnTrace("chat-1", 3)) {
            connector(NetworkMode.Direct, audit).open(listOf("127.0.0.1:${echo.localPort}")).close()
        }
        val entry = audit.entries.value.single()
        assertEquals("chat-1", entry.session)
        assertEquals(3, entry.turn)
        assertEquals(listOf(entry.id), audit.forTurn("chat-1", 3).map { it.id })
    }
}
