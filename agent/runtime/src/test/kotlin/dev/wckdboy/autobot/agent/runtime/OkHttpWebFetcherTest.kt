package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkPolicy
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** `web_fetch` on Android: through the kill switch and audit log, redirects reported, bodies capped. */
class OkHttpWebFetcherTest {
    private lateinit var server: MockWebServer
    private lateinit var policy: NetworkPolicy
    private lateinit var audit: AuditLog
    private lateinit var fetcher: OkHttpWebFetcher

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        policy = NetworkPolicy() // Offline; loopback allowed
        audit = AuditLog(capacity = 20, clock = { 0L })
        fetcher = OkHttpWebFetcher(HttpClientFactory(policy, audit))
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    @Test
    fun returnsTheBodyAndAuditsTheRequest() = runTest {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/plain").body("hello").build())
        val response = fetcher.get(url("/a?secret=1"), "text/plain", 1024)
        assertEquals(200, response.status)
        assertEquals("hello", response.body)
        assertTrue(response.contentType!!.startsWith("text/plain"))
        val entry = audit.entries.value.single()
        assertEquals("agent:web_fetch", entry.tag)
        assertEquals("/a", entry.path) // the query never reaches the log
    }

    @Test
    fun redirectsAreReportedNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://elsewhere.example/").build())
        val response = fetcher.get(url("/r"), "*/*", 1024)
        assertEquals(302, response.status)
        assertEquals("https://elsewhere.example/", response.location)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun bodiesAreCappedAndErrorsCarryNoBody() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("x".repeat(5000)).build())
        assertEquals(100, fetcher.get(url("/big"), "*/*", 100).body.length)
        server.enqueue(MockResponse.Builder().code(500).body("stack trace").build())
        val failed = fetcher.get(url("/err"), "*/*", 100)
        assertEquals(500, failed.status)
        assertEquals("", failed.body)
    }

    @Test
    fun theKillSwitchBlocksBeforeAnyIo() = runTest {
        policy.setAllowLoopbackWhenOffline(false)
        val error = runCatching { fetcher.get(url("/x"), "*/*", 100) }.exceptionOrNull() as WebFetchException
        assertEquals("BLOCKED", error.code)
        assertEquals(0, server.requestCount)
        assertTrue(audit.entries.value.single().blocked)
        assertNull(audit.entries.value.single().status)
    }
}
