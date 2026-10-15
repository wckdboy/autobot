package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.AgentBridge
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.LocalEngine
import dev.wckdboy.autobot.shared.LocalSink
import dev.wckdboy.autobot.shared.ProviderDirectory
import dev.wckdboy.autobot.shared.ProviderSpec
import dev.wckdboy.autobot.shared.SessionState
import dev.wckdboy.autobot.shared.SessionStoreBridge
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptsTest {

    // -----------------------------------------------------------------------------------------
    // Correlation

    @Test
    fun theAuditLogTiesEntriesToTurnsAndTakesLateByteCounts() {
        val log = AuditLog(clock = { 0L })
        val a = log.record("POST", "api.example.com", "/v1/chat?key=x", bytesSent = 900, route = "direct", blocked = false, session = "s1", turn = 1)
        log.record("GET", "huggingface.co", "/models", route = "direct", blocked = false) // not an agent turn
        log.record("GET", "example.org", "/", route = "tor", blocked = false, session = "s1", turn = 2)
        log.record("GET", "example.org", "/", route = "tor", blocked = false, session = "s2", turn = 1)

        assertNull(a.bytesReceived) // a stream has no Content-Length
        log.received(a.id, 4096)
        log.received(999, 1) // gone: ignored

        val turn = log.forTurn("s1", 1).single()
        assertEquals("/v1/chat", turn.path)
        assertEquals(4096L, turn.bytesReceived)
        assertEquals(listOf("example.org"), log.forTurn("s1", 2).map { it.host })
    }

    @Test
    fun requestsCarryTheTurnTheyAreMadeFor() = runBlocking {
        val spec = HttpRequestSpec("GET", "https://example.org", emptyMap(), null, "agent:web_fetch")
        assertSame(spec, spec.traced()) // outside a turn: unchanged
        val traced = withContext(TurnTrace("s1", 3)) { spec.traced() }
        assertEquals(TurnTrace("s1", 3), traced.trace)
        assertEquals("agent:web_fetch", traced.tag)
    }

    // -----------------------------------------------------------------------------------------
    // Building and summarizing

    @Test
    fun networkGroupsByHostAndRoute() {
        val log = AuditLog(clock = { 0L })
        log.record("POST", "api.example.com", "/a", bytesSent = 1000, bytesReceived = 50, tag = "Example", route = "direct", blocked = false)
        log.record("POST", "api.example.com", "/a", bytesSent = 2000, tag = "Example", route = "direct", blocked = false)
        log.record("GET", "example.org", "/", tag = "agent:web_fetch", route = "direct", blocked = true, error = "Offline")
        val hosts = Receipts.network(log.entries.value)
        assertEquals(
            listOf(
                ReceiptHost("api.example.com", "direct", requests = 2, bytesSent = 3000, bytesReceived = 50, tags = listOf("Example")),
                ReceiptHost("example.org", "direct", requests = 1, blocked = 1, tags = listOf("agent:web_fetch")),
            ),
            hosts,
        )
    }

    @Test
    fun modelsGetTheirProviderAndDataPolicy() {
        val go = ReceiptProvider("OpenCode Go", onDevice = false, ProviderPresets.OPENCODE_GO, "per model")
        val described = Receipts.describe(ReceiptModel("p", "glm-5"), go)
        assertEquals("OpenCode Go", described.name)
        assertEquals("no retention", described.policy)
        assertEquals("kept 30 days", Receipts.describe(ReceiptModel("p", "claude-haiku-5"), go).policy)
        assertEquals("trains on prompts", Receipts.describe(ReceiptModel("p", "big-contributor"), go).policy)

        val other = Receipts.describe(ReceiptModel("d", "deepseek-chat"), ReceiptProvider("DeepSeek", false, "deepseek", "Stored in the PRC."))
        assertNull(other.policy)
        assertEquals("Stored in the PRC.", other.policyNote)

        val phone = Receipts.describe(ReceiptModel("l", "gemma"), ReceiptProvider("This phone", true, null, null))
        assertTrue(phone.onDevice)
        assertEquals("on device", phone.policy)
    }

    @Test
    fun theLineSaysWhatLeftThePhone() {
        val phone = ReceiptModel("l", "gemma", "This phone", onDevice = true, policy = "on device")
        val local = Receipts.summarize(SessionEvent.TurnReceipt(1, listOf(phone), tools = listOf("read"), network = emptyList()))
        assertTrue(local.onDevice)
        assertEquals("on device", local.line)
        assertEquals(listOf(ReceiptTool("read", network = false)), local.tools)

        // Loopback (a model server on the phone) and refused requests never left it.
        val loopback = Receipts.summarize(
            SessionEvent.TurnReceipt(
                1,
                listOf(phone),
                network = listOf(ReceiptHost("127.0.0.1", "loopback", 1, 500, 500), ReceiptHost("example.org", "direct", 1, blocked = 1)),
            ),
        )
        assertTrue(loopback.onDevice)
        assertEquals("on device · 1 blocked", loopback.line)

        val cloud = ReceiptModel("p", "glm-5", "OpenCode Go", policy = "no retention")
        val sent = Receipts.summarize(
            SessionEvent.TurnReceipt(
                2,
                listOf(cloud),
                tools = listOf("web_fetch", "mcp__notes__search", "read"),
                network = listOf(ReceiptHost("opencode.ai", "direct", 2, 12_000, 30_000), ReceiptHost("example.org", "direct", 1, 2_336, 9_000)),
            ),
        )
        assertFalse(sent.onDevice)
        assertEquals("2 hosts · 14 KB out · no retention", sent.line)
        assertEquals(14_336L, sent.bytesSent)
        assertEquals(39_000L, sent.bytesReceived)
        assertEquals(listOf(true, true, false), sent.tools.map { it.network })

        // Tor shows; the least private policy wins; an undocumented one names the provider.
        val mixed = Receipts.summarize(
            SessionEvent.TurnReceipt(
                3,
                listOf(cloud, ReceiptModel("p", "claude-haiku-5", "OpenCode Go", policy = "kept 30 days")),
                network = listOf(ReceiptHost("opencode.ai", "tor", 1, 2 * 1024 * 1024 + 300_000, 0)),
            ),
        )
        assertEquals("1 host · 2.3 MB out · tor · kept 30 days", mixed.line)
        assertEquals("1 host · 0 B out · DeepSeek", Receipts.summarize(
            SessionEvent.TurnReceipt(4, listOf(ReceiptModel("d", "deepseek-chat", "DeepSeek")), network = listOf(ReceiptHost("api.deepseek.com", "direct", 1))),
        ).line)

        // Untracked traffic: never "on device".
        val untracked = Receipts.summarize(SessionEvent.TurnReceipt(5, listOf(cloud)))
        assertFalse(untracked.onDevice)
        assertFalse(untracked.tracked)
        assertEquals("no retention", untracked.line)
    }

    // -----------------------------------------------------------------------------------------
    // Projection

    private fun entries(vararg events: SessionEvent) = events.mapIndexed { i, e -> LogEntry(i.toLong() + 1, i * 10L, e) }

    @Test
    fun theReceiptLandsOnTheTurnsLastAnswer() {
        val receipt = SessionEvent.TurnReceipt(1, listOf(ReceiptModel("p", "m", onDevice = true)), network = emptyList())
        val p = projectSession(
            entries(
                SessionEvent.TurnStart(1),
                SessionEvent.UserMessage("hi"),
                SessionEvent.AssistantMessage(1, 1, "looking"),
                SessionEvent.AssistantMessage(1, 2, "done"),
                receipt,
                SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED),
                // A turn with no answer: its receipt has nowhere to go and must not land on turn 1's.
                SessionEvent.TurnStart(2),
                SessionEvent.UserMessage("again"),
                receipt.copy(turn = 2),
                SessionEvent.TurnEnd(2, TurnEndReason.ERROR, "TRANSPORT: down"),
            ),
            emptySet(),
            emptySet(),
        ) { _, a -> a }
        val answers = p.items.filterIsInstance<SessionItem.Assistant>()
        assertNull(answers[0].receipt)
        assertEquals("on device", answers[1].receipt!!.line)
        assertEquals(1, answers[1].receipt!!.turn)
    }

    @Test
    fun oldLogsAndUnknownFieldsStillDecode() {
        // A log written before receipts existed has none; a receipt from a newer build with extra fields still loads.
        val old = SessionJson.decodeFromString(LogEntry.serializer(), """{"seq":1,"time":0,"event":{"type":"assistant/message","turn":1,"step":1,"text":"hi"}}""")
        val p = projectSession(listOf(old), emptySet(), emptySet()) { _, a -> a }
        assertNull((p.items.single() as SessionItem.Assistant).receipt)

        val newer = SessionJson.decodeFromString(
            LogEntry.serializer(),
            """{"seq":2,"time":0,"event":{"type":"turn/receipt","turn":1,"tools":["read"],"network":[{"host":"a.b","route":"tor","requests":1,"rtt":3}],"redactions":2}}""",
        )
        val event = newer.event as SessionEvent.TurnReceipt
        assertEquals(listOf("read"), event.tools)
        assertEquals("tor", event.network!!.single().route)
        // And it round-trips.
        assertEquals(newer, SessionJson.decodeFromString(LogEntry.serializer(), SessionJson.encodeToString(LogEntry.serializer(), newer)))
    }

    // -----------------------------------------------------------------------------------------
    // End to end through the bridge

    /** A host transport that audits like the apps do: with the request's turn, then the bytes read. */
    private class AuditingTransport(private val audit: AuditLog, private val bodies: ArrayDeque<List<String>>) : HttpTransport {
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            val entry = audit.record(
                request.method, request.url.substringAfter("://").substringBefore('/'), "/", bytesSent = request.body?.length?.toLong(),
                tag = request.tag, route = "tor", blocked = false, session = request.trace?.sessionId, turn = request.trace?.turn,
            )
            sink.onResponse(200, emptyMap(), null)
            var read = 0L
            bodies.removeFirst().forEach { line ->
                read += line.length + 1
                sink.onLine(line)
            }
            audit.received(entry.id, read)
            sink.onComplete()
            return Cancellable { }
        }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private class MemoryStore : SessionStoreBridge {
        val lines = CopyOnWriteArrayList<String>()
        override fun load(sessionId: String): List<String> = lines.toList()
        override fun append(sessionId: String, entries: List<String>) {
            lines.addAll(entries)
        }
    }

    @Test
    fun aBridgeTurnEndsWithAReceiptOfItsTraffic() = runBlocking {
        val dir = Files.createTempDirectory("receipt").toFile()
        File(dir, "notes.md").writeText("hello")
        val audit = AuditLog()
        audit.record("GET", "huggingface.co", "/", route = "direct", blocked = false) // a download, not this chat
        val transport = AuditingTransport(
            audit,
            ArrayDeque(
                listOf(
                    listOf(
                        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read","arguments":"{\"file_path\":\"notes.md\"}"}}]}}]}""",
                        "data: [DONE]",
                    ),
                    listOf("""data: {"choices":[{"delta":{"content":"It says hello."}}]}""", "data: [DONE]"),
                ),
            ),
        )
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Go", "https://opencode.ai/zen/go/v1", "k", "glm-5", presetId = ProviderPresets.OPENCODE_GO)
        }
        val store = MemoryStore()
        val bridge = AgentBridge(dir.path, providers, transport, NoLocal, store, null, { null }, audit)
        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "glm-5") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        bridge.send("s1", "What is in notes.md?", emptyList())
        withTimeout(10_000) {
            while (states.lastOrNull()?.let { s -> !s.running && s.projection.items.any { it is SessionItem.Assistant } } != true) delay(20)
        }
        handle.cancel()

        val receipt = states.last().projection.items.filterIsInstance<SessionItem.Assistant>().last().receipt!!
        val host = receipt.hosts.single()
        assertEquals("opencode.ai", host.host)
        assertEquals(2, host.requests)
        assertEquals("tor", host.route)
        assertEquals(listOf("Go"), host.tags)
        assertTrue(host.bytesSent > 0 && host.bytesReceived > 0)
        assertEquals(listOf(ReceiptTool("read", network = false)), receipt.tools)
        assertEquals("no retention", receipt.models.single().policy)
        assertEquals("1 host · ${Receipts.formatBytes(host.bytesSent)} out · tor · no retention", receipt.line)
        // Persisted with the session log, ahead of the turn's end.
        val types = store.lines.map { Json.parseToJsonElement(it).toString() }
        val receiptAt = types.indexOfFirst { "\"turn/receipt\"" in it }
        assertTrue(receiptAt >= 0 && "\"turn/end\"" in types[receiptAt + 1])
    }
}
