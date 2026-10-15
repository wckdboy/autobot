package dev.wckdboy.autobot.engine.audiogen

import dev.wckdboy.autobot.core.models.StableAudioCatalog
import dev.wckdboy.autobot.shared.audiogen.AudioGenProgress
import dev.wckdboy.autobot.shared.audiogen.AudioGenRequest
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.audiogen.StableAudioPipeline
import dev.wckdboy.autobot.shared.audiogen.StereoAudio
import dev.wckdboy.autobot.shared.audiogen.T5Tokenizer
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AudioGenException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * On-device text to audio (Stable Audio Open Small): a prompt in, up to 11.9 s of 44.1 kHz stereo
 * float PCM out. [folder] is an installed bundle (`ModelLibrary.dirOf(id)` of
 * [StableAudioCatalog.onnx]). Generations are serialized and run on [Dispatchers.Default];
 * cancelling the caller stops between model calls (a DiT step takes about a second). The sessions
 * are freed after each generation unless [keepLoaded] (faster repeats, ~0.5 GB held). Runs in the
 * app process; nothing is fetched or sent.
 */
class LocalAudioGen(private val keepLoaded: Boolean = false, private val lowMemory: Boolean = false) {
    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private class Loaded(val folder: File, val models: StableAudioOnnx, val tokenizer: T5Tokenizer)

    /** Called before each generation (the app's `EngineMemoryCoordinator` frees the other large models). */
    @Volatile
    var beforeJob: () -> Unit = {}

    /** Whether a generation is running (memory pressure only frees an idle engine). */
    val isBusy: Boolean get() = mutex.isLocked

    val isLoaded: Boolean get() = loaded != null

    /** Audio for [request]; [onProgress] is called from the worker thread per stage and DiT step. Throws [AudioGenException]. */
    suspend fun generate(folder: File, request: AudioGenRequest, onProgress: (AudioGenProgress) -> Unit = {}): StereoAudio {
        beforeJob()
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val l = load(folder)
                try {
                    StableAudioPipeline(l.models, l.tokenizer).generate(request, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw AudioGenException("Audio generation failed: ${e.message}", e)
                } catch (e: OutOfMemoryError) {
                    throw AudioGenException("Not enough memory to generate audio", e)
                } finally {
                    if (!keepLoaded) runCatching { l.models.close() }
                }
            }
        }
    }

    /** Reads the tokenizer and embedder now (sessions still open on first use). */
    suspend fun prepare(folder: File) {
        mutex.withLock { withContext(Dispatchers.IO) { load(folder) } }
    }

    /** Frees the sessions and the tokenizer. */
    suspend fun unload() = mutex.withLock {
        runCatching { loaded?.models?.close() }
        loaded = null
    }

    private fun load(folder: File): Loaded {
        loaded?.takeIf { it.folder == folder }?.let { return it }
        loaded?.models?.close()
        loaded = null
        if (!StableAudioOnnx.installed(folder)) throw AudioGenException("Stable Audio Open Small is not installed")
        return try {
            Loaded(folder, StableAudioOnnx(folder, lowMemory = lowMemory), T5Tokenizer.load(File(folder, StableAudioCatalog.TOKENIZER).path))
        } catch (e: Exception) {
            throw AudioGenException("Cannot load the audio model: ${e.message}", e)
        }.also { loaded = it }
    }

    companion object {
        const val SAMPLE_RATE = StableAudio.SAMPLE_RATE
    }
}
