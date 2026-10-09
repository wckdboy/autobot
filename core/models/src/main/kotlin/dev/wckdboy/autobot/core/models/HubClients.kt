package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.security.Secret
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class HubException(message: String, val code: Code = Code.FAILED) : Exception(message) {
    enum class Code { BLOCKED, UNAUTHORIZED, GATED, NOT_FOUND, RATE_LIMITED, FAILED }
}

private val HubJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** Shared GET-JSON helper: policy-bound client, typed failures, no cookies, no redirects. */
internal suspend fun HttpClientFactory.getJson(tag: String, url: HttpUrl, bearer: Secret? = null): JsonElement =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (bearer != null && !bearer.isBlank) header("Authorization", "Bearer ${bearer.reveal()}")
        }.build()
        try {
            create(tag).newCall(request).execute().use { response ->
                val body = response.body.string()
                when {
                    response.isSuccessful -> HubJson.parseToJsonElement(body)
                    response.header("X-Error-Code") == "GatedRepo" ->
                        throw HubException("This repository is gated: accept its terms on huggingface.co, then sign in", HubException.Code.GATED)
                    response.code == 401 || response.code == 403 -> throw HubException("Not authorized (HTTP ${response.code})", HubException.Code.UNAUTHORIZED)
                    response.code == 404 -> throw HubException("Not found", HubException.Code.NOT_FOUND)
                    response.code == 429 -> throw HubException("Rate limited — try again in a few minutes", HubException.Code.RATE_LIMITED)
                    else -> throw HubException("HTTP ${response.code}")
                }
            }
        } catch (e: NetworkBlockedException) {
            throw HubException(e.message ?: "Blocked by network policy", HubException.Code.BLOCKED)
        } catch (e: IOException) {
            throw HubException(e.message ?: "Network error")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw HubException("Unexpected response")
        }
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

/** Hugging Face Hub REST API (search, file listing, whoami). */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val clients: HttpClientFactory,
    private val accounts: ModelAccounts,
) {
    private val base = "https://huggingface.co".toHttpUrl()

    /** Searches models. [gguf] limits to GGUF repos (chat/code); otherwise text-to-image. */
    suspend fun search(query: String, gguf: Boolean, limit: Int = 30): List<HfModelSummary> {
        val url = base.newBuilder().addPathSegments("api/models")
            .addQueryParameter("search", query.trim())
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("limit", limit.toString())
            .apply { if (gguf) addQueryParameter("filter", "gguf") else addQueryParameter("filter", "text-to-image") }
            .addQueryParameter("expand[]", "downloads")
            .addQueryParameter("expand[]", "likes")
            .addQueryParameter("expand[]", "gated")
            .addQueryParameter("expand[]", "tags")
            .build()
        val array = clients.getJson("models:huggingface", url, accounts.huggingFaceToken()) as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val tags = (o["tags"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
            HfModelSummary(
                id = o["id"].str() ?: return@mapNotNull null,
                downloads = o["downloads"].long() ?: 0,
                likes = o["likes"].long() ?: 0,
                license = tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
                gated = o["gated"].let { it.bool() == true || (it.str() != null && it.str() != "false") },
                tags = tags,
            )
        }
    }

    /** Files with sizes and LFS SHA-256s, plus the commit to pin downloads to. */
    suspend fun repo(id: String): HfRepo {
        val url = base.newBuilder().addPathSegments("api/models").addPathSegments(id).addQueryParameter("blobs", "true").build()
        val o = clients.getJson("models:huggingface", url, accounts.huggingFaceToken()).jsonObject
        val siblings = (o["siblings"] as? JsonArray).orEmpty().mapNotNull { s ->
            val so = s as? JsonObject ?: return@mapNotNull null
            val lfs = so["lfs"] as? JsonObject
            HfFile(
                path = so["rfilename"].str() ?: return@mapNotNull null,
                sizeBytes = so["size"].long() ?: lfs?.get("size").long() ?: 0,
                sha256 = lfs?.get("sha256").str()?.lowercase(),
            )
        }
        val card = o["cardData"] as? JsonObject
        return HfRepo(
            id = o["id"].str() ?: id,
            commit = o["sha"].str(),
            gated = o["gated"].let { it.bool() == true || (it.str() != null && it.str() != "false") },
            license = card?.get("license").str(),
            files = siblings,
        )
    }

    /** Validates a token; returns the account name. */
    suspend fun whoami(token: Secret): HfUser {
        val o = clients.getJson("models:huggingface", base.newBuilder().addPathSegments("api/whoami-v2").build(), token).jsonObject
        return HfUser(o["name"].str() ?: throw HubException("Unexpected whoami response"), o["fullname"].str())
    }

    fun resolveUrl(repo: String, revision: String?, path: String): String =
        base.newBuilder().addPathSegments(repo).addPathSegment("resolve").addPathSegment(revision ?: "main")
            .addPathSegments(path).build().toString()

    /** Builds an install plan for one file of [repo] (with known sibling components for bundles). */
    fun plan(repo: HfRepo, file: HfFile, kind: ModelKind): ModelPlan {
        val owner = repo.id.substringBefore('/')
        val format = formatOf(file.path, owner)
        return ModelPlan(
            id = "hf:${repo.id}:${file.path}",
            kind = kind,
            source = ModelSource.HUGGING_FACE,
            title = file.path.substringAfterLast('/').substringBeforeLast('.'),
            subtitle = repo.id,
            format = format,
            engine = engineFor(kind, format),
            license = repo.license,
            manifest = ModelManifest(
                files = listOf(
                    ModelFile(FileRole.MODEL, file.path.substringAfterLast('/'), resolveUrl(repo.id, repo.commit, file.path), file.sizeBytes, file.sha256, AuthHost.HUGGING_FACE),
                ),
                pageUrl = "https://huggingface.co/${repo.id}",
                gated = repo.gated,
                notes = if (format == ModelFormat.LOCAL_DREAM) "Local Dream package: run it with Local Dream's host mode (Imagine → backends)." else null,
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Civitai

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
@Singleton
class CivitaiClient @Inject constructor(
    private val clients: HttpClientFactory,
    private val accounts: ModelAccounts,
) {
    private suspend fun base(): HttpUrl = "https://${accounts.current().civitaiHost.host}".toHttpUrl()

    suspend fun search(
        query: String,
        types: List<String>,
        baseModels: List<String> = emptyList(),
        cursor: String? = null,
        limit: Int = 20,
    ): CivitaiPage {
        val account = accounts.current()
        val url = base().newBuilder().addPathSegments("api/v1/models").apply {
            if (query.isNotBlank()) addQueryParameter("query", query.trim())
            types.forEach { addQueryParameter("types", it) }
            baseModels.forEach { addQueryParameter("baseModels", it) }
            addQueryParameter("sort", "Most Downloaded")
            addQueryParameter("limit", limit.toString())
            addQueryParameter("nsfw", account.showMature.toString())
            addQueryParameter("primaryFileOnly", "true")
            cursor?.let { addQueryParameter("cursor", it) }
        }.build()
        val root = clients.getJson("models:civitai", url, accounts.civitaiKey()).jsonObject
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { parseCivitaiModel(it as? JsonObject, account.showMature) }
        return CivitaiPage(items, (root["metadata"] as? JsonObject)?.get("nextCursor").str())
    }

    /** Validates an API key; returns the username. */
    suspend fun me(key: Secret): String {
        val o = clients.getJson("models:civitai", base().newBuilder().addPathSegments("api/v1/me").build(), key).jsonObject
        return o["username"].str() ?: throw HubException("Unexpected /me response")
    }

    /** Maps a model version to an install plan (primary file only). */
    fun plan(model: CivitaiModel, version: CivitaiVersion): ModelPlan? = civitaiPlan(model, version)
}

/** Parses one `items[]` entry; drops minors, real people and (unless allowed) mature models. */
internal fun parseCivitaiModel(o: JsonObject?, showMature: Boolean): CivitaiModel? {
    o ?: return null
    // Never surface content flagged as depicting minors or real people.
    if (o["minor"].bool() == true || o["poi"].bool() == true) return null
    val nsfw = o["nsfw"].bool() == true
    if (nsfw && !showMature) return null
    val versions = (o["modelVersions"] as? JsonArray).orEmpty().mapNotNull { v ->
        val vo = v as? JsonObject ?: return@mapNotNull null
        val files = (vo["files"] as? JsonArray).orEmpty().mapNotNull { f ->
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
        val image = (vo["images"] as? JsonArray).orEmpty().firstNotNullOfOrNull { im ->
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
            trainedWords = (vo["trainedWords"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
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
internal fun civitaiPlan(model: CivitaiModel, version: CivitaiVersion): ModelPlan? {
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
        engine = engineFor(kind, format),
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

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
