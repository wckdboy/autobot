package dev.wckdboy.autobot.feature.studio.ui.audio

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.tts.LocalVoice
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.WavFile
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.BatchPlanner
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.Seeds
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import dev.wckdboy.autobot.shared.tts.KokoroFrontend
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

enum class AudioTab(val label: String) { SOUND("sound"), VOICEOVER("voiceover") }

@Immutable
data class AudioRoomState(
    val loaded: Boolean = false,
    val tab: AudioTab = AudioTab.SOUND,
    val hasAudioModel: Boolean = false,
    val hasVoice: Boolean = false,
    val voices: List<String> = emptyList(),
    val prompt: String = "",
    val seconds: Float = 10f,
    val steps: Int = StableAudio.DEFAULT_STEPS,
    val cfg: Float = StableAudio.DEFAULT_CFG,
    val seedMode: SeedMode = SeedMode.RANDOM,
    val seed: Long? = null,
    val batch: Int = 1,
    val text: String = "",
    val voice: String = KokoroFrontend.DEFAULT_VOICE,
    val speed: Float = 1f,
    val jobIds: List<String> = emptyList(),
    /** Audio workflows saved for a ComfyUI backend (Stable Audio, ACE-Step…), run from Workflows. */
    val audioWorkflows: List<StudioWorkflow> = emptyList(),
    val error: String? = null,
    val message: String? = null,
) {
    val canGenerate: Boolean get() = hasAudioModel && prompt.isNotBlank()
    val canSpeak: Boolean get() = hasVoice && text.isNotBlank()
}

interface AudioActions {
    fun setTab(tab: AudioTab)
    fun setPrompt(text: String)
    fun setSeconds(seconds: Float)
    fun setSteps(steps: Int)
    fun setCfg(cfg: Float)
    fun setSeedMode(mode: SeedMode)
    fun setSeed(seed: Long?)
    fun setBatch(count: Int)
    fun generate()
    fun setText(text: String)
    fun setVoice(voice: String)
    fun setSpeed(speed: Float)
    fun speak()
    fun trim(asset: StudioAsset, startMs: Long, endMs: Long)
    fun dismiss()
}

/**
 * The audio room: text to audio with Stable Audio Open Small on the phone (each clip one queue
 * job), Kokoro voiceover, and trimming a clip into a new asset. Audio is 16-bit WAV, encrypted at
 * rest like every Studio asset.
 */
@HiltViewModel
class AudioRoomViewModel @Inject constructor(
    private val host: StudioHost,
    private val library: ModelLibrary,
) : ViewModel(), AudioActions {
    private val local = MutableStateFlow(AudioRoomState())

    val state: StateFlow<AudioRoomState> = combine(local, library.models, host.store.observeWorkflows()) { l, models, workflows ->
        val audio = models.any { it.isReady && it.kind == ModelKind.AUDIO && it.engine == EngineKind.ONNX }
        val voice = models.firstOrNull { it.isReady && it.kind == ModelKind.VOICE && it.engine == EngineKind.ONNX }
        val voices = voice?.let { v -> withContext(Dispatchers.IO) { LocalVoice.voices(library.dirOf(v.id)) } }.orEmpty()
        l.copy(loaded = true, hasAudioModel = audio, hasVoice = voice != null, voices = voices, audioWorkflows = workflows.filter { it.output == AssetType.AUDIO })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioRoomState())

    override fun setTab(tab: AudioTab) = local.update { it.copy(tab = tab) }
    override fun setPrompt(text: String) = local.update { it.copy(prompt = text) }
    override fun setSeconds(seconds: Float) = local.update { it.copy(seconds = (Math.round(seconds * 2) / 2f).coerceIn(0.5f, StableAudio.MAX_SECONDS.toFloat())) }
    override fun setSteps(steps: Int) = local.update { it.copy(steps = steps.coerceIn(1, StableAudio.MAX_STEPS)) }
    override fun setCfg(cfg: Float) = local.update { it.copy(cfg = (cfg * 2).toInt() / 2f) }
    override fun setSeedMode(mode: SeedMode) = local.update { it.copy(seedMode = mode, seed = it.seed ?: if (mode == SeedMode.RANDOM) null else Seeds.random()) }
    override fun setSeed(seed: Long?) = local.update { it.copy(seed = seed) }
    override fun setBatch(count: Int) = local.update { it.copy(batch = count.coerceIn(1, 8)) }
    override fun setText(text: String) = local.update { it.copy(text = text) }
    override fun setVoice(voice: String) = local.update { it.copy(voice = voice) }
    override fun setSpeed(speed: Float) = local.update { it.copy(speed = (speed * 20).toInt() / 20f) }

    override fun generate() {
        val s = state.value
        if (!s.canGenerate) return
        viewModelScope.launch {
            val base = StudioRecipes.audioRecipe(s.prompt.trim(), s.seconds.toDouble(), s.steps, s.cfg, if (s.seedMode == SeedMode.RANDOM) null else s.seed)
            val jobs = host.queue.submitAll(host.currentProject(), BatchPlanner.plan(base, s.batch, s.seedMode))
            local.update { it.copy(jobIds = (it.jobIds + jobs.map(StudioJob::id)).takeLast(32)) }
        }
    }

    override fun speak() {
        val s = state.value
        if (!s.canSpeak) return
        viewModelScope.launch {
            val job = host.queue.submit(host.currentProject(), StudioRecipes.speechRecipe(s.text.trim(), s.voice, s.speed), "Voiceover · ${s.voice}")
            local.update { it.copy(jobIds = (it.jobIds + job.id).takeLast(32)) }
        }
    }

    override fun trim(asset: StudioAsset, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            val bytes = host.store.bytes(asset.id) ?: return@launch
            val wav = withContext(Dispatchers.Default) { WavFile.decode(bytes)?.let { WavFile.trim(it, startMs, endMs) } }
            if (wav == null) {
                local.update { it.copy(error = "Only WAV clips can be trimmed") }
                return@launch
            }
            val recipe = asset.recipe.copy(params = asset.recipe.params + ("trim" to ParamValue.of("$startMs-$endMs")))
            host.addAsset(asset.projectId, AssetType.AUDIO, "audio/wav", wav, recipe, parentId = asset.id, label = "trim ${startMs / 1000.0}–${endMs / 1000.0} s")
            local.update { it.copy(message = "Trimmed copy saved to the library") }
        }
    }

    override fun dismiss() = local.update { it.copy(error = null, message = null) }
}
