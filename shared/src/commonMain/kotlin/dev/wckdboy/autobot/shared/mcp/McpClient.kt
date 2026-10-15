package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/** Which generation of MCP a server speaks. */
enum class McpEra {
    /** 2026-07-28: stateless, every request carries `_meta` and mirrored headers. */
    MODERN,

    /** 2025-03-26 … 2025-11-25: `initialize` handshake and an optional session. */
    LEGACY,
}

/** What discovery (`server/discover` or `initialize`) told us about the server. */
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
    val headerParams: List<McpHeaderParam> = emptyList(),
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
 * The server's era is detected once per client (the hub keeps one client per server
 * configuration) the way the spec's backward-compatibility section describes: a modern
 * `server/discover` first; a 4xx without a recognized modern JSON-RPC error (or an
 * `UnsupportedProtocolVersion` listing only older revisions) falls back to the legacy flow.
 *
 * - **2026-07-28:** no session; every request carries `_meta` (protocol version, client info,
 *   empty capabilities) and the `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers,
 *   plus `Mcp-Param-*` for parameters a tool marks with `x-mcp-header` (tools with invalid
 *   annotations are left out). A `HeaderMismatch` on `tools/call` re-lists and retries once.
 *   List changes arrive on a `subscriptions/listen` stream when the server offers them.
 * - **Legacy:** `initialize` → `notifications/initialized`; the `Mcp-Session-Id` and negotiated
 *   `MCP-Protocol-Version` go on every later request; a 404 for that session renews it once; a
 *   stream that ends early resumes with GET + `Last-Event-ID`; [close] sends DELETE.
 *
 * Either way: JSON or SSE responses, server `ping`s answered (other server requests get "method
 * not found": no client capabilities are declared), and [token] goes as `Authorization: Bearer`
 * only to the endpoint's own host.
 */
class McpClient(
    val endpoint: McpEndpoint,
    private val transport: HttpTransport,
    private val token: String?,
    private val route: String,
    private val tag: String,
    private val scope: CoroutineScope,
    era: McpEra? = null,
    /** Open a `subscriptions/listen` stream for list changes (off for one-shot tests). */
    private val subscribe: Boolean = true,
) {
    private val ids = AtomicLong(0L)
    private val initLock = Mutex()
    private val changed = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private var listenJob: Job? = null

    @kotlin.concurrent.Volatile
    var era: McpEra? = era
        private set

    @kotlin.concurrent.Volatile
    private var info: McpServerInfo? = null

    @kotlin.concurrent.Volatile
    var sessionId: String? = null
        private set

    @kotlin.concurrent.Volatile
    private var protocolVersion: String? = null

    @kotlin.concurrent.Volatile
    private var headerParams: Map<String, List<McpHeaderParam>> = emptyMap()

    /** Called (on a transport thread) when the server announces a changed tool list. */
    var onToolsChanged: (() -> Unit)? = null

    /** True once `notifications/tools/list_changed` arrived and [listTools] has not run since. */
    val toolsChanged: Boolean get() = changed.load()

    val serverInfo: McpServerInfo? get() = info

    /**
     * Connects: `server/discover` for a modern (or not yet known) server, else the legacy
     * handshake. A detected legacy era is kept for the client's lifetime; a modern server is
     * simply rediscovered, which also notices a server that moved back to the legacy flow.
     */
    suspend fun initialize(): McpServerInfo = initLock.withLock {
        info ?: run {
            if (era != McpEra.LEGACY) era = probe()
            info ?: handshake()
        }
    }

    /** Every tool, following `nextCursor` pagination. */
    suspend fun listTools(): List<McpTool> {
        changed.store(false)
        val tools = ArrayList<McpTool>()
        val seen = HashSet<String>()
        var cursor: String? = null
        var pages = 0
        while (pages++ < MAX_PAGES) {
            val result = call("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            (result["tools"] as? JsonArray)?.forEach { element -> parseTool(element)?.let(tools::add) }
            cursor = result.str("nextCursor")?.takeIf { it.isNotEmpty() && seen.add(it) } ?: break
        }
        headerParams = tools.associate { it.name to it.headerParams }
        if (subscribe && era == McpEra.MODERN && info?.toolsListChanged == true) listen()
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = try {
            call("tools/call", params, headerParams[name].orEmpty().let { McpHeaders.values(it, arguments) })
        } catch (e: McpException) {
            // The tool's x-mcp-header set changed under us: re-list, then retry once.
            if (era != McpEra.MODERN || e.rpcCode != McpProtocol.HEADER_MISMATCH) throw e
            listTools()
            call("tools/call", params, headerParams[name].orEmpty().let { McpHeaders.values(it, arguments) })
        }
        val content = (result["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseContent) }
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return McpCallResult(content, result["structuredContent"]?.takeUnless { it is JsonNull }, isError)
    }

    /**
     * Ends the connection: stops the `subscriptions/listen` stream, and for a legacy session
     * sends DELETE (a 405 means the server keeps sessions itself). Never throws.
     */
    suspend fun close() {
        listenJob?.cancel()
        listenJob = null
        listening.store(false)
        val session = sessionId
        initLock.withLock {
            info = null
            sessionId = null
        }
        if (session == null) return
        runCatching {
            exchange(HttpRequestSpec("DELETE", endpoint.url, legacyHeaders(null, session), null, tag, route, ERROR_BYTES), null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Era detection

    /** One modern `server/discover`; its answer decides the era (and fills [info] when modern). */
    private suspend fun probe(): McpEra {
        val id = ids.incrementAndFetch()
        val result = try {
            resultOf(exchangeOnce(modernSpec(id, "server/discover", null, emptyMap()), id))
        } catch (e: McpException) {
            return when {
                e.kind == McpErrorKind.BLOCKED || e.kind == McpErrorKind.NETWORK || e.kind == McpErrorKind.AUTH -> throw e
                e.status in 300..399 -> throw e
                e.rpcCode == McpProtocol.UNSUPPORTED_VERSION -> {
                    val supported = ((e.rpcData as? JsonObject)?.get("supported") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                    if (supported.any { it in McpProtocol.LEGACY_VERSIONS }) {
                        McpEra.LEGACY
                    } else {
                        throw McpException(McpErrorKind.PROTOCOL, "No common MCP protocol version (the server supports ${supported.joinToString()})")
                    }
                }
                e.rpcCode in McpProtocol.MODERN_ERRORS -> throw e
                // A modern server without the method answers 404 + "method not found".
                e.status == 404 && e.rpcCode == McpProtocol.METHOD_NOT_FOUND -> {
                    info = McpServerInfo("", "", McpProtocol.MODERN_VERSION, toolsListChanged = false, instructions = null)
                    McpEra.MODERN
                }
                else -> McpEra.LEGACY
            }
        }
        val supported = (result["supportedVersions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (supported.isNotEmpty() && McpProtocol.MODERN_VERSION !in supported) {
            if (supported.any { it in McpProtocol.LEGACY_VERSIONS }) return McpEra.LEGACY
            throw McpException(McpErrorKind.PROTOCOL, "No common MCP protocol version (the server supports ${supported.joinToString()})")
        }
        val serverInfo = (result["_meta"] as? JsonObject)?.get(McpProtocol.META_SERVER_INFO) as? JsonObject
        val tools = (result["capabilities"] as? JsonObject)?.get("tools") as? JsonObject
        info = McpServerInfo(
            name = serverInfo?.str("name").orEmpty(),
            version = serverInfo?.str("version").orEmpty(),
            protocolVersion = McpProtocol.MODERN_VERSION,
            toolsListChanged = (tools?.get("listChanged") as? JsonPrimitive)?.booleanOrNull ?: false,
            instructions = result.str("instructions"),
        )
        return McpEra.MODERN
    }

    /** Legacy `initialize` → `notifications/initialized`. */
    private suspend fun handshake(): McpServerInfo {
        sessionId = null
        protocolVersion = null
        val id = ids.incrementAndFetch()
        val params = buildJsonObject {
            put("protocolVersion", McpProtocol.LEGACY_VERSION)
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
        if (version !in McpProtocol.LEGACY_VERSIONS) {
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

    // ---------------------------------------------------------------------------------------
    // Requests

    private suspend fun call(method: String, params: JsonObject?, extraHeaders: Map<String, String> = emptyMap()): JsonObject {
        initialize()
        if (era == McpEra.MODERN) {
            val id = ids.incrementAndFetch()
            return resultOf(exchangeOnce(modernSpec(id, method, params, extraHeaders), id))
        }
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

    /** A 2026-07-28 request: `_meta` in the body, the same facts mirrored into headers. */
    private fun modernSpec(id: Long, method: String, params: JsonObject?, extraHeaders: Map<String, String>, maxBytes: Long = MAX_BYTES): HttpRequestSpec {
        val body = McpProtocol.request(id, method, JsonObject((params ?: JsonObject(emptyMap())) + ("_meta" to McpProtocol.meta())))
        val headers = buildMap {
            put("Accept", McpProtocol.ACCEPT_POST)
            put("Content-Type", "application/json")
            put(McpProtocol.HEADER_VERSION, McpProtocol.MODERN_VERSION)
            put(McpProtocol.HEADER_METHOD, method)
            if (method == "tools/call" || method == "resources/read" || method == "prompts/get") {
                (params?.str("name") ?: params?.str("uri"))?.let { put(McpProtocol.HEADER_NAME, McpHeaders.encode(it)) }
            }
            putAll(extraHeaders)
            authorization()?.let { put("Authorization", it) }
        }
        return HttpRequestSpec("POST", endpoint.url, headers, body.toString(), tag, route, maxBytes)
    }

    private fun resultOf(response: JsonObject?): JsonObject {
        val message = response ?: throw McpException(McpErrorKind.PROTOCOL, "The server sent no response")
        (message["error"] as? JsonObject)?.let { throw rpcError(it, 0) }
        val result = message["result"] as? JsonObject ?: throw McpException(McpErrorKind.PROTOCOL, "Malformed JSON-RPC response")
        when (val type = result.str("resultType")) {
            null, "complete" -> Unit
            "input_required" -> throw McpException(McpErrorKind.PROTOCOL, "The server asked for input this client does not provide (sampling, elicitation or roots)")
            else -> throw McpException(McpErrorKind.PROTOCOL, "Unknown MCP result type $type")
        }
        return result
    }

    private fun rpcError(error: JsonObject, status: Int): McpException {
        val code = (error["code"] as? JsonPrimitive)?.intOrNull
        return McpException(McpErrorKind.RPC, error.str("message") ?: "JSON-RPC error", status, code, error["data"])
    }

    /** A modern request: no session to resume, so a stream that ends early is an error. */
    private suspend fun exchangeOnce(spec: HttpRequestSpec, expectId: Long): JsonObject? =
        when (val outcome = exchange(spec, expectId)) {
            is Outcome.Done -> outcome.response
            is Outcome.Ended -> throw McpException(McpErrorKind.PROTOCOL, "The server closed the stream without a response")
        }

    private suspend fun post(message: JsonObject, expectId: Long?, captureSession: Boolean = false): JsonObject? {
        val session = if (captureSession) null else sessionId
        val spec = HttpRequestSpec("POST", endpoint.url, legacyHeaders(McpProtocol.ACCEPT_POST, session, json = true), message.toString(), tag, route, MAX_BYTES)
        var outcome = exchange(spec, expectId, captureSession)
        var resumes = 0
        while (outcome is Outcome.Ended) {
            val lastId = outcome.lastEventId
                ?: throw McpException(McpErrorKind.PROTOCOL, "The server closed the stream without a response")
            if (++resumes > MAX_RESUMES) throw McpException(McpErrorKind.PROTOCOL, "The response stream kept closing without a response")
            delay((outcome.retryMs ?: DEFAULT_RETRY_MS).coerceAtMost(MAX_RETRY_MS))
            val headers = legacyHeaders(McpProtocol.ACCEPT_SSE, sessionId) + ("Last-Event-ID" to lastId)
            outcome = exchange(HttpRequestSpec("GET", endpoint.url, headers, null, tag, route, MAX_BYTES), expectId, resuming = true)
        }
        return (outcome as Outcome.Done).response
    }

    private fun legacyHeaders(accept: String?, session: String?, json: Boolean = false): Map<String, String> = buildMap {
        accept?.let { put("Accept", it) }
        if (json) put("Content-Type", "application/json")
        session?.let { put(McpProtocol.HEADER_SESSION, it) }
        protocolVersion?.let { put(McpProtocol.HEADER_VERSION, it) }
        authorization()?.let { put("Authorization", it) }
    }

    /** The token belongs to this endpoint's host only; nothing else is ever dialled. */
    private fun authorization(): String? =
        if (!token.isNullOrBlank() && McpEndpoint.hostOf(endpoint.url) == endpoint.host) "Bearer $token" else null

    // ---------------------------------------------------------------------------------------
    // subscriptions/listen (2026-07-28)

    /**
     * Keeps one `subscriptions/listen` stream open for `toolsListChanged`. It reconnects after a
     * dropped stream (backing off; it gives up after repeated quick failures until the next
     * [listTools]) and stops when the server ends the subscription gracefully or [close] runs.
     */
    private fun listen() {
        if (!listening.compareAndSet(false, true)) return
        listenJob = scope.launch {
            try {
                var failures = 0
                while (isActive && failures < MAX_LISTEN_FAILURES) {
                    val started = TimeSource.Monotonic.markNow()
                    val id = ids.incrementAndFetch()
                    val params = buildJsonObject { putJsonObject("notifications") { put("toolsListChanged", true) } }
                    val outcome = runCatchingMcp { exchange(modernSpec(id, "subscriptions/listen", params, emptyMap(), LISTEN_BYTES), id) }
                    if (outcome.getOrNull() is Outcome.Done) break
                    // Refusals (offline, auth, an error answer) will not change by retrying.
                    val kind = (outcome.exceptionOrNull() as? McpException)?.kind
                    if (kind != null && kind != McpErrorKind.NETWORK && kind != McpErrorKind.PROTOCOL) break
                    failures = if (started.elapsedNow().inWholeMilliseconds > HEALTHY_STREAM_MS) 0 else failures + 1
                    delay(LISTEN_BACKOFF_MS * (1L shl failures.coerceAtMost(4)))
                }
            } finally {
                listening.store(false)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transport

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
        if (status == 401 && head.headers.containsKey("www-authenticate")) {
            return McpException(McpErrorKind.AUTH, "Unauthorized (HTTP 401). The server asks for sign-in; set a bearer token (OAuth is not supported yet)", status)
        }
        if (status == 401 || status == 403) return McpException(McpErrorKind.AUTH, "Unauthorized (HTTP $status). Check the token", status)
        if (status in 300..399) {
            return McpException(McpErrorKind.HTTP, "HTTP $status redirect to ${head.headers["location"] ?: "(no location)"}. Redirects are not followed; use the final URL", status)
        }
        if (resuming && status == 405) return McpException(McpErrorKind.HTTP, "The response stream closed and the server cannot resume it", status)
        // 4xx bodies may carry a JSON-RPC error (modern servers always do); keep its code and data.
        val error = head.errorBody?.let(::parse)?.let { (it as? JsonObject)?.get("error") as? JsonObject }
        if (error != null) return rpcError(error, status).let { McpException(it.kind, "HTTP $status: ${it.message}", status, it.rpcCode, it.rpcData) }
        val detail = head.errorBody?.trim()?.take(200)?.takeIf { it.isNotEmpty() && !it.startsWith("<") }
        return McpException(McpErrorKind.HTTP, "HTTP $status" + (detail?.let { ": $it" } ?: ""), status)
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

    /** Legacy server → client requests: `ping` is answered; nothing else is offered. */
    private fun answer(id: JsonElement, method: String) {
        if (era == McpEra.MODERN) return // 2026-07-28 servers send no requests on streams
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
        val schema = tool["inputSchema"] as? JsonObject ?: JsonObject(mapOf("type" to JsonPrimitive("object")))
        // Modern servers may mirror parameters into headers; a tool with invalid annotations is left out.
        val headers = if (era == McpEra.MODERN) McpHeaders.params(schema) ?: return null else emptyList()
        return McpTool(
            name = name,
            title = tool.str("title") ?: annotations?.str("title"),
            description = tool.str("description").orEmpty(),
            inputSchema = schema,
            readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            headerParams = headers,
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
        const val LISTEN_BYTES = 64L * 1024 * 1024
        const val ERROR_BYTES = 16L * 1024
        const val MAX_PAGES = 50
        const val MAX_RESUMES = 5
        const val DEFAULT_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
        const val MAX_LISTEN_FAILURES = 5
        const val LISTEN_BACKOFF_MS = 2_000L
        const val HEALTHY_STREAM_MS = 60_000L
    }
}
