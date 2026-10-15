package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
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
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHubTest {
    private val notes = McpServerSpec("n1", "Notes", "https://notes.example.com/mcp", route = "TOR")
    private val docs = McpServerSpec("d1", "Docs", "https://docs.example.org/mcp", trusted = true)

    /** Routes requests to one fake MCP server per host. */
    private class Hosts(vararg servers: Pair<String, FakeMcpServer>) {
        val map = servers.toMap()
        val transport = FakeTransport { r -> map.getValue(McpEndpoint.hostOf(r.url)!!).handle(r) }
    }

    private fun hub(hosts: Hosts, clock: () -> Long = { 0L }) = McpHub(hosts.transport, { id -> "token-$id" }, clock)

    @Test
    fun enabledServersRegisterPrefixedToolsWithTheirSchema() = runBlocking {
        val hosts = Hosts("notes.example.com" to FakeMcpServer().apply { pages = listOf(listOf("search", "read")) }, "docs.example.org" to FakeMcpServer())
        val hub = hub(hosts)
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes, docs, McpServerSpec("x", "Off", "https://off.example.com/mcp", enabled = false)))
        assertTrue(hosts.transport.sent.isEmpty()) // nothing is dialled until a turn needs tools

        hub.prepare()
        val names = harness.tools.list().map { it.name }.filter { it.startsWith("mcp__") }
        assertEquals(listOf("mcp__docs__echo", "mcp__notes__read", "mcp__notes__search"), names)
        val tool = harness.tools.get("mcp__notes__search") as McpToolDefinition
        assertEquals("notes.example.com", tool.host)
        assertEquals("search", tool.remoteName)
        assertEquals("[MCP server Notes] Tool search", tool.description)
        assertTrue(tool.parameters.toString().contains("\"text\""))
        assertEquals("notes.example.com · text=hi", tool.describeCall(buildJsonObject { put("text", "hi") }))

        val status = hub.status.value.associateBy { it.id }
        assertEquals(McpServerState.READY, status.getValue("n1").state)
        assertEquals(2, status.getValue("n1").toolCount)
        assertEquals(McpServerState.DISABLED, status.getValue("x").state)
        assertTrue(hosts.transport.sent.none { it.url.contains("off.example.com") })
        // Each server's token and route stay with that server's host.
        hosts.transport.sent.forEach { r ->
            val host = McpEndpoint.hostOf(r.url)
            assertEquals(if (host == "notes.example.com") "Bearer token-n1" else "Bearer token-d1", r.headers["Authorization"])
            assertEquals(if (host == "notes.example.com") "TOR" else "INHERIT", r.route)
        }
        assertTrue(hub.notice()!!.contains("Notes (notes.example.com): 2 tools, named mcp__notes__*"))
    }

    @Test
    fun listChangedRefreshesAtTheNextPrepare() = runBlocking {
        val server = FakeMcpServer(sse = true)
        val hosts = Hosts("notes.example.com" to server)
        val hub = hub(hosts)
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes))
        hub.prepare()
        assertNotNull(harness.tools.get("mcp__notes__echo"))

        server.pages = listOf(listOf("echo2"))
        server.preamble += buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/tools/list_changed") }
        (harness.tools.get("mcp__notes__echo") as McpToolDefinition).execute(buildJsonObject { put("text", "a") }, NoRun)

        val before = hosts.transport.methods().count { it == "tools/list" }
        hub.prepare()
        assertEquals(before + 1, hosts.transport.methods().count { it == "tools/list" })
        assertNull(harness.tools.get("mcp__notes__echo"))
        assertNotNull(harness.tools.get("mcp__notes__echo2"))
        // Same session: no second initialize.
        assertEquals(1, hosts.transport.methods().count { it == "initialize" })

        hub.prepare() // not stale: nothing sent
        assertEquals(before + 1, hosts.transport.methods().count { it == "tools/list" })
    }

    @Test
    fun offlineServersContributeNoToolsAndAClearNotice() = runBlocking {
        val hosts = Hosts("notes.example.com" to FakeMcpServer())
        hosts.transport.blocked = "Offline mode: network access is disabled"
        var now = 0L
        val hub = hub(hosts) { now }
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes))
        hub.prepare()

        assertTrue(harness.tools.list().none { it.name.startsWith("mcp__") })
        val status = hub.status.value.single()
        assertEquals(McpServerState.UNAVAILABLE, status.state)
        assertEquals("blocked: Offline mode: network access is disabled", status.message)
        assertTrue(hub.notice()!!.contains("Notes (notes.example.com): unavailable — blocked: Offline mode"))

        // Back online: retried only after the back-off.
        hosts.transport.blocked = null
        hub.prepare()
        assertTrue(hosts.transport.sent.isEmpty())
        now = McpHub.RETRY_MS
        hub.prepare()
        assertEquals(McpServerState.READY, hub.status.value.single().state)
        assertNotNull(harness.tools.get("mcp__notes__echo"))
    }

    @Test
    fun disablingOrRemovingAServerDropsItsToolsAndEndsTheSession() = runBlocking {
        val hosts = Hosts("notes.example.com" to FakeMcpServer())
        val hub = hub(hosts)
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes))
        hub.prepare()
        assertNotNull(harness.tools.get("mcp__notes__echo"))

        hub.update(listOf(notes.copy(enabled = false)))
        assertNull(harness.tools.get("mcp__notes__echo"))
        assertEquals(McpServerState.DISABLED, hub.status.value.single().state)
        withTimeout(5_000) { while (hosts.transport.sent.none { it.method == "DELETE" }) delay(10) }
        assertNull(hub.notice())

        hub.update(emptyList())
        assertTrue(hub.status.value.isEmpty())
    }

    @Test
    fun aNewRevisionReconnects() = runBlocking {
        val hosts = Hosts("notes.example.com" to FakeMcpServer())
        val hub = hub(hosts)
        hub.update(listOf(notes))
        hub.prepare()
        hub.update(listOf(notes.copy(revision = 2)))
        hub.prepare()
        assertEquals(2, hosts.transport.methods().count { it == "initialize" })
    }

    @Test
    fun duplicateServerNamesGetDistinctPrefixes() = runBlocking {
        val hosts = Hosts("notes.example.com" to FakeMcpServer(), "docs.example.org" to FakeMcpServer())
        val hub = hub(hosts)
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes, docs.copy(name = "Notes")))
        hub.prepare()
        assertEquals(setOf("mcp__notes__echo", "mcp__notes_2__echo"), harness.tools.list().map { it.name }.filter { it.startsWith("mcp__") }.toSet())
    }

    @Test
    fun testConnectionReportsToolCountAndEndsTheSession() = runBlocking {
        val server = FakeMcpServer().apply { pages = listOf(listOf("a", "b"), listOf("c")) }
        val transport = FakeTransport(server::handle)
        val tested = McpHub.test(transport, notes, "t").getOrThrow()
        assertEquals(3, tested.toolCount)
        assertEquals(McpProtocol.LEGACY_VERSION, tested.protocolVersion)
        assertEquals("server/discover", transport.methods().first())
        assertEquals("DELETE", transport.sent.last().method)
        assertTrue(transport.sent.all { it.headers["Authorization"] == "Bearer t" && it.tag == "mcp:Notes" })

        transport.blocked = "Offline mode: network access is disabled"
        val failure = McpHub.test(transport, notes, null).exceptionOrNull()!!
        assertEquals("blocked: Offline mode: network access is disabled", McpHub.describe(failure))
        assertEquals("Invalid URL", McpHub.test(transport, notes.copy(url = "notes"), null).exceptionOrNull()!!.message)
    }

    // ---------------------------------------------------------------------------------------
    // End to end through the shared agent loop: approval gate, trust, results in the transcript.

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null    }

    private object NoStore : SessionStoreBridge {
        override fun load(sessionId: String): List<String> = emptyList()
        override fun append(sessionId: String, entries: List<String>) = Unit
    }

    /** The chat provider (scripted SSE) and the MCP servers behind one transport. */
    private class World(tool: String) : HttpTransport {
        val mcp = Hosts("notes.example.com" to FakeMcpServer(), "docs.example.org" to FakeMcpServer())
        val chat = CopyOnWriteArrayList<HttpRequestSpec>()
        private val replies = ArrayDeque(
            listOf(
                listOf(
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"$tool","arguments":"{\"text\":\"hi\"}"}}]}}]}""",
                    """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                    "data: [DONE]",
                ),
                listOf("""data: {"choices":[{"delta":{"content":"Done."}}]}""", """data: {"choices":[{"finish_reason":"stop"}]}""", "data: [DONE]"),
            ),
        )

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            if (!request.url.startsWith("https://api.example.com")) return mcp.transport.send(request, sink)
            chat += request
            sink.onResponse(200, emptyMap(), null)
            synchronized(replies) { replies.removeFirst() }.forEach(sink::onLine)
            sink.onComplete()
            return Cancellable { }
        }
    }

    private fun runTurn(
        world: World,
        servers: List<McpServerSpec>,
        preset: PermissionPreset = PermissionPreset.WORKSPACE,
        answer: ((SessionState) -> Unit)?,
    ): List<SessionState> = runBlocking {
        val providers = object : ProviderDirectory {
            override fun provider(id: String) = ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", null, "m").takeIf { id == "p" }
        }
        val dir = Files.createTempDirectory("mcp").toFile()
        val bridge = AgentBridge(dir.path, providers, world, NoLocal, NoStore, null, { id -> "token-$id" })
        bridge.setMcpServers(servers)
        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        bridge.setPermission("s1", preset)
        withTimeout(5_000) { while (states.last().permission != preset.label) delay(10) }
        bridge.send("s1", "Use the notes", emptyList())
        withTimeout(15_000) {
            while (true) {
                val last = states.lastOrNull()
                if (last != null && last.approvals.isNotEmpty() && answer != null) {
                    answer(last)
                    bridge.answerApproval(last.approvals.single().callId, ApprovalOutcome.ALLOWED_ONCE)
                    while (states.last().approvals.isNotEmpty()) delay(10)
                }
                if (last != null && !last.running && last.projection.items.any { it is SessionItem.Assistant }) break
                delay(20)
            }
        }
        handle.cancel()
        states
    }

    @Test
    fun untrustedServerToolsAlwaysAskAndShowServerAndHost() {
        var asked: SessionState? = null
        val world = World("mcp__notes__echo")
        val states = runTurn(world, listOf(notes, docs)) { asked = it }

        val approval = asked!!.approvals.single()
        assertEquals("mcp__notes__echo", approval.toolName)
        assertEquals("MCP · Notes · notes.example.com", approval.reason)
        assertEquals("notes.example.com · text=hi", approval.summary)
        val card = states.last().projection.items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals(ToolState.OK, card.state)
        assertEquals("echo: hi", card.output)
        // The model saw the MCP tools and the notice; the call went to the right host with its token.
        assertTrue(world.chat[0].body!!.contains("\"mcp__notes__echo\""))
        assertTrue(world.chat[0].body!!.contains("MCP servers (remote tools"))
        val call = world.mcp.transport.sent.single { it.body?.contains("tools/call") == true }
        assertEquals("https://notes.example.com/mcp", call.url)
        assertEquals("Bearer token-n1", call.headers["Authorization"])
        assertTrue(world.chat[1].body!!.contains("echo: hi"))
    }

    @Test
    fun untrustedServerToolsAskEvenUnderFullAccess() {
        var asked: SessionState? = null
        runTurn(World("mcp__notes__echo"), listOf(notes, docs), PermissionPreset.FULL_ACCESS) { asked = it }
        assertEquals("MCP · Notes · notes.example.com", asked!!.approvals.single().reason)
    }

    @Test
    fun trustedServerToolsAskLikeWebFetchUnderWorkspaceAndReadOnly() {
        for (preset in listOf(PermissionPreset.WORKSPACE, PermissionPreset.READ_ONLY)) {
            var asked: SessionState? = null
            val world = World("mcp__docs__echo")
            val states = runTurn(world, listOf(notes, docs), preset) { asked = it }
            assertEquals(preset.label, "MCP · Docs · docs.example.org", asked?.approvals?.single()?.reason)
            assertEquals(ToolState.OK, states.last().projection.items.filterIsInstance<SessionItem.Tool>().single().state)
        }
    }

    @Test
    fun trustedServerToolsRunWithoutAskingUnderFullAccess() {
        val world = World("mcp__docs__echo")
        val states = runTurn(world, listOf(notes, docs), PermissionPreset.FULL_ACCESS, answer = null)
        assertTrue(states.all { it.approvals.isEmpty() })
        val card = states.last().projection.items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals(ToolState.OK, card.state)
        assertFalse(world.mcp.transport.sent.none { it.url.startsWith("https://docs.example.org") && it.body?.contains("tools/call") == true })
    }

    @Test
    fun mcpToolsAreNetworkToolsAndReadOnlyHintIsOnlyShown() = runBlocking {
        val server = FakeMcpServer()
        val hosts = Hosts("notes.example.com" to server)
        val readOnly = FakeMcpServer.tool("look").let { t ->
            kotlinx.serialization.json.JsonObject(t + ("annotations" to buildJsonObject { put("readOnlyHint", true) }))
        }
        server.toolOverrides["look"] = readOnly
        server.pages = listOf(listOf("look"))
        val hub = hub(hosts)
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(notes))
        hub.prepare()
        val tool = harness.tools.get("mcp__notes__look") as McpToolDefinition
        assertEquals(dev.wckdboy.autobot.agent.core.tools.ToolKind.FETCH, tool.kind)
        assertEquals(true, tool.readOnlyHint)
        assertTrue(tool.description.startsWith("[MCP server Notes · read-only]"))
    }

    private object NoRun : dev.wckdboy.autobot.agent.core.tools.ToolRunContext {
        override val callId = "c"
        override val sessionId = "s"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: dev.wckdboy.autobot.agent.core.session.SessionEvent) = Unit
        override suspend fun askUser(question: dev.wckdboy.autobot.agent.core.tools.UserQuestion): List<String>? = null
    }
}
