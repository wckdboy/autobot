package dev.wckdboy.autobot.feature.studio.ui.video

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.MediaInfo
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import dev.wckdboy.autobot.shared.studio.WorkflowException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class VideoRoomState(
    val loaded: Boolean = false,
    val backends: List<ComfyBackendConfig> = emptyList(),
    val backendId: String? = null,
    val workflows: List<StudioWorkflow> = emptyList(),
    val workflowId: String? = null,
    val values: Map<String, ParamValue> = emptyMap(),
    val randomizeSeed: Boolean = true,
    val jobIds: List<String> = emptyList(),
    val message: String? = null,
) {
    val backend: ComfyBackendConfig? get() = backends.firstOrNull { it.id == backendId } ?: backends.firstOrNull()
    val workflow: StudioWorkflow? get() = workflows.firstOrNull { it.id == workflowId } ?: workflows.firstOrNull()
}

interface VideoActions {
    fun selectBackend(id: String)
    fun selectWorkflow(id: String)
    fun setValue(name: String, value: ParamValue)
    fun setRandomizeSeed(enabled: Boolean)
    fun run()
    fun exportFrame(asset: StudioAsset, timeMs: Long)
    fun dismiss()
}

/**
 * The video room: video comes from ComfyUI workflows on the user's PC (LTX-Video, Wan …) —
 * public weights don't make on-device video viable — so every run says where it goes. A frame of
 * a clip can be kept as an image asset (an init image for image-to-video, or a still).
 */
@HiltViewModel
class VideoRoomViewModel @Inject constructor(private val host: StudioHost) : ViewModel(), VideoActions {
    private val local = MutableStateFlow(VideoRoomState())

    val state: StateFlow<VideoRoomState> = combine(local, host.settings.comfyBackends, host.store.observeWorkflows()) { l, b, w ->
        l.copy(loaded = true, backends = b, workflows = w.filter { it.output == AssetType.VIDEO })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoRoomState())

    override fun selectBackend(id: String) = local.update { it.copy(backendId = id) }
    override fun selectWorkflow(id: String) = local.update { it.copy(workflowId = id, values = emptyMap()) }
    override fun setValue(name: String, value: ParamValue) = local.update { it.copy(values = it.values + (name to value)) }
    override fun setRandomizeSeed(enabled: Boolean) = local.update { it.copy(randomizeSeed = enabled) }

    override fun run() {
        val s = state.value
        val workflow = s.workflow ?: return
        val backend = s.backend ?: return
        viewModelScope.launch {
            try {
                val job: StudioJob = host.submitWorkflow(workflow, s.values, backend.id, s.randomizeSeed)
                local.update { it.copy(jobIds = (it.jobIds + job.id).takeLast(32), message = null) }
            } catch (e: WorkflowException) {
                local.update { it.copy(message = e.message) }
            }
        }
    }

    override fun exportFrame(asset: StudioAsset, timeMs: Long) {
        viewModelScope.launch {
            val bytes = host.store.bytes(asset.id) ?: return@launch
            val png = withContext(Dispatchers.Default) {
                if (asset.mimeType.startsWith("image/")) ImageCodec.decode(bytes)?.let(ImageCodec::png) else MediaInfo.frame(bytes, timeMs)?.let(ImageCodec::png)
            }
            if (png == null) {
                local.update { it.copy(message = "Couldn't read a frame there") }
                return@launch
            }
            val recipe = Recipe(StudioRecipes.IMPORT_ENGINE, params = mapOf("frame_ms" to ParamValue.of(timeMs)), output = AssetType.IMAGE)
            host.addAsset(asset.projectId, AssetType.IMAGE, "image/png", png, recipe, parentId = asset.id, label = "frame ${timeMs / 1000.0} s")
            local.update { it.copy(message = "Frame saved to the library") }
        }
    }

    override fun dismiss() = local.update { it.copy(message = null) }
}
