package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.providers.remote.internal.WireJson
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A chat model a provider offers, from its models endpoint. Only what the app uses is kept:
 * [contextWindow] budgets the agent's context, the rest labels the picker.
 *
 * @param created release or upload time, epoch seconds (null when the provider does not say).
 * @param inputPerMTok / [outputPerMTok] USD per million tokens (OpenRouter-style pricing).
 * @param detail a short provider-specific note, e.g. `8.0B · Q4_K_M` for a local Ollama model.
 */
@Serializable
data class RemoteModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val created: Long? = null,
    val inputPerMTok: Double? = null,
    val outputPerMTok: Double? = null,
    val detail: String? = null,
) {
    /** Picker caption: `1M ctx · $2.00 / $8.00 per M · 8.0B · Q4_K_M` (empty when nothing is known). */
    fun caption(): String = listOfNotNull(
        contextWindow?.let { "${tokens(it)} ctx" },
        if (inputPerMTok != null && outputPerMTok != null) {
            if (inputPerMTok == 0.0 && outputPerMTok == 0.0) "free" else "$${money(inputPerMTok)} / $${money(outputPerMTok)} per M"
        } else {
            null
        },
        detail,
    ).joinToString(" · ")

    private companion object {
        fun tokens(n: Int): String = when {
            n >= 1_000_000 -> trim(n / 1_000_000.0) + "M"
            n >= 1_000 -> "${n / 1_000}k"
            else -> n.toString()
        }

        fun trim(v: Double): String = ((v * 10).toLong() / 10.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }

        fun money(v: Double): String {
            val cents = kotlin.math.round(v * 100).toLong()
            return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        }
    }
}

/** The shape of a provider's model list (`ProviderPreset.modelsApi`). */
enum class ModelsApi {
    /** `GET {base}/models` → `{data:[{id,…}]}` or a bare array; reads the common metadata extras. */
    OPENAI,

    /** OpenAI shape plus OpenRouter's `pricing` (USD per token, as strings). */
    OPENROUTER,

    /** `GET {base}/models?limit=1000` → `{data:[{id, display_name, created_at, max_input_tokens}]}`. */
    ANTHROPIC,

    /** Gemini's native `GET /v1beta/models` → `{models:[{name, inputTokenLimit, supportedGenerationMethods}]}`. */
    GEMINI,

    /** Cohere's `GET /v1/models?endpoint=chat` → `{models:[{name, endpoints, context_length}]}`. */
    COHERE,

    /** Alibaba Model Studio's `GET /api/v1/models` → `{output:{models:[{model, model_info, inference_metadata}]}}`. */
    DASHSCOPE,

    /** Ollama's `GET /api/tags` → `{models:[{name, modified_at, details}]}`. */
    OLLAMA,

    /** No usable models endpoint: the preset's suggestions are the list. */
    NONE,
}

/** A model list kept per provider; [fetchedAt] in epoch milliseconds. */
@Serializable
data class ModelCache(val fetchedAt: Long, val models: List<RemoteModel>) {
    fun ids(): List<String> = models.map { it.id }

    fun find(id: String): RemoteModel? = models.firstOrNull { it.id == id } ?: models.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** `fetched just now`, `fetched 12 min ago`, `fetched 3 h ago`, `fetched 2 d ago`. */
    fun ageLabel(nowMillis: Long): String = "fetched " + age(nowMillis - fetchedAt)

    fun encode(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        /** A stored cache, or null when it is missing or unreadable (it is then simply refetched). */
        fun decode(json: String?): ModelCache? = json?.let {
            try {
                WireJson.decodeFromString(serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun age(millis: Long): String {
            val minutes = millis.coerceAtLeast(0) / 60_000
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes min ago"
                minutes < 48 * 60 -> "${minutes / 60} h ago"
                else -> "${minutes / (24 * 60)} d ago"
            }
        }
    }
}

/** Outcome of [ChatProvider.listModels]: chat models only, flagship and newest first. */
sealed interface ModelListResult {
    data class Success(val models: List<RemoteModel>) : ModelListResult
    data class Failure(val kind: ErrorKind, val message: String) : ModelListResult
}

/** One models-list request: absolute URL and headers (the key only when the URL is on the provider's host). */
data class ModelsRequest(val url: String, val headers: Map<String, String>)

/**
 * Model lists: where to fetch them, how to parse every supported shape, which entries are chat
 * models and in what order to show them. Shared by both apps; the transports only move bytes.
 */
object ModelLists {

    /** The request for [preset]'s model list at the user's [baseUrl], or null if it has none. */
    fun request(preset: ProviderPreset, baseUrl: String, apiKey: String?): ModelsRequest? {
        if (preset.modelsApi == ModelsApi.NONE) return null
        val base = baseUrl.trim().trimEnd('/')
        if (ProviderAuth.origin(base) == null) return null
        val origin = AUTHORITY.find(base)?.value ?: return null
        val url = (preset.modelsPath ?: "{base}/models").replace("{base}", base).replace("{origin}", origin)
        val headers = mapOf("Accept" to "application/json") + ProviderAuth.scopedHeaders(preset.modelsAuth, apiKey, url, base)
        return ModelsRequest(url, headers)
    }

    /** Up to [limit] models whose id or name contains [query] (the picker's search), in list order. */
    fun filter(models: List<RemoteModel>, query: String, limit: Int = 40): List<RemoteModel> {
        val q = query.trim().lowercase()
        return models.asSequence()
            .filter { q.isEmpty() || q in it.id.lowercase() || it.name?.lowercase()?.contains(q) == true }
            .take(limit)
            .toList()
    }

    /** A models-endpoint response as a [ModelListResult]. */
    fun result(preset: ProviderPreset, status: Int, body: String): ModelListResult {
        if (status !in 200..299) return OpenAiCodec.httpError(status, body).let { ModelListResult.Failure(it.kind, it.message) }
        val models = parse(preset.modelsApi, body, preset.suggestedModels)
            ?: return ModelListResult.Failure(ErrorKind.PROTOCOL, "Unexpected model list from ${preset.displayName}")
        return ModelListResult.Success(models)
    }

    /** The documented list of a provider without a models endpoint. */
    fun documented(preset: ProviderPreset): ModelListResult.Success = ModelListResult.Success(preset.suggestedModels.map { RemoteModel(it) })

    /**
     * The chat models in [body], sorted with [sort]. Returns null when the body is not a model
     * list of the expected [api] shape.
     */
    fun parse(api: ModelsApi, body: String, flagship: List<String> = emptyList()): List<RemoteModel>? {
        val entries = entries(api, body) ?: return null
        return sort(entries.filter { it.chat }.map { it.model }.distinctBy { it.id }, flagship)
    }

    /** Every entry of [body] with its chat verdict (for tests and diagnostics). */
    internal fun entries(api: ModelsApi, body: String): List<Entry>? {
        val root = try {
            WireJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val list: List<JsonElement> = when (api) {
            ModelsApi.GEMINI, ModelsApi.COHERE, ModelsApi.OLLAMA -> (root as? JsonObject)?.get("models") as? JsonArray
            ModelsApi.DASHSCOPE -> ((root as? JsonObject)?.obj("output"))?.get("models") as? JsonArray
            ModelsApi.NONE -> null
            else -> root as? JsonArray ?: (root as? JsonObject)?.get("data") as? JsonArray
        } ?: return null
        return list.mapNotNull { (it as? JsonObject)?.let { o -> entry(api, o) } }
    }

    internal class Entry(val model: RemoteModel, val chat: Boolean)

    private fun entry(api: ModelsApi, o: JsonObject): Entry? {
        val id = when (api) {
            ModelsApi.GEMINI -> o.str("name")?.removePrefix("models/")
            ModelsApi.COHERE, ModelsApi.OLLAMA -> o.str("name") ?: o.str("model")
            ModelsApi.DASHSCOPE -> o.str("model")
            else -> o.str("id") ?: o.str("model") ?: o.str("key")
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val model = RemoteModel(
            id = id,
            name = displayName(api, o, id),
            contextWindow = contextWindow(o)?.takeIf { it > 0 },
            created = created(o)?.takeIf { it > 0 },
            inputPerMTok = if (api == ModelsApi.OPENROUTER) perMillion(o.obj("pricing")?.get("prompt")) else null,
            outputPerMTok = if (api == ModelsApi.OPENROUTER) perMillion(o.obj("pricing")?.get("completion")) else null,
            detail = if (api == ModelsApi.OLLAMA) {
                listOfNotNull(o.obj("details")?.str("parameter_size"), o.obj("details")?.str("quantization_level"))
                    .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null }
            } else {
                null
            },
        )
        return Entry(model, isChat(api, o, id))
    }

    private fun displayName(api: ModelsApi, o: JsonObject, id: String): String? = when (api) {
        ModelsApi.GEMINI -> o.str("displayName")
        ModelsApi.DASHSCOPE -> o.str("name")
        ModelsApi.COHERE, ModelsApi.OLLAMA -> null
        else -> o.str("display_name") ?: o.str("name")
    }?.takeIf { it.isNotBlank() && it != id }

    /** Context window from whichever field the provider uses. */
    private fun contextWindow(o: JsonObject): Int? {
        val direct = listOf(
            "context_length", "context_window", "max_model_len", "max_context_length", "max_input_tokens",
            "inputTokenLimit", "context_size", "contextLength",
        ).firstNotNullOfOrNull { o.long(it) }
        val nested = direct
            ?: o.obj("top_provider")?.long("context_length")
            ?: o.obj("metadata")?.long("context_length")
            ?: o.obj("model_spec")?.long("availableContextTokens")
            ?: o.obj("model_info")?.long("context_window")
            ?: o.obj("limits")?.long("max_context_length")
            ?: o.obj("meta")?.long("n_ctx_train")
            // Hugging Face router: the largest window among live providers.
            ?: (o["providers"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("status") != "error" }?.long("context_length") }?.maxOrNull()
        return nested?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    @OptIn(ExperimentalTime::class)
    private fun created(o: JsonObject): Long? {
        o.long("created")?.let { return if (it > 100_000_000_000L) it / 1000 else it }
        val iso = o.str("created_at") ?: o.str("createTime") ?: o.str("modified_at") ?: o.str("published_time") ?: return null
        val normalized = if ('T' !in iso && iso.length >= 19) iso.replaceFirst(' ', 'T') + "Z" else iso
        return runCatching { Instant.parse(normalized).epochSeconds }.getOrNull()
    }

    private fun perMillion(value: JsonElement?): Double? {
        val p = (value as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
        return if (p < 0) null else p * 1_000_000
    }

    /**
     * Whether an entry is a chat model. Metadata wins where the provider has it; ids are checked
     * in every case, since several providers list embedding, speech and image models side by side
     * without saying so ([nonChatId]).
     */
    private fun isChat(api: ModelsApi, o: JsonObject, id: String): Boolean {
        if (nonChatId(id)) return false
        if (o.bool("active") == false || o.bool("is_deprecated") == true) return false
        o.str("status")?.let { if (it.lowercase() in INACTIVE_STATUS) return false }
        o.str("type")?.lowercase()?.let { type ->
            if (type in NON_CHAT_TYPES) return false
        }
        o.obj("capabilities")?.bool("completion_chat")?.let { if (!it) return false } // Mistral
        o.bool("supports_chat")?.let { if (!it) return false }
        val outputs = (o.obj("architecture")?.get("output_modalities") ?: o["output_modalities"]) as? JsonArray
        if (outputs != null && outputs.none { (it as? JsonPrimitive)?.contentOrNull == "text" }) return false
        o.obj("metadata")?.let { meta -> // DeepInfra tags
            val tags = (meta["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.orEmpty()
            if (tags.isNotEmpty() && tags.none { it in CHAT_TAGS } && tags.any { it in NON_CHAT_TAGS }) return false
        }
        return when (api) {
            ModelsApi.GEMINI -> (o["supportedGenerationMethods"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "generateContent" } ?: true
            ModelsApi.COHERE -> (o["endpoints"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "chat" } ?: true
            ModelsApi.DASHSCOPE -> (o.obj("inference_metadata")?.get("response_modality") as? JsonArray)
                ?.any { (it as? JsonPrimitive)?.contentOrNull.equals("text", ignoreCase = true) } ?: true
            else -> true
        }
    }

    /**
     * Ids that name a non-chat model: embeddings, rerankers, speech, transcription, moderation,
     * image/video generation, OCR, safety classifiers, legacy completions and realtime/audio-only
     * models. Matched on whole tokens of the id (split on anything but letters and digits), plus
     * the substrings `embed`, `rerank`, `dall-e`, `stable-diffusion` and `computer-use`.
     */
    fun nonChatId(id: String): Boolean {
        val lower = id.lowercase()
        if (NON_CHAT_SUBSTRINGS.any { it in lower }) return true
        val tokens = lower.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        return tokens.any { it in NON_CHAT_TOKENS }
    }

    /**
     * Picker order: the preset's [flagship] ids first (in its order, when the provider lists
     * them), then the newest by release time, then the rest alphabetically.
     */
    fun sort(models: List<RemoteModel>, flagship: List<String> = emptyList()): List<RemoteModel> {
        val rank = flagship.withIndex().associate { (i, id) -> id.lowercase() to i }
        return models.sortedWith(
            compareBy<RemoteModel> { rank[it.id.lowercase()] ?: Int.MAX_VALUE }
                .thenByDescending { it.created ?: Long.MIN_VALUE }
                .thenBy { it.id.lowercase() },
        )
    }

    private val AUTHORITY = Regex("^https?://[^/?#]+", RegexOption.IGNORE_CASE)
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    private val NON_CHAT_SUBSTRINGS = listOf("embed", "rerank", "dall-e", "stable-diffusion", "computer-use")
    private val NON_CHAT_TOKENS = setOf(
        "tts", "whisper", "transcribe", "transcription", "speech", "audio", "realtime", "live", "orpheus",
        "moderation", "guard", "ocr", "image", "imagen", "imagine", "veo", "sora", "flux", "sdxl", "sd3", "sd35", "lyria",
        "aqa", "babbage", "davinci", "bge", "e5", "gte", "minilm",
    )
    private val NON_CHAT_TYPES = setOf(
        "embedding", "embeddings", "image", "images", "audio", "rerank", "moderation", "transcribe", "transcription",
        "tts", "stt", "speech", "video", "upscale", "inpaint", "classify", "classifier",
    )
    private val CHAT_TAGS = setOf("chat", "vlm", "vision", "reasoning", "llm", "text-generation")
    private val NON_CHAT_TAGS = setOf("embed", "embeddings", "tts", "stt", "image-gen", "video-gen", "rerank")
    private val INACTIVE_STATUS = setOf("deleted", "error", "validating", "deprecated", "inactive")
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
