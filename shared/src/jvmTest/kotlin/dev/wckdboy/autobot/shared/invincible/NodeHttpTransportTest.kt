package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.TransportChatProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NodeHttpTransport] against an in-memory node: a Kotlin Noise responder that answers each
 * session with a canned HTTP response, so the HTTP framing (chunked SSE, content length, errors)
 * and the [TransportChatProvider] path are checked without a network.
 */
class NodeHttpTransportTest {
    private val crypto = JvmNodeCrypto
    private val node = NodeKeyPair.generate(crypto)
    private val device = NodeKeyPair.generate(crypto)

    /** One direction of an in-memory byte stream. */
    private class Pipe {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var held = ByteArray(0)
    }

    private class MemoryLink(private val input: Pipe, private val output: Pipe) : NodeLink {
        override suspend fun readExactly(count: Int): ByteArray {
            while (input.held.size < count) input.held += input.chunks.receive()
            return input.held.copyOfRange(0, count).also { input.held = input.held.copyOfRange(count, input.held.size) }
        }

        override suspend fun write(bytes: ByteArray) {
            output.chunks.send(bytes)
        }

        override fun close() {
            output.chunks.close()
        }
    }

    /**
     * A node that answers every session with [answer] (raw HTTP, sent in [split]-byte transport
     * frames to exercise reassembly) and records the requests it got.
     */
    private fun fakeNode(answer: String, split: Int = 7, requests: MutableList<String> = mutableListOf()): suspend () -> NodeLink = {
        val toNode = Pipe()
        val toDevice = Pipe()
        val nodeSide = MemoryLink(toNode, toDevice)
        val done = CompletableDeferred<Unit>()
        kotlinx.coroutines.GlobalScope.launch {
            check(nodeSide.readExactly(1)[0] == 2.toByte()) { "session mode" }
            val handshake = NoiseHandshake.responder(crypto, node, "invincible/0 session".encodeToByteArray())
            handshake.read(nodeSide.readFrame())
            check(handshake.remoteStaticKey!!.contentEquals(device.publicKey)) { "unknown device" }
            nodeSide.writeFrame(handshake.write(ByteArray(0)))
            val transport = handshake.split()
            val request = StringBuilder()
            while (!request.contains("\r\n\r\n")) request.append(transport.decrypt(nodeSide.readFrame()).decodeToString())
            val length = Regex("content-length: (\\d+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0
            while (request.length - request.indexOf("\r\n\r\n") - 4 < length) request.append(transport.decrypt(nodeSide.readFrame()).decodeToString())
            requests += request.toString()
            for (piece in answer.encodeToByteArray().toList().chunked(split)) nodeSide.writeFrame(transport.encrypt(piece.toByteArray()))
            nodeSide.close()
            done.complete(Unit)
        }
        MemoryLink(toDevice, toNode)
    }

    private fun provider(connect: suspend () -> NodeLink) =
        TransportChatProvider("http://invincible/v1", null, NodeHttpTransport(crypto, node.publicKey, device, connect), "node")

    private fun chunked(vararg parts: String) =
        parts.joinToString("") { "${it.encodeToByteArray().size.toString(16)}\r\n$it\r\n" } + "0\r\n\r\n"

    private val hello = ChatRequest("qwen3", listOf(ChatMessage(ChatRole.USER, "hi")))

    @Test
    fun aStreamedAnswerArrivesAsChatEvents() = runBlocking {
        val requests = mutableListOf<String>()
        val sse = chunked(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"}}]}\n\ndata: [DONE]\n\n",
        )
        val answer = "HTTP/1.1 200 OK\r\ncontent-type: text/event-stream\r\ntransfer-encoding: chunked\r\ninvincible-request-id: abc\r\n\r\n$sse"
        val events = withTimeout(10_000) { provider(fakeNode(answer, requests = requests)).streamChat(hello).toList() }
        assertEquals("Hello", events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.text })
        assertEquals(ChatEvent.Done, events.last())
        val request = requests.single()
        assertTrue(request, request.startsWith("POST /v1/chat/completions HTTP/1.1\r\n"))
        assertTrue(request, "connection: close" in request && "\"model\":\"qwen3\"" in request)
    }

    @Test
    fun modelsArriveWithAContentLength() = runBlocking {
        val json = """{"object":"list","data":[{"id":"qwen3-0.6b","object":"model"}]}"""
        val answer = "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: ${json.length}\r\n\r\n$json"
        val result = withTimeout(10_000) { provider(fakeNode(answer)).listModels() }
        assertTrue("$result", result is ModelListResult.Success && result.models.single().id == "qwen3-0.6b")
    }

    @Test
    fun aRefusalIsAnError() = runBlocking {
        val body = """{"error":{"message":"blocked: no-model","type":"request_blocked"}}"""
        val answer = "HTTP/1.1 503 Service Unavailable\r\ncontent-length: ${body.length}\r\n\r\n$body"
        val events = withTimeout(10_000) { provider(fakeNode(answer)).streamChat(hello).toList() }
        val error = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue("$error", "no-model" in error.message)
    }

    @Test
    fun aBlockedConnectionIsReportedAsBlocked() = runBlocking {
        val events = provider { throw NodeBlockedException("Offline mode") }.streamChat(hello).toList()
        assertEquals(ErrorKind.BLOCKED, events.filterIsInstance<ChatEvent.Error>().single().kind)
    }

    @Test
    fun requestsAreEncodedAsHttp1() {
        val bytes = NodeHttpTransport.encode(
            HttpRequestSpec("POST", "http://invincible/v1/chat/completions?x=1", mapOf("Accept" to "text/event-stream", "Host" to "evil"), "{}", "t"),
        )
        assertEquals(
            "POST /v1/chat/completions?x=1 HTTP/1.1\r\nhost: invincible\r\nAccept: text/event-stream\r\ncontent-length: 2\r\nconnection: close\r\n\r\n{}",
            bytes.decodeToString(),
        )
    }

    @Test
    fun theTurnReachesTheConnector() = runBlocking {
        val seen = CompletableDeferred<dev.wckdboy.autobot.agent.core.TurnTrace?>()
        val transport = NodeHttpTransport(crypto, node.publicKey, device, {
            seen.complete(dev.wckdboy.autobot.agent.core.TurnTrace.current())
            throw NodeBlockedException("stop here")
        })
        val provider = TransportChatProvider("http://invincible/v1", null, transport, "node")
        kotlinx.coroutines.withContext(dev.wckdboy.autobot.agent.core.TurnTrace("chat-9", 2)) { provider.streamChat(hello).toList() }
        assertEquals(dev.wckdboy.autobot.agent.core.TurnTrace("chat-9", 2), withTimeout(5_000) { seen.await() })
    }
}
