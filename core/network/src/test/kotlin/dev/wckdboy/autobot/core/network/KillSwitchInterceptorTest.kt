package dev.wckdboy.autobot.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KillSwitchInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var policy: NetworkPolicy
    private lateinit var auditLog: AuditLog
    private lateinit var factory: HttpClientFactory

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        policy = NetworkPolicy()
        auditLog = AuditLog(capacity = 50, clock = { 1_000L })
        factory = HttpClientFactory(policy, auditLog)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun loopbackUrl(path: String): HttpUrl = server.url(path).newBuilder().host("127.0.0.1").build()

    private fun OkHttpClient.get(url: String) = newCall(Request.Builder().url(url).build()).execute()
    private fun OkHttpClient.get(url: HttpUrl) = newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun defaultModeIsOffline() {
        assertEquals(NetworkMode.Offline, policy.mode.value)
        assertTrue(policy.allowLoopbackWhenOffline.value)
    }

    @Test
    fun offlineBlocksRemoteHostsBeforeAnyIo() {
        val client = factory.create("test")
        val error = assertThrows(NetworkBlockedException::class.java) {
            client.get("https://api.example.invalid/v1/chat/completions?api_key=secret")
        }
        assertTrue(error.message!!.contains("Offline"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blockedRequestIsAuditedWithoutQuery() {
        val client = factory.create("deepseek")
        runCatching { client.get("https://api.example.invalid/v1/chat/completions?api_key=secret#frag") }

        val entry = auditLog.entries.value.single()
        assertTrue(entry.blocked)
        assertEquals("GET", entry.method)
        assertEquals("api.example.invalid", entry.host)
        assertEquals("/v1/chat/completions", entry.path)
        assertEquals("deepseek", entry.tag)
        assertEquals("offline", entry.route)
        assertNull(entry.status)
        assertFalse(entry.toString().contains("secret"))
    }

    @Test
    fun offlineAllowsLoopbackByDefault() {
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        val client = factory.create("ollama")
        client.get(loopbackUrl("/v1/models")).use { response ->
            assertEquals(200, response.code)
        }
        assertEquals(1, server.requestCount)
        val entry = auditLog.entries.value.single()
        assertFalse(entry.blocked)
        assertEquals(200, entry.status)
        assertEquals("loopback", entry.route)
    }

    @Test
    fun offlineBlocksLoopbackWhenDisallowed() {
        policy.setAllowLoopbackWhenOffline(false)
        val client = factory.create("ollama")
        assertThrows(NetworkBlockedException::class.java) { client.get(loopbackUrl("/v1/models")) }
        assertEquals(0, server.requestCount)
        assertTrue(auditLog.entries.value.single().blocked)
    }

    @Test
    fun cleartextToRemoteHostIsBlockedEvenWhenDirect() {
        policy.setMode(NetworkMode.Direct)
        val client = factory.create("test")
        assertThrows(NetworkBlockedException::class.java) { client.get("http://example.invalid/") }
    }

    @Test
    fun routeChangeAfterClientCreationIsBlocked() {
        policy.setMode(NetworkMode.Direct)
        val client = factory.create("test")
        policy.setMode(NetworkMode.Tor())
        val error = assertThrows(NetworkBlockedException::class.java) {
            client.get("https://api.example.invalid/")
        }
        assertTrue(error.message!!.contains("route changed"))
    }

    @Test
    fun switchingToOfflineBlocksExistingClients() {
        policy.setMode(NetworkMode.Direct)
        val client = factory.create("test")
        policy.setMode(NetworkMode.Offline)
        policy.setAllowLoopbackWhenOffline(false)
        assertThrows(NetworkBlockedException::class.java) { client.get(loopbackUrl("/")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun auditRecordsBytesSentAndTag() {
        policy.setMode(NetworkMode.Direct)
        server.enqueue(MockResponse.Builder().code(201).body("created").build())
        val client = factory.create("default-tag")
        val request = Request.Builder()
            .url(loopbackUrl("/upload?token=abc"))
            .post("12345".toRequestBody("text/plain".toMediaType()))
            .tag(AuditTag::class.java, AuditTag("per-request"))
            .build()
        client.newCall(request).execute().use { assertEquals(201, it.code) }

        val entry = auditLog.entries.value.single()
        assertEquals("POST", entry.method)
        assertEquals("/upload", entry.path)
        assertEquals(5L, entry.bytesSent)
        assertEquals("per-request", entry.tag)
    }

    @Test
    fun headerScrubSetsUserAgentAndRemovesReferer() {
        server.enqueue(MockResponse.Builder().code(200).build())
        val client = factory.create("test")
        val request = Request.Builder()
            .url(loopbackUrl("/"))
            .header("Referer", "https://tracker.example")
            .header("User-Agent", "okhttp/5.0 Android 15 Pixel")
            .build()
        client.newCall(request).execute().close()

        val recorded = server.takeRequest()
        assertEquals(HeaderScrubInterceptor.USER_AGENT, recorded.headers["User-Agent"])
        assertNull(recorded.headers["Referer"])
    }
}
