package dev.wckdboy.autobot.shared.search

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    private val searx = WebSearchConfig(SearchProvider.SEARXNG, "https://search.example.org/", route = "TOR")
    private val brave = WebSearchConfig(SearchProvider.BRAVE, apiKey = " key123 ")

    private val searxBody = """
        {"query":"kotlin","results":[
          {"title":"Kotlin <b>Programming</b> Language","url":"https://kotlinlang.org/","content":"Kotlin is a  modern\n language &amp; more","engine":"ddg"},
          {"title":"Duplicate","url":"https://kotlinlang.org/","content":"same url"},
          {"title":"Not http","url":"javascript:alert(1)","content":"x"},
          {"title":"","url":"https://example.com/docs","content":"${"long ".repeat(100)}"},
          {"title":"Third","url":"http://third.example","content":""}
        ],"answers":[],"number_of_results":0}
    """.trimIndent()

    private val braveBody = """
        {"type":"search","query":{"original":"kotlin"},"web":{"type":"search","results":[
          {"title":"Kotlin","url":"https://kotlinlang.org/","description":"A <strong>modern</strong> language","age":"1 day"},
          {"title":"Kotlin docs","url":"https://kotlinlang.org/docs/","description":"Docs"}
        ]}}
    """.trimIndent()

    @Test
    fun buildsRequestsForBothProviders() {
        val s = WebSearchCodec.request(searx, "kotlin & café?", 3)!!
        assertEquals("GET", s.method)
        assertEquals("https://search.example.org/search?q=kotlin%20%26%20caf%C3%A9%3F&format=json&pageno=1", s.url)
        assertEquals("application/json", s.headers["Accept"])
        assertEquals("agent:web_search", s.tag)
        assertEquals("TOR", s.route)
        assertEquals(WebSearchCodec.MAX_BYTES, s.maxBytes)
        assertNull(s.body)

        val b = WebSearchCodec.request(brave, "kotlin", 50)!!
        assertEquals("https://api.search.brave.com/res/v1/web/search?q=kotlin&count=10", b.url)
        assertEquals("key123", b.headers["X-Subscription-Token"])
        assertEquals("INHERIT", b.route)
        assertEquals("agent:web_search", b.tag)
    }

    @Test
    fun reportsConfigurationProblems() {
        assertTrue(WebSearchCodec.problem(null)!!.contains("not configured"))
        assertTrue(WebSearchCodec.problem(WebSearchConfig(SearchProvider.NONE)) != null)
        assertTrue(WebSearchCodec.problem(WebSearchConfig(SearchProvider.SEARXNG, ""))!!.contains("SearXNG URL"))
        assertTrue(WebSearchCodec.problem(WebSearchConfig(SearchProvider.SEARXNG, "ftp://x"))!!.contains("SearXNG URL"))
        assertTrue(WebSearchCodec.problem(WebSearchConfig(SearchProvider.BRAVE, apiKey = " "))!!.contains("API key"))
        assertNull(WebSearchCodec.problem(searx))
        assertNull(WebSearchCodec.request(WebSearchConfig(SearchProvider.BRAVE), "q", 5))
        assertFalse(brave.toString().contains("key123"))
    }

    @Test
    fun parsesSearxngResults() {
        val results = (WebSearchCodec.parse(SearchProvider.SEARXNG, searxBody, 10) as WebSearchCodec.Result.Ok).results
        assertEquals(listOf("https://kotlinlang.org/", "https://example.com/docs", "http://third.example"), results.map { it.url })
        assertEquals("Kotlin Programming Language", results[0].title)
        assertEquals("Kotlin is a modern language & more", results[0].snippet)
        assertEquals("https://example.com/docs", results[1].title)
        assertEquals(WebSearchCodec.MAX_SNIPPET, results[1].snippet.length)
        assertTrue(results[1].snippet.endsWith("…"))
        assertEquals(2, (WebSearchCodec.parse(SearchProvider.SEARXNG, searxBody, 2) as WebSearchCodec.Result.Ok).results.size)
    }

    @Test
    fun parsesBraveResults() {
        val results = (WebSearchCodec.parse(SearchProvider.BRAVE, braveBody, 5) as WebSearchCodec.Result.Ok).results
        assertEquals(2, results.size)
        assertEquals("A modern language", results[0].snippet)
        // Brave omits `web` when nothing matched.
        assertTrue((WebSearchCodec.parse(SearchProvider.BRAVE, """{"type":"search"}""", 5) as WebSearchCodec.Result.Ok).results.isEmpty())
    }

    @Test
    fun malformedBodiesAndHttpErrorsMapToCodes() {
        assertEquals("PARSE", (WebSearchCodec.parse(SearchProvider.SEARXNG, "<html>", 5) as WebSearchCodec.Result.Failed).failure.code)
        assertEquals("PARSE", (WebSearchCodec.parse(SearchProvider.SEARXNG, "{}", 5) as WebSearchCodec.Result.Failed).failure.code)

        assertEquals("AUTH", WebSearchCodec.httpError(SearchProvider.BRAVE, 401, "").code)
        assertEquals("AUTH", WebSearchCodec.httpError(SearchProvider.BRAVE, 403, "").code)
        val searx403 = WebSearchCodec.httpError(SearchProvider.SEARXNG, 403, "Forbidden")
        assertEquals("HTTP_403", searx403.code)
        assertTrue(searx403.message.contains("search.formats"))
        assertEquals("RATE_LIMITED", WebSearchCodec.httpError(SearchProvider.BRAVE, 429, "").code)
        assertEquals("HTTP_502", WebSearchCodec.httpError(SearchProvider.SEARXNG, 502, "").code)
        val detail = WebSearchCodec.httpError(SearchProvider.BRAVE, 422, """{"error":{"detail":"Unable to validate request parameter(s)"}}""")
        assertEquals("Brave Search returned HTTP 422: Unable to validate request parameter(s)", detail.message)
    }

    @Test
    fun formatsNumberedResults() {
        val text = WebSearchCodec.format(
            SearchProvider.BRAVE,
            "kotlin",
            listOf(SearchResult("Kotlin", "https://kotlinlang.org/", "A modern language"), SearchResult("Docs", "https://kotlinlang.org/docs/", "")),
        )
        assertTrue(text.startsWith("Web results for \"kotlin\" (Brave Search):\n\n1. Kotlin\n   https://kotlinlang.org/\n   A modern language\n\n2. Docs\n"))
        assertEquals("No web results for \"x\" (SearXNG).", WebSearchCodec.format(SearchProvider.SEARXNG, "x", emptyList()))
    }

    private class Transport(private val behave: (HttpRequestSpec, HttpSink) -> Unit) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            behave(request, sink)
            return Cancellable { }
        }
    }

    private fun ok(body: String) = { _: HttpRequestSpec, sink: HttpSink ->
        sink.onResponse(200, mapOf("content-type" to "application/json"), null)
        body.lines().forEach(sink::onLine)
        sink.onComplete()
    }

    private object Run : ToolRunContext {
        override val callId = "c1"
        override val sessionId = "s1"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun args(query: String, count: Int? = null): JsonObject = buildJsonObject {
        put("query", query)
        count?.let { put("count", it) }
    }

    private fun search(transport: HttpTransport, config: WebSearchConfig?, query: String = "kotlin", count: Int? = null): ToolOutput =
        runBlocking { WebSearchTool(transport) { config }.execute(args(query, count), Run) }

    @Test
    fun toolSearchesThroughTheTransportAndMapsFailures() {
        val transport = Transport(ok(searxBody))
        val out = search(transport, searx, count = 2)
        assertFalse(out.isError)
        assertTrue(out.content.contains("1. Kotlin Programming Language\n   https://kotlinlang.org/"))
        assertFalse(out.content.contains("3. "))
        assertEquals("agent:web_search", transport.sent.single().tag)
        assertEquals("TOR", transport.sent.single().route)

        val blocked = search(Transport { _, sink -> sink.onFailure(blocked = true, message = "Offline mode") }, searx)
        assertEquals("BLOCKED", blocked.code)
        assertTrue(blocked.content.contains("Offline mode"))

        assertEquals("TRANSPORT", search(Transport { _, sink -> sink.onFailure(false, "timeout") }, brave).code)
        assertEquals("AUTH", search(Transport { _, sink -> sink.onResponse(401, emptyMap(), "{}") }, brave).code)
        assertEquals("PARSE", search(Transport(ok("not json")), brave).code)

        val unconfigured = Transport(ok(searxBody))
        assertEquals("NOT_CONFIGURED", search(unconfigured, null).code)
        assertEquals("NOT_CONFIGURED", search(unconfigured, WebSearchConfig(SearchProvider.BRAVE)).code)
        assertTrue(unconfigured.sent.isEmpty())
        assertEquals("INVALID_ARGS", search(unconfigured, searx, query = "  ").code)
    }

    private fun toolNames(body: String): List<String> =
        (Json.parseToJsonElement(body).jsonObject["tools"] as? JsonArray).orEmpty()
            .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

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

    @Test
    fun bridgeRegistersTheToolAndItAsksBeforeSearching() = runBlocking {
        val call = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search","arguments":"{\"query\":\"kotlin\"}"}}]}}]}"""
        val transport = Transport { request, sink ->
            val body = when {
                request.tag == WebSearchCodec.TAG -> braveBody.lines()
                request.body!!.contains("\"tool_call_id\":\"c1\"") ->
                    listOf("""data: {"choices":[{"delta":{"content":"Found it."}}]}""", """data: {"choices":[{"finish_reason":"stop"}]}""", "data: [DONE]")
                else -> listOf(call, """data: {"choices":[{"finish_reason":"tool_calls"}]}""", "data: [DONE]")
            }
            sink.onResponse(200, emptyMap(), null)
            body.forEach(sink::onLine)
            sink.onComplete()
        }
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", null, "m").takeIf { id == "p" }
        }
        val bridge = AgentBridge(Files.createTempDirectory("search").toString(), providers, transport, NoLocal, NoStore, null, { null })
        bridge.setWebSearch { brave }

        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        bridge.send("s1", "Search for kotlin", emptyList())
        var asked = false
        withTimeout(10_000) {
            while (true) {
                val s = states.lastOrNull()
                s?.approvals?.firstOrNull()?.takeIf { !asked }?.let {
                    asked = true
                    assertEquals("web_search", it.toolName)
                    assertEquals("kotlin", it.summary)
                    // Nothing has gone to the search provider before the user answers.
                    assertTrue(transport.sent.none { r -> r.tag == WebSearchCodec.TAG })
                    bridge.answerApproval(it.callId, ApprovalOutcome.ALLOWED_ONCE)
                }
                if (s != null && !s.running && s.projection.items.any { it is SessionItem.Assistant && it.text == "Found it." }) break
                delay(20)
            }
        }
        assertTrue(asked)
        val card = states.last().projection.items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals(ToolState.OK, card.state)
        assertTrue(card.output!!.contains("https://kotlinlang.org/docs/"))
        assertTrue("web_search" in toolNames(transport.sent.first().body!!))
        assertEquals("key123", transport.sent.single { it.tag == WebSearchCodec.TAG }.headers["X-Subscription-Token"])

        bridge.setWebSearch(null)
        bridge.send("s1", "And again?", emptyList())
        withTimeout(10_000) { while (transport.sent.count { it.tag != WebSearchCodec.TAG } < 3 || states.last().running) delay(20) }
        assertFalse("web_search" in toolNames(transport.sent.last().body!!))
        handle.cancel()
    }
}
