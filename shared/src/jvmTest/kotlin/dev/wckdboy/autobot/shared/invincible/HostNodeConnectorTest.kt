package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The callback-style host connector (iOS) as the node code's suspend connector. */
class HostNodeConnectorTest {
    /** Echoes what is written; [closed] counts closes. */
    private class Echo : HostNodeLink {
        var held = ByteArray(0)
        var closed = 0

        override fun read(count: Int, done: (ByteArray?, String?) -> Unit) {
            if (held.size < count) return done(null, "closed")
            val out = held.copyOfRange(0, count)
            held = held.copyOfRange(count, held.size)
            done(out, null)
        }

        override fun write(bytes: ByteArray, done: (String?) -> Unit) {
            held += bytes
            done(null)
        }

        override fun close() {
            closed++
        }
    }

    private class Host(val answer: (List<String>, String) -> Triple<HostNodeLink?, Boolean, String?>) : HostNodeConnector {
        val asked = mutableListOf<String>()

        override fun open(addresses: List<String>, route: String, session: String?, turn: Int, done: (HostNodeLink?, Boolean, String?) -> Unit) {
            asked += "$addresses $route $session $turn"
            val (link, blocked, message) = answer(addresses, route)
            done(link, blocked, message)
        }
    }

    @Test
    fun aLinkCarriesBytesAndTheTurnReachesTheHost() = runBlocking {
        val echo = Echo()
        val host = Host { _, _ -> Triple(echo, false, null) }
        val connect = nodeConnector(host)
        val link = withContext(TurnTrace("chat-1", 3)) { connect(listOf("192.168.1.20:7778"), "TOR") }
        link.write("hello".encodeToByteArray())
        assertArrayEquals("hel".encodeToByteArray(), link.readExactly(3))
        assertArrayEquals("lo".encodeToByteArray(), link.readExactly(2))
        assertTrue(runCatching { link.readExactly(1) }.exceptionOrNull() is NodeConnectException)
        link.close()
        assertEquals(1, echo.closed)
        assertEquals(listOf("[192.168.1.20:7778] TOR chat-1 3"), host.asked)
        // Outside a turn: no session, turn -1.
        connect(listOf("a:1"), "INHERIT")
        assertEquals("[a:1] INHERIT null -1", host.asked.last())
    }

    @Test
    fun refusalsAndFailuresBecomeTheNodeExceptions() = runBlocking {
        val blocked = runCatching { nodeConnector(Host { _, _ -> Triple(null, true, "Offline mode") })(listOf("a:1"), "INHERIT") }.exceptionOrNull()
        assertTrue("$blocked", blocked is NodeBlockedException && blocked.message == "Offline mode")
        val failed = runCatching { nodeConnector(Host { _, _ -> Triple(null, false, "connection refused") })(listOf("a:1"), "INHERIT") }.exceptionOrNull()
        assertTrue("$failed", failed is NodeConnectException && failed.message == "connection refused")
    }
}
