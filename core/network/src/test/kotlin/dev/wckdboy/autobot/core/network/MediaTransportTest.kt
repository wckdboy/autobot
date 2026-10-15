package dev.wckdboy.autobot.core.network

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.shared.BinarySink
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.WebSocketListener
import dev.wckdboy.autobot.shared.WebSocketSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The binary and WebSocket sides of [OkHttpTransport] go through the same kill switch and audit log. */
class MediaTransportTest {
    private lateinit var server: MockWebServer
    private lateinit var policy: NetworkPolicy
    private lateinit var auditLog: AuditLog
    private lateinit var transport: OkHttpTransport

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        policy = NetworkPolicy()
        auditLog = AuditLog(capacity = 50, clock = { 1_000L })
        transport = OkHttpTransport(HttpClientFactory(policy, auditLog))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun loopback(path: String, scheme: String = "http") =
        server.url(path).newBuilder().host("127.0.0.1").build().toString().replaceFirst("http", scheme)

    private class Collect : BinarySink {
        val done = CountDownLatch(1)
        var status = 0
        var body = ByteArray(0)
        var failure: Pair<Boolean, String>? = null
        override fun onResponse(status: Int, headers: Map<String, String>, body: ByteArray) {
            this.status = status
            this.body = body
            done.countDown()
        }
        override fun onFailure(blocked: Boolean, message: String) {
            failure = blocked to message
            done.countDown()
        }
    }

    @Test
    fun binaryExchangeSendsAndReceivesRawBytes() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 10, 13)
        server.enqueue(MockResponse.Builder().code(200).body(okio.Buffer().write(png)).build())
        val sink = Collect()
        val upload = byteArrayOf(0, 1, 2, 3, 0xFF.toByte())
        transport.exchange(
            HttpRequestSpec("POST", loopback("/upload/image"), mapOf("Content-Type" to "multipart/form-data; boundary=x"), null, "studio:comfyui", trace = TurnTrace("s", 2)),
            upload,
            sink,
        )
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertEquals(200, sink.status)
        assertArrayEquals(png, sink.body)
        val recorded = server.takeRequest()
        assertArrayEquals(upload, recorded.body!!.toByteArray())
        val entry = auditLog.forTurn("s", 2).single()
        assertEquals("studio:comfyui", entry.tag)
        assertEquals(5L, entry.bytesSent)
        assertEquals(png.size.toLong(), entry.bytesReceived)
    }

    @Test
    fun binaryExchangeRefusesBodiesOverTheLimitAndBlockedHosts() {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(1000)).build())
        val big = Collect()
        transport.exchange(HttpRequestSpec("GET", loopback("/view"), emptyMap(), null, "t", maxBytes = 10), null, big)
        assertTrue(big.done.await(5, TimeUnit.SECONDS))
        assertEquals(false to "Response larger than 10 bytes", big.failure)

        val blocked = Collect()
        transport.exchange(HttpRequestSpec("GET", "https://pc.example.invalid/view", emptyMap(), null, "t"), null, blocked)
        assertTrue(blocked.done.await(5, TimeUnit.SECONDS))
        assertEquals(true, blocked.failure!!.first)
    }

    private class Events : WebSocketListener {
        val events = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        override fun onOpen() {
            events += "open"
        }
        override fun onText(text: String) {
            events += "text:$text"
        }
        override fun onBinary(bytes: ByteArray) {
            events += "binary:${bytes.size}"
        }
        override fun onClosed(code: Int, reason: String) {
            events += "closed:$code"
            ended.countDown()
        }
        override fun onFailure(blocked: Boolean, message: String) {
            events += "failure:$blocked"
            ended.countDown()
        }
    }

    @Test
    fun webSocketDeliversMessagesAndAuditsTheirBytes() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : okhttp3.WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"status"}""")
                        webSocket.send(ByteArray(12) { it.toByte() }.toByteString())
                        webSocket.close(1000, "done")
                    }
                },
            ).build(),
        )
        val listener = Events()
        transport.connect(WebSocketSpec(loopback("/ws?clientId=c1", "ws"), emptyMap(), "studio:comfyui", trace = TurnTrace("s", 3)), listener)
        assertTrue(listener.ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("open", "text:{\"type\":\"status\"}", "binary:12", "closed:1000"), listener.events)
        assertEquals("/ws", server.takeRequest().url.encodedPath)
        val entry = auditLog.forTurn("s", 3).single()
        assertEquals("studio:comfyui", entry.tag)
        assertEquals("loopback", entry.route)
        assertEquals(17L + 12L, entry.bytesReceived)
    }

    @Test
    fun webSocketsAreBlockedLikeRequests() {
        // Offline: a remote socket never opens.
        val offline = Events()
        transport.connect(WebSocketSpec("wss://pc.example.invalid/ws", emptyMap(), "studio:comfyui"), offline)
        assertTrue(offline.ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failure:true"), offline.events)
        assertTrue(auditLog.entries.value.last().blocked)
        // Direct: cleartext ws:// to a remote host is still refused.
        policy.setMode(NetworkMode.Direct)
        val cleartext = Events()
        transport.connect(WebSocketSpec("ws://pc.example.invalid/ws", emptyMap(), "studio:comfyui"), cleartext)
        assertTrue(cleartext.ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("failure:true"), cleartext.events)
        assertEquals(0, server.requestCount)
    }
}
