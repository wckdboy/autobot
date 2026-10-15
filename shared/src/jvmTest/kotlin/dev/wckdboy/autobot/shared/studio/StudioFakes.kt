package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.BinaryHttpTransport
import dev.wckdboy.autobot.shared.BinarySink
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.WebSocketConnection
import dev.wckdboy.autobot.shared.WebSocketListener
import dev.wckdboy.autobot.shared.WebSocketSpec
import dev.wckdboy.autobot.shared.WebSocketTransport
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Fixtures from `src/jvmTest/resources/studio`. */
internal fun fixture(name: String): String =
    checkNotNull(StudioFakes::class.java.getResource("/studio/$name")) { "missing fixture $name" }.readText()

internal fun fixtureObject(name: String, key: String? = null): JsonObject {
    val root = Json.parseToJsonElement(fixture(name)).jsonObject
    return if (key == null) root else root.getValue(key).jsonObject
}

internal object StudioFakes

/**
 * A scripted engine: each job waits for [gate] (when set) and returns one output; records the
 * jobs it ran and how many ran at once.
 */
internal class FakeEngine(
    kind: String = EngineKinds.DIFFUSION,
    backendId: String? = null,
    onDevice: Boolean = backendId == null,
    private val output: AssetType = AssetType.IMAGE,
    var gate: CompletableDeferred<Unit>? = null,
    var fail: String? = null,
) : StudioEngine {
    override val capabilities = EngineCapabilities(kind, backendId, backendId ?: "This phone", setOf(output), onDevice)
    val ran = CopyOnWriteArrayList<StudioJob>()
    var running = 0
    var maxRunning = 0

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        ran += job
        running++
        maxRunning = maxOf(maxRunning, running)
        try {
            onEvent(EngineEvent.Status("working"))
            onEvent(EngineEvent.Progress(1, 2))
            gate?.await()
            fail?.let { throw StudioEngineException(it) }
            onEvent(EngineEvent.Progress(2, 2))
            val mime = when (output) {
                AssetType.IMAGE -> "image/png"
                AssetType.AUDIO -> "audio/wav"
                AssetType.VIDEO -> "video/mp4"
            }
            return listOf(EngineOutput(output, mime, byteArrayOf(1, 2, 3), width = 64, height = 64))
        } finally {
            running--
        }
    }
}

/** An [OutputSink] into an in-memory store with sequential ids. */
internal class MemorySink(val store: InMemoryStudioStore = InMemoryStudioStore()) : OutputSink {
    private var next = 0
    val bytes = HashMap<String, ByteArray>()

    override suspend fun save(job: StudioJob, output: EngineOutput, index: Int): StudioAsset {
        val asset = StoreOutputSink.assetFor(job, output, "a-%012x".format(++next), 1_000L + next)
        bytes[asset.id] = output.bytes
        store.putAsset(asset)
        return asset
    }
}

/**
 * A fake ComfyUI server behind the three host transports: JSON routes by "METHOD path", binary
 * routes for `/view` and `/upload/image`, and a scripted socket.
 */
internal class FakeComfy : HttpTransport, BinaryHttpTransport, WebSocketTransport {
    val requests = CopyOnWriteArrayList<HttpRequestSpec>()
    val bodies = CopyOnWriteArrayList<ByteArray?>()
    val sockets = CopyOnWriteArrayList<WebSocketSpec>()
    val json = HashMap<String, (HttpRequestSpec) -> Pair<Int, String>>()
    val files = HashMap<String, ByteArray>()
    var blocked = false

    /** What the socket does once connected; null fails the connection. */
    var socketScript: ((WebSocketListener) -> Unit)? = null

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.substringAfter("://").substringAfter('/').let { p -> "/$p" }}" }

    override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
        requests += request
        bodies += request.body?.encodeToByteArray()
        if (blocked) {
            sink.onFailure(true, "Offline")
            return Cancellable { }
        }
        val path = "/" + request.url.substringAfter("://").substringAfter('/')
        val key = "${request.method} ${path.substringBefore('?')}"
        val route = json[key] ?: json.entries.firstOrNull { key.startsWith(it.key) }?.value
        val (status, body) = route?.invoke(request) ?: (404 to "Not Found")
        if (status !in 200..299) {
            sink.onResponse(status, emptyMap(), body)
            return Cancellable { }
        }
        sink.onResponse(status, mapOf("content-type" to "application/json"), null)
        body.lines().forEach(sink::onLine)
        sink.onComplete()
        return Cancellable { }
    }

    override fun exchange(request: HttpRequestSpec, body: ByteArray?, sink: BinarySink): Cancellable {
        requests += request
        bodies += body
        if (blocked) {
            sink.onFailure(true, "Offline")
            return Cancellable { }
        }
        val path = "/" + request.url.substringAfter("://").substringAfter('/')
        when {
            path.startsWith("/view") -> {
                val name = path.substringAfter("filename=").substringBefore('&')
                val data = files[name]
                if (data == null) sink.onResponse(404, emptyMap(), ByteArray(0)) else sink.onResponse(200, emptyMap(), data)
            }
            path.startsWith("/upload/image") -> sink.onResponse(200, emptyMap(), """{"name":"input.png","subfolder":"autobot","type":"input"}""".encodeToByteArray())
            else -> sink.onResponse(404, emptyMap(), ByteArray(0))
        }
        return Cancellable { }
    }

    override fun connect(spec: WebSocketSpec, listener: WebSocketListener): WebSocketConnection {
        sockets += spec
        val script = socketScript
        if (script == null) listener.onFailure(false, "refused") else script(listener)
        return object : WebSocketConnection {
            override fun sendText(text: String) = true
            override fun close(code: Int, reason: String) = Unit
            override fun cancel() = Unit
        }
    }
}
