package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.providers.remote.internal.WireChatRequest
import dev.wckdboy.autobot.providers.remote.internal.WireChunk
import dev.wckdboy.autobot.providers.remote.internal.WireErrorEnvelope
import dev.wckdboy.autobot.providers.remote.internal.WireFunctionCall
import dev.wckdboy.autobot.providers.remote.internal.WireFunctionDef
import dev.wckdboy.autobot.providers.remote.internal.WireJson
import dev.wckdboy.autobot.providers.remote.internal.WireMessage
import dev.wckdboy.autobot.providers.remote.internal.WireModelList
import dev.wckdboy.autobot.providers.remote.internal.WireStreamOptions
import dev.wckdboy.autobot.providers.remote.internal.WireTool
import dev.wckdboy.autobot.providers.remote.internal.WireToolCall
import dev.wckdboy.autobot.shared.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The OpenAI-compatible `/chat/completions` wire format, shared by the Android client (OkHttp)
 * and the iOS transport (URLSession): request encoding, SSE stream decoding and HTTP error
 * mapping. Transports only move bytes; everything the model sees or sends is decided here.
 */
object OpenAiCodec {

    /** Serializes [request] to the OpenAI wire format. Throws on a tool schema that is not a JSON object. */
    fun encodeRequest(
        request: ChatRequest,
        includeUsage: Boolean,
        fs: FileSystem = SystemFileSystem,
        reasoning: OpenAiReasoning = OpenAiReasoning(),
        echoExtraContent: Boolean = false,
    ): String = WireJson.encodeToString(
        WireChatRequest.serializer(),
        WireChatRequest(
            model = request.model,
            messages = request.messages.map { it.toWire(request.echoReasoning, fs, echoExtraContent) },
            reasoningEffort = reasoning.effort,
            reasoning = reasoning.reasoning,
            chatTemplateKwargs = reasoning.chatTemplateKwargs,
            reasoningBudgetTokens = reasoning.reasoningBudgetTokens,
            enableThinking = reasoning.enableThinking,
            thinkingBudget = reasoning.thinkingBudget,
            thinking = reasoning.thinking,
            stream = true,
            streamOptions = if (includeUsage) WireStreamOptions() else null,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { spec ->
                val schema = runCatching { WireJson.parseToJsonElement(spec.parametersJson) }.getOrNull() as? JsonObject
                    ?: throw IllegalArgumentException("${spec.name}: parameters must be a JSON object")
                WireTool(function = WireFunctionDef(spec.name, spec.description, schema))
            },
        ),
    )

    private fun ChatMessage.toWire(echoReasoning: Boolean, fs: FileSystem, echoExtraContent: Boolean): WireMessage = WireMessage(
        role = role.name.lowercase(),
        content = when {
            role == ChatRole.ASSISTANT && toolCalls.isNotEmpty() && content.isEmpty() -> null
            images.isNotEmpty() -> buildJsonArray {
                if (content.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", content) })
                images.forEach { path ->
                    add(
                        buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", dataUrl(fs.read(path.toPath()) { readByteArray() })) })
                        },
                    )
                }
            }
            else -> JsonPrimitive(content)
        },
        toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
            WireToolCall(
                ToolCallIds.bare(it.id),
                function = WireFunctionCall(it.name, it.arguments),
                extraContent = if (echoExtraContent) ToolCallIds.state(it.id, ToolCallIds.GEMINI_EXTRA) as? JsonObject else null,
            )
        },
        toolCallId = toolCallId?.let(ToolCallIds::bare),
        reasoningContent = if (echoReasoning && role == ChatRole.ASSISTANT) reasoning else null,
    )

    /** An attached image as a `data:` URL (cloud vision models take images inline). */
    fun dataUrl(bytes: ByteArray): String = "data:${imageMime(bytes)};base64," + Base64.encode(bytes)

    /** The media type of an attached image (PNG, WebP, else JPEG: attachments are re-encoded to these). */
    fun imageMime(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    /** Maps a failed HTTP response (status + up to 16 KiB of body) to a [ChatEvent.Error]. */
    fun httpError(code: Int, body: String): ChatEvent.Error {
        val parsed = runCatching { WireJson.decodeFromString(WireErrorEnvelope.serializer(), body) }.getOrNull()
        val detail = (parsed?.error?.message ?: parsed?.message)
            ?.take(MAX_ERROR_MESSAGE)
            ?.takeIf { it.isNotBlank() }
        val kind = when (code) {
            400, 404, 422 -> ErrorKind.BAD_REQUEST
            401, 403 -> ErrorKind.UNAUTHORIZED
            402 -> ErrorKind.QUOTA
            429 -> ErrorKind.RATE_LIMITED
            in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.HTTP
        }
        val fallback = when (kind) {
            ErrorKind.UNAUTHORIZED -> "Authentication failed — check the API key"
            ErrorKind.QUOTA -> "Insufficient balance or quota"
            ErrorKind.RATE_LIMITED -> "Rate limited — try again later"
            ErrorKind.SERVER -> "Provider server error"
            ErrorKind.BAD_REQUEST -> "Request rejected by provider"
            else -> "HTTP error"
        }
        return ChatEvent.Error(kind, "HTTP $code: ${detail ?: fallback}", httpCode = code)
    }

    /** Number of models in a `GET /models` response, or null when it cannot be parsed. */
    fun modelCount(body: String): Int? = runCatching { WireJson.decodeFromString(WireModelList.serializer(), body).data.size }.getOrNull()

    /** Parses JSON text with the wire format's lenient settings. */
    fun parseJson(text: String): JsonElement = WireJson.parseToJsonElement(text)

    private const val MAX_ERROR_MESSAGE = 300
}

/**
 * Turns Server-Sent Event lines of a `/chat/completions` stream into [ChatEvent]s.
 *
 * - `delta.content` → [ChatEvent.ContentDelta]
 * - `delta.reasoning_content` (DeepSeek) or `delta.reasoning` (OpenRouter, Ollama) →
 *   [ChatEvent.ReasoningDelta]
 * - `delta.tool_calls[]` → [ChatEvent.ToolCallDelta] (fragments, keyed by `index`)
 * - `finish_reason` → [ChatEvent.Finish]
 * - `usage` (requested with `stream_options.include_usage`) → [ChatEvent.Usage]
 *
 * Feed lines until [done] or an [ChatEvent.Error] comes out, then emit [ChatEvent.Done] yourself
 * if no error occurred.
 */
class SseDecoder : StreamDecoder {
    /** `data: [DONE]` was seen. */
    override var done: Boolean = false
        private set

    override fun feed(line: String): List<ChatEvent> {
        if (done || line.isEmpty() || line.startsWith(":")) return emptyList() // keep-alive / comment
        if (!line.startsWith("data:")) return emptyList() // ignore `event:`, `id:`, `retry:`
        val data = line.removePrefix("data:").trim()
        if (data == DONE) {
            done = true
            return emptyList()
        }
        val chunk = try {
            WireJson.decodeFromString(WireChunk.serializer(), data)
        } catch (_: SerializationException) {
            return listOf(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed stream chunk"))
        } catch (_: IllegalArgumentException) {
            return listOf(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed stream chunk"))
        }
        chunk.error?.let { error -> return listOf(ChatEvent.Error(ErrorKind.SERVER, error.message ?: "Provider reported an error")) }
        val events = mutableListOf<ChatEvent>()
        for (choice in chunk.choices) {
            val delta = choice.delta
            if (delta != null) {
                val (text, thinking) = content(delta.content)
                val reasoning = delta.reasoningContent ?: delta.reasoning ?: thinking
                if (!reasoning.isNullOrEmpty()) events += ChatEvent.ReasoningDelta(reasoning)
                if (!text.isNullOrEmpty()) events += ChatEvent.ContentDelta(text)
                delta.toolCalls?.forEach { call ->
                    // Gemini's thought signature travels in the id (see ToolCallIds).
                    val id = call.id?.let { id -> call.extraContent?.let { ToolCallIds.attach(id, ToolCallIds.GEMINI_EXTRA, it) } ?: id }
                    events += ChatEvent.ToolCallDelta(call.index, id, call.function?.name, call.function?.arguments.orEmpty())
                }
            }
            choice.finishReason?.let { events += ChatEvent.Finish(it) }
        }
        (chunk.usage ?: chunk.xGroq?.usage)?.let { events += ChatEvent.Usage(it.promptTokens, it.completionTokens, it.totalTokens) }
        return events
    }

    /** `delta.content` as (text, thinking): a string, or Mistral's list of `text` / `thinking` chunks. */
    private fun content(element: JsonElement?): Pair<String?, String?> = when (element) {
        null, is JsonNull -> null to null
        is JsonPrimitive -> element.contentOrNull to null
        is JsonArray -> {
            val text = StringBuilder()
            val thinking = StringBuilder()
            element.forEach { part ->
                val o = part as? JsonObject ?: return@forEach
                when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> text.append((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                    "thinking" -> when (val t = o["thinking"]) {
                        is JsonArray -> t.forEach { (it as? JsonObject)?.get("text")?.let { x -> thinking.append((x as? JsonPrimitive)?.contentOrNull.orEmpty()) } }
                        is JsonPrimitive -> thinking.append(t.contentOrNull.orEmpty())
                        else -> Unit
                    }
                }
            }
            text.toString().ifEmpty { null } to thinking.toString().ifEmpty { null }
        }
        else -> null to null
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}
