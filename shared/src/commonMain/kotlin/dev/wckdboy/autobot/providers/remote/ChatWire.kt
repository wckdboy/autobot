package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.providers.remote.internal.WireJson
import dev.wckdboy.autobot.shared.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.FileSystem

/** Turns the lines of a streamed response into [ChatEvent]s (see [SseDecoder]). */
interface StreamDecoder {
    /** The stream signalled its end; later lines are ignored. */
    val done: Boolean

    fun feed(line: String): List<ChatEvent>
}

/**
 * One chat API dialect: where requests go, how they are authenticated, encoded and decoded.
 * Transports (OkHttp on Android, URLSession on iOS) only move the bytes.
 */
interface ChatWire {
    /** Path of the streaming chat endpoint, relative to the provider's base URL. */
    val chatPath: String

    /** Authentication and version headers. The key is only ever sent to the provider's own host. */
    fun headers(apiKey: String?): Map<String, String>

    /** The request body. Throws [IllegalArgumentException] on a tool schema that is not a JSON object. */
    fun encode(request: ChatRequest, fs: FileSystem = SystemFileSystem): String

    fun decoder(): StreamDecoder

    fun httpError(code: Int, body: String): ChatEvent.Error = OpenAiCodec.httpError(code, body)
}

/**
 * `POST {base}/chat/completions` with a bearer key.
 *
 * @param includeUsage send `stream_options.include_usage` (only where the provider documents it).
 * @param echoExtraContent send tool calls' `extra_content` back (Gemini thought signatures).
 * @param reasoning the thinking fields to send for a request ([RemoteThinking.openAi]); none by default.
 */
class OpenAiWire(
    private val includeUsage: Boolean = true,
    private val echoExtraContent: Boolean = false,
    private val reasoning: (ChatRequest) -> OpenAiReasoning = { OpenAiReasoning() },
) : ChatWire {
    override val chatPath = "chat/completions"

    override fun headers(apiKey: String?): Map<String, String> = ProviderAuth.headers(AuthStyle.BEARER, apiKey)

    override fun encode(request: ChatRequest, fs: FileSystem): String =
        OpenAiCodec.encodeRequest(request, includeUsage, fs, reasoning(request), echoExtraContent)

    override fun decoder(): StreamDecoder = SseDecoder()
}

/** How a provider takes its API key. */
enum class AuthStyle {
    /** `Authorization: Bearer <key>` (OpenAI and nearly every compatible API). */
    BEARER,

    /** `x-api-key: <key>` plus `anthropic-version` (Anthropic's native API). */
    ANTHROPIC,

    /** `x-goog-api-key: <key>` (Gemini's native API, used for its model list). */
    GOOGLE,
}

object ProviderAuth {
    /** The pinned Anthropic API version (https://docs.claude.com/en/api/versioning). */
    const val ANTHROPIC_VERSION = "2023-06-01"

    fun headers(style: AuthStyle, apiKey: String?): Map<String, String> {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        return when (style) {
            AuthStyle.BEARER -> key?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
            AuthStyle.ANTHROPIC -> buildMap {
                key?.let { put("x-api-key", it) }
                put("anthropic-version", ANTHROPIC_VERSION)
            }
            AuthStyle.GOOGLE -> key?.let { mapOf("x-goog-api-key" to it) }.orEmpty()
        }
    }

    /**
     * [headers] for a request to [url], or none when [url] is not on [baseUrl]'s host: a key is
     * never sent anywhere but the provider it belongs to (scheme, host and port must match).
     */
    fun scopedHeaders(style: AuthStyle, apiKey: String?, url: String, baseUrl: String): Map<String, String> {
        val target = origin(url) ?: return emptyMap()
        if (target != origin(baseUrl)) return emptyMap()
        return headers(style, apiKey)
    }

    /** `scheme://host:port` of an http(s) URL, lower-cased, with the default port filled in. */
    fun origin(url: String): String? {
        val m = ORIGIN.find(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val host = m.groupValues[3].lowercase().trimEnd('.')
        if (host.isEmpty() || m.groupValues[2].isNotEmpty()) return null // no userinfo
        val port = m.groupValues[4].removePrefix(":").ifEmpty { if (scheme == "https") "443" else "80" }
        return "$scheme://$host:$port"
    }

    private val ORIGIN = Regex("^(https?)://([^/?#@]*@)?(\\[[^\\]]+]|[^:/?#]+)(:\\d+)?(?:[/?#]|$)", RegexOption.IGNORE_CASE)
}

/**
 * Provider state that has to travel back with a tool call: Gemini's thought signature
 * (`extra_content`) and Anthropic's signed thinking blocks. It rides in the call id, which the
 * agent stores and echoes on the assistant message and on the tool result; each wire strips it
 * before sending and restores only the state meant for it. Ids without state are unchanged.
 */
object ToolCallIds {
    private const val SEPARATOR = "~~"

    const val GEMINI_EXTRA = "extra_content"
    const val ANTHROPIC_THINKING = "thinking"

    /** [id] carrying [state] under [key]. */
    fun attach(id: String, key: String, state: JsonElement): String {
        val payload = WireJson.encodeToString(JsonObject.serializer(), JsonObject(mapOf(key to state)))
        return id + SEPARATOR + Base64.UrlSafe.encode(payload.encodeToByteArray())
    }

    /** The provider's own id. */
    fun bare(id: String): String = id.substringBefore(SEPARATOR)

    /** The state stored under [key], if this id carries any. */
    fun state(id: String, key: String): JsonElement? {
        val encoded = id.substringAfter(SEPARATOR, "").ifEmpty { return null }
        val payload = runCatching { Base64.UrlSafe.decode(encoded).decodeToString() }.getOrNull() ?: return null
        val obj = runCatching { WireJson.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        return obj[key]
    }
}
