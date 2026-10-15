package dev.wckdboy.autobot.shared.studio.comfy

import dev.wckdboy.autobot.core.models.encodePathSegment
import dev.wckdboy.autobot.core.models.encodeQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * The ComfyUI wire format (server.py / protocol.py): REST bodies, WebSocket messages and the
 * binary preview frames. Pure functions over JSON and bytes; [ComfyClient] does the I/O.
 *
 *   GET  /object_info          node classes and their inputs (combo lists = installed models)
 *   GET  /system_stats         {system:{comfyui_version,…}, devices:[{name,type,vram_total,vram_free}]}
 *   POST /prompt               {prompt, client_id} → {prompt_id, number, node_errors}; 400 {error, node_errors}
 *   GET  /history/{id}         {id: {prompt, outputs:{node:{images|gifs|audio:[file]}}, status:{status_str, completed, messages}}}
 *   GET  /view?filename&subfolder&type
 *   POST /upload/image         multipart image, type, subfolder, overwrite → {name, subfolder, type}
 *   POST /interrupt            {prompt_id}
 *   GET  /queue                {queue_running:[[n,id,…]], queue_pending:[…]}
 *   WS   /ws?clientId=         {type, data} text messages; binary = u32 BE event | payload
 */

internal val ComfyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
internal fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

/** A file on the ComfyUI server ([type] `output`, `temp` or `input`). */
@Serializable
data class ComfyFile(val filename: String, val subfolder: String = "", val type: String = "output")

/** One input of a node class from `/object_info`. */
class InputSpec(
    val name: String,
    /** `INT`, `FLOAT`, `STRING`, `BOOLEAN`, `COMBO`, or a link type (`MODEL`, `LATENT`, …). */
    val type: String,
    val required: Boolean,
    val default: JsonElement? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val multiline: Boolean = false,
    /** Uploadable input (LoadImage's `image`). */
    val upload: Boolean = false,
) {
    /** True for a widget value (set in the graph as a literal), false for a node link. */
    val isWidget: Boolean get() = type in WIDGET_TYPES

    companion object {
        val WIDGET_TYPES = setOf("INT", "FLOAT", "STRING", "BOOLEAN", "COMBO")
    }
}

/** A node class from `/object_info`. */
class NodeInfo(
    val name: String,
    val displayName: String,
    val category: String,
    val inputs: Map<String, InputSpec>,
    val outputs: List<String>,
    val outputNode: Boolean,
)

/** `/object_info`: the server's node classes. */
class ObjectInfo(val nodes: Map<String, NodeInfo>) {
    operator fun get(classType: String): NodeInfo? = nodes[classType]

    fun input(classType: String, input: String): InputSpec? = nodes[classType]?.inputs?.get(input)

    companion object {
        val EMPTY = ObjectInfo(emptyMap())

        /** Parses `/object_info`; both the classic (`[[options], {…}]`) and V3 (`["COMBO", {options}]`) combo forms. */
        fun parse(json: String): ObjectInfo = parse(ComfyJson.parseToJsonElement(json).obj() ?: JsonObject(emptyMap()))

        fun parse(root: JsonObject): ObjectInfo = ObjectInfo(
            root.mapNotNull { (name, value) ->
                val node = value.obj() ?: return@mapNotNull null
                val input = node["input"].obj()
                val inputs = LinkedHashMap<String, InputSpec>()
                for ((group, required) in listOf("required" to true, "optional" to false)) {
                    input?.get(group).obj()?.forEach { (inputName, spec) -> parseInput(inputName, spec, required)?.let { inputs[inputName] = it } }
                }
                name to NodeInfo(
                    name = name,
                    displayName = node["display_name"].str() ?: name,
                    category = node["category"].str().orEmpty(),
                    inputs = inputs,
                    outputs = node["output"].arr()?.map { it.str() ?: "*" }.orEmpty(),
                    outputNode = (node["output_node"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }.toMap(),
        )

        private fun parseInput(name: String, spec: JsonElement, required: Boolean): InputSpec? {
            val parts = spec.arr() ?: return null
            val head = parts.getOrNull(0) ?: return null
            val opts = parts.getOrNull(1).obj()
            fun d(key: String) = opts?.get(key)?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.content?.toDoubleOrNull() }
            val (type, options) = when {
                head is JsonArray -> "COMBO" to head.mapNotNull { it.str() ?: it.prim()?.content }
                head.str() == "COMBO" -> "COMBO" to opts?.get("options").arr()?.mapNotNull { it.str() ?: it.prim()?.content }.orEmpty()
                else -> (head.str() ?: return null) to emptyList()
            }
            return InputSpec(
                name = name,
                type = type,
                required = required,
                default = opts?.get("default"),
                min = d("min"),
                max = d("max"),
                step = d("step"),
                options = options,
                multiline = (opts?.get("multiline") as? JsonPrimitive)?.booleanOrNull == true,
                upload = opts?.get("image_upload") != null || opts?.get("audio_upload") != null || opts?.get("video_upload") != null,
            )
        }
    }
}

/** A GPU (or CPU) from `/system_stats`. */
class ComfyDevice(val name: String, val type: String, val vramTotal: Long, val vramFree: Long)

/** `/system_stats`. */
class SystemStats(val comfyVersion: String?, val os: String?, val devices: List<ComfyDevice>) {
    companion object {
        fun parse(json: String): SystemStats {
            val root = ComfyJson.parseToJsonElement(json).obj() ?: JsonObject(emptyMap())
            val system = root["system"].obj()
            return SystemStats(
                comfyVersion = system?.get("comfyui_version").str(),
                os = system?.get("os").str(),
                devices = root["devices"].arr()?.mapNotNull { d ->
                    val o = d.obj() ?: return@mapNotNull null
                    ComfyDevice(o["name"].str().orEmpty(), o["type"].str().orEmpty(), o["vram_total"].prim()?.longOrNull ?: 0, o["vram_free"].prim()?.longOrNull ?: 0)
                }.orEmpty(),
            )
        }
    }
}

/** `/queue`: prompt ids running and waiting. */
class QueueStatus(val running: List<String>, val pending: List<String>) {
    companion object {
        fun parse(json: String): QueueStatus {
            val root = ComfyJson.parseToJsonElement(json).obj()
            fun ids(key: String) = root?.get(key).arr()?.mapNotNull { it.arr()?.getOrNull(1).str() }.orEmpty()
            return QueueStatus(ids("queue_running"), ids("queue_pending"))
        }
    }
}

/** One validation problem of a node in a refused prompt. */
class NodeError(val nodeId: String, val classType: String, val messages: List<String>)

/** The answer to `POST /prompt`. */
sealed interface SubmitResult {
    class Queued(val promptId: String, val number: Int) : SubmitResult

    /** Refused (validation): [message] is the readable summary. */
    class Refused(val message: String, val nodeErrors: List<NodeError>) : SubmitResult

    companion object {
        fun parse(status: Int, body: String): SubmitResult {
            val root = runCatching { ComfyJson.parseToJsonElement(body).obj() }.getOrNull()
            val nodeErrors = root?.get("node_errors").obj()?.map { (id, v) ->
                val o = v.obj()
                NodeError(
                    id,
                    o?.get("class_type").str().orEmpty(),
                    o?.get("errors").arr()?.mapNotNull { e ->
                        val eo = e.obj() ?: return@mapNotNull null
                        listOfNotNull(eo["message"].str(), eo["details"].str()?.takeIf { it.isNotBlank() }).joinToString(": ")
                    }.orEmpty(),
                )
            }.orEmpty()
            val promptId = root?.get("prompt_id").str()
            if (status in 200..299 && promptId != null && nodeErrors.isEmpty()) return Queued(promptId, root?.get("number").int() ?: 0)
            val error = root?.get("error")
            val head = error.obj()?.let { e -> listOfNotNull(e["message"].str(), e["details"].str()?.takeIf { it.isNotBlank() }).joinToString(": ") }
                ?: error.str() ?: if (status == 0) "No response" else "HTTP $status"
            val details = nodeErrors.joinToString("; ") { n -> "${n.classType.ifEmpty { "node" }} #${n.nodeId}: ${n.messages.joinToString(", ")}" }
            return Refused(listOf(head, details).filter { it.isNotBlank() }.joinToString(" — "), nodeErrors)
        }
    }
}

/** One entry of `/history/{id}`. */
class HistoryEntry(
    val promptId: String,
    val completed: Boolean,
    /** `success` or `error`. */
    val status: String?,
    /** Output files by node id, in node order. */
    val outputs: Map<String, List<ComfyFile>>,
    /** Node ids whose outputs are animated (SaveAnimatedWEBP/PNG report `animated: [true]`). */
    val animated: Set<String>,
    /** The execution error, when [status] is `error`. */
    val error: String?,
) {
    companion object {
        /** Output keys that hold files. */
        val FILE_KEYS = listOf("images", "gifs", "videos", "video", "audio")

        /** The entry for [promptId] in a `/history` body; null while it is not there yet. */
        fun parse(json: String, promptId: String): HistoryEntry? {
            val root = runCatching { ComfyJson.parseToJsonElement(json).obj() }.getOrNull() ?: return null
            val entry = root[promptId].obj() ?: return null
            val status = entry["status"].obj()
            val outputs = LinkedHashMap<String, List<ComfyFile>>()
            val animated = HashSet<String>()
            entry["outputs"].obj()?.forEach { (node, v) ->
                val o = v.obj() ?: return@forEach
                outputs[node] = files(o)
                if (o["animated"].arr()?.any { (it as? JsonPrimitive)?.booleanOrNull == true } == true) animated += node
            }
            val error = status?.get("messages").arr()?.firstNotNullOfOrNull { m ->
                val pair = m.arr()
                if (pair?.getOrNull(0).str() == "execution_error") errorText(pair?.getOrNull(1).obj()) else null
            }
            return HistoryEntry(
                promptId = promptId,
                completed = (status?.get("completed") as? JsonPrimitive)?.booleanOrNull == true,
                status = status?.get("status_str").str(),
                outputs = outputs,
                animated = animated,
                error = error,
            )
        }

        /** The files in one node's output object (`executed.output` or a history output). */
        fun files(output: JsonObject): List<ComfyFile> = FILE_KEYS.flatMap { key ->
            output[key].arr()?.mapNotNull { f ->
                val o = f.obj() ?: return@mapNotNull null
                val name = o["filename"].str() ?: return@mapNotNull null
                ComfyFile(name, o["subfolder"].str().orEmpty(), o["type"].str() ?: "output")
            }.orEmpty()
        }

        internal fun errorText(data: JsonObject?): String {
            if (data == null) return "Execution failed"
            val type = data["node_type"].str()
            val message = data["exception_message"].str()?.trim().orEmpty().ifEmpty { data["exception_type"].str() ?: "error" }
            return if (type != null) "$type: $message" else message
        }
    }
}

/** A text message on `/ws`. */
sealed interface ComfyMessage {
    /** Queue state; the first one after connecting carries the socket's [sid]. */
    class Status(val queueRemaining: Int?, val sid: String?) : ComfyMessage
    class ExecutionStart(val promptId: String) : ComfyMessage
    class ExecutionCached(val promptId: String, val nodes: List<String>) : ComfyMessage

    /** Node [node] started; null [node] means the prompt finished (older servers). */
    class Executing(val promptId: String?, val node: String?) : ComfyMessage
    class Progress(val promptId: String?, val node: String?, val value: Int, val max: Int) : ComfyMessage
    class Executed(val promptId: String?, val node: String, val files: List<ComfyFile>, val output: JsonObject) : ComfyMessage
    class ExecutionSuccess(val promptId: String) : ComfyMessage
    class ExecutionError(val promptId: String?, val nodeId: String?, val nodeType: String?, val message: String) : ComfyMessage
    class ExecutionInterrupted(val promptId: String?) : ComfyMessage
    class Other(val type: String) : ComfyMessage

    companion object {
        /** Parses one text frame; null when it is not a `{type, data}` message. */
        fun parse(text: String): ComfyMessage? {
            val root = runCatching { ComfyJson.parseToJsonElement(text).obj() }.getOrNull() ?: return null
            val type = root["type"].str() ?: return null
            val data = root["data"].obj() ?: JsonObject(emptyMap())
            val prompt = data["prompt_id"].str()
            return when (type) {
                "status" -> Status(data["status"].obj()?.get("exec_info").obj()?.get("queue_remaining").int(), data["sid"].str())
                "execution_start" -> ExecutionStart(prompt.orEmpty())
                "execution_cached" -> ExecutionCached(prompt.orEmpty(), data["nodes"].arr()?.mapNotNull { it.str() ?: it.prim()?.content }.orEmpty())
                "executing" -> Executing(prompt, data["node"].str() ?: data["node"].prim()?.contentOrNull)
                "progress" -> Progress(prompt, data["node"].str(), data["value"].int() ?: 0, data["max"].int() ?: 0)
                "executed" -> {
                    val output = data["output"].obj() ?: JsonObject(emptyMap())
                    Executed(prompt, data["node"].str() ?: data["node"].prim()?.content.orEmpty(), HistoryEntry.files(output), output)
                }
                "execution_success" -> ExecutionSuccess(prompt.orEmpty())
                "execution_error" -> ExecutionError(prompt, data["node_id"].str(), data["node_type"].str(), HistoryEntry.errorText(data))
                "execution_interrupted" -> ExecutionInterrupted(prompt)
                else -> Other(type)
            }
        }
    }
}

/** A binary frame on `/ws`: u32 big-endian event type, then the payload. */
sealed interface ComfyBinary {
    /** A sampler preview (event 1: u32 image type 1 = JPEG, 2 = PNG, then the image; event 4: u32 metadata length, JSON, image). */
    class PreviewImage(val mimeType: String, val bytes: ByteArray, val promptId: String? = null, val nodeId: String? = null) : ComfyBinary

    /** Progress text from a node (event 3: u32 node id length, node id, UTF-8 text). */
    class Text(val nodeId: String, val text: String) : ComfyBinary
    class Other(val event: Int) : ComfyBinary

    companion object {
        const val PREVIEW_IMAGE = 1
        const val UNENCODED_PREVIEW_IMAGE = 2
        const val TEXT = 3
        const val PREVIEW_IMAGE_WITH_METADATA = 4

        private fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

        fun parse(bytes: ByteArray): ComfyBinary? {
            if (bytes.size < 4) return null
            val event = u32(bytes, 0).toInt()
            return when (event) {
                PREVIEW_IMAGE -> {
                    if (bytes.size < 8) return null
                    val mime = when (u32(bytes, 4).toInt()) {
                        1 -> "image/jpeg"
                        2 -> "image/png"
                        else -> return Other(event)
                    }
                    PreviewImage(mime, bytes.copyOfRange(8, bytes.size))
                }
                PREVIEW_IMAGE_WITH_METADATA -> {
                    if (bytes.size < 8) return null
                    val length = u32(bytes, 4)
                    if (length < 0 || 8 + length > bytes.size) return null
                    val end = 8 + length.toInt()
                    val meta = runCatching { ComfyJson.parseToJsonElement(bytes.copyOfRange(8, end).decodeToString()).obj() }.getOrNull()
                    PreviewImage(
                        meta?.get("image_type").str() ?: "image/png",
                        bytes.copyOfRange(end, bytes.size),
                        meta?.get("prompt_id").str(),
                        meta?.get("node_id").str(),
                    )
                }
                TEXT -> {
                    if (bytes.size < 8) return null
                    val length = u32(bytes, 4)
                    if (8 + length > bytes.size) return null
                    val end = 8 + length.toInt()
                    Text(bytes.copyOfRange(8, end).decodeToString(), bytes.copyOfRange(end, bytes.size).decodeToString())
                }
                else -> Other(event)
            }
        }
    }
}

/** Request builders: paths, bodies and the multipart upload. */
object ComfyRequests {
    fun promptBody(graph: JsonObject, clientId: String): String = buildJsonObject {
        put("prompt", graph)
        put("client_id", clientId)
    }.toString()

    fun interruptBody(promptId: String?): String = buildJsonObject { promptId?.let { put("prompt_id", it) } }.toString()

    /** Removes [promptId] from the pending queue (it has not started yet). */
    fun deleteFromQueueBody(promptId: String): String = """{"delete":[${JsonPrimitive(promptId)}]}"""

    fun viewPath(file: ComfyFile): String =
        "/view?filename=${encodeQuery(file.filename)}&subfolder=${encodeQuery(file.subfolder)}&type=${encodeQuery(file.type)}"

    fun historyPath(promptId: String): String = "/history/${encodePathSegment(promptId)}"

    /** `ws://` / `wss://` for an `http://` / `https://` base URL. */
    fun socketUrl(base: String, clientId: String): String {
        val ws = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring(8)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring(7)
            else -> base
        }
        return "$ws/ws?clientId=${encodeQuery(clientId)}"
    }

    /** A multipart body for `/upload/image`; returns the content type and the bytes. */
    fun uploadBody(fileName: String, mimeType: String, bytes: ByteArray, subfolder: String, overwrite: Boolean, boundary: String): Pair<String, ByteArray> {
        val safeName = fileName.replace(Regex("[\"\\r\\n\\\\/]"), "_")
        val head = StringBuilder()
        fun field(name: String, value: String) {
            head.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                .append(value).append("\r\n")
        }
        field("type", "input")
        field("subfolder", subfolder)
        field("overwrite", if (overwrite) "true" else "false")
        head.append("--").append(boundary).append("\r\n")
            .append("Content-Disposition: form-data; name=\"image\"; filename=\"").append(safeName).append("\"\r\n")
            .append("Content-Type: ").append(mimeType).append("\r\n\r\n")
        val tail = "\r\n--$boundary--\r\n"
        return "multipart/form-data; boundary=$boundary" to (head.toString().encodeToByteArray() + bytes + tail.encodeToByteArray())
    }

    /** The `/upload/image` answer. */
    fun parseUpload(json: String): ComfyFile? {
        val root = runCatching { ComfyJson.parseToJsonElement(json).obj() }.getOrNull() ?: return null
        val name = root["name"].str() ?: return null
        return ComfyFile(name, root["subfolder"].str().orEmpty(), root["type"].str() ?: "input")
    }

    /** The value a LoadImage input takes for an uploaded [file]. */
    fun imageInputValue(file: ComfyFile): String = if (file.subfolder.isEmpty()) file.filename else "${file.subfolder}/${file.filename}"
}
