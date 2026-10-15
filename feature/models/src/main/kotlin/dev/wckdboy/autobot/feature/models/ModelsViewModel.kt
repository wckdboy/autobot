package dev.wckdboy.autobot.feature.models

import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.models.AccountState
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.CivitaiClient
import dev.wckdboy.autobot.core.models.CivitaiModel
import dev.wckdboy.autobot.core.models.DeviceProfile
import dev.wckdboy.autobot.core.models.DeviceProfiler
import dev.wckdboy.autobot.core.models.DownloadProgress
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
import dev.wckdboy.autobot.core.models.launchOf
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import java.io.File
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

@Immutable
data class HfState(
    val scope: HfScope = HfScope.CHAT,
    val query: String = "",
    val loading: Boolean = false,
    val results: List<HfModelSummary> = emptyList(),
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
)

@Immutable
data class ModelsUiState(
    val device: DeviceProfile? = null,
    val installed: List<InstalledModel> = emptyList(),
    val progress: DownloadProgress? = null,
    val tab: ModelsTab = ModelsTab.RECOMMENDED,
    val hf: HfState = HfState(),
    val civitai: CivitaiState = CivitaiState(),
    val accounts: AccountState = AccountState(),
    val message: String? = null,
) {
    fun installedIds(): Set<String> = installed.map { it.id }.toSet()
}

/** Where tapping an installed model goes. */
sealed interface ModelsEvent {
    data class OpenSession(val conversationId: String) : ModelsEvent
    data class OpenImagine(val modelId: String) : ModelsEvent
    data object OpenGallery : ModelsEvent
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
    accounts: ModelAccounts,
) : ViewModel() {

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

    val uiState: StateFlow<ModelsUiState> = combine(library.models, downloader.progress, accounts.state, local) { installed, progress, acc, l ->
        ModelsUiState(l.device, installed, progress, l.tab, l.hf, l.civitai, acc, l.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    init {
        refreshDevice()
    }

    fun refreshDevice() {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) { profiler.profile() }
            local.update { it.copy(device = profile) }
        }
    }

    val recommended: List<ModelPlan> get() = Catalog.all
    val npuImages: List<NpuOffer> get() = NpuCatalog.image
    val npuUpscalers: List<NpuOffer> get() = NpuCatalog.upscalers

    fun selectTab(tab: ModelsTab) = local.update { it.copy(tab = tab) }

    fun install(plan: ModelPlan) {
        downloader.install(plan)
        local.update { it.copy(message = "Queued ${plan.title}") }
    }

    fun pause(id: String) = downloader.pause(id)
    fun resume(id: String) = downloader.resume(id)
    fun remove(id: String) = downloader.remove(id)
    fun consumeMessage() = local.update { it.copy(message = null) }

    /**
     * Puts an installed model to work: a new session on "This phone" for chat and code models,
     * the image studio set up for image models, the gallery for upscalers.
     */
    fun use(model: InstalledModel) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { model.paths.values.all { File(it).exists() } }
            val target = launchOf(model.kind, model.engine, model.isReady, local.value.device?.fit(model.kind, model.totalBytes, model.engine), files)
            target.problem?.let { problem ->
                local.update { it.copy(message = problem) }
                return@launch
            }
            when (target.task) {
                ModelTask.CHAT -> {
                    if (providers.getProvider(LocalProviderSync.LOCAL_PROVIDER_ID) == null) {
                        local.update { it.copy(message = "\"This phone\" isn't ready as a provider yet — try again in a moment") }
                        return@launch
                    }
                    val incognito = settings.settings.first().incognitoByDefault
                    val conversation = conversations.createConversation(incognito, LocalProviderSync.LOCAL_PROVIDER_ID, model.id)
                    host.setToolsEnabled(conversation.id, target.tools)
                    events.send(ModelsEvent.OpenSession(conversation.id))
                }
                ModelTask.IMAGINE -> events.send(ModelsEvent.OpenImagine(model.id))
                ModelTask.GALLERY -> events.send(ModelsEvent.OpenGallery)
                ModelTask.NONE -> Unit
            }
        }
    }

    // ---- Hugging Face

    fun setHfScope(scope: HfScope) {
        local.update { it.copy(hf = it.hf.copy(scope = scope)) }
        searchHf()
    }

    fun setHfQuery(query: String) = local.update { it.copy(hf = it.hf.copy(query = query)) }

    fun searchHf() {
        val hf = local.value.hf
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            local.update { it.copy(hf = it.hf.copy(loading = true)) }
            val results = guarded {
                if (hf.scope == HfScope.NPU) {
                    hub.search(hf.query, gguf = false, author = NPU_PUBLISHER)
                } else {
                    hub.search(hf.query.ifBlank { if (hf.scope == HfScope.IMAGE) "stable diffusion gguf" else "instruct" }, gguf = true)
                }
            }
            local.update { it.copy(hf = it.hf.copy(loading = false, results = results.orEmpty())) }
        }
    }

    fun openRepo(id: String) {
        viewModelScope.launch {
            local.update { it.copy(hf = it.hf.copy(openLoading = true, openRepo = null)) }
            val repo = guarded { hub.repo(id) }
            local.update { it.copy(hf = it.hf.copy(openLoading = false, openRepo = repo)) }
        }
    }

    fun closeRepo() = local.update { it.copy(hf = it.hf.copy(openRepo = null)) }

    fun installHfFile(repo: HfRepo, path: String) {
        val file = repo.files.firstOrNull { it.path == path } ?: return
        closeRepo()
        viewModelScope.launch {
            local.update { it.copy(message = "Reading ${file.path.substringAfterLast('/')}…") }
            guarded { hub.planForFile(repo, file, local.value.hf.scope.kind) }?.let(::install)
        }
    }

    /** Installs a curated NPU/GPU package, choosing the build for this phone's NPU. */
    fun installNpuOffer(offer: NpuOffer) {
        viewModelScope.launch {
            local.update { it.copy(message = "Preparing ${offer.title}…") }
            guarded { hub.npuOfferPlan(offer, local.value.device?.htpArch) }?.let(::install)
        }
    }

    // ---- Civitai

    fun setCivitaiScope(scope: CivitaiScope) {
        local.update { it.copy(civitai = it.civitai.copy(scope = scope)) }
        searchCivitai(reset = true)
    }

    fun setCivitaiQuery(query: String) = local.update { it.copy(civitai = it.civitai.copy(query = query)) }

    fun searchCivitai(reset: Boolean = true) {
        val state = local.value.civitai
        if (!reset && state.nextCursor == null) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            local.update { it.copy(civitai = it.civitai.copy(loading = true)) }
            val page = guarded {
                civitai.search(state.query, state.scope.types, SUPPORTED_BASES, cursor = if (reset) null else state.nextCursor)
            }
            local.update {
                it.copy(
                    civitai = it.civitai.copy(
                        loading = false,
                        results = if (reset) page?.items.orEmpty() else it.civitai.results + page?.items.orEmpty(),
                        nextCursor = page?.nextCursor,
                    ),
                )
            }
        }
    }

    fun installCivitai(model: CivitaiModel) {
        val version = model.versions.firstOrNull() ?: return
        val plan = civitai.plan(model, version) ?: run {
            local.update { it.copy(message = "No downloadable file in this version") }
            return
        }
        install(plan)
    }

    suspend fun preview(url: String): ImageBitmap? = previews.load(url)?.let { bytes ->
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HubException) {
        local.update { it.copy(message = e.message) }
        null
    }

    companion object {
        /** Base models stable-diffusion.cpp runs well on a phone. */
        /** Publisher of the NPU/GPU packages the NPU scope lists. */
        const val NPU_PUBLISHER = "xororz"

        val SUPPORTED_BASES = listOf("SD 1.5", "SD 1.5 LCM", "SD 1.5 Hyper", "SDXL 1.0", "SDXL Turbo", "SDXL Lightning", "Pony", "Illustrious", "NoobAI")
    }
}
