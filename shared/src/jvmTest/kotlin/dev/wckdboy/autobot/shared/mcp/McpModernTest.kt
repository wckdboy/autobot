package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.agent.core.Harness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** MCP 2026-07-28 (stateless) and the era detection / fallback to the initialize-based flow. */
class McpModernTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val url = "https://mcp.example.com/mcp"

    private fun client(transport: FakeTransport, token: String? = "tok", subscribe: Boolean = false) =
        McpClient(McpEndpoint.parse(url)!!, transport, token, "INHERIT", "mcp:test", scope, subscribe = subscribe)

    private fun modern(listChanged: Boolean = false) = FakeMcpServer(era = FakeMcpServer.Era.MODERN).apply { this.listChanged = listChanged }

    private fun meta(body: JsonObject) = body["params"]!!.jsonObject["_meta"]!!.jsonObject

    @Test
    fun modernServersAreUsedStatelesslyWithMetaAndMirroredHeaders() = runBlocking {
        val transport = FakeTransport(modern()::handle)
        val c = client(transport)
        val info = c.initialize()
        assertEquals(McpEra.MODERN, c.era)
        assertEquals("2026-07-28", info.protocolVersion)
        assertEquals("modern", info.name)
        assertEquals("Use the tools.", info.instructions)

        assertEquals(listOf("echo"), c.listTools().map { it.name })
        val result = c.callTool("echo", buildJsonObject { put("text", "hi") })
        assertEquals("echo: hi", (result.content.single() as McpContent.Text).text)
        c.close()

        // No initialize, no session, no DELETE: discover, list, call.
        assertEquals(listOf("server/discover", "tools/list", "tools/call"), transport.methods())
        transport.sent.forEachIndexed { i, r ->
            val body = transport.bodies()[i]
            assertEquals("POST", r.method)
            assertEquals("2026-07-28", r.headers["MCP-Protocol-Version"])
            assertEquals(body["method"]!!.jsonPrimitive.content, r.headers["Mcp-Method"])
            assertEquals("application/json, text/event-stream", r.headers["Accept"])
            assertNull(r.headers["Mcp-Session-Id"])
            assertEquals("Bearer tok", r.headers["Authorization"])
            val m = meta(body)
            assertEquals("2026-07-28", m["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), m["io.modelcontextprotocol/clientCapabilities"])
            assertEquals("Autobot", m["io.modelcontextprotocol/clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        }
        assertEquals("echo", transport.sent[2].headers["Mcp-Name"])
        assertNull(transport.sent[1].headers["Mcp-Name"])
        Unit
    }

    @Test
    fun modernSseResponsesWork() = runBlocking {
        val server = modern().apply { sse = true }
        val c = client(FakeTransport(server::handle))
        assertEquals(listOf("echo"), c.listTools().map { it.name })
        assertEquals("echo: x", (c.callTool("echo", buildJsonObject { put("text", "x") }).content.single() as McpContent.Text).text)
    }

    @Test
    fun legacyServersFallBackAfterTheProbeAndTheEraIsCached() = runBlocking {
        val server = FakeMcpServer() // legacy, sessionful: the probe gets 400 without a modern error
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        val info = c.initialize()
        assertEquals(McpEra.LEGACY, c.era)
        assertEquals("2025-11-25", info.protocolVersion)
        c.listTools()
        c.close()
        c.listTools() // reconnects: still legacy, no second probe
        assertEquals(
            listOf("server/discover", "initialize", "notifications/initialized", "tools/list", "DELETE", "initialize", "notifications/initialized", "tools/list"),
            transport.methods(),
        )
        assertEquals("2026-07-28", transport.sent[0].headers["MCP-Protocol-Version"])
        assertNull(transport.bodies()[1]["params"]!!.jsonObject["_meta"])
        assertEquals("sess-1", transport.sent[3].headers["Mcp-Session-Id"])
    }

    @Test
    fun statelessLegacyServersThatAnswerMethodNotFoundFallBack() = runBlocking {
        val transport = FakeTransport(FakeMcpServer(session = null)::handle) // -32601 inside a 200
        val c = client(transport)
        c.listTools()
        assertEquals(McpEra.LEGACY, c.era)
        assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list"), transport.methods())
    }

    @Test
    fun dualEraServersStayModern() = runBlocking {
        val transport = FakeTransport(FakeMcpServer(era = FakeMcpServer.Era.DUAL)::handle)
        val c = client(transport)
        c.listTools()
        assertEquals(McpEra.MODERN, c.era)
        assertTrue(transport.methods().none { it == "initialize" })
    }

    @Test
    fun unsupportedVersionListingOnlyLegacyRevisionsFallsBack() = runBlocking {
        val server = FakeMcpServer(era = FakeMcpServer.Era.DUAL).apply { supportedVersions = listOf("2025-11-25") }
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        c.listTools()
        assertEquals(McpEra.LEGACY, c.era)
        assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list"), transport.methods())
    }

    @Test
    fun unsupportedVersionWithNothingInCommonFails() = runBlocking {
        val server = modern().apply { supportedVersions = listOf("2027-01-01") }
        try {
            client(FakeTransport(server::handle)).initialize()
            fail("expected a failure")
        } catch (e: McpException) {
            assertEquals(McpErrorKind.PROTOCOL, e.kind)
            assertTrue(e.message!!.contains("2027-01-01"))
        }
    }

    @Test
    fun authAndPolicyFailuresDoNotFallBack() = runBlocking {
        val unauthorized = FakeTransport { FakeResponse(401) }
        try {
            client(unauthorized).initialize()
            fail("expected AUTH")
        } catch (e: McpException) {
            assertEquals(McpErrorKind.AUTH, e.kind)
        }
        assertEquals(1, unauthorized.sent.size)

        val offline = FakeTransport(modern()::handle).apply { blocked = "Offline mode: network access is disabled" }
        try {
            client(offline).initialize()
            fail("expected BLOCKED")
        } catch (e: McpException) {
            assertEquals(McpErrorKind.BLOCKED, e.kind)
        }
        Unit
    }

    @Test
    fun xMcpHeaderParametersAreMirroredAndEncoded() = runBlocking {
        val server = modern()
        server.toolOverrides["sql"] = buildJsonObject {
            put("name", "sql")
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("region") { put("type", "string"); put("x-mcp-header", "Region") }
                    putJsonObject("limit") { put("type", "integer"); put("x-mcp-header", "Limit") }
                    putJsonObject("dry") { put("type", "boolean"); put("x-mcp-header", "Dry") }
                    putJsonObject("opts") {
                        put("type", "object")
                        putJsonObject("properties") { putJsonObject("tenant") { put("type", "string"); put("x-mcp-header", "Tenant") } }
                    }
                    putJsonObject("query") { put("type", "string") }
                }
            }
        }
        server.pages = listOf(listOf("sql"))
        val c = client(FakeTransport(server::handle))
        val tool = c.listTools().single()
        assertEquals(listOf("Region", "Limit", "Dry", "Tenant"), tool.headerParams.map { it.header })

        c.callTool(
            "sql",
            buildJsonObject {
                put("region", "Hello, 世界")
                put("limit", 42)
                put("dry", false)
                putJsonObject("opts") { put("tenant", "acme") }
                put("query", "select 1")
            },
        )
        assertEquals(
            mapOf(
                "Mcp-Param-Region" to "=?base64?SGVsbG8sIOS4lueVjA==?=",
                "Mcp-Param-Limit" to "42",
                "Mcp-Param-Dry" to "false",
                "Mcp-Param-Tenant" to "acme",
            ),
            server.paramHeaders.single(),
        )
        // Absent arguments mean no header.
        c.callTool("sql", buildJsonObject { put("query", "select 2") })
        assertEquals(emptyMap<String, String>(), server.paramHeaders.last())
    }

    @Test
    fun headerValueEncodingFollowsTheSpecExamples() {
        assertEquals("us-west1", McpHeaders.encode("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpHeaders.encode("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpHeaders.encode(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpHeaders.encode("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpHeaders.encode("=?base64?literal?="))
    }

    @Test
    fun toolsWithInvalidXMcpHeaderAnnotationsAreLeftOut() = runBlocking {
        fun tool(name: String, properties: JsonObject) = buildJsonObject {
            put("name", name)
            putJsonObject("inputSchema") { put("type", "object"); put("properties", properties) }
        }
        val server = modern()
        server.toolOverrides["number"] = tool("number", buildJsonObject { putJsonObject("n") { put("type", "number"); put("x-mcp-header", "N") } })
        server.toolOverrides["dupe"] = tool(
            "dupe",
            buildJsonObject {
                putJsonObject("a") { put("type", "string"); put("x-mcp-header", "Same") }
                putJsonObject("b") { put("type", "string"); put("x-mcp-header", "same") }
            },
        )
        server.toolOverrides["token"] = tool("token", buildJsonObject { putJsonObject("a") { put("type", "string"); put("x-mcp-header", "Bad Name") } })
        server.toolOverrides["nested"] = tool(
            "nested",
            buildJsonObject {
                putJsonObject("list") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string"); put("x-mcp-header", "Item") }
                }
            },
        )
        server.toolOverrides["anyof"] = tool(
            "anyof",
            buildJsonObject { putJsonObject("v") { putJsonArray("anyOf") { add(buildJsonObject { put("type", "string"); put("x-mcp-header", "V") }) } } },
        )
        server.pages = listOf(listOf("echo", "number", "dupe", "token", "nested", "anyof"))
        assertEquals(listOf("echo"), client(FakeTransport(server::handle)).listTools().map { it.name })
    }

    @Test
    fun unsafeToolNamesAreBase64EncodedInMcpName() = runBlocking {
        val server = modern()
        server.pages = listOf(listOf("météo"))
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        c.listTools()
        c.callTool("météo", JsonObject(emptyMap()))
        assertTrue(transport.sent.last().headers["Mcp-Name"]!!.startsWith("=?base64?"))
    }

    @Test
    fun headerMismatchReListsAndRetriesOnce() = runBlocking {
        val server = modern().apply { mismatchNextCall = true }
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        c.listTools()
        val result = c.callTool("echo", buildJsonObject { put("text", "again") })
        assertEquals("echo: again", (result.content.single() as McpContent.Text).text)
        assertEquals(listOf("server/discover", "tools/list", "tools/call", "tools/list", "tools/call"), transport.methods())
    }

    @Test
    fun inputRequiredAndUnknownResultTypesAreErrors() = runBlocking {
        for (type in listOf("input_required", "something_new")) {
            val server = modern()
            server.callResult = { _, _ -> buildJsonObject { put("resultType", type) } }
            try {
                client(FakeTransport(server::handle)).callTool("echo", JsonObject(emptyMap()))
                fail("expected a failure for $type")
            } catch (e: McpException) {
                assertEquals(McpErrorKind.PROTOCOL, e.kind)
            }
        }
    }

    @Test
    fun subscriptionsListenDeliversListChanged() = runBlocking {
        val server = modern(listChanged = true)
        server.listenNotifications += buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/tools/list_changed") }
        val transport = FakeTransport(server::handle)
        val c = client(transport, subscribe = true)
        c.listTools()
        withTimeout(5_000) { while (!c.toolsChanged) delay(10) }
        val listen = transport.sent.single { it.headers["Mcp-Method"] == "subscriptions/listen" }
        val body = transport.bodies()[transport.sent.indexOf(listen)]
        assertEquals(true, body["params"]!!.jsonObject["notifications"]!!.jsonObject["toolsListChanged"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2026-07-28", meta(body)["io.modelcontextprotocol/protocolVersion"]!!.jsonPrimitive.content)
        // The server ended the subscription gracefully: no reconnect loop.
        delay(100)
        assertEquals(1, transport.methods().count { it == "subscriptions/listen" })
    }

    @Test
    fun noSubscriptionWhenTheServerDoesNotOfferListChanged() = runBlocking {
        val transport = FakeTransport(modern(listChanged = false)::handle)
        client(transport, subscribe = true).listTools()
        delay(100)
        assertTrue(transport.methods().none { it == "subscriptions/listen" })
    }

    @Test
    fun hubConnectsModernServersAndReListsOnListChanged() = runBlocking {
        val server = modern(listChanged = true)
        val transport = FakeTransport(server::handle)
        val hub = McpHub(transport, { "t" })
        val harness = Harness()
        harness.root.plugin(hub.plugin)
        hub.update(listOf(McpServerSpec("m", "Modern", url)))
        server.pages = listOf(listOf("one"))
        server.listenNotifications += buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/tools/list_changed") }
        hub.prepare()
        assertNotNull(harness.tools.get("mcp__modern__one"))
        assertEquals("2026-07-28", hub.status.value.single().protocolVersion)

        withTimeout(5_000) { while (transport.methods().none { it == "subscriptions/listen" }) delay(10) }
        server.pages = listOf(listOf("two"))
        withTimeout(5_000) {
            while (harness.tools.get("mcp__modern__two") == null) {
                hub.prepare()
                delay(20)
            }
        }
        assertNull(harness.tools.get("mcp__modern__one"))
        assertFalse(transport.methods().contains("initialize"))

        val tested = McpHub.test(transport, McpServerSpec("m", "Modern", url), null).getOrThrow()
        assertEquals("1 tools · MCP 2026-07-28", tested.toString())
    }
}
