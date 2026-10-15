package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * A scriptable MCP server. [era] picks how it behaves:
 * - LEGACY: `initialize` + session (requests without the session get 400, like the SDKs);
 * - MODERN: 2026-07-28 only — validates `_meta` and the mirrored headers, no sessions;
 * - DUAL: modern when the request carries `_meta`, legacy otherwise.
 */
class FakeMcpServer(
    var version: String = McpProtocol.LEGACY_VERSION,
    var sse: Boolean = false,
    var session: String? = "sess-1",
    var era: Era = Era.LEGACY,
) {
    enum class Era { LEGACY, MODERN, DUAL }

    var pages: List<List<String>> = listOf(listOf("echo"))
    val toolOverrides = HashMap<String, JsonObject>()
    var callResult: (name: String, args: JsonObject) -> JsonObject = { _, args ->
        buildJsonObject {
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "echo: ${args["text"]?.jsonPrimitive?.content}") }) }
        }
    }

    /** Extra SSE messages sent before the response to the next request (then cleared). */
    val preamble = ArrayList<JsonObject>()
    var sessions = 0

    // Modern behaviour.
    var supportedVersions = listOf(McpProtocol.MODERN_VERSION)
    var listChanged = true

    /** Notifications sent on the next `subscriptions/listen` stream after the acknowledgment. */
    val listenNotifications = ArrayList<JsonObject>()

    /** Rejects the next `tools/call` with `HeaderMismatch`. */
    var mismatchNextCall = false

    /** `Mcp-Param-*` headers seen on `tools/call` requests. */
    val paramHeaders = CopyOnWriteArrayList<Map<String, String>>()

    fun handle(request: HttpRequestSpec): FakeResponse {
        if (request.method == "DELETE") return if (era == Era.MODERN) FakeResponse(405) else FakeResponse(200)
        val body = request.body?.let { Json.parseToJsonElement(it).jsonObject } ?: return FakeResponse(405)
        val params = body["params"] as? JsonObject
        val modern = (params?.get("_meta") as? JsonObject)?.get(McpProtocol.META_VERSION) != null
        return when (era) {
            Era.MODERN -> modern(request, body)
            Era.DUAL -> if (modern) modern(request, body) else legacy(request, body)
            Era.LEGACY -> legacy(request, body)
        }
    }

    private fun legacy(request: HttpRequestSpec, body: JsonObject): FakeResponse {
        val method = body["method"]?.jsonPrimitive?.content
        val id = body["id"]
        if (session != null && method != "initialize" && request.headers[McpProtocol.HEADER_SESSION] == null) {
            return error400(JsonNull, -32000, "Bad Request: No valid session ID provided")
        }
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
            "tools/list" -> toolsList(body)
            "tools/call" -> call(body)
            else -> return respond(id, null, buildJsonObject { put("code", -32601); put("message", "no $method") }, headers)
        }
        return respond(id, result, null, headers)
    }

    private fun modern(request: HttpRequestSpec, body: JsonObject): FakeResponse {
        val method = body["method"]?.jsonPrimitive?.content
        val id = body["id"] ?: return FakeResponse(202)
        val params = body["params"] as? JsonObject ?: JsonObject(emptyMap())
        val meta = params["_meta"] as? JsonObject ?: return error400(id, -32602, "Missing _meta")
        if (meta[McpProtocol.META_CAPABILITIES] !is JsonObject) return error400(id, -32602, "Missing client capabilities")
        val version = meta[McpProtocol.META_VERSION]?.jsonPrimitive?.content
        if (version !in supportedVersions) {
            return FakeResponse(
                400, mapOf("content-type" to "application/json"),
                errorBody = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", id)
                    putJsonObject("error") {
                        put("code", McpProtocol.UNSUPPORTED_VERSION); put("message", "Unsupported protocol version")
                        putJsonObject("data") { putJsonArray("supported") { supportedVersions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }; put("requested", version) }
                    }
                }.toString(),
            )
        }
        if (request.headers[McpProtocol.HEADER_VERSION] != version || request.headers[McpProtocol.HEADER_METHOD] != method) {
            return error400(id, McpProtocol.HEADER_MISMATCH, "Header mismatch")
        }
        if (method == "tools/call") {
            val name = params["name"]!!.jsonPrimitive.content
            if (request.headers[McpProtocol.HEADER_NAME] != McpHeaders.encode(name)) return error400(id, McpProtocol.HEADER_MISMATCH, "Mcp-Name mismatch")
            paramHeaders += request.headers.filterKeys { it.startsWith("Mcp-Param-") }
            if (mismatchNextCall) {
                mismatchNextCall = false
                return error400(id, McpProtocol.HEADER_MISMATCH, "Mcp-Param-Region missing")
            }
        }
        val result: JsonObject = when (method) {
            "server/discover" -> buildJsonObject {
                put("resultType", "complete")
                putJsonArray("supportedVersions") { supportedVersions.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", listChanged) } }
                putJsonObject("_meta") { putJsonObject(McpProtocol.META_SERVER_INFO) { put("name", "modern"); put("version", "2.0") } }
                put("instructions", "Use the tools.")
            }
            "tools/list" -> JsonObject(toolsList(body) + ("resultType" to kotlinx.serialization.json.JsonPrimitive("complete")))
            "tools/call" -> call(body).let { if ("resultType" in it) it else JsonObject(it + ("resultType" to kotlinx.serialization.json.JsonPrimitive("complete"))) }
            "subscriptions/listen" -> return listen(id, params)
            else -> return FakeResponse(
                404, mapOf("content-type" to "application/json"),
                errorBody = """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found: $method"}}""",
            )
        }
        return respond(id, result, null, HashMap())
    }

    private fun listen(id: JsonElement, params: JsonObject): FakeResponse {
        val subscription = buildJsonObject { put("io.modelcontextprotocol/subscriptionId", id) }
        val lines = ArrayList<String>()
        fun send(message: JsonObject) {
            lines += listOf("event: message", "data: $message", "")
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/subscriptions/acknowledged")
            putJsonObject("params") { put("_meta", subscription); put("notifications", params["notifications"] ?: JsonObject(emptyMap())) }
        })
        listenNotifications.forEach { n -> send(JsonObject(n + ("params" to buildJsonObject { put("_meta", subscription) }))) }
        listenNotifications.clear()
        // Graceful end of the subscription.
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); putJsonObject("result") { put("resultType", "complete"); put("_meta", subscription) } })
        return FakeResponse(200, mapOf("content-type" to "text/event-stream"), lines)
    }

    private fun toolsList(body: JsonObject): JsonObject {
        val cursor = (body["params"] as? JsonObject)?.get("cursor")?.jsonPrimitive?.content
        val index = cursor?.removePrefix("page")?.toInt() ?: 0
        return buildJsonObject {
            put("tools", buildJsonArray { pages[index].forEach { add(toolOverrides[it] ?: tool(it)) } })
            if (index + 1 < pages.size) put("nextCursor", "page${index + 1}")
        }
    }

    private fun call(body: JsonObject): JsonObject {
        val params = body["params"]!!.jsonObject
        return callResult(params["name"]!!.jsonPrimitive.content, params["arguments"]?.jsonObject ?: JsonObject(emptyMap()))
    }

    private fun error400(id: JsonElement, code: Int, message: String) = FakeResponse(
        400, mapOf("content-type" to "application/json"),
        errorBody = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            putJsonObject("error") { put("code", code); put("message", message) }
        }.toString(),
    )

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
        if (era != Era.MODERN) lines += listOf("id: prime", "data:", "")
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
