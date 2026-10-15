package dev.wckdboy.autobot

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.engine.audiogen.LocalAudioGen
import dev.wckdboy.autobot.engine.diffusion.LocalSd
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.npu.LocalNpu
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import dev.wckdboy.autobot.engine.tts.LocalVoice
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The on-device engines that hold model weights in memory. */
enum class Engine { LLM, IMAGE, NPU, SPEECH, VOICE, AUDIO }

/**
 * Which engines to free, and when. The chat model, the image models (stable-diffusion.cpp and
 * the NPU/GPU engine) are each large enough that two resident at once can get the app killed,
 * so they exclude each other, and so does text to audio (Stable Audio: ~3 GB at its peak, in the
 * app process). Whisper is freed before any of them works, but does not evict the
 * chat model (dictation runs between turns). The Kokoro voice is small and is freed only under
 * memory pressure.
 */
object EngineMemoryPolicy {
    private val EXCLUSIVE = setOf(Engine.LLM, Engine.IMAGE, Engine.NPU, Engine.AUDIO)

    /** Engines to unload before [starting] runs a job. */
    fun before(starting: Engine): Set<Engine> = when (starting) {
        Engine.LLM, Engine.IMAGE, Engine.NPU, Engine.AUDIO -> EXCLUSIVE - starting + Engine.SPEECH
        Engine.SPEECH -> setOf(Engine.IMAGE, Engine.NPU, Engine.AUDIO)
        Engine.VOICE -> emptySet()
    }

    /** Engines to release for an `onTrimMemory(level)`; [busy] ones keep running. */
    fun onTrim(level: Int, busy: Set<Engine>): Set<Engine> {
        val free = when {
            // Real pressure (or we are in the background list): give everything back.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> Engine.entries.toSet()
            @Suppress("DEPRECATION")
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> Engine.entries.toSet()
            // The UI is gone: the in-app voice goes; models stay for an agent turn running on.
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> setOf(Engine.VOICE, Engine.SPEECH)
            else -> setOf(Engine.VOICE)
        }
        return free - busy
    }
}

/**
 * The one place that frees engine memory (see [EngineMemoryPolicy]): each engine calls in before
 * a job, and Android's memory callbacks release idle engines. Main process only.
 */
@Singleton
class EngineMemoryCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val llm: LocalLlm,
    private val sd: LocalSd,
    private val npu: LocalNpu,
    private val speech: LocalSpeech,
    private val voice: LocalVoice,
    private val audio: LocalAudioGen,
) : ComponentCallbacks2 {

    fun install() {
        llm.beforeJob = { free(EngineMemoryPolicy.before(Engine.LLM)) }
        sd.beforeJob = { free(EngineMemoryPolicy.before(Engine.IMAGE)) }
        npu.beforeJob = { free(EngineMemoryPolicy.before(Engine.NPU)) }
        speech.beforeJob = { free(EngineMemoryPolicy.before(Engine.SPEECH)) }
        audio.beforeJob = { free(EngineMemoryPolicy.before(Engine.AUDIO)) }
        context.registerComponentCallbacks(this)
    }

    private fun busy(): Set<Engine> = buildSet {
        if (llm.isBusy) add(Engine.LLM)
        if (sd.isBusy) add(Engine.IMAGE)
        if (npu.isBusy) add(Engine.NPU)
        if (speech.isBusy) add(Engine.SPEECH)
        if (voice.isBusy) add(Engine.VOICE)
        if (audio.isBusy) add(Engine.AUDIO)
    }

    /** Unloads [engines]' models (a running job finishes first: engines run jobs serially) and ends idle processes. */
    private fun free(engines: Set<Engine>) {
        for (engine in engines) {
            when (engine) {
                Engine.LLM -> llm.run { unloadModel(); releaseIfIdle() }
                Engine.IMAGE -> sd.run { unloadModel(); releaseIfIdle() }
                Engine.NPU -> npu.run { unloadModels(); releaseIfIdle() }
                Engine.SPEECH -> if (!speech.isBusy) speech.run { unloadModel(); releaseIfIdle() }
                Engine.VOICE -> if (voice.isLoaded) scope.launch { voice.unload() }
                Engine.AUDIO -> if (audio.isLoaded && !audio.isBusy) scope.launch { audio.unload() }
            }
        }
    }

    override fun onTrimMemory(level: Int) = free(EngineMemoryPolicy.onTrim(level, busy()))

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = free(Engine.entries.toSet() - busy())

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
