package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One canned HTTP response. */
class FakeResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val lines: List<String> = emptyList(), val errorBody: String? = null)

/** Records every request and answers through [handler]; blocked / failing hosts never reach it. */
class FakeTransport(private val handler: (HttpRequestSpec) -> FakeResponse) : HttpTransport {
    val sent = CopyOnWriteArrayList<HttpRequestSpec>()
    var blocked: String? = null

    override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
        blocked?.let {
            sink.onFailure(true, it)
            return Cancellable { }
        }
        sent += request
        val response = handler(request)
        if (response.status !in 200..299) {
            sink.onResponse(response.status, response.headers, response.errorBody ?: response.lines.joinToString("\n"))
            return Cancellable { }
        }
        sink.onResponse(response.status, response.headers, null)
        response.lines.forEach(sink::onLine)
        sink.onComplete()
        return Cancellable { }
    }

    fun bodies(): List<JsonObject> = sent.mapNotNull { r -> r.body?.let { Json.parseToJsonElement(it).jsonObject } }
    fun methods(): List<String> = sent.map { r -> r.body?.let { Json.parseToJsonElement(it).jsonObject["method"]?.jsonPrimitive?.content ?: "(reply)" } ?: r.method }
}

/**
 * A scriptable MCP server: answers `initialize`, `tools/list` (paged), `tools/call` and
 * notifications as JSON or SSE, and assigns a session id.
 */
class FakeMcpServer(
    var version: String = McpProtocol.LATEST_VERSION,
    var sse: Boolean = false,
    var session: String? = "sess-1",
) {
    var pages: List<List<String>> = listOf(listOf("echo"))
    var callResult: (name: String, args: JsonObject) -> JsonObject = { _, args ->
        buildJsonObject {
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "echo: ${args["text"]?.jsonPrimitive?.content}") }) }
        }
    }

    /** Extra SSE messages sent before the response to the next request (then cleared). */
    val preamble = ArrayList<JsonObject>()
    var sessions = 0

    fun handle(request: HttpRequestSpec): FakeResponse {
        if (request.method == "DELETE") return FakeResponse(200)
        val body = request.body?.let { Json.parseToJsonElement(it).jsonObject } ?: return FakeResponse(405)
        val method = body["method"]?.jsonPrimitive?.content
        val id = body["id"]
        if (method == null || id == null) return FakeResponse(202)
        val headers = HashMap<String, String>()
        val result: JsonObject = when (method) {
            "initialize" -> {
                sessions++
                session?.let { headers["mcp-session-id"] = if (sessions == 1) it else "$it-$sessions" }
                buildJsonObject {
                    put("protocolVersion", version)
                    putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", true) } }
                    putJsonObject("serverInfo") { put("name", "fake"); put("version", "0.1") }
                }
            }
            "tools/list" -> {
                val cursor = (body["params"] as? JsonObject)?.get("cursor")?.jsonPrimitive?.content
                val index = cursor?.removePrefix("page")?.toInt() ?: 0
                buildJsonObject {
                    put("tools", buildJsonArray { pages[index].forEach { add(tool(it)) } })
                    if (index + 1 < pages.size) put("nextCursor", "page${index + 1}")
                }
            }
            "tools/call" -> {
                val params = body["params"]!!.jsonObject
                callResult(params["name"]!!.jsonPrimitive.content, params["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            else -> return respond(id, null, buildJsonObject { put("code", -32601); put("message", "no $method") }, headers)
        }
        return respond(id, result, null, headers)
    }

    private fun respond(id: JsonElement, result: JsonObject?, error: JsonObject?, headers: MutableMap<String, String>): FakeResponse {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            result?.let { put("result", it) }
            error?.let { put("error", it) }
        }
        if (!sse) {
            headers["content-type"] = "application/json"
            return FakeResponse(200, headers, listOf(message.toString()))
        }
        headers["content-type"] = "text/event-stream"
        val lines = ArrayList<String>()
        lines += listOf("id: prime", "data:", "")
        preamble.forEach { lines += listOf("event: message", "data: $it", "") }
        preamble.clear()
        lines += listOf("id: e1", "data: $message", "")
        return FakeResponse(200, headers, lines)
    }

    companion object {
        fun tool(name: String) = buildJsonObject {
            put("name", name)
            put("description", "Tool $name")
            putJsonObject("inputSchema") {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("text") { put("type", "string") } }
            }
        }

        fun text(t: String) = buildJsonObject { put("type", "text"); put("text", t) }

        fun content(vararg blocks: JsonObject, isError: Boolean = false) = buildJsonObject {
            put("content", JsonArray(blocks.toList()))
            if (isError) put("isError", true)
        }
    }
}
