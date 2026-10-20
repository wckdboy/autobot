package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** An agent-to-renderer A2UI v1.0 message, after its envelope was read. */
sealed class A2uiMessage {
    abstract val surfaceId: String?

    /** The whole message as received. */
    abstract val json: JsonObject

    /** The message type, e.g. `updateComponents`. */
    abstract val kind: String

    class CreateSurface internal constructor(
        override val surfaceId: String,
        val catalogId: String?,
        val sendDataModel: Boolean,
        val components: List<JsonObject>,
        val dataModel: JsonObject?,
        override val json: JsonObject,
    ) : A2uiMessage() {
        override val kind get() = CREATE_SURFACE
    }

    class UpdateComponents internal constructor(
        override val surfaceId: String,
        val components: List<JsonObject>,
        override val json: JsonObject,
    ) : A2uiMessage() {
        override val kind get() = UPDATE_COMPONENTS
    }

    /** [value] null deletes what is at [path]; [path] `""` is the whole model. */
    class UpdateDataModel internal constructor(
        override val surfaceId: String,
        val path: String,
        val value: JsonElement?,
        override val json: JsonObject,
    ) : A2uiMessage() {
        override val kind get() = UPDATE_DATA_MODEL
    }

    class DeleteSurface internal constructor(
        override val surfaceId: String,
        override val json: JsonObject,
    ) : A2uiMessage() {
        override val kind get() = DELETE_SURFACE
    }

    /** `callRendererFunction` or `agentFunctionResponse`: read, but not supported yet. */
    class Unsupported internal constructor(
        override val kind: String,
        override val surfaceId: String?,
        val functionCallId: String?,
        override val json: JsonObject,
    ) : A2uiMessage()

    companion object {
        const val VERSION = "v1.0"
        const val CREATE_SURFACE = "createSurface"
        const val UPDATE_COMPONENTS = "updateComponents"
        const val UPDATE_DATA_MODEL = "updateDataModel"
        const val DELETE_SURFACE = "deleteSurface"
        const val CALL_RENDERER_FUNCTION = "callRendererFunction"
        const val AGENT_FUNCTION_RESPONSE = "agentFunctionResponse"

        internal val KINDS = listOf(CREATE_SURFACE, UPDATE_COMPONENTS, UPDATE_DATA_MODEL, DELETE_SURFACE, CALL_RENDERER_FUNCTION, AGENT_FUNCTION_RESPONSE)

        /** The message type key of [json], when it has exactly one. */
        internal fun kindOf(json: JsonObject): String? = KINDS.filter { it in json }.singleOrNull()

        /** Reads a message whose envelope already validated; null if it is not one of the known kinds. */
        internal fun from(json: JsonObject): A2uiMessage? {
            val kind = kindOf(json) ?: return null
            val body = json[kind] as? JsonObject ?: return null
            val surfaceId = body["surfaceId"]?.stringOrNull()
            fun components() = (body["components"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return when (kind) {
                CREATE_SURFACE -> CreateSurface(
                    surfaceId ?: return null,
                    body["catalogId"]?.stringOrNull(),
                    body["sendDataModel"]?.booleanOrNull() ?: false,
                    components(),
                    body["dataModel"] as? JsonObject,
                    json,
                )
                UPDATE_COMPONENTS -> UpdateComponents(surfaceId ?: return null, components(), json)
                UPDATE_DATA_MODEL -> UpdateDataModel(
                    surfaceId ?: return null,
                    body["path"]?.stringOrNull() ?: "",
                    body["value"].let { if (it == null || it == JsonNull) null else it },
                    json,
                )
                DELETE_SURFACE -> DeleteSurface(surfaceId ?: return null, json)
                CALL_RENDERER_FUNCTION -> Unsupported(kind, surfaceId, body["functionCallId"]?.stringOrNull(), json)
                else -> Unsupported(kind, null, body["functionCallId"]?.stringOrNull(), json)
            }
        }
    }
}

/** The messages read from a stream, and what could not be read. */
class A2uiParseResult internal constructor(
    /** Each message object with its 0-based position in the stream. */
    internal val messages: List<Pair<Int, JsonObject>>,
    val errors: List<A2uiError>,
) {
    val messageCount: Int get() = messages.size
}

/**
 * Reads agent output into message objects. Accepts a JSONL stream (one message per line), a JSON
 * array of messages, a `{"messages": [...]}` wrapper, or one message; a surrounding Markdown code
 * fence is ignored. Each message is checked for size, depth and its `version` before it is used.
 */
object A2uiParser {
    fun parse(text: String, limits: A2uiLimits = A2uiLimits.standard): A2uiParseResult {
        val errors = ArrayList<A2uiError>()
        if (text.length > limits.maxStreamChars) {
            return A2uiParseResult(emptyList(), listOf(A2uiError(A2uiErrorCode.VALIDATION_FAILED, "stream is ${text.length} characters; the limit is ${limits.maxStreamChars}")))
        }
        val body = stripFence(text.trim())
        val raw = ArrayList<Pair<Int, JsonElement>>()
        checkJsonShape(body, limits.maxStreamChars, limits.maxJsonDepth + 2)?.let {
            return A2uiParseResult(emptyList(), listOf(A2uiError(A2uiErrorCode.VALIDATION_FAILED, it)))
        }
        val whole = if (body.startsWith("[") || body.startsWith("{")) tryParse(body) else null
        when {
            whole is JsonArray -> whole.forEachIndexed { i, e -> raw += i to e }
            whole is JsonObject && whole["messages"] is JsonArray && A2uiMessage.kindOf(whole) == null ->
                (whole["messages"] as JsonArray).forEachIndexed { i, e -> raw += i to e }
            whole is JsonObject -> raw += 0 to whole
            else -> {
                var index = 0
                for (line in body.lines()) {
                    val l = line.trim()
                    if (l.isEmpty()) continue
                    val i = index++
                    checkJsonShape(l, limits.maxMessageChars, limits.maxJsonDepth)?.let {
                        errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, it, messageIndex = i)
                        null
                    } ?: try {
                        raw += i to Json.parseToJsonElement(l)
                    } catch (e: Exception) {
                        errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "line is not valid JSON: ${e.message?.lineSequence()?.firstOrNull()}", messageIndex = i)
                    }
                }
            }
        }
        if (raw.size > limits.maxMessages) {
            errors += A2uiError(A2uiErrorCode.VALIDATION_FAILED, "stream has ${raw.size} messages; the limit is ${limits.maxMessages}")
            return A2uiParseResult(emptyList(), errors)
        }
        val messages = ArrayList<Pair<Int, JsonObject>>()
        for ((i, e) in raw) {
            val problem = checkMessage(e, limits)
            if (problem != null) errors += problem.copy(messageIndex = i) else messages += i to (e as JsonObject)
        }
        return A2uiParseResult(messages, errors)
    }

    /** Envelope checks shared by every entry point: an object, the v1.0 version, within limits. */
    internal fun checkMessage(e: JsonElement, limits: A2uiLimits): A2uiError? {
        if (e !is JsonObject) return A2uiError(A2uiErrorCode.VALIDATION_FAILED, "a message must be a JSON object, got ${jsonTypeName(e)}")
        val surfaceId = A2uiMessage.kindOf(e)?.let { (e[it] as? JsonObject)?.get("surfaceId")?.stringOrNull() }
        val version = e["version"]
        if (version == null) {
            return A2uiError(A2uiErrorCode.VALIDATION_FAILED, "missing \"version\"; this renderer accepts \"v1.0\"", "/version", surfaceId)
        }
        if (version.stringOrNull() != A2uiMessage.VERSION) {
            return A2uiError(
                A2uiErrorCode.VALIDATION_FAILED,
                "unsupported A2UI version ${version.toJsonText()}; this renderer accepts only \"v1.0\"",
                "/version",
                surfaceId,
            )
        }
        checkJsonShape(e.toJsonText(), limits.maxMessageChars, limits.maxJsonDepth)?.let {
            return A2uiError(A2uiErrorCode.VALIDATION_FAILED, it, "", surfaceId)
        }
        checkJsonSizes(e, limits)?.let { (path, why) -> return A2uiError(A2uiErrorCode.VALIDATION_FAILED, why, path, surfaceId) }
        return null
    }

    private fun tryParse(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            null
        }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```")) return text
        val firstNewline = text.indexOf('\n')
        if (firstNewline < 0) return text
        val inner = text.substring(firstNewline + 1)
        return inner.trimEnd().removeSuffix("```").trim()
    }
}
