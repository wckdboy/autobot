package dev.wckdboy.autobot.feature.models

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.models.AccountState
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.CivitaiClient
import dev.wckdboy.autobot.core.models.CivitaiModel
import dev.wckdboy.autobot.core.models.DeviceProfile
import dev.wckdboy.autobot.core.models.DeviceProfiler
import dev.wckdboy.autobot.core.models.DownloadEnvironmentSource
import dev.wckdboy.autobot.core.models.DownloadProgress
import dev.wckdboy.autobot.core.models.DownloadQueue
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.HfModelSummary
import dev.wckdboy.autobot.core.models.HfRepo
import dev.wckdboy.autobot.core.models.HuggingFaceClient
import dev.wckdboy.autobot.core.models.HubException
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelAccounts
import dev.wckdboy.autobot.core.models.ModelDownloader
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.ModelTask
import dev.wckdboy.autobot.core.models.NpuCatalog
import dev.wckdboy.autobot.core.models.NpuOffer
import dev.wckdboy.autobot.core.models.PreviewImages
import dev.wckdboy.autobot.core.models.formatBytes
import dev.wckdboy.autobot.core.models.launchOf
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelsTab(val label: String) { RECOMMENDED("for this phone"), HUGGING_FACE("hugging face"), CIVITAI("civitai") }

/** What the Hub search looks for. */
enum class HfScope(val label: String, val kind: ModelKind) {
    CHAT("chat", ModelKind.CHAT),
    CODE("code", ModelKind.CODE),
    IMAGE("image", ModelKind.IMAGE),

    /** Precompiled NPU/GPU image packages (Local Dream layout). */
    NPU("npu", ModelKind.IMAGE),
}

enum class CivitaiScope(val label: String, val types: List<String>) {
    CHECKPOINT("checkpoints", listOf("Checkpoint")),
    LORA("lora", listOf("LORA", "LoCon", "DoRA")),
}

/** Why the last hub search came back empty-handed; [blocked] means the kill switch refused it. */
@Immutable
data class SearchFailure(val message: String, val blocked: Boolean = false)

fun searchFailure(e: Throwable): SearchFailure = when {
    e is HubException && e.code == HubException.Code.BLOCKED -> SearchFailure(e.message ?: "Blocked by the kill switch", blocked = true)
    e is NetworkBlockedException -> SearchFailure(e.message ?: "Blocked by the kill switch", blocked = true)
    e is HubException -> SearchFailure(e.message ?: "The hub refused the request")
    else -> SearchFailure("Could not reach the hub: ${e.message ?: e.javaClass.simpleName}")
}

@Immutable
data class HfState(
    val scope: HfScope = HfScope.CHAT,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
    val searched: Boolean = false,
    val failure: SearchFailure? = null,
    val openRepo: HfRepo? = null,
    val openLoading: Boolean = false,
)

@Immutable
data class CivitaiState(
    val scope: CivitaiScope = CivitaiScope.CHECKPOINT,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<CivitaiModel> = emptyList(),
    val nextCursor: String? = null,
    val searched: Boolean = false,
    val failure: SearchFailure? = null,
)

@Immutable
data class ModelsUiState(
    val device: DeviceProfile? = null,
    val installed: List<InstalledModel> = emptyList(),
    val progress: DownloadProgress? = null,
    val downloads: DownloadsState = DownloadsState(),
    val tab: ModelsTab = ModelsTab.RECOMMENDED,
    val hf: HfState = HfState(),
    val civitai: CivitaiState = CivitaiState(),
    val accounts: AccountState = AccountState(),
    val message: String? = null,
    /** Offline network mode: the kill switch blocks the hubs and downloads. */
    val offline: Boolean = false,
) {
    /** Built once per state, not once per row that asks. */
    private val ids: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { installed.mapTo(HashSet()) { it.id } }

    fun installedIds(): Set<String> = ids
}

/** Where tapping an installed model goes. */
sealed interface ModelsEvent {
    data class OpenSession(val conversationId: String) : ModelsEvent
    data class OpenImagine(val modelId: String) : ModelsEvent
    data object OpenGallery : ModelsEvent

    /** A new chat in voice mode (a dictation model was tapped). */
    data object OpenVoice : ModelsEvent
}

interface ModelsActions {
    val recommended: List<ModelPlan>
    val npuImages: List<NpuOffer>
    val npuUpscalers: List<NpuOffer>
    fun selectTab(tab: ModelsTab)
    fun install(plan: ModelPlan)
    fun setWifiOnly(enabled: Boolean)
    fun notificationsDenied()
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String)
    fun consumeMessage()
    fun use(model: InstalledModel)
    fun setHfScope(scope: HfScope)
    fun setHfQuery(query: String)
    fun searchHf()
    fun openRepo(id: String)
    fun closeRepo()
    fun installHfFile(repo: HfRepo, path: String)
    fun installNpuOffer(offer: NpuOffer)
    fun setCivitaiScope(scope: CivitaiScope)
    fun setCivitaiQuery(query: String)
    fun searchCivitai(reset: Boolean = true)
    fun installCivitai(model: CivitaiModel)
    suspend fun preview(url: String): ImageBitmap?
}

@HiltViewModel
class ModelsViewModel @Inject constructor(
    private val library: ModelLibrary,
    private val downloader: ModelDownloader,
    private val profiler: DeviceProfiler,
    private val hub: HuggingFaceClient,
    private val civitai: CivitaiClient,
    private val previews: PreviewImages,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val host: AgentHost,
    private val settings: SettingsRepository,
    private val downloadEnvironment: DownloadEnvironmentSource,
    private val policy: NetworkPolicy,
    accounts: ModelAccounts,
) : ViewModel(), ModelsActions {

    private data class Local(
        val device: DeviceProfile? = null,
        val tab: ModelsTab = ModelsTab.RECOMMENDED,
        val hf: HfState = HfState(),
        val civitai: CivitaiState = CivitaiState(),
        val message: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var searchJob: Job? = null
    private val events = Channel<ModelsEvent>(Channel.BUFFERED)
    val navigation: Flow<ModelsEvent> = events.receiveAsFlow()

    val uiState: StateFlow<ModelsUiState> = combine(library.models, downloader.downloads, accounts.state, local, policy.mode) { installed, downloads, acc, l, mode ->
        ModelsUiState(l.device, installed, downloads.active, downloads, l.tab, l.hf, l.civitai, acc, l.message, offline = mode == NetworkMode.Offline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    init {
        refreshDevice()
    }

    fun refreshDevice() {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { runCatching { profiler.profile() }.getOrNull() } ?: return@launch
            local.update { it.copy(device = profile) }
        }
    }

    override val recommended: List<ModelPlan> get() = Catalog.all
    override val npuImages: List<NpuOffer> get() = NpuCatalog.image
    override val npuUpscalers: List<NpuOffer> get() = NpuCatalog.upscalers

    // A hub is only contacted when the user searches: opening its tab sends nothing.
    override fun selectTab(tab: ModelsTab) = local.update { it.copy(tab = tab) }

    override fun install(plan: ModelPlan) {
        downloader.install(plan)
        viewModelScope.launch {
            val metered = DownloadQueue.warnMetered(plan.totalBytes, downloadEnvironment.now())
            local.update {
                it.copy(
                    message = if (metered) {
                        "Queued ${plan.title} · ${formatBytes(plan.totalBytes)} over mobile data — turn on wi-fi only in downloads to wait for wi-fi"
                    } else {
                        "Queued ${plan.title}"
                    },
                )
            }
        }
    }

    override fun setWifiOnly(enabled: Boolean) = downloader.setWifiOnly(enabled)
    fun pauseAll() {
        downloader.pauseAll()
    }

    /** Notifications were refused: downloads still run, Android lists them in Task Manager. */
    override fun notificationsDenied() = local.update {
        it.copy(message = "Downloads continue without notifications — Android shows them under active apps in quick settings")
    }

    override fun pause(id: String) {
        downloader.pause(id)
    }
    override fun resume(id: String) {
        downloader.resume(id)
    }
    override fun remove(id: String) {
        downloader.remove(id)
    }
    override fun consumeMessage() = local.update { it.copy(message = null) }

    /**
     * Puts an installed model to work: a new session on "This phone" for chat and code models,
     * the image studio set up for image models, the gallery for upscalers, voice mode for
     * dictation models. A model whose files were deleted outside the app is offered for download
     * again instead.
     */
    override fun use(model: InstalledModel) {
        viewModelScope.launchSafely({ e -> local.update { it.copy(message = userMessage(e)) } }) {
            val missing = library.missingFiles(model)
            if (missing.isNotEmpty()) {
                downloader.markMissing(model.id).join()
                local.update { it.copy(message = "${model.title}: its files were deleted outside the app — tap retry in downloads to get them again") }
                return@launchSafely
            }
            val target = launchOf(model.kind, model.engine, model.isReady, local.value.device?.fit(model.kind, model.totalBytes, model.engine), true)
            target.problem?.let { problem ->
                local.update { it.copy(message = problem) }
                return@launchSafely
            }
            when (target.task) {
                ModelTask.CHAT -> {
                    if (providers.getProvider(LocalProviderSync.LOCAL_PROVIDER_ID) == null) {
                        local.update { it.copy(message = "\"This phone\" isn't ready as a provider yet — try again in a moment") }
                        return@launchSafely
                    }
                    val incognito = settings.settings.first().incognitoByDefault
                    val conversation = conversations.createConversation(incognito, LocalProviderSync.LOCAL_PROVIDER_ID, model.id)
                    host.setToolsEnabled(conversation.id, target.tools)
                    events.send(ModelsEvent.OpenSession(conversation.id))
                }
                ModelTask.IMAGINE -> events.send(ModelsEvent.OpenImagine(model.id))
                ModelTask.GALLERY -> events.send(ModelsEvent.OpenGallery)
                ModelTask.VOICE -> events.send(ModelsEvent.OpenVoice)
                ModelTask.NONE -> Unit
            }
        }
    }

    // ---- Hugging Face

    override fun setHfScope(scope: HfScope) {
        local.update { it.copy(hf = it.hf.copy(scope = scope)) }
        searchHf()
    }

    override fun setHfQuery(query: String) = local.update { it.copy(hf = it.hf.copy(query = query)) }

    override fun searchHf() {
        val hf = local.value.hf
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            local.update { it.copy(hf = it.hf.copy(loading = true, failure = null)) }
            val result = searching {
                if (hf.scope == HfScope.NPU) {
                    hub.search(hf.query, gguf = false, author = NPU_PUBLISHER)
                } else {
                    hub.search(hf.query.ifBlank { if (hf.scope == HfScope.IMAGE) "stable diffusion gguf" else "instruct" }, gguf = true)
                }
            }
            local.update {
                it.copy(hf = it.hf.copy(loading = false, searched = true, results = result.getOrNull().orEmpty(), failure = result.exceptionOrNull()?.let(::searchFailure)))
            }
        }
    }

    override fun openRepo(id: String) {
        viewModelScope.launch {
            local.update { it.copy(hf = it.hf.copy(openLoading = true, openRepo = null)) }
            val repo = guarded { hub.repo(id) }
            local.update { it.copy(hf = it.hf.copy(openLoading = false, openRepo = repo)) }
        }
    }

    override fun closeRepo() = local.update { it.copy(hf = it.hf.copy(openRepo = null)) }

    override fun installHfFile(repo: HfRepo, path: String) {
        val file = repo.files.firstOrNull { it.path == path } ?: return
        closeRepo()
        viewModelScope.launch {
            local.update { it.copy(message = "Reading ${file.path.substringAfterLast('/')}…") }
            guarded { hub.planForFile(repo, file, local.value.hf.scope.kind) }?.let(::install)
        }
    }

    /** Installs a curated NPU/GPU package, choosing the build for this phone's NPU. */
    override fun installNpuOffer(offer: NpuOffer) {
        viewModelScope.launch {
            local.update { it.copy(message = "Preparing ${offer.title}…") }
            guarded { hub.npuOfferPlan(offer, local.value.device?.htpArch) }?.let(::install)
        }
    }

    // ---- Civitai

    override fun setCivitaiScope(scope: CivitaiScope) {
        local.update { it.copy(civitai = it.civitai.copy(scope = scope)) }
        searchCivitai(reset = true)
    }

    override fun setCivitaiQuery(query: String) = local.update { it.copy(civitai = it.civitai.copy(query = query)) }

    override fun searchCivitai(reset: Boolean) {
        val state = local.value.civitai
        if (!reset && state.nextCursor == null) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            local.update { it.copy(civitai = it.civitai.copy(loading = true, failure = null)) }
            val result = searching {
                civitai.search(state.query, state.scope.types, SUPPORTED_BASES, cursor = if (reset) null else state.nextCursor)
            }
            val page = result.getOrNull()
            local.update {
                it.copy(
                    civitai = it.civitai.copy(
                        loading = false,
                        searched = true,
                        results = if (reset) page?.items.orEmpty() else it.civitai.results + page?.items.orEmpty(),
                        // A failed "more" keeps its cursor so it can be tried again.
                        nextCursor = if (page != null) page.nextCursor else it.civitai.nextCursor.takeUnless { reset },
                        failure = result.exceptionOrNull()?.let(::searchFailure),
                    ),
                )
            }
        }
    }

    override fun installCivitai(model: CivitaiModel) {
        val version = model.versions.firstOrNull() ?: return
        val plan = civitai.plan(model, version) ?: run {
            local.update { it.copy(message = "No downloadable file in this version") }
            return
        }
        install(plan)
    }

    /** A card preview, decoded at most [PREVIEW_PX] on its long side (Civitai serves full-size images). */
    override suspend fun preview(url: String): ImageBitmap? {
        previewCache.get(url)?.let { return it }
        val bytes = try {
            previews.load(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_PX) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap() }.getOrNull()
        }?.also { previewCache.put(url, it) }
    }

    /** Decoded card previews (scrolling back must not decode them again), bounded by memory. */
    private val previewCache = object : LruCache<String, ImageBitmap>(PREVIEW_CACHE_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = (value.width * value.height * 4 / 1024).coerceAtLeast(1)
    }

    /** A search outcome; a failure is shown in place of the results, not as a passing snackbar. */
    private suspend fun <T> searching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val failure = searchFailure(e)
        local.update { it.copy(message = if (failure.blocked) "${failure.message} — choose a network mode in the privacy center" else failure.message) }
        null
    }

    companion object {
        /** Base models stable-diffusion.cpp runs well on a phone. */
        /** Publisher of the NPU/GPU packages the NPU scope lists. */
        const val NPU_PUBLISHER = "xororz"

        private const val PREVIEW_PX = 512
        private const val PREVIEW_CACHE_KB = 16 * 1024

        val SUPPORTED_BASES = listOf("SD 1.5", "SD 1.5 LCM", "SD 1.5 Hyper", "SDXL 1.0", "SDXL Turbo", "SDXL Lightning", "Pony", "Illustrious", "NoobAI")
    }
}
