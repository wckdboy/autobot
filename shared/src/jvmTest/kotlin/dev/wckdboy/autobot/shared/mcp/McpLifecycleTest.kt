package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S4 / S5: turns never wait long for MCP, and list-change streams stop in the background. */
class McpLifecycleTest {
    private val url = "https://mcp.example.com/mcp"

    /** Accepts every request and never answers. */
    private object BlackHole : HttpTransport {
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable = Cancellable { }
    }

    /**
     * A modern server whose `subscriptions/listen` streams stay open (acknowledged, then quiet)
     * until cancelled; everything else is answered by [FakeMcpServer].
     */
    private class HangingListen(private val server: FakeMcpServer) : HttpTransport {
        val listens = AtomicInteger()
        val cancelled = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            val method = request.body?.let { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content } ?: request.method
            methods += method
            if (method != "subscriptions/listen") return FakeTransport(server::handle).send(request, sink)
            listens.incrementAndGet()
            sink.onResponse(200, mapOf("content-type" to "text/event-stream"), null)
            sink.onLine("event: message")
            val id = Json.parseToJsonElement(request.body!!).jsonObject["id"]
            sink.onLine(
                """data: {"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged",""" +
                    """"params":{"_meta":{"io.modelcontextprotocol/subscriptionId":$id},"notifications":{"toolsListChanged":true}}}""",
            )
            sink.onLine("")
            return Cancellable { cancelled.incrementAndGet() }
        }
    }

    @Test
    fun aDeadServerHoldsATurnForAtMostAFewHundredMilliseconds() = runBlocking {
        val hub = McpHub(BlackHole, { null })
        hub.update(listOf(McpServerSpec("d", "Dead", url)))
        val started = TimeSource.Monotonic.markNow()
        hub.prepare()
        val first = started.elapsedNow()
        assertTrue("first prepare took $first", first < 600.milliseconds)
        val again = TimeSource.Monotonic.markNow()
        hub.prepare()
        assertTrue("second prepare took ${again.elapsedNow()}", again.elapsedNow() < 600.milliseconds)
        // The turn's notice is rendered from the current status.
        assertTrue(hub.notice().orEmpty(), hub.notice().orEmpty().contains("not connected yet"))
        hub.close()
    }

    @Test
    fun listenStreamsPauseInTheBackgroundAndResubscribeAfter() = runBlocking {
        val server = FakeMcpServer(era = FakeMcpServer.Era.MODERN).apply { listChanged = true }
        val transport = HangingListen(server)
        val hub = McpHub(transport, { "t" })
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(McpServerSpec("m", "Modern", url)))
        withTimeout(5_000) {
            while (transport.listens.get() == 0) {
                hub.prepare()
                delay(10)
            }
        }
        val lists = transport.methods.count { it == "tools/list" }

        hub.setForeground(false)
        withTimeout(5_000) { while (transport.cancelled.get() == 0) delay(10) }
        // No reconnect loop while in the background, also when a turn runs meanwhile.
        hub.prepare()
        delay(300)
        assertEquals(1, transport.listens.get())

        hub.setForeground(true)
        hub.prepare()
        // Back: the server is re-listed (catching changes missed meanwhile) and resubscribed.
        withTimeout(5_000) { while (transport.listens.get() < 2) delay(10) }
        assertTrue(transport.methods.count { it == "tools/list" } > lists)
        hub.close()
    }
}
