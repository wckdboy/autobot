package dev.wckdboy.autobot.feature.home

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.DeviceProfile
import dev.wckdboy.autobot.core.models.DeviceProfiler
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.formatBytes
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.feature.imagine.generate.GenerationState
import dev.wckdboy.autobot.feature.imagine.generate.ImageGenerator
import dev.wckdboy.autobot.feature.imagine.generate.imageAccelerator
import dev.wckdboy.autobot.feature.imagine.generate.imageRoute
import dev.wckdboy.autobot.feature.imagine.generate.onDeviceImageModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RunMode { CHAT, AGENT, CODE }

/** Where a mode will run: a provider + model, labelled for the card. */
@Immutable
data class RunTarget(val providerId: String, val model: String, val label: String, val route: Route)

@Immutable
data class RecentRun(
    val key: String,
    val title: String,
    val subtitle: String,
    val route: Route,
    val time: Long,
    val conversationId: String? = null,
    val galleryId: String? = null,
)

@Immutable
data class RunUiState(
    val status: String = "",
    val busy: Boolean = false,
    val live: Int = 0,
    val chat: RunTarget? = null,
    val agent: RunTarget? = null,
    val code: RunTarget? = null,
    val imageLabel: String = "no backend",
    val imageRoute: Route? = null,
    val lastImageId: String? = null,
    val recent: List<RecentRun> = emptyList(),
    val hasAnything: Boolean = true,
)

sealed interface RunEvent {
    data class OpenSession(val conversationId: String) : RunEvent
}

/**
 * Where a plain chat starts: this phone with its first downloaded chat model, else the first
 * remote provider. Shared by the RUN card and the system entry points.
 */
fun defaultChatTarget(providers: List<Provider>, models: List<InstalledModel>): Pair<Provider, String>? {
    val local = providers.firstOrNull { it.kind == ProviderKind.LOCAL }
    if (local != null) {
        val model = models.firstOrNull { it.isReady && it.engine == EngineKind.LLAMA && it.kind == ModelKind.CHAT }?.id
        return local to (model ?: local.defaultModel)
    }
    return providers.firstOrNull { it.kind != ProviderKind.LOCAL }?.let { it to it.defaultModel }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class RunViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val host: AgentHost,
    private val library: ModelLibrary,
    private val backends: DiffusionBackendRepository,
    private val gallery: GalleryRepository,
    private val generator: ImageGenerator,
    private val settings: SettingsRepository,
    private val compute: ComputeProfile,
    profiler: DeviceProfiler,
) : ViewModel() {

    private val device = MutableStateFlow<DeviceProfile?>(null)
    private val events = Channel<RunEvent>(Channel.BUFFERED)
    val navigation: Flow<RunEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { device.value = withContext(Dispatchers.IO) { runCatching { profiler.profile() }.getOrNull() } }
    }

    private val inputs = combine(
        providers.observeProviders(),
        library.models,
        backends.backends,
        backends.defaultBackendId,
        backends.lastRequest.map { it.model }.distinctUntilChanged(),
    ) { p, m, b, d, imageModel ->
        Inputs(p, m, b, d, imageModel)
    }

    private data class Inputs(
        val providers: List<Provider>,
        val models: List<InstalledModel>,
        val backends: List<DiffusionBackend>,
        val defaultBackend: String?,
        /** The image studio's selected model (its stored request). */
        val imageModel: String?,
    )

    /** Whether an image job runs: not every step (or preview) of it. */
    private val generating = generator.state.map { it is GenerationState.Running }.distinctUntilChanged()

    /** Providers of the sessions with a turn in progress (a handful of lookups, only when that set changes). */
    private val runningProviders = host.running.mapLatest { ids ->
        ids.size to ids.mapNotNullTo(HashSet()) { id -> runCatching { conversations.getConversation(id)?.providerId }.getOrNull() }
    }

    val uiState: StateFlow<RunUiState> = combine(
        inputs,
        // Only the newest few rows: the card shows 8 of them (LIMIT in SQL, not whole tables).
        combine(conversations.observeRecentConversations(RECENT), gallery.observeRecent(RECENT)) { c, g -> c to g },
        runningProviders,
        generating,
        combine(device, compute.lastRan, compute.fastestByModel) { d, ran, measured -> Triple(d, ran, measured) },
    ) { input, (convs, images), (runningCount, busyProviders), genRunning, (profile, ran, measured) ->
        val models = input.models
        val titles = models.associate { it.id to it.title }
        val chosen = models.associate { it.id to it.backend }

        /** On this phone: the engine [model] runs on, once known (picked, or reported after a reply). */
        fun routeOf(p: Provider, model: String? = null): Route =
            if (p.kind == ProviderKind.LOCAL) {
                Route.local(model?.let { m -> chosen[m]?.let { ComputeProfile.shownBackend(ran[m], it, measured[m]) } }?.id)
            } else {
                providerRoute(p)
            }
        val local = input.providers.firstOrNull { it.kind == ProviderKind.LOCAL }
        val remote = input.providers.filter { it.kind != ProviderKind.LOCAL }
        val readyChat = models.filter { it.isLocalChat }
        val codeModel = readyChat.firstOrNull { it.kind == ModelKind.CODE }

        fun target(p: Provider, model: String = p.defaultModel) =
            RunTarget(p.id, model, titles[model] ?: model.ifBlank { p.displayName }, routeOf(p, model))

        val chat = defaultChatTarget(input.providers, models)?.let { (p, m) -> target(p, m) }
        val agent = remote.firstOrNull()?.let(::target) ?: chat
        val code = if (local != null && codeModel != null) target(local, codeModel.id) else remote.firstOrNull()?.let(::target) ?: chat

        val backend = input.backends.firstOrNull { it.id == input.defaultBackend } ?: input.backends.firstOrNull()
        // The model the studio will use (NPU and GPU packages too), so the card's route names its engine.
        val imageModel = onDeviceImageModel(models, input.imageModel)
        val imageLabel = when {
            backend == null -> "no backend"
            backend.kind == BackendKind.ON_DEVICE -> imageModel?.title ?: "This phone"
            else -> backend.name
        }

        val providerById = input.providers.associateBy { it.id }
        val byId = models.associateBy { it.id }
        val recent = (
            convs.map { c ->
                RecentRun(
                    key = "c-${c.id}",
                    title = c.title,
                    subtitle = "session · ${titles[c.model] ?: c.model ?: "—"}",
                    route = providerById[c.providerId]?.let { routeOf(it, c.model) } ?: Route.CLOUD_API,
                    time = c.updatedAt,
                    conversationId = c.id,
                )
            } + images.map { g ->
                RecentRun(
                    key = "g-${g.id}",
                    title = g.prompt.ifBlank { "image" },
                    subtitle = "image · ${g.width}×${g.height} · ${g.durationMs / 1000} s",
                    route = galleryRoute(g.engine, g.model?.let(byId::get)),
                    time = g.createdAt,
                    galleryId = g.id,
                )
            }
        ).sortedByDescending { it.time }.take(8)

        val localBusy = genRunning || (local != null && local.id in busyProviders)
        RunUiState(
            status = runStatus(profile?.socModel, localBusy, profile?.freeStorageBytes),
            busy = localBusy,
            live = runningCount + if (genRunning) 1 else 0,
            chat = chat,
            agent = agent,
            code = code,
            imageLabel = imageLabel,
            imageRoute = imageRoute(backend, imageModel),
            lastImageId = images.firstOrNull()?.id,
            recent = recent,
            hasAnything = input.providers.isNotEmpty() || models.isNotEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunUiState())

    /** Starts a session for [mode] on its target; tools on for agent and code. */
    fun start(mode: RunMode) {
        val state = uiState.value
        val target = when (mode) {
            RunMode.CHAT -> state.chat
            RunMode.AGENT -> state.agent
            RunMode.CODE -> state.code
        } ?: return
        // Storage errors (disk full, database unavailable) must not crash the home screen.
        viewModelScope.launchSafely({ Log.w("Run", "cannot start a session", it) }) {
            val incognito = settings.settings.first().incognitoByDefault
            // Tapping a tile again reuses the newest chat when nothing was ever sent in it (same
            // model, not filed away), so repeated taps no longer leave a trail of empty "New chat"s.
            val unused = if (incognito) {
                null
            } else {
                conversations.observeRecentConversations(1).first().firstOrNull()?.takeIf {
                    it.providerId == target.providerId && it.model == target.model && !it.pinned && !it.archived && it.folderId == null &&
                        !conversations.isIncognito(it.id) && conversations.searchText(it.id).isNullOrBlank()
                }
            }
            val conversation = unused ?: conversations.createConversation(incognito, target.providerId, target.model)
            host.setToolsEnabled(conversation.id, mode != RunMode.CHAT)
            events.send(RunEvent.OpenSession(conversation.id))
        }
    }

    private val thumbs = LruCache<String, ImageBitmap>(THUMB_CACHE)

    /** A recent image's thumbnail, decoded at most [THUMB_PX] wide and kept for the screen's lifetime. */
    suspend fun thumbnail(galleryId: String): ImageBitmap? {
        thumbs.get(galleryId)?.let { return it }
        val bytes = runCatching { gallery.thumbnail(galleryId) }.getOrNull() ?: return null
        return withContext(Dispatchers.Default) { decodeSampled(bytes, THUMB_PX)?.asImageBitmap() }?.also { thumbs.put(galleryId, it) }
    }

    internal companion object {
        /** Rows read per table for the recent list. */
        const val RECENT = 12

        /**
         * A provider's route; on this phone the engine is not known here (see the per-model one in
         * [uiState]). Not named `routeOf`: the local `routeOf(p, model)` there would shadow it and
         * call itself forever for every cloud or LAN provider.
         */
        fun providerRoute(p: Provider): Route = when {
            p.kind == ProviderKind.LOCAL -> Route.LOCAL
            Loopback.isLoopbackUrl(p.baseUrl) -> Route.LOCAL
            isPrivateHost(p.baseUrl) -> Route.PC_LAN
            else -> Route.CLOUD_API
        }
        private const val THUMB_CACHE = 16
        private const val THUMB_PX = 256
        private val PRIVATE_172 = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")

        /**
         * The header line, short enough for one line on a phone: `SM8850 · idle · 143 GB free`
         * (whole gigabytes from 10 GB up). Busy while a local model or an image job runs (CPU, GPU or NPU).
         */
        fun runStatus(soc: String?, busy: Boolean, freeBytes: Long?): String = listOfNotNull(
            soc?.takeIf { it.isNotBlank() },
            if (busy) "busy" else "idle",
            freeBytes?.let { "${compactBytes(it)} free" },
        ).joinToString(" · ")

        /** `143 GB` from 10 GB up (a decimal adds nothing there), else [formatBytes]. */
        fun compactBytes(bytes: Long): String =
            if (bytes >= 10_000_000_000L) "${(bytes + 500_000_000L) / 1_000_000_000L} GB" else formatBytes(bytes)

        /** A recent image's route: made on this phone (by its model's engine) or on a PC. */
        fun galleryRoute(engine: String, model: InstalledModel?): Route =
            if (engine.startsWith(ON_DEVICE_ENGINE)) {
                Route.local(model?.let(::imageAccelerator) ?: "cpu")
            } else {
                Route.PC_LAN
            }

        /** The gallery's engine name for images made on this phone (the on-device backend's name). */
        private const val ON_DEVICE_ENGINE = "This phone"

        fun isPrivateHost(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            return host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local") || host.endsWith(".lan") ||
                PRIVATE_172.containsMatchIn(host) || host.endsWith(".ts.net")
        }

        /** Decodes [bytes] subsampled (powers of two) so the image is about [maxPx] on its longer side. */
        fun decodeSampled(bytes: ByteArray, maxPx: Int): android.graphics.Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
