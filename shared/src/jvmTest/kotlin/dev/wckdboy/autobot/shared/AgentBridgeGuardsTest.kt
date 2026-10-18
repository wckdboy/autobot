package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.mcp.McpServerSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin/Native ends the process on an exception that escapes a coroutine, and a Swift
 * continuation that is never resumed hangs its caller: the bridge must absorb failures and
 * always answer.
 */
class AgentBridgeGuardsTest {

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    /** A host transport that throws instead of reporting a failure. */
    private object ThrowingTransport : HttpTransport {
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable = throw IllegalStateException("host transport exploded")
    }

    private class Store(var failing: Boolean = false) : SessionStoreBridge {
        val lines = HashMap<String, MutableList<String>>()
        override fun load(sessionId: String): List<String> = synchronized(lines) { lines[sessionId].orEmpty().toList() }
        override fun append(sessionId: String, entries: List<String>) {
            if (failing) throw IllegalStateException("disk full")
            synchronized(lines) { lines.getOrPut(sessionId) { mutableListOf() }.addAll(entries) }
        }
    }

    private val providers = object : ProviderDirectory {
        override fun provider(id: String) = ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", "k", "m")
    }

    private val spec = ProviderSpec("p", ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", "k", "m")

    private fun bridge(store: SessionStoreBridge = Store(), transport: HttpTransport = ThrowingTransport, dir: String = Files.createTempDirectory("guards").toString()) =
        AgentBridge(dir, providers, transport, NoLocal, store, null, { null })

    private fun <T> CompletableFuture<T>.within(): T = get(10, TimeUnit.SECONDS)

    @Test
    fun launchesSurviveExceptionsAndStillAnswer() {
        val bridge = bridge()
        val test = CompletableFuture<Pair<Boolean, String>>()
        bridge.testConnection(spec) { ok, message, _ -> test.complete(ok to message) }
        assertFalse(test.within().first)

        val models = CompletableFuture<Pair<String?, String?>>()
        bridge.listModels(spec) { json, error -> models.complete(json to error) }
        assertEquals(null, models.within().first)
        assertTrue(models.within().second!!.isNotBlank())

        val mcp = CompletableFuture<Boolean>()
        bridge.testMcpServer(McpServerSpec("m", "M", "https://mcp.example.com/mcp"), null) { ok, _ -> mcp.complete(ok) }
        assertFalse(mcp.within())
    }

    @Test
    fun callbacksStillResumeAfterShutdown() {
        val bridge = bridge()
        val done = CompletableFuture<Unit>()
        bridge.shutdown { done.complete(Unit) }
        done.within()

        val test = CompletableFuture<String>()
        bridge.testConnection(spec) { _, message, _ -> test.complete(message) }
        assertEquals("Cancelled", test.within())
        val models = CompletableFuture<String?>()
        bridge.listModels(spec) { _, error -> models.complete(error) }
        assertEquals("Cancelled", models.within())
        val mcp = CompletableFuture<String>()
        bridge.testMcpServer(McpServerSpec("m", "M", "https://mcp.example.com/mcp"), null) { _, message -> mcp.complete(message) }
        assertEquals("Cancelled", mcp.within())
        // A second shutdown (wipe after a lock) still reports done.
        val again = CompletableFuture<Unit>()
        bridge.shutdown { again.complete(Unit) }
        again.within()
    }

    @Test
    fun aFailingStoreDoesNotTakeTheBridgeDown() {
        val store = Store(failing = true)
        val bridge = bridge(store)
        val states = AtomicInteger()
        val handle = bridge.open("s1", "p", "m") { states.incrementAndGet() }
        bridge.setToolsEnabled("s1", false) // logs a settings change: the store throws
        bridge.compact("s1")
        bridge.send("s1", "hello", emptyList()) // the turn fails on the store, not the process

        val test = CompletableFuture<Boolean>()
        bridge.testConnection(spec) { ok, _, _ -> test.complete(ok) }
        assertFalse(test.within())
        handle.cancel()
    }

    @Test
    fun badHostInputIsAbsorbed() {
        // A workspace that cannot be created (a file is in the way) fails tool calls, not the constructor.
        val file = File.createTempFile("not-a-dir", ".txt")
        val bridge = bridge(dir = File(file, "workspace").path)
        assertEquals(emptyList<String>(), bridge.skills())

        bridge.registerTool(
            object : HostTool {
                override val name = "broken"
                override val description = "schema is not JSON"
                override val parametersJson = "{not json"
                override val kind = "GENERATE"
                override val timeoutMs = 0L
                override fun describe(argsJson: String) = ""
                override fun execute(callId: String, sessionId: String, argsJson: String, sink: HostToolSink) = Cancellable { }
            },
        )
        assertTrue(bridge.harness.tools.get("broken") != null)
    }

    @Test
    fun nextSeqGoesAboveUndecodableLines() = runBlocking {
        val good = SessionJson.encodeToString(LogEntry.serializer(), LogEntry(1, 0, SessionEvent.TurnStart(1)))
        val store = Store()
        store.append(
            "s",
            listOf(
                good,
                """{"seq":7,"time":0,"event":{"type":"from_a_newer_version"}}""", // decodes as JSON, not as an entry
                "garbage that is not JSON", // its seq is unknown: at least one above the line before
            ),
        )
        val stored = BridgedSessionStore(store).loadLog("s")
        assertEquals(1, stored.entries.size)
        assertEquals(8, stored.lastSeq)
    }
}
