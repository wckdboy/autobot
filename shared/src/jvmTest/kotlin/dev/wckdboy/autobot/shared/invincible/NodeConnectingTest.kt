package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.shared.TransportChatProvider
import dev.wckdboy.autobot.shared.session.explainFailure
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a phone sees when connecting to a node goes wrong (found against a real test node, see the
 * app polish findings): every failure in plain words, a refusal not retried as a network error,
 * no handshake waiting forever, and pairing again keeping one device entry on the node.
 */
class NodeConnectingTest {
    private val crypto = JvmNodeCrypto
    private val node = NodeKeyPair.generate(crypto)
    private val device = NodeKeyPair.generate(crypto)
    private val addresses = listOf("127.0.0.1:7802", "10.0.2.2:7802")
    private val hello = ChatRequest("auto", listOf(ChatMessage(ChatRole.USER, "hi")))

    private class Pipe {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var held = ByteArray(0)
    }

    /** An in-memory stream; closing it ends the other side's reads (as a closed socket does). */
    private class MemoryLink(private val input: Pipe, private val output: Pipe) : NodeLink {
        override suspend fun readExactly(count: Int): ByteArray {
            while (input.held.size < count) input.held += input.chunks.receiveCatching().getOrNull() ?: throw IOException("the node closed the connection")
            return input.held.copyOfRange(0, count).also { input.held = input.held.copyOfRange(count, input.held.size) }
        }

        override suspend fun write(bytes: ByteArray) {
            output.chunks.trySend(bytes)
        }

        override fun close() {
            output.chunks.close()
            input.chunks.close()
        }
    }

    /** A connection whose node side runs [serve]. */
    private fun link(serve: suspend (NodeLink) -> Unit): NodeLink {
        val toNode = Pipe()
        val toDevice = Pipe()
        val nodeSide = MemoryLink(toNode, toDevice)
        GlobalScope.launch { runCatching { serve(nodeSide) } }
        return MemoryLink(toDevice, toNode)
    }

    private fun transport(connect: suspend () -> NodeLink, handshakeMillis: Long = NodeErrors.HANDSHAKE_MILLIS) =
        NodeHttpTransport(crypto, node.publicKey, device, connect, node = "node-a", addresses = addresses, handshakeMillis = handshakeMillis)

    private fun provider(connect: suspend () -> NodeLink, handshakeMillis: Long = NodeErrors.HANDSHAKE_MILLIS) =
        TransportChatProvider("http://invincible/v1", null, transport(connect, handshakeMillis), "node")

    private suspend fun error(connect: suspend () -> NodeLink, handshakeMillis: Long = NodeErrors.HANDSHAKE_MILLIS): ChatEvent.Error =
        withTimeout(10_000) { provider(connect, handshakeMillis).streamChat(hello).toList() }.filterIsInstance<ChatEvent.Error>().single()

    /** A node that accepts the session and answers with [answer] (raw HTTP), then closes. */
    private fun answering(answer: String): suspend () -> NodeLink = {
        link { side ->
            side.readExactly(1)
            val handshake = NoiseHandshake.responder(crypto, node, "invincible/0 session".encodeToByteArray())
            handshake.read(side.readFrame())
            side.writeFrame(handshake.write(ByteArray(0)))
            val transport = handshake.split()
            val request = StringBuilder()
            while (!request.contains("\r\n\r\n")) request.append(transport.decrypt(side.readFrame()).decodeToString())
            side.writeFrame(transport.encrypt(answer.encodeToByteArray()))
            side.close()
        }
    }

    @Test
    fun aNodeThatNoLongerKnowsThisPhoneSaysSoAndIsNotRetried() = runBlocking {
        // Unpaired on the node: it reads the handshake and closes without a reply.
        val e = error({ link { side -> side.readExactly(1); side.readFrame(); side.close() } })
        assertEquals(ErrorKind.UNAUTHORIZED, e.kind)
        assertTrue(e.message, "node-a doesn't know this phone any more" in e.message)
        // Shown as it is, not as a rejected API key.
        assertEquals(NodeErrors.unpaired("node-a"), explainFailure("AUTH: ${e.message}"))
    }

    @Test
    fun aHandshakeThatNeverAnswersTimesOut() = runBlocking {
        val started = System.nanoTime()
        val e = error({ link { side -> side.readExactly(1); CompletableDeferred<Unit>().await() } }, handshakeMillis = 300)
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals(NodeErrors.timeout("node-a"), e.message)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 5_000_000_000)
    }

    @Test
    fun anAnswerCutOffBetweenChunksIsADroppedConnection() = runBlocking {
        val chunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n"
        val cut = "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n${chunk.length.toString(16)}\r\n$chunk\r\n"
        val events = withTimeout(10_000) { provider(answering(cut)).streamChat(hello).toList() }
        assertEquals("Hel", events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.text })
        assertEquals(NodeErrors.dropped("node-a"), events.filterIsInstance<ChatEvent.Error>().single().message)
    }

    @Test
    fun aNodeThatIsDownIsUnreachableInWords() = runBlocking {
        val e = error({ throw IOException("failed to connect to /10.0.2.2 (port 7802) after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)") })
        assertEquals(ErrorKind.NETWORK, e.kind)
        assertEquals("Can't reach node-a at 127.0.0.1:7802 or 10.0.2.2:7802. Check that Invincible is running there and this phone is on its network (or Tailscale).", e.message)
        assertFalse(e.message, "ECONNREFUSED" in e.message)
        // The chat shows it once, not inside "Couldn't reach the provider (…)".
        assertEquals(e.message, explainFailure("TRANSPORT: ${e.message}"))
    }

    @Test
    fun noNetworkAtAllIsSaidSo() = runBlocking {
        val e = error({ throw IOException("connect failed: ENETUNREACH (Network is unreachable)") })
        assertEquals(NodeErrors.NO_NETWORK, e.message)
    }

    @Test
    fun pairingFailuresAreWords() {
        assertEquals(NodeErrors.CODE_REFUSED, NodeErrors.pairing(NodeRefusedException("Connection reset"), addresses))
        assertEquals(
            "Can't reach the node at 127.0.0.1:7802 or 10.0.2.2:7802. Check that Invincible is running there and this phone is on its network (or Tailscale).",
            NodeErrors.pairing(NodeConnectException("ECONNREFUSED"), addresses),
        )
        assertEquals(NodeErrors.timeout("The node"), NodeErrors.pairing(NodeTimeoutException("x"), addresses))
        assertTrue(NodeErrors.pairing(NodeBlockedException("Offline mode"), addresses).startsWith("Blocked by the network settings"))
    }

    @Test
    fun aUsedCodeIsRefusedNotAConnectionError() = runBlocking {
        val code = PairingCode(node.publicKey, ByteArray(32) { 1 }, addresses)
        val failure = runCatching {
            withTimeout(10_000) { PairedNode.pair(crypto, code, "phone") { link { side -> side.readExactly(1); side.readFrame(); side.close() } } }
        }.exceptionOrNull()
        assertTrue("$failure", failure is NodeRefusedException)
        val down = runCatching { PairedNode.pair(crypto, code, "phone") { throw IOException("Connection refused") } }.exceptionOrNull()
        assertTrue("$down", down is NodeConnectException)
    }

    /** A node that pairs any device holding [secret], and records the device keys it pinned. */
    private fun pairingNode(secret: ByteArray, pinned: MutableList<ByteArray>): suspend (List<String>) -> NodeLink = {
        link { side ->
            side.readExactly(1)
            val handshake = NoiseHandshake.responder(crypto, node, "invincible/0 pair".encodeToByteArray(), psk = secret)
            handshake.read(side.readFrame())
            pinned += handshake.remoteStaticKey!!
            val reply = buildJsonObject {
                put("node", "node-a")
                put("device", "phone")
            }.toString().encodeToByteArray()
            side.writeFrame(handshake.write(reply))
        }
    }

    @Test
    fun pairingAgainWithTheSameNodeKeepsThisDevicesKey() = runBlocking {
        val secret = ByteArray(32) { 3 }
        val code = PairingCode(node.publicKey, secret, addresses)
        val pinned = mutableListOf<ByteArray>()
        val first = withTimeout(10_000) { PairedNode.pair(crypto, code, "phone", connect = pairingNode(secret, pinned)) }
        assertTrue(PairedNode.sameNode(first.toSecret(), code))
        val again = withTimeout(10_000) { PairedNode.pair(crypto, code, "phone", PairedNode.fromSecret(first.toSecret()), pairingNode(secret, pinned)) }
        assertArrayEquals(first.device.publicKey, again.device.publicKey)
        assertArrayEquals(pinned[0], pinned[1])
        // Another node's pairing is not reused.
        val other = PairingCode(NodeKeyPair.generate(crypto).publicKey, secret, addresses)
        assertFalse(PairedNode.sameNode(first.toSecret(), other))
        assertFalse(PairedNode.sameNode(null, code))
    }

    @Test
    fun anOlderNodeWithoutTheAgentSaysToUpdate() = runBlocking {
        val body = """{"error":{"message":"not found","type":"not_found"}}"""
        val agent = NodeAgent(transport(answering("HTTP/1.1 404 Not Found\r\ncontent-length: ${body.length}\r\n\r\n$body")), "node-b · agent")
        val failure = runCatching { withTimeout(10_000) { agent.info() } }.exceptionOrNull() as NodeAgentException
        assertEquals(404, failure.status)
        assertEquals(NodeErrors.noAgent("node-b"), failure.message)
    }
}
