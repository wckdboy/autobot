package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An MCP server that does not answer must not hold up every turn for the connect timeout. */
class McpUnreachableTest {
    private val dead = McpServerSpec("d", "Dead", "https://dead.example.com/mcp")

    /** Accepts every request and never answers (a black-holed host). */
    private object BlackHole : HttpTransport {
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable = Cancellable { }
    }

    @Test
    fun anUnreachableServerDoesNotBlockTurnStart() = runBlocking {
        var now = 0L
        val hub = McpHub(BlackHole, { null }, { now })
        hub.update(listOf(dead))

        // First attempt: the turn waits a bounded time, not the full connect timeout.
        var started = TimeSource.Monotonic.markNow()
        hub.prepare()
        assertTrue(started.elapsedNow() < (McpHub.FIRST_WAIT_MS + 2_000).milliseconds)
        assertTrue(McpHub.FIRST_WAIT_MS < McpHub.CONNECT_TIMEOUT_MS)

        // While it is still connecting in the background, later turns do not wait again.
        started = TimeSource.Monotonic.markNow()
        hub.prepare()
        assertTrue(started.elapsedNow() < (McpHub.FIRST_WAIT_MS + 2_000).milliseconds)
        hub.close()
    }

    @Test
    fun aFailingTokenStoreIsTheServersFailureNotTheTurns() = runBlocking {
        val hub = McpHub(BlackHole, { error("keychain locked") })
        hub.update(listOf(dead))
        hub.prepare()
        val status = hub.status.value.single()
        assertEquals(McpServerState.UNAVAILABLE, status.state)
        assertEquals("keychain locked", status.message)

        // The retry is not awaited in full, and comes after a growing back-off.
        val started = TimeSource.Monotonic.markNow()
        hub.prepare()
        assertTrue(started.elapsedNow() < (McpHub.RETRY_WAIT_MS + 2_000).milliseconds)
        assertEquals(McpHub.RETRY_MS, McpHub.retryDelay(1))
        assertEquals(2 * McpHub.RETRY_MS, McpHub.retryDelay(2))
        assertEquals(McpHub.MAX_RETRY_MS, McpHub.retryDelay(50))
        hub.close()
    }
}
