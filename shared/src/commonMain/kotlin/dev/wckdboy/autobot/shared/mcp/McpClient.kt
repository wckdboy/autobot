package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What `initialize` told us about the server. */
class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    val toolsListChanged: Boolean,
    val instructions: String?,
)

/** One entry of `tools/list`. */
class McpTool(
    val name: String,
    val title: String?,
    val description: String,
    val inputSchema: JsonObject,
    val readOnlyHint: Boolean?,
)

/** One block of a `tools/call` result. Binary payloads are only measured, never kept. */
sealed interface McpContent {
    data class Text(val text: String) : McpContent
    data class Image(val mimeType: String, val bytes: Int) : McpContent
    data class Audio(val mimeType: String, val bytes: Int) : McpContent
    data class ResourceLink(val uri: String, val name: String?, val mimeType: String?) : McpContent
    data class Resource(val uri: String, val mimeType: String?, val text: String?, val blobBytes: Int?) : McpContent
    data class Unsupported(val type: String) : McpContent
}

class McpCallResult(val content: List<McpContent>, val structured: JsonElement?, val isError: Boolean)

/**
 * A Model Context Protocol client for one server over the Streamable HTTP transport, on the
 * host's [HttpTransport] (so the kill switch, the server's [route] and the audit log apply to
 * every request, and redirects are never followed).
 *
 * - `initialize` → `notifications/initialized`; the `Mcp-Session-Id` the server assigns and the
 *   negotiated `MCP-Protocol-Version` go on every later request. A 404 on a request carrying the
 *   session id starts a new session and retries once.
 * - Responses may be `application/json` or an SSE stream; on SSE, server `ping`s are answered,
 *   other server requests get "method not found" (no client capabilities are declared), and
 *   `notifications/tools/list_changed` sets [toolsChanged]. A stream that ends before its
 *   response is resumed with GET + `Last-Event-ID`, honouring `retry`.
 * - [token] is sent as `Authorization: Bearer` only to the endpoint's own host.
 * - [close] ends the session with DELETE.
 *
 * No standalone GET stream is opened: list changes are picked up from response streams.
 */
class McpClient(
    val endpoint: McpEndpoint,
    private val transport: HttpTransport,
    private val token: String?,
    private val route: String,
    private val tag: String,
    private val scope: CoroutineScope,
) {
    private val ids = AtomicLong(0L)
    private val initLock = Mutex()
    private val changed = AtomicBoolean(false)

    @kotlin.concurrent.Volatile
    private var info: McpServerInfo? = null

    @kotlin.concurrent.Volatile
    var sessionId: String? = null
        private set

    @kotlin.concurrent.Volatile
    private var protocolVersion: String? = null

    /** Called (on a transport thread) when the server announces a changed tool list. */
    var onToolsChanged: (() -> Unit)? = null

    /** True once `notifications/tools/list_changed` arrived and [listTools] has not run since. */
    val toolsChanged: Boolean get() = changed.load()

    val serverInfo: McpServerInfo? get() = info

    suspend fun initialize(): McpServerInfo = initLock.withLock { info ?: handshake() }

    /** Every tool, following `nextCursor` pagination. */
    suspend fun listTools(): List<McpTool> {
        changed.store(false)
        val tools = ArrayList<McpTool>()
        val seen = HashSet<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = call("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            (result["tools"] as? JsonArray)?.forEach { element -> parseTool(element)?.let(tools::add) }
            cursor = result.str("nextCursor")?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return tools
        }
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val result = call(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
        val content = (result["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseContent) }
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return McpCallResult(content, result["structuredContent"]?.takeUnless { it is JsonNull }, isError)
    }

    /** Ends the session (DELETE; a 405 means the server keeps sessions itself). Never throws. */
    suspend fun close() {
        val session = sessionId ?: return
        initLock.withLock {
            info = null
            sessionId = null
        }
        runCatching {
            exchange(HttpRequestSpec("DELETE", endpoint.url, headers(null, session), null, tag, route, ERROR_BYTES), null)
        }
    }

    // ---------------------------------------------------------------------------------------

    private suspend fun handshake(): McpServerInfo {
        sessionId = null
        protocolVersion = null
        val id = ids.incrementAndFetch()
        val params = buildJsonObject {
            put("protocolVersion", McpProtocol.LATEST_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", McpProtocol.CLIENT_NAME)
                put("version", McpProtocol.CLIENT_VERSION)
            }
        }
        val response = post(McpProtocol.request(id, "initialize", params), id, captureSession = true)
        val result = resultOf(response)
        val version = result.str("protocolVersion")
            ?: throw McpException(McpErrorKind.PROTOCOL, "The server's initialize result has no protocol version")
        if (version !in McpProtocol.SUPPORTED_VERSIONS) {
            throw McpException(McpErrorKind.PROTOCOL, "Unsupported MCP protocol version $version")
        }
        protocolVersion = version
        val serverInfo = result["serverInfo"] as? JsonObject
        val tools = (result["capabilities"] as? JsonObject)?.get("tools") as? JsonObject
        post(McpProtocol.notification("notifications/initialized"), null)
        return McpServerInfo(
            name = serverInfo?.str("name").orEmpty(),
            version = serverInfo?.str("version").orEmpty(),
            protocolVersion = version,
            toolsListChanged = (tools?.get("listChanged") as? JsonPrimitive)?.booleanOrNull ?: false,
            instructions = result.str("instructions"),
        ).also { info = it }
    }

    /** A request after the handshake; a session the server forgot is renewed once. */
    private suspend fun call(method: String, params: JsonObject?): JsonObject {
        initialize()
        var renewed = false
        while (true) {
            val id = ids.incrementAndFetch()
            try {
                return resultOf(post(McpProtocol.request(id, method, params), id))
            } catch (e: SessionExpired) {
                if (renewed) throw McpException(McpErrorKind.PROTOCOL, "The server ended the MCP session again")
                renewed = true
                initLock.withLock {
                    if (sessionId == e.session) {
                        info = null
                        handshake()
                    }
                }
            }
        }
    }

    private fun resultOf(response: JsonObject?): JsonObject {
        val message = response ?: throw McpException(McpErrorKind.PROTOCOL, "The server sent no response")
        (message["error"] as? JsonObject)?.let { error ->
            val code = (error["code"] as? JsonPrimitive)?.intOrNull
            throw McpException(McpErrorKind.RPC, error.str("message") ?: "JSON-RPC error", rpcCode = code)
        }
        return message["result"] as? JsonObject ?: throw McpException(McpErrorKind.PROTOCOL, "Malformed JSON-RPC response")
    }

    private suspend fun post(message: JsonObject, expectId: Long?, captureSession: Boolean = false): JsonObject? {
        val session = if (captureSession) null else sessionId
        val spec = HttpRequestSpec("POST", endpoint.url, headers(McpProtocol.ACCEPT_POST, session, json = true), message.toString(), tag, route, MAX_BYTES)
        var outcome = exchange(spec, expectId, captureSession)
        var resumes = 0
        while (outcome is Outcome.Ended) {
            val lastId = outcome.lastEventId
                ?: throw McpException(McpErrorKind.PROTOCOL, "The server closed the stream without a response")
            if (++resumes > MAX_RESUMES) throw McpException(McpErrorKind.PROTOCOL, "The response stream kept closing without a response")
            delay((outcome.retryMs ?: DEFAULT_RETRY_MS).coerceAtMost(MAX_RETRY_MS))
            val headers = headers(McpProtocol.ACCEPT_SSE, sessionId) + ("Last-Event-ID" to lastId)
            outcome = exchange(HttpRequestSpec("GET", endpoint.url, headers, null, tag, route, MAX_BYTES), expectId, resuming = true)
        }
        return (outcome as Outcome.Done).response
    }

    private fun headers(accept: String?, session: String?, json: Boolean = false): Map<String, String> = buildMap {
        accept?.let { put("Accept", it) }
        if (json) put("Content-Type", "application/json")
        session?.let { put(McpProtocol.HEADER_SESSION, it) }
        protocolVersion?.let { put(McpProtocol.HEADER_VERSION, it) }
        // The token belongs to this endpoint's host only; nothing else is ever dialled.
        if (!token.isNullOrBlank() && McpEndpoint.hostOf(endpoint.url) == endpoint.host) put("Authorization", "Bearer $token")
    }

    private sealed interface Outcome {
        class Done(val response: JsonObject?) : Outcome
        class Ended(val lastEventId: String?, val retryMs: Long?) : Outcome
    }

    private sealed interface Wire {
        class Head(val status: Int, val headers: Map<String, String>, val errorBody: String?) : Wire
        class Line(val text: String) : Wire
        data object End : Wire
        class Failed(val blocked: Boolean, val message: String) : Wire
    }

    private class SessionExpired(val session: String) : Exception("session expired")

    /**
     * Sends [spec] and reads until the response to [expectId] arrives (null: until the server
     * accepted the message). Bodies are JSON or SSE, decided by the response's content type.
     */
    private suspend fun exchange(spec: HttpRequestSpec, expectId: Long?, captureSession: Boolean = false, resuming: Boolean = false): Outcome {
        val wires = Channel<Wire>(Channel.UNLIMITED)
        val call = transport.send(
            spec,
            object : HttpSink {
                override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                    wires.trySend(Wire.Head(status, headers, errorBody))
                }

                override fun onLine(line: String) {
                    wires.trySend(Wire.Line(line))
                }

                override fun onComplete() {
                    wires.trySend(Wire.End)
                }

                override fun onFailure(blocked: Boolean, message: String) {
                    wires.trySend(Wire.Failed(blocked, message))
                }
            },
        )
        try {
            var sse = false
            val parser = SseEventParser()
            val body = StringBuilder()
            for (wire in wires) {
                when (wire) {
                    is Wire.Failed -> throw McpException(if (wire.blocked) McpErrorKind.BLOCKED else McpErrorKind.NETWORK, wire.message)
                    is Wire.Head -> {
                        if (wire.status !in 200..299) throw httpError(spec, wire, resuming)
                        if (captureSession) {
                            wire.headers[McpProtocol.HEADER_SESSION.lowercase()]?.let { id ->
                                if (!McpProtocol.validSessionId(id)) throw McpException(McpErrorKind.PROTOCOL, "The server sent an invalid session id")
                                sessionId = id
                            }
                        }
                        if (expectId == null) return Outcome.Done(null)
                        val type = wire.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
                        sse = type == "text/event-stream"
                        if (!sse && type != null && type != "application/json") {
                            throw McpException(McpErrorKind.PROTOCOL, "Unexpected response type $type")
                        }
                    }
                    is Wire.Line -> if (sse) {
                        parser.feed(wire.text)?.let { event -> onEvent(event, expectId)?.let { return Outcome.Done(it) } }
                    } else {
                        body.append(wire.text).append('\n')
                    }
                    Wire.End -> {
                        if (sse) {
                            parser.finish()?.let { event -> onEvent(event, expectId)?.let { return Outcome.Done(it) } }
                            return Outcome.Ended(parser.lastEventId, parser.retryMs)
                        }
                        val element = parse(body.toString()) ?: throw McpException(McpErrorKind.PROTOCOL, "The server's response is not JSON")
                        return Outcome.Done(dispatch(element, expectId) ?: throw McpException(McpErrorKind.PROTOCOL, "The server sent no response"))
                    }
                }
            }
            throw McpException(McpErrorKind.NETWORK, "Connection closed")
        } finally {
            call.cancel()
        }
    }

    private fun httpError(spec: HttpRequestSpec, head: Wire.Head, resuming: Boolean): Exception {
        val status = head.status
        val sentSession = spec.headers[McpProtocol.HEADER_SESSION]
        if (status == 404 && sentSession != null && spec.method == "POST") return SessionExpired(sentSession)
        val detail = head.errorBody?.let(::parse)?.let { (it as? JsonObject)?.get("error") as? JsonObject }?.str("message")
            ?: head.errorBody?.trim()?.take(200)?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
        val message = when {
            status == 401 && head.headers.containsKey("www-authenticate") ->
                "Unauthorized (HTTP 401). The server asks for sign-in; set a bearer token (OAuth is not supported yet)"
            status == 401 || status == 403 -> "Unauthorized (HTTP $status). Check the token"
            status in 300..399 -> "HTTP $status redirect to ${head.headers["location"] ?: "(no location)"}. Redirects are not followed; use the final URL"
            resuming && status == 405 -> "The response stream closed and the server cannot resume it"
            else -> "HTTP $status" + (detail?.let { ": $it" } ?: "")
        }
        return McpException(if (status == 401 || status == 403) McpErrorKind.AUTH else McpErrorKind.HTTP, message, status)
    }

    private fun onEvent(event: SseEvent, expectId: Long?): JsonObject? {
        if (event.type != "message" || event.data.isBlank()) return null
        val element = parse(event.data) ?: return null
        return dispatch(element, expectId)
    }

    /** Handles server messages; returns the response to [expectId] if this is it. */
    private fun dispatch(element: JsonElement, expectId: Long?): JsonObject? {
        if (element is JsonArray) return element.firstNotNullOfOrNull { dispatch(it, expectId) }
        val message = element as? JsonObject ?: return null
        val method = message.str("method")
        val id = message["id"]?.takeUnless { it is JsonNull }
        when {
            method == null -> {
                if (id != null && (id as? JsonPrimitive)?.longOrNull == expectId) return message
            }
            id != null -> answer(id, method)
            method == "notifications/tools/list_changed" -> {
                changed.store(true)
                onToolsChanged?.invoke()
            }
        }
        return null
    }

    /** Server → client requests: `ping` is answered; nothing else is offered. */
    private fun answer(id: JsonElement, method: String) {
        val reply = if (method == "ping") {
            McpProtocol.result(id, JsonObject(emptyMap()))
        } else {
            McpProtocol.error(id, McpProtocol.METHOD_NOT_FOUND, "Method not supported by this client: $method")
        }
        scope.launch { runCatching { post(reply, null) } }
    }

    private fun parse(text: String): JsonElement? = try {
        McpProtocol.Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseTool(element: JsonElement): McpTool? {
        val tool = element as? JsonObject ?: return null
        val name = tool.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val annotations = tool["annotations"] as? JsonObject
        return McpTool(
            name = name,
            title = tool.str("title") ?: annotations?.str("title"),
            description = tool.str("description").orEmpty(),
            inputSchema = tool["inputSchema"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("object"))),
            readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
        )
    }

    private fun parseContent(block: JsonObject): McpContent = when (val type = block.str("type")) {
        "text" -> McpContent.Text(block.str("text").orEmpty())
        "image" -> McpContent.Image(block.str("mimeType") ?: "image/*", decodedSize(block.str("data")))
        "audio" -> McpContent.Audio(block.str("mimeType") ?: "audio/*", decodedSize(block.str("data")))
        "resource_link" -> McpContent.ResourceLink(block.str("uri").orEmpty(), block.str("name"), block.str("mimeType"))
        "resource" -> {
            val resource = block["resource"] as? JsonObject ?: JsonObject(emptyMap())
            McpContent.Resource(
                uri = resource.str("uri").orEmpty(),
                mimeType = resource.str("mimeType"),
                text = resource.str("text"),
                blobBytes = resource.str("blob")?.let(::decodedSize),
            )
        }
        else -> McpContent.Unsupported(type ?: "unknown")
    }

    private fun decodedSize(base64: String?): Int = base64?.let { b -> (b.length / 4) * 3 - b.takeLast(2).count { it == '=' } }?.coerceAtLeast(0) ?: 0

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val ERROR_BYTES = 16L * 1024
        const val MAX_PAGES = 50
        const val MAX_RESUMES = 5
        const val DEFAULT_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
