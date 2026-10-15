package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.OkHttpTransport
import dev.wckdboy.autobot.core.security.Secret
import javax.inject.Inject
import javax.inject.Singleton

private fun Secret?.token(): String? = this?.takeUnless { it.isBlank }?.reveal()

/**
 * Hugging Face Hub (search, file listing, whoami) through the shared [HuggingFaceHub] over the
 * policy-bound OkHttp client, plus the Android-only NPU/GPU package plans (Local Dream layout).
 */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val clients: HttpClientFactory,
    private val accounts: ModelAccounts,
) {
    private val hub = HuggingFaceHub(OkHttpTransport(clients))

    /**
     * Searches models. [gguf] limits to GGUF repos (chat/code); otherwise text-to-image. [author]
     * lists one publisher's repos instead of filtering by type (e.g. NPU packages).
     */
    suspend fun search(query: String, gguf: Boolean, limit: Int = 30, author: String? = null): List<HfModelSummary> =
        hub.search(query, gguf, limit, author, coreML = false, token = accounts.huggingFaceToken().token())

    /** Files with sizes and LFS SHA-256s, plus the commit to pin downloads to. */
    suspend fun repo(id: String): HfRepo = hub.repo(id, accounts.huggingFaceToken().token())

    /** Validates a token; returns the account name. */
    suspend fun whoami(token: Secret): HfUser = hub.whoami(token.reveal())

    fun resolveUrl(repo: String, revision: String?, path: String): String = HuggingFaceApi.resolveUrl(repo, revision, path)

    /**
     * Install plan for a Local Dream–layout NPU/GPU package: a zip is listed remotely and only
     * the entries Autobot needs are planned (fetched later by byte range).
     */
    suspend fun localDreamPlan(repo: HfRepo, file: HfFile): ModelPlan {
        val url = resolveUrl(repo.id, repo.commit, file.path)
        val token = accounts.huggingFaceToken()
        val fetcher = FileFetcher(clients)
        val zip = RemoteZip { from, to -> fetcher.readRange(url, from, to, token, AuthHost.HUGGING_FACE) }
        return try {
            val entries = zip.list()
            val wanted = entries.filter { e ->
                !e.isDirectory && (e.compressedSize == 0L || e.baseName.endsWith(".json") || e.baseName.endsWith(".bin") ||
                    e.baseName.endsWith(".mnn") || e.baseName.endsWith(".weight")) && !e.baseName.endsWith(".patch")
            }
            val resolved = wanted.map { e -> e to if (e.compressedSize == 0L) ZipEntryRef(e.name) else zip.resolve(e) }
            LocalDream.planFromZip(repo.id, file.path, url, resolved)
        } catch (e: DownloadException) {
            throw HubException(e.message ?: "Cannot read the package")
        }
    }

    /**
     * Install plan for a file picked in the Hub browser: NPU/GPU packages (zips, per-chip files,
     * upscalers) get a package plan, everything else a single-file plan.
     */
    suspend fun planForFile(repo: HfRepo, file: HfFile, kind: ModelKind): ModelPlan {
        val name = file.path.lowercase()
        val chip = Regex("_(8gen[1-4]|min)\\.bin$").find(name)?.groupValues?.get(1)
        return when {
            name.endsWith(".zip") && (repo.id.startsWith("xororz/", ignoreCase = true) || "_qnn" in name) -> localDreamPlan(repo, file)
            chip != null && "upscaler_" in name -> {
                val folder = file.path.substringBeforeLast('/', "")
                LocalDream.upscalerPlans(repo, LocalDream.archOfChip(chip)) { resolveUrl(repo.id, repo.commit, it) }
                    .firstOrNull { it.id.startsWith("hf:${repo.id}:$folder:") } ?: plan(repo, file, kind)
            }
            chip != null -> LocalDream.planFromRepo(repo, LocalDream.archOfChip(chip)) { resolveUrl(repo.id, repo.commit, it) } ?: plan(repo, file, kind)
            else -> plan(repo, file, kind)
        }
    }

    /** Resolves a curated NPU package for a phone with HTP [arch]. */
    suspend fun npuOfferPlan(offer: NpuOffer, arch: String?): ModelPlan {
        val path = offer.fileFor(arch) ?: throw HubException("No build of ${offer.title} for this phone's NPU", HubException.Code.NOT_FOUND)
        val repo = repo(offer.repo)
        val file = repo.files.firstOrNull { it.path == path } ?: throw HubException("${offer.title} is no longer available", HubException.Code.NOT_FOUND)
        val plan = if (offer.kind == ModelKind.UPSCALER) planForFile(repo, file, ModelKind.UPSCALER) else localDreamPlan(repo, file)
        return plan.copy(id = "${offer.id}:$path", title = offer.title)
    }

    /** NPU upscalers available for a phone with HTP [arch]. */
    suspend fun upscalerPlans(arch: String?): List<ModelPlan> {
        val repo = repo(NpuCatalog.UPSCALER_REPO)
        return LocalDream.upscalerPlans(repo, arch) { resolveUrl(repo.id, repo.commit, it) }
    }

    /** Builds an install plan for one file of [repo] (with known sibling components for bundles). */
    fun plan(repo: HfRepo, file: HfFile, kind: ModelKind): ModelPlan = HuggingFaceApi.plan(repo, file, kind)
}

/** Civitai public API v1 through the shared [CivitaiHub]. The same API is served on civitai.com and civitai.red. */
@Singleton
class CivitaiClient @Inject constructor(
    clients: HttpClientFactory,
    private val accounts: ModelAccounts,
) {
    private val hub = CivitaiHub(OkHttpTransport(clients))

    suspend fun search(
        query: String,
        types: List<String>,
        baseModels: List<String> = emptyList(),
        cursor: String? = null,
        limit: Int = 20,
    ): CivitaiPage {
        val account = accounts.current()
        return hub.search(account.civitaiHost, query, types, baseModels, cursor, limit, account.showMature, accounts.civitaiKey().token())
    }

    /** Validates an API key; returns the username. */
    suspend fun me(key: Secret): String = hub.me(accounts.current().civitaiHost, key.reveal())

    /** Maps a model version to an install plan (primary file only). */
    fun plan(model: CivitaiModel, version: CivitaiVersion): ModelPlan? = civitaiPlan(model, version)
}
