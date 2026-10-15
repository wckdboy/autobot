package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.fetchAll
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Hugging Face and Civitai: request building and response parsing for both apps. A hub request
 * goes to the hub's own host only (so its token never reaches anyone else) and is sent through the
 * host's policy-bound client (kill switch, route, audit), which never follows redirects.
 */

class HubException(message: String, val code: Code = Code.FAILED) : Exception(message) {
    enum class Code { BLOCKED, UNAUTHORIZED, GATED, NOT_FOUND, RATE_LIMITED, FAILED }
}

/** A hub's answer: status, headers (any case) and the body as text. */
class HubResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private val HubJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

/** `gated` is `false`, `"auto"` or `"manual"`. */
private fun JsonElement?.gated(): Boolean = bool() == true || (str() != null && str() != "false")

/** The JSON body of a successful response, or the hub's failure as a [HubException]. */
private fun HubResponse.json(): JsonElement {
    when {
        status in 200..299 -> return try {
            HubJson.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw HubException("Unexpected response")
        }
        header("X-Error-Code") == "GatedRepo" ->
            throw HubException("This repository is gated: accept its terms on huggingface.co, then sign in", HubException.Code.GATED)
        status == 401 || status == 403 -> throw HubException("Not authorized (HTTP $status)", HubException.Code.UNAUTHORIZED)
        status == 404 -> throw HubException("Not found", HubException.Code.NOT_FOUND)
        status == 429 -> throw HubException("Rate limited — try again in a few minutes", HubException.Code.RATE_LIMITED)
        else -> throw HubException("HTTP $status")
    }
}

private fun HubResponse.jsonObject(): JsonObject = json() as? JsonObject ?: throw HubException("Unexpected response")

private const val MAX_HUB_BYTES = 32L * 1024 * 1024

private fun hubRequest(url: String, tag: String, token: String?) = HttpRequestSpec(
    method = "GET",
    url = url,
    headers = buildMap {
        put("Accept", "application/json")
        if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
    },
    body = null,
    tag = tag,
    maxBytes = MAX_HUB_BYTES,
)

/** Sends a hub request through [HttpTransport]; transport failures become [HubException]s. */
private suspend fun HttpTransport.hub(request: HttpRequestSpec): HubResponse {
    val r = fetchAll(this, request)
    if (r.failure != null) {
        throw HubException(r.failure, if (r.blocked) HubException.Code.BLOCKED else HubException.Code.FAILED)
    }
    return HubResponse(r.status, r.headers, r.body)
}

// ---------------------------------------------------------------------------------------------
// Hugging Face

data class HfModelSummary(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val license: String?,
    val gated: Boolean,
    val tags: List<String>,
)

data class HfFile(val path: String, val sizeBytes: Long, val sha256: String?)

data class HfRepo(val id: String, val commit: String?, val gated: Boolean, val license: String?, val files: List<HfFile>)

@Serializable
data class HfUser(val name: String, val fullname: String? = null)

/** Hugging Face Hub REST API (search, file listing, whoami): requests, parsing and install plans. */
object HuggingFaceApi {
    const val BASE = "https://huggingface.co"
    const val TAG = "models:huggingface"

    /**
     * Searches models. [gguf] limits to GGUF repos (chat/code), [coreML] to Core ML repos;
     * otherwise text-to-image. [author] lists one publisher's repos instead of filtering by type
     * (e.g. NPU packages).
     */
    fun searchRequest(query: String, gguf: Boolean, limit: Int, author: String?, coreML: Boolean, token: String?): HttpRequestSpec {
        val params = buildList {
            if (query.isNotBlank()) add("search" to query.trim())
            if (author != null) add("author" to author)
            add("sort" to "downloads")
            add("direction" to "-1")
            add("limit" to limit.toString())
            if (author == null) add("filter" to if (coreML) "core-ml" else if (gguf) "gguf" else "text-to-image")
            listOf("downloads", "likes", "gated", "tags").forEach { add("expand[]" to it) }
        }
        return hubRequest(withQuery("$BASE/api/models", params), TAG, token)
    }

    /** Files with sizes and LFS SHA-256s, plus the commit to pin downloads to. */
    fun repoRequest(id: String, token: String?): HttpRequestSpec =
        hubRequest(withQuery("$BASE/api/models/${encodePath(id)}", listOf("blobs" to "true")), TAG, token)

    fun whoamiRequest(token: String): HttpRequestSpec = hubRequest("$BASE/api/whoami-v2", TAG, token)

    @Throws(HubException::class)
    fun parseSearch(response: HubResponse): List<HfModelSummary> = response.json().array().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val tags = o["tags"].array().mapNotNull { it.str() }
        HfModelSummary(
            id = o["id"].str() ?: return@mapNotNull null,
            downloads = o["downloads"].long() ?: 0,
            likes = o["likes"].long() ?: 0,
            license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
            gated = o["gated"].gated(),
            tags = tags,
        )
    }

    @Throws(HubException::class)
    fun parseRepo(response: HubResponse, id: String): HfRepo {
        val o = response.jsonObject()
        val siblings = o["siblings"].array().mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            val lfs = so["lfs"] as? JsonObject
            HfFile(
                path = so["rfilename"].str() ?: return@mapNotNull null,
                sizeBytes = so["size"].long() ?: lfs?.get("size").long() ?: 0,
                sha256 = lfs?.get("sha256").str()?.lowercase(),
            )
        }
        return HfRepo(
            id = o["id"].str() ?: id,
            commit = o["sha"].str(),
            gated = o["gated"].gated(),
            license = (o["cardData"] as? JsonObject)?.get("license").str(),
            files = siblings,
        )
    }

    /** Validates a token; returns the account. */
    @Throws(HubException::class)
    fun parseWhoami(response: HubResponse): HfUser {
        val o = response.jsonObject()
        return HfUser(o["name"].str() ?: throw HubException("Unexpected whoami response"), o["fullname"].str())
    }

    fun resolveUrl(repo: String, revision: String?, path: String): String =
        "$BASE/${encodePath(repo)}/resolve/${encodePathSegment(revision ?: "main")}/${encodePath(path)}"

    /** Best vision projector in a repo (F16 preferred, then Q8_0, then any). */
    private fun visionProjector(repo: HfRepo): HfFile? {
        val projectors = repo.files.filter { it.path.substringAfterLast('/').lowercase().startsWith("mmproj") && it.path.endsWith(".gguf", ignoreCase = true) }
        return projectors.firstOrNull { "f16" in it.path.lowercase() } ?: projectors.firstOrNull { "q8_0" in it.path.lowercase() } ?: projectors.firstOrNull()
    }

    /** Builds an install plan for one file of [repo] (with the vision projector for chat models). */
    fun plan(repo: HfRepo, file: HfFile, kind: ModelKind, platform: AppPlatform = HostPlatform): ModelPlan {
        val owner = repo.id.substringBefore('/')
        val format = formatOf(file.path, owner)
        return ModelPlan(
            id = "hf:${repo.id}:${file.path}",
            kind = kind,
            source = ModelSource.HUGGING_FACE,
            title = file.path.substringAfterLast('/').substringBeforeLast('.'),
            subtitle = repo.id,
            format = format,
            engine = engineFor(kind, format, platform),
            license = repo.license,
            manifest = ModelManifest(
                files = listOfNotNull(
                    ModelFile(FileRole.MODEL, file.path.substringAfterLast('/'), resolveUrl(repo.id, repo.commit, file.path), file.sizeBytes, file.sha256, AuthHost.HUGGING_FACE),
                    // A vision projector in the same repo enables image input for chat models.
                    visionProjector(repo).takeIf { format == ModelFormat.GGUF && (kind == ModelKind.CHAT || kind == ModelKind.CODE) }?.let { p ->
                        ModelFile(FileRole.MMPROJ, p.path.substringAfterLast('/'), resolveUrl(repo.id, repo.commit, p.path), p.sizeBytes, p.sha256, AuthHost.HUGGING_FACE)
                    },
                ),
                pageUrl = "$BASE/${repo.id}",
                gated = repo.gated,
                notes = if (format == ModelFormat.LOCAL_DREAM) "Local Dream package: run it with Local Dream's host mode (Imagine → backends)." else null,
            ),
        )
    }
}

/** [HuggingFaceApi] over a host [HttpTransport]. Tokens are passed per call and sent to huggingface.co only. */
class HuggingFaceHub(private val transport: HttpTransport) {
    @Throws(HubException::class, CancellationException::class)
    suspend fun search(query: String, gguf: Boolean, limit: Int = 30, author: String? = null, coreML: Boolean = false, token: String? = null): List<HfModelSummary> =
        HuggingFaceApi.parseSearch(transport.hub(HuggingFaceApi.searchRequest(query, gguf, limit, author, coreML, token)))

    @Throws(HubException::class, CancellationException::class)
    suspend fun repo(id: String, token: String? = null): HfRepo = HuggingFaceApi.parseRepo(transport.hub(HuggingFaceApi.repoRequest(id, token)), id)

    @Throws(HubException::class, CancellationException::class)
    suspend fun whoami(token: String): HfUser = HuggingFaceApi.parseWhoami(transport.hub(HuggingFaceApi.whoamiRequest(token)))
}

// ---------------------------------------------------------------------------------------------
// Civitai

/** Civitai web/API host. `.red` is the domain that includes mature content (split 2026-04-15). */
enum class CivitaiHost(val host: String) { COM("civitai.com"), RED("civitai.red") }

data class CivitaiFile(val name: String, val sizeBytes: Long, val sha256: String?, val format: String?, val primary: Boolean, val downloadUrl: String)

data class CivitaiVersion(
    val id: Long,
    val name: String,
    val baseModel: String?,
    val trainedWords: List<String>,
    val files: List<CivitaiFile>,
    val previewUrl: String?,
    val previewMature: Boolean,
    val earlyAccess: Boolean,
)

data class CivitaiModel(
    val id: Long,
    val name: String,
    val type: String,
    val nsfw: Boolean,
    val creator: String?,
    val downloads: Long,
    val allowCommercialUse: String?,
    val versions: List<CivitaiVersion>,
)

data class CivitaiPage(val items: List<CivitaiModel>, val nextCursor: String?)

/** Civitai public API v1. The same API is served on civitai.com and civitai.red. */
object CivitaiApi {
    const val TAG = "models:civitai"

    fun searchRequest(
        host: CivitaiHost,
        query: String,
        types: List<String>,
        baseModels: List<String>,
        cursor: String?,
        limit: Int,
        showMature: Boolean,
        key: String?,
    ): HttpRequestSpec {
        val params = buildList {
            if (query.isNotBlank()) add("query" to query.trim())
            types.forEach { add("types" to it) }
            baseModels.forEach { add("baseModels" to it) }
            add("sort" to "Most Downloaded")
            add("limit" to limit.toString())
            add("nsfw" to showMature.toString())
            add("primaryFileOnly" to "true")
            cursor?.let { add("cursor" to it) }
        }
        return hubRequest(withQuery("https://${host.host}/api/v1/models", params), TAG, key)
    }

    fun meRequest(host: CivitaiHost, key: String): HttpRequestSpec = hubRequest("https://${host.host}/api/v1/me", TAG, key)

    /** One page of models; minors, real people and (unless [showMature]) mature models are dropped. */
    @Throws(HubException::class)
    fun parseSearch(response: HubResponse, showMature: Boolean): CivitaiPage {
        val root = response.jsonObject()
        val items = root["items"].array().mapNotNull { parseCivitaiModel(it as? JsonObject, showMature) }
        return CivitaiPage(items, (root["metadata"] as? JsonObject)?.get("nextCursor").str())
    }

    /** Validates an API key; returns the username. */
    @Throws(HubException::class)
    fun parseMe(response: HubResponse): String = response.jsonObject()["username"].str() ?: throw HubException("Unexpected /me response")
}

/** [CivitaiApi] over a host [HttpTransport]. The key is passed per call and sent to the Civitai host only. */
class CivitaiHub(private val transport: HttpTransport) {
    @Throws(HubException::class, CancellationException::class)
    suspend fun search(
        host: CivitaiHost,
        query: String,
        types: List<String>,
        baseModels: List<String> = emptyList(),
        cursor: String? = null,
        limit: Int = 20,
        showMature: Boolean = false,
        key: String? = null,
    ): CivitaiPage = CivitaiApi.parseSearch(transport.hub(CivitaiApi.searchRequest(host, query, types, baseModels, cursor, limit, showMature, key)), showMature)

    @Throws(HubException::class, CancellationException::class)
    suspend fun me(host: CivitaiHost, key: String): String = CivitaiApi.parseMe(transport.hub(CivitaiApi.meRequest(host, key)))
}

/** Parses one `items[]` entry; drops minors, real people and (unless allowed) mature models. */
internal fun parseCivitaiModel(o: JsonObject?, showMature: Boolean): CivitaiModel? {
    o ?: return null
    // Never surface content flagged as depicting minors or real people.
    if (o["minor"].bool() == true || o["poi"].bool() == true) return null
    val nsfw = o["nsfw"].bool() == true
    if (nsfw && !showMature) return null
    val versions = o["modelVersions"].array().mapNotNull { v ->
        val vo = v as? JsonObject ?: return@mapNotNull null
        val files = vo["files"].array().mapNotNull { f ->
            val fo = f as? JsonObject ?: return@mapNotNull null
            CivitaiFile(
                name = fo["name"].str() ?: return@mapNotNull null,
                sizeBytes = ((fo["sizeKB"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).let { (it * 1024).toLong() },
                sha256 = (fo["hashes"] as? JsonObject)?.get("SHA256").str()?.lowercase(),
                format = (fo["metadata"] as? JsonObject)?.get("format").str(),
                primary = fo["primary"].bool() == true,
                downloadUrl = fo["downloadUrl"].str() ?: return@mapNotNull null,
            )
        }
        val image = vo["images"].array().firstNotNullOfOrNull { im ->
            val io = im as? JsonObject ?: return@firstNotNullOfOrNull null
            if (io["minor"].bool() == true || io["poi"].bool() == true) return@firstNotNullOfOrNull null
            val level = io["nsfwLevel"].long() ?: 1
            if (level and 32L != 0L) return@firstNotNullOfOrNull null
            io["url"].str()?.let { it to (level and 28L != 0L) }
        }
        CivitaiVersion(
            id = vo["id"].long() ?: return@mapNotNull null,
            name = vo["name"].str().orEmpty(),
            baseModel = vo["baseModel"].str(),
            trainedWords = vo["trainedWords"].array().mapNotNull { it.str() },
            files = files,
            previewUrl = image?.first,
            previewMature = image?.second ?: false,
            earlyAccess = vo["availability"].str() == "EarlyAccess",
        )
    }
    return CivitaiModel(
        id = o["id"].long() ?: return null,
        name = o["name"].str().orEmpty(),
        type = o["type"].str().orEmpty(),
        nsfw = nsfw,
        creator = (o["creator"] as? JsonObject)?.get("username").str(),
        downloads = ((o["stats"] as? JsonObject)?.get("downloadCount")).long() ?: 0,
        allowCommercialUse = o["allowCommercialUse"]?.let { if (it is JsonArray) it.joinToString(",") { e -> e.str().orEmpty() } else it.str() },
        versions = versions,
    )
}

/** Maps a Civitai version to an install plan (primary file only). */
fun civitaiPlan(model: CivitaiModel, version: CivitaiVersion, platform: AppPlatform = HostPlatform): ModelPlan? {
    val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull() ?: return null
    val kind = when (model.type) {
        "Checkpoint" -> ModelKind.IMAGE
        "LORA", "LoCon", "DoRA" -> ModelKind.LORA
        "TextualInversion" -> ModelKind.EMBEDDING
        "VAE" -> ModelKind.VAE
        "Upscaler" -> ModelKind.UPSCALER
        "LLM" -> ModelKind.CHAT
        else -> ModelKind.OTHER
    }
    val format = formatOf(file.name)
    return ModelPlan(
        id = "civitai:${version.id}",
        kind = kind,
        source = ModelSource.CIVITAI,
        title = model.name,
        subtitle = listOfNotNull(version.name, version.baseModel).joinToString(" · "),
        format = format,
        engine = engineFor(kind, format, platform),
        baseModel = version.baseModel,
        license = model.allowCommercialUse?.let { "civitai (commercial: $it)" },
        nsfw = model.nsfw,
        manifest = ModelManifest(
            files = listOf(ModelFile(FileRole.MODEL, file.name, file.downloadUrl, file.sizeBytes, file.sha256, AuthHost.CIVITAI)),
            pageUrl = "https://civitai.com/models/${model.id}",
            trainedWords = version.trainedWords,
        ),
    )
}
