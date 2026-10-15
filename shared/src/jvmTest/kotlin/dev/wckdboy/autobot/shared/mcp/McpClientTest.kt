package dev.wckdboy.autobot.shared.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val url = "https://mcp.example.com/mcp"

    private fun client(transport: FakeTransport, token: String? = "tok", route: String = "TOR") =
        McpClient(McpEndpoint.parse(url)!!, transport, token, route, "mcp:test", scope)

    private suspend fun expectFailure(kind: McpErrorKind, block: suspend () -> Unit): McpException {
        try {
            block()
        } catch (e: McpException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected $kind")
        error("unreachable")
    }

    @Test
    fun initializeHandshakeNegotiatesAndAcknowledges() = runBlocking {
        val server = FakeMcpServer()
        val transport = FakeTransport(server::handle)
        val info = client(transport).initialize()

        assertEquals("fake", info.name)
        assertEquals(McpProtocol.LATEST_VERSION, info.protocolVersion)
        assertTrue(info.toolsListChanged)
        assertEquals(listOf("initialize", "notifications/initialized"), transport.methods())

        val init = transport.sent[0]
        assertEquals("POST", init.method)
        assertEquals(url, init.url)
        assertEquals("application/json, text/event-stream", init.headers["Accept"])
        assertEquals("application/json", init.headers["Content-Type"])
        assertNull(init.headers[McpProtocol.HEADER_SESSION])
        assertNull(init.headers[McpProtocol.HEADER_VERSION])
        assertEquals("TOR", init.route)
        assertEquals("mcp:test", init.tag)
        val params = transport.bodies()[0]["params"]!!.jsonObject
        assertEquals("2025-11-25", params["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), params["capabilities"])
        assertEquals("Autobot", params["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val ack = transport.sent[1]
        assertEquals("sess-1", ack.headers[McpProtocol.HEADER_SESSION])
        assertEquals("2025-11-25", ack.headers[McpProtocol.HEADER_VERSION])
        assertNull(transport.bodies()[1]["id"])
    }

    @Test
    fun laterRequestsCarryTheSessionAndTheNegotiatedVersion() = runBlocking {
        val server = FakeMcpServer(version = "2025-06-18")
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        c.listTools()
        val list = transport.sent.last()
        assertEquals("tools/list", transport.methods().last())
        assertEquals("sess-1", list.headers["Mcp-Session-Id"])
        assertEquals("2025-06-18", list.headers["MCP-Protocol-Version"])
    }

    @Test
    fun serversWithoutSessionsAreFine() = runBlocking {
        val server = FakeMcpServer(session = null)
        val transport = FakeTransport(server::handle)
        assertEquals(1, client(transport).listTools().size)
        assertTrue(transport.sent.none { it.headers.containsKey(McpProtocol.HEADER_SESSION) })
    }

    @Test
    fun unsupportedProtocolVersionFails() = runBlocking {
        val transport = FakeTransport(FakeMcpServer(version = "2024-11-05")::handle)
        val e = expectFailure(McpErrorKind.PROTOCOL) { client(transport).initialize() }
        assertTrue(e.message!!.contains("2024-11-05"))
    }

    @Test
    fun sseResponsesAnswerPingsAndIgnorePrimingEvents() = runBlocking {
        val server = FakeMcpServer(sse = true)
        val transport = FakeTransport(server::handle)
        val c = client(transport)
        c.initialize()
        server.preamble += buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/message")
            putJsonObject("params") { put("level", "info"); put("data", "hi") }
        }
        server.preamble += buildJsonObject { put("jsonrpc", "2.0"); put("id", "srv-1"); put("method", "ping") }
        server.preamble += buildJsonObject { put("jsonrpc", "2.0"); put("id", 7); put("method", "sampling/createMessage") }

        assertEquals(listOf("echo"), c.listTools().map { it.name })
        withTimeout(5_000) { while (transport.sent.count { it.body?.contains("\"result\"") == true || it.body?.contains("\"error\"") == true } < 2) delay(10) }
        val replies = transport.bodies().filter { it["method"] == null }
        val ping = replies.single { it["id"]!!.jsonPrimitive.content == "srv-1" }
        assertEquals(JsonObject(emptyMap()), ping["result"])
        val sampling = replies.single { it["id"]!!.jsonPrimitive.content == "7" }
        assertEquals(-32601, sampling["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        // Replies go to the same endpoint, in the same session.
        assertTrue(transport.sent.filter { it.body?.contains("srv-1") == true }.all { it.url == url && it.headers["Mcp-Session-Id"] == "sess-1" })
    }

    @Test
    fun jsonAndSseResultsMapToTheSameToolOutput() = runBlocking {
        for (sse in listOf(false, true)) {
            val server = FakeMcpServer(sse = sse)
            server.callResult = { _, _ ->
                FakeMcpServer.content(
                    FakeMcpServer.text("line one"),
                    buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "AAAA") },
                    buildJsonObject { put("type", "resource_link"); put("uri", "file:///a.txt"); put("name", "a.txt") },
                    buildJsonObject {
                        put("type", "resource")
                        putJsonObject("resource") { put("uri", "mem://note"); put("mimeType", "text/plain"); put("text", "note body") }
                    },
                    buildJsonObject { put("type", "mystery") },
                )
            }
            val transport = FakeTransport(server::handle)
            val result = client(transport).callTool("echo", buildJsonObject { put("text", "x") })
            val output = McpResults.toOutput(result, "notes")
            assertFalse(output.isError)
            assertEquals(
                "line one\n\n[image image/png, 3 B — not shown]\n\n[resource a.txt: file:///a.txt]\n\n" +
                    "Resource mem://note:\nnote body\n\n[unsupported content type mystery]",
                output.content,
            )
            assertEquals("notes", output.meta!!["mcpServer"]!!.jsonPrimitive.content)
            assertEquals(1, output.meta!!["mcpMedia"]!!.jsonPrimitive.content.toInt())
            val call = transport.bodies().last { it["method"]?.jsonPrimitive?.content == "tools/call" }
            assertEquals("x", call["params"]!!.jsonObject["arguments"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun structuredContentIsUsedWhenThereIsNoText() = runBlocking {
        val server = FakeMcpServer()
        server.callResult = { _, _ -> buildJsonObject { put("content", kotlinx.serialization.json.JsonArray(emptyList())); putJsonObject("structuredContent") { put("t", 21) } } }
        val output = McpResults.toOutput(client(FakeTransport(server::handle)).callTool("echo", JsonObject(emptyMap())), "s")
        assertEquals("""{"t":21}""", output.content)
    }

    @Test
    fun toolErrorsAndRpcErrorsBecomeErrorOutputs() = runBlocking {
        val server = FakeMcpServer()
        server.callResult = { _, _ -> FakeMcpServer.content(FakeMcpServer.text("no such note"), isError = true) }
        val c = client(FakeTransport(server::handle))
        val toolError = McpResults.toOutput(c.callTool("echo", JsonObject(emptyMap())), "notes")
        assertTrue(toolError.isError)
        assertEquals("MCP_TOOL_ERROR", toolError.code)
        assertEquals("no such note", toolError.content)

        val rpcTransport = FakeTransport { r ->
            if (r.body?.contains("tools/call") == true) {
                FakeResponse(200, mapOf("content-type" to "application/json"), listOf("""{"jsonrpc":"2.0","id":${idOf(r.body)},"error":{"code":-32602,"message":"Unknown tool: nope"}}"""))
            } else {
                server.handle(r)
            }
        }
        val rpc = expectFailure(McpErrorKind.RPC) { client(rpcTransport).callTool("nope", JsonObject(emptyMap())) }
        assertEquals(-32602, rpc.rpcCode)
        val output = McpResults.failure(rpc, "notes")
        assertTrue(output.isError)
        assertEquals("MCP_ERROR", output.code)
        assertTrue(output.content.contains("MCP server notes"))
    }

    @Test
    fun paginationFollowsCursorsAndStopsOnARepeatedOne() = runBlocking {
        val server = FakeMcpServer()
        server.pages = listOf(listOf("a", "b"), listOf("c"), listOf("d"))
        val transport = FakeTransport(server::handle)
        assertEquals(listOf("a", "b", "c", "d"), client(transport).listTools().map { it.name })
        val cursors = transport.bodies().filter { it["method"]?.jsonPrimitive?.content == "tools/list" }
            .map { (it["params"] as? JsonObject)?.get("cursor")?.jsonPrimitive?.content }
        assertEquals(listOf(null, "page1", "page2"), cursors)

        // A server that keeps returning the same cursor cannot loop the client.
        val looping = FakeTransport { r ->
            if (r.body?.contains("tools/list") == true) {
                val id = kotlinx.serialization.json.Json.parseToJsonElement(r.body!!).jsonObject["id"]
                FakeResponse(200, mapOf("content-type" to "application/json"), listOf("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[],"nextCursor":"same"}}"""))
            } else {
                FakeMcpServer().handle(r)
            }
        }
        assertEquals(0, client(looping).listTools().size)
        assertEquals(2, looping.methods().count { it == "tools/list" })
    }

    @Test
    fun listChangedNotificationMarksToolsStale() = runBlocking {
        val server = FakeMcpServer(sse = true)
        val c = client(FakeTransport(server::handle))
        var notified = 0
        c.onToolsChanged = { notified++ }
        c.listTools()
        assertFalse(c.toolsChanged)
        server.preamble += buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/tools/list_changed") }
        c.callTool("echo", JsonObject(emptyMap()))
        assertTrue(c.toolsChanged)
        assertEquals(1, notified)
        c.listTools()
        assertFalse(c.toolsChanged)
    }

    @Test
    fun expiredSessionStartsANewOneAndRetriesOnce() = runBlocking {
        val server = FakeMcpServer()
        var expire = true
        val transport = FakeTransport { r ->
            if (expire && r.body?.contains("tools/call") == true && r.headers["Mcp-Session-Id"] == "sess-1") {
                expire = false
                FakeResponse(404, errorBody = "")
            } else {
                server.handle(r)
            }
        }
        val c = client(transport)
        c.initialize()
        val result = c.callTool("echo", buildJsonObject { put("text", "again") })
        assertEquals("echo: again", (result.content.single() as McpContent.Text).text)
        assertEquals(listOf("initialize", "notifications/initialized", "tools/call", "initialize", "notifications/initialized", "tools/call"), transport.methods())
        assertEquals("sess-1-2", transport.sent.last().headers["Mcp-Session-Id"])
        assertNull(transport.sent[3].headers["Mcp-Session-Id"])
    }

    @Test
    fun aStreamThatEndsEarlyIsResumedWithLastEventId() = runBlocking {
        val server = FakeMcpServer()
        var listId: kotlinx.serialization.json.JsonElement? = null
        val transport = FakeTransport { r ->
            when {
                r.body?.contains("tools/list") == true -> {
                    listId = idOf(r.body)
                    FakeResponse(200, mapOf("content-type" to "text/event-stream"), listOf("id: ev-9", "retry: 1", "data:", ""))
                }
                r.method == "GET" -> FakeResponse(
                    200,
                    mapOf("content-type" to "text/event-stream"),
                    listOf("id: ev-10", """data: {"jsonrpc":"2.0","id":$listId,"result":{"tools":[${FakeMcpServer.tool("late")}]}}""", ""),
                )
                else -> server.handle(r)
            }
        }
        val tools = client(transport).listTools()
        assertEquals(listOf("late"), tools.map { it.name })
        val resume = transport.sent.last()
        assertEquals("GET", resume.method)
        assertEquals("ev-9", resume.headers["Last-Event-ID"])
        assertEquals("text/event-stream", resume.headers["Accept"])
        assertEquals("sess-1", resume.headers["Mcp-Session-Id"])
        assertNull(resume.body)
    }

    @Test
    fun aStreamThatEndsWithoutAnEventIdFails() = runBlocking {
        val server = FakeMcpServer()
        val transport = FakeTransport { r ->
            if (r.body?.contains("tools/list") == true) FakeResponse(200, mapOf("content-type" to "text/event-stream"), listOf(": nothing", "")) else server.handle(r)
        }
        expectFailure(McpErrorKind.PROTOCOL) { client(transport).listTools() }
        Unit
    }

    @Test
    fun bearerTokenGoesOnlyToTheServersOwnHost() = runBlocking {
        val server = FakeMcpServer()
        val transport = FakeTransport(server::handle)
        client(transport, token = "secret-token").listTools()
        assertTrue(transport.sent.isNotEmpty())
        assertTrue(transport.sent.all { McpEndpoint.hostOf(it.url) == "mcp.example.com" && it.headers["Authorization"] == "Bearer secret-token" })

        val anonymous = FakeTransport(FakeMcpServer()::handle)
        client(anonymous, token = null).listTools()
        assertTrue(anonymous.sent.none { it.headers.containsKey("Authorization") })
    }

    @Test
    fun redirectsAreReportedNotFollowed() = runBlocking {
        val transport = FakeTransport { FakeResponse(307, mapOf("location" to "https://evil.example.net/mcp")) }
        val e = expectFailure(McpErrorKind.HTTP) { client(transport, token = "secret-token").initialize() }
        assertTrue(e.message!!.contains("not followed"))
        assertEquals(1, transport.sent.size)
        assertTrue(transport.sent.none { it.url.contains("evil") })
    }

    @Test
    fun unauthorizedMentionsTheToken() = runBlocking {
        val transport = FakeTransport { FakeResponse(401, mapOf("www-authenticate" to "Bearer resource_metadata=\"x\"")) }
        val e = expectFailure(McpErrorKind.AUTH) { client(transport).initialize() }
        assertTrue(e.message!!.contains("OAuth"))
    }

    @Test
    fun blockedAndNetworkFailuresAreTyped() = runBlocking {
        val transport = FakeTransport(FakeMcpServer()::handle).apply { blocked = "Offline mode: network access is disabled" }
        val e = expectFailure(McpErrorKind.BLOCKED) { client(transport).initialize() }
        assertTrue(transport.sent.isEmpty())
        val output = McpResults.failure(e, "notes")
        assertEquals("BLOCKED", output.code)
        assertTrue(output.content.contains("Offline"))
    }

    @Test
    fun closeSendsDeleteWithTheSession() = runBlocking {
        val transport = FakeTransport(FakeMcpServer()::handle)
        val c = client(transport)
        c.initialize()
        c.close()
        val delete = transport.sent.last()
        assertEquals("DELETE", delete.method)
        assertEquals("sess-1", delete.headers["Mcp-Session-Id"])
        assertEquals("Bearer tok", delete.headers["Authorization"])
        assertNull(c.sessionId)
        c.close() // no session, no request
        assertEquals("DELETE", transport.methods().last())
        assertEquals(1, transport.sent.count { it.method == "DELETE" })
    }

    @Test
    fun endpointParsingRejectsCredentialsAndNonHttp() {
        assertEquals("mcp.example.com", McpEndpoint.parse("https://mcp.example.com:8443/mcp?x=1")!!.host)
        assertEquals("::1", McpEndpoint.parse("http://[::1]:3000/mcp")!!.host)
        assertNull(McpEndpoint.parse("https://user:pw@mcp.example.com/mcp"))
        assertNull(McpEndpoint.parse("ftp://mcp.example.com"))
        assertNull(McpEndpoint.parse("mcp.example.com/mcp"))
    }

    @Test
    fun sseParserJoinsDataAndTracksIdAndRetry() {
        val parser = SseEventParser()
        val events = listOf(": comment", "event: message", "id: 1", "retry: 2500", "data: {\"a\":", "data: 1}", "", "data: tail")
            .mapNotNull(parser::feed) + listOfNotNull(parser.finish())
        assertEquals(listOf("{\"a\":\n1}", "tail"), events.map { it.data })
        assertEquals("1", parser.lastEventId)
        assertEquals(2500L, parser.retryMs)
    }

    @Test
    fun namesAndSchemasAreSafeForChatApis() {
        assertEquals("my_notes", McpNames.serverSlug("My  Notes!", "id"))
        assertEquals("mcp__my_notes__search", McpNames.toolName("my_notes", "search"))
        val dotted = McpNames.toolName("gh", "repo.search")
        assertTrue(dotted, dotted.matches(Regex("mcp__gh__repo_search_[0-9a-f]{6}")))
        val long = McpNames.toolName("server", "x".repeat(100))
        assertTrue(long.length <= 64 && long.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(
            JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
            McpNames.schema(JsonObject(emptyMap())),
        )
    }

    private fun idOf(body: String?) = body?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["id"] }
}
