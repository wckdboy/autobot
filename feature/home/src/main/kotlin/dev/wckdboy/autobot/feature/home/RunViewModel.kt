package dev.wckdboy.autobot.feature.home

import android.graphics.BitmapFactory
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
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
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
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    profiler: DeviceProfiler,
) : ViewModel() {

    private val device = MutableStateFlow<DeviceProfile?>(null)
    private val events = Channel<RunEvent>(Channel.BUFFERED)
    val navigation: Flow<RunEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch { device.value = withContext(Dispatchers.IO) { profiler.profile() } }
    }

    private val inputs = combine(providers.observeProviders(), library.models, backends.backends, backends.defaultBackendId) { p, m, b, d ->
        Inputs(p, m, b, d)
    }

    private data class Inputs(val providers: List<Provider>, val models: List<InstalledModel>, val backends: List<DiffusionBackend>, val defaultBackend: String?)

    val uiState: StateFlow<RunUiState> = combine(
        inputs,
        combine(conversations.observeConversations(), gallery.observe()) { c, g -> c to g },
        host.running,
        generator.state,
        device,
    ) { input, (convs, images), running, gen, profile ->
        val models = input.models
        val titles = models.associate { it.id to it.title }
        val local = input.providers.firstOrNull { it.kind == ProviderKind.LOCAL }
        val remote = input.providers.filter { it.kind != ProviderKind.LOCAL }
        val readyChat = models.filter { it.isReady && it.engine == EngineKind.LLAMA }
        val codeModel = readyChat.firstOrNull { it.kind == ModelKind.CODE }

        fun target(p: Provider, model: String = p.defaultModel) =
            RunTarget(p.id, model, titles[model] ?: model.ifBlank { p.displayName }, routeOf(p))

        val chat = local?.let { target(it, readyChat.firstOrNull { m -> m.kind == ModelKind.CHAT }?.id ?: it.defaultModel) } ?: remote.firstOrNull()?.let(::target)
        val agent = remote.firstOrNull()?.let(::target) ?: chat
        val code = if (local != null && codeModel != null) target(local, codeModel.id) else remote.firstOrNull()?.let(::target) ?: chat

        val backend = input.backends.firstOrNull { it.id == input.defaultBackend } ?: input.backends.firstOrNull()
        val imageModel = models.firstOrNull { it.isReady && it.kind == ModelKind.IMAGE && it.engine == EngineKind.DIFFUSION }
        val imageLabel = when {
            backend == null -> "no backend"
            backend.kind == BackendKind.ON_DEVICE -> imageModel?.title ?: "This phone"
            else -> backend.name
        }

        val providerById = input.providers.associateBy { it.id }
        val recent = (
            convs.take(12).map { c ->
                RecentRun(
                    key = "c-${c.id}",
                    title = c.title,
                    subtitle = "session · ${titles[c.model] ?: c.model ?: "—"}",
                    route = providerById[c.providerId]?.let(::routeOf) ?: Route.CLOUD_API,
                    time = c.updatedAt,
                    conversationId = c.id,
                )
            } + images.take(12).map { g ->
                RecentRun(
                    key = "g-${g.id}",
                    title = g.prompt.ifBlank { "image" },
                    subtitle = "image · ${g.width}×${g.height} · ${g.durationMs / 1000} s",
                    route = if (g.engine == "This phone") Route.LOCAL_CPU else Route.PC_LAN,
                    time = g.createdAt,
                    galleryId = g.id,
                )
            }
        ).sortedByDescending { it.time }.take(8)

        val genRunning = gen is GenerationState.Running
        val localBusy = genRunning || running.any { id -> convs.firstOrNull { it.id == id }?.providerId == local?.id }
        RunUiState(
            status = buildString {
                append("this phone")
                profile?.let { p -> if (p.socModel.isNotBlank()) append(" · ").append(p.socModel) }
                append(if (localBusy) " · cpu busy" else " · cpu idle")
                profile?.let { p -> append(" · ").append(formatBytes(p.freeStorageBytes)).append(" free") }
            },
            busy = localBusy,
            live = running.size + if (genRunning) 1 else 0,
            chat = chat,
            agent = agent,
            code = code,
            imageLabel = imageLabel,
            imageRoute = backend?.let { if (it.kind == BackendKind.ON_DEVICE || it.isLoopback) Route.LOCAL_CPU else Route.PC_LAN },
            lastImageId = images.firstOrNull()?.id,
            recent = recent,
            hasAnything = input.providers.isNotEmpty() || models.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunUiState())

    /** Starts a session for [mode] on its target; tools on for agent and code. */
    fun start(mode: RunMode) {
        val state = uiState.value
        val target = when (mode) {
            RunMode.CHAT -> state.chat
            RunMode.AGENT -> state.agent
            RunMode.CODE -> state.code
        } ?: return
        viewModelScope.launch {
            val incognito = settings.settings.first().incognitoByDefault
            val conversation = conversations.createConversation(incognito, target.providerId, target.model)
            host.setToolsEnabled(conversation.id, mode != RunMode.CHAT)
            events.send(RunEvent.OpenSession(conversation.id))
        }
    }

    suspend fun thumbnail(galleryId: String): ImageBitmap? {
        val bytes = gallery.thumbnail(galleryId) ?: return null
        return withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }

    private fun routeOf(p: Provider): Route = when {
        p.kind == ProviderKind.LOCAL -> Route.LOCAL_CPU
        Loopback.isLoopbackUrl(p.baseUrl) -> Route.LOCAL_CPU
        isPrivateHost(p.baseUrl) -> Route.PC_LAN
        else -> Route.CLOUD_API
    }

    private fun isPrivateHost(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return host.startsWith("192.168.") || host.startsWith("10.") || host.endsWith(".local") || host.endsWith(".lan") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) || host.endsWith(".ts.net")
    }
}
