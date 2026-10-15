package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.providers.remote.internal.WireJson
import dev.wckdboy.autobot.shared.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Anthropic's native Messages API (`POST {base}/messages`, https://docs.claude.com/en/api/messages).
 *
 * Native rather than the OpenAI SDK compatibility layer, which Anthropic describes as meant for
 * testing and comparing models, not for production, and which ignores prompt caching. Here the
 * tool definitions, the system prompt and the newest turn carry `cache_control` breakpoints, so an
 * agent loop that resends the same prefix on every step reads it from the cache. Thinking is left
 * at the model's default (on for Claude 5, with the text omitted); the signed thinking blocks of a
 * tool-use turn are replayed unmodified, as the API requires, via [ToolCallIds].
 */
object AnthropicWire : ChatWire {
    override val chatPath = "messages"

    override fun headers(apiKey: String?): Map<String, String> = ProviderAuth.headers(AuthStyle.ANTHROPIC, apiKey)

    override fun encode(request: ChatRequest, fs: FileSystem): String = AnthropicCodec.encodeRequest(request, fs)

    override fun decoder(): StreamDecoder = AnthropicSseDecoder()
}

object AnthropicCodec {

    fun encodeRequest(request: ChatRequest, fs: FileSystem = SystemFileSystem): String {
        val system = mutableListOf<String>()
        val turns = mutableListOf<Turn>()
        fun add(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = turns.lastOrNull()
            // Consecutive same-role messages become one turn (tool results + a steering note).
            if (last?.role == role) last.blocks += blocks else turns += Turn(role, blocks.toMutableList())
        }
        for (m in request.messages) {
            when (m.role) {
                ChatRole.SYSTEM -> if (turns.isEmpty()) {
                    if (m.content.isNotBlank()) system += m.content
                } else {
                    add(USER, listOfNotNull(text("[system] ${m.content}")))
                }
                ChatRole.USER -> add(USER, m.images.map { image(fs.read(it.toPath()) { readByteArray() }) } + listOfNotNull(text(m.content)))
                ChatRole.ASSISTANT -> add(
                    ASSISTANT,
                    thinking(m) + listOfNotNull(text(m.content)) + m.toolCalls.map { call ->
                        buildJsonObject {
                            put("type", "tool_use")
                            put("id", ToolCallIds.bare(call.id))
                            put("name", call.name)
                            put("input", runCatching { WireJson.parseToJsonElement(call.arguments) }.getOrNull() as? JsonObject ?: JsonObject(emptyMap()))
                        }
                    },
                )
                ChatRole.TOOL -> add(
                    USER,
                    listOf(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", ToolCallIds.bare(m.toolCallId.orEmpty()))
                            if (m.content.isNotEmpty()) put("content", m.content)
                        },
                    ),
                )
            }
        }
        if (turns.firstOrNull()?.role == ASSISTANT) turns.add(0, Turn(USER, mutableListOf(text("…")!!)))
        // Cache breakpoints: the end of the conversation so far (the next step re-reads it).
        turns.lastOrNull { it.role == USER }?.let { turn -> turn.blocks[turn.blocks.lastIndex] = turn.blocks.last().cached() }

        val tools = request.tools.map { spec ->
            val schema = runCatching { WireJson.parseToJsonElement(spec.parametersJson) }.getOrNull() as? JsonObject
                ?: throw IllegalArgumentException("${spec.name}: parameters must be a JSON object")
            buildJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("input_schema", schema)
            }
        }
        return WireJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", request.model)
                put("max_tokens", request.maxTokens ?: defaultMaxTokens(request.model))
                put("stream", true)
                if (system.isNotEmpty()) {
                    put("system", buildJsonArray { add(text(system.joinToString("\n\n"))!!.cached()) })
                }
                put("messages", buildJsonArray { turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", JsonArray(t.blocks)) }) } })
                if (tools.isNotEmpty()) put("tools", JsonArray(tools.dropLast(1) + tools.last().cached()))
                // Anthropic's range is 0–1 (OpenAI's is 0–2).
                request.temperature?.let { put("temperature", it.coerceIn(0.0, 1.0)) }
            },
        )
    }

    /**
     * `max_tokens` is required by the Messages API. Claude 3 models cap output at 4096 tokens and
     * 3.5 at 8192; newer ones allow far more, 16k leaves room for long tool arguments.
     */
    fun defaultMaxTokens(model: String): Int {
        val m = model.lowercase()
        return when {
            "claude-3-5" in m || "claude-3.5" in m -> 8_192
            "claude-3-" in m && "claude-3-7" !in m -> 4_096
            else -> 16_384
        }
    }

    /** Messages API stop reasons → the OpenAI finish reasons the agent loop understands. */
    fun finishReason(stopReason: String): String = when (stopReason) {
        "tool_use" -> "tool_calls"
        "max_tokens", "model_context_window_exceeded" -> "length"
        "refusal" -> "content_filter"
        else -> "stop" // end_turn, stop_sequence, pause_turn
    }

    private class Turn(val role: String, val blocks: MutableList<JsonObject>)

    /** The signed thinking blocks of a tool-use turn, replayed unmodified and first (they ride in the first call id). */
    private fun thinking(m: ChatMessage): List<JsonObject> =
        (m.toolCalls.firstOrNull()?.let { ToolCallIds.state(it.id, ToolCallIds.ANTHROPIC_THINKING) } as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    private fun text(value: String): JsonObject? = value.takeIf { it.isNotBlank() }?.let {
        buildJsonObject {
            put("type", "text")
            put("text", it)
        }
    }

    private fun image(bytes: ByteArray) = buildJsonObject {
        put("type", "image")
        put(
            "source",
            buildJsonObject {
                put("type", "base64")
                put("media_type", OpenAiCodec.imageMime(bytes))
                put("data", Base64.encode(bytes))
            },
        )
    }

    private fun JsonObject.cached(): JsonObject = JsonObject(this + ("cache_control" to buildJsonObject { put("type", "ephemeral") }))

    private const val USER = "user"
    private const val ASSISTANT = "assistant"
}

/**
 * Decodes the Messages API event stream (`event:` / `data:` lines):
 *
 * - `content_block_start` (`tool_use`) and `input_json_delta` → [ChatEvent.ToolCallDelta], indexed
 *   by tool call rather than by content block
 * - `text_delta` → [ChatEvent.ContentDelta]; `thinking_delta` → [ChatEvent.ReasoningDelta]
 * - `message_start` / `message_delta` usage → [ChatEvent.Usage] (input includes cache reads and writes)
 * - `message_delta.stop_reason` → [ChatEvent.Finish] ([AnthropicCodec.finishReason])
 * - `message_stop` ends the stream; an `error` event becomes [ChatEvent.Error]
 */
class AnthropicSseDecoder : StreamDecoder {
    override var done: Boolean = false
        private set

    private var inputTokens: Int? = null
    private val toolOrdinals = HashMap<Int, Int>()

    /** Thinking blocks of this message, rebuilt for replay: block index → (type, text, signature / data). */
    private val thinking = LinkedHashMap<Int, Array<StringBuilder>>()
    private val thinkingTypes = HashMap<Int, String>()

    private fun thinkingState(): JsonArray? = thinking.takeIf { it.isNotEmpty() }?.let { blocks ->
        JsonArray(
            blocks.map { (index, parts) ->
                buildJsonObject {
                    if (thinkingTypes[index] == "redacted_thinking") {
                        put("type", "redacted_thinking")
                        put("data", parts[1].toString())
                    } else {
                        put("type", "thinking")
                        put("thinking", parts[0].toString())
                        put("signature", parts[1].toString())
                    }
                }
            },
        )
    }

    override fun feed(line: String): List<ChatEvent> {
        if (done || !line.startsWith("data:")) return emptyList()
        val event = try {
            WireJson.parseToJsonElement(line.removePrefix("data:").trim()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return listOf(ChatEvent.Error(ErrorKind.PROTOCOL, "Malformed stream chunk"))
        return when (event.string("type")) {
            "message_start" -> {
                (event.obj("message")?.obj("usage"))?.let { usage -> inputTokens = usage.inputTotal() }
                emptyList()
            }
            "content_block_start" -> {
                val index = event.int("index") ?: 0
                val block = event.obj("content_block") ?: return emptyList()
                when (block.string("type")) {
                    "tool_use" -> {
                        val ordinal = toolOrdinals.size
                        toolOrdinals[index] = ordinal
                        // The first call of a turn carries its thinking blocks back (see ToolCallIds).
                        val id = block.string("id")?.let { id ->
                            thinkingState()?.takeIf { ordinal == 0 }?.let { ToolCallIds.attach(id, ToolCallIds.ANTHROPIC_THINKING, it) } ?: id
                        }
                        listOf(ChatEvent.ToolCallDelta(ordinal, id, block.string("name"), ""))
                    }
                    "text" -> listOfNotNull(block.string("text")?.takeIf { it.isNotEmpty() }?.let { ChatEvent.ContentDelta(it) })
                    "thinking" -> {
                        val text = block.string("thinking").orEmpty()
                        thinkingTypes[index] = "thinking"
                        thinking[index] = arrayOf(StringBuilder(text), StringBuilder(block.string("signature").orEmpty()))
                        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { ChatEvent.ReasoningDelta(it) })
                    }
                    "redacted_thinking" -> {
                        thinkingTypes[index] = "redacted_thinking"
                        thinking[index] = arrayOf(StringBuilder(), StringBuilder(block.string("data").orEmpty()))
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            "content_block_delta" -> {
                val delta = event.obj("delta") ?: return emptyList()
                when (delta.string("type")) {
                    "text_delta" -> listOfNotNull(delta.string("text")?.takeIf { it.isNotEmpty() }?.let { ChatEvent.ContentDelta(it) })
                    "thinking_delta" -> {
                        val text = delta.string("thinking").orEmpty()
                        thinking[event.int("index") ?: 0]?.get(0)?.append(text)
                        listOfNotNull(text.takeIf { it.isNotEmpty() }?.let { ChatEvent.ReasoningDelta(it) })
                    }
                    "signature_delta" -> {
                        thinking[event.int("index") ?: 0]?.get(1)?.append(delta.string("signature").orEmpty())
                        emptyList()
                    }
                    "input_json_delta" -> {
                        val ordinal = toolOrdinals[event.int("index") ?: 0] ?: return emptyList()
                        listOf(ChatEvent.ToolCallDelta(ordinal, null, null, delta.string("partial_json").orEmpty()))
                    }
                    else -> emptyList() // signature_delta, citations_delta
                }
            }
            "message_delta" -> buildList {
                event.obj("delta")?.string("stop_reason")?.let { add(ChatEvent.Finish(AnthropicCodec.finishReason(it))) }
                event.obj("usage")?.let { usage ->
                    usage.inputTotal()?.let { inputTokens = it }
                    val output = usage.int("output_tokens")
                    val input = inputTokens
                    add(ChatEvent.Usage(input, output, if (input != null && output != null) input + output else null))
                }
            }
            "message_stop" -> {
                done = true
                emptyList()
            }
            "error" -> {
                val error = event.obj("error")
                val kind = when (error?.string("type")) {
                    "rate_limit_error" -> ErrorKind.RATE_LIMITED
                    "authentication_error", "permission_error" -> ErrorKind.UNAUTHORIZED
                    "invalid_request_error" -> ErrorKind.BAD_REQUEST
                    else -> ErrorKind.SERVER // overloaded_error, api_error
                }
                listOf(ChatEvent.Error(kind, error?.string("message") ?: "Provider reported an error"))
            }
            else -> emptyList() // ping
        }
    }

    private fun JsonObject.inputTotal(): Int? {
        val parts = listOf("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens").mapNotNull { int(it) }
        return if (parts.isEmpty()) null else parts.sum()
    }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
