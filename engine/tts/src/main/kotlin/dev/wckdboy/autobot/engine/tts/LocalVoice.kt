package dev.wckdboy.autobot.engine.tts

import dev.wckdboy.autobot.shared.tts.KokoroFrontend
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VoiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Kokoro, the on-device neural voice: text in, 24 kHz mono float PCM out, sentence by sentence.
 * [folder] is an installed Kokoro voice model (`ModelLibrary.dirOf(id)`: `model_quantized.onnx`,
 * `config.json`, misaki lexicons, `<voice>.bin`). Loads lazily on first use and stays loaded
 * (about 100 MB of weights plus the lexicons) until [unload]. Calls are serialized; nothing is
 * fetched or sent. Runs in the app process.
 */
class LocalVoice {
    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private class Loaded(val folder: File, val frontend: KokoroFrontend, val model: KokoroOnnx) {
        val voices = HashMap<String, FloatArray>()
    }

    /**
     * Speech for [text] as one PCM block per sentence (or per part of a long one), each emitted as
     * soon as it is synthesized, so playback can start after the first. [voice] is a voice id
     * (`af_heart`; see [voices]); [speed] 1 is natural. Cancelling the collector stops after the
     * current sentence. Throws [VoiceException].
     */
    fun synthesize(folder: File, text: String, voice: String = KokoroFrontend.DEFAULT_VOICE, speed: Float = 1f): Flow<FloatArray> = flow {
        val chunks = mutex.withLock {
            val frontend = load(folder).frontend
            try {
                frontend.chunks(text, voice)
            } catch (e: Exception) {
                throw VoiceException("The voice cannot read this text: ${e.message}", e)
            }
        }
        for (chunk in chunks) {
            currentCoroutineContext().ensureActive()
            val pcm = mutex.withLock {
                val l = load(folder)
                try {
                    val pack = l.voices.getOrPut(voice) { readVoice(l.folder, voice) }
                    l.model.run(chunk, pack, speed)
                } catch (e: VoiceException) {
                    throw e
                } catch (e: Exception) {
                    throw VoiceException("The voice failed: ${e.message}", e)
                } catch (e: OutOfMemoryError) {
                    throw VoiceException("Not enough memory for the voice", e)
                }
            }
            emit(pcm)
        }
    }.flowOn(Dispatchers.Default)

    /** All of [text] as one PCM block (for a short reply, or to prepare the next piece while one plays). */
    suspend fun synthesizeAll(folder: File, text: String, voice: String = KokoroFrontend.DEFAULT_VOICE, speed: Float = 1f): FloatArray {
        val parts = mutableListOf<FloatArray>()
        synthesize(folder, text, voice, speed).collect { parts += it }
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** Loads the model now (first synthesis otherwise pays for it: about a second). */
    suspend fun prepare(folder: File) {
        mutex.withLock { load(folder) }
    }

    /** Frees the model and lexicons. */
    suspend fun unload() = mutex.withLock {
        runCatching { loaded?.model?.close() }
        loaded = null
    }

    /** Whether a synthesis is running (memory pressure only frees an idle voice). */
    val isBusy: Boolean get() = mutex.isLocked

    /** Whether the model is in memory. */
    val isLoaded: Boolean get() = loaded != null

    private suspend fun load(folder: File): Loaded {
        loaded?.takeIf { it.folder == folder }?.let { return it }
        runCatching { loaded?.model?.close() }
        loaded = null
        return withContext(Dispatchers.IO) {
            val model = File(folder, MODEL)
            if (!model.isFile) throw VoiceException("The Kokoro voice is not installed")
            try {
                Loaded(folder, KokoroFrontend.load(folder.path), KokoroOnnx(model))
            } catch (e: Exception) {
                throw VoiceException("Cannot load the voice: ${e.message}", e)
            } catch (e: LinkageError) {
                // ONNX Runtime's native library missing or not loadable on this phone.
                throw VoiceException("The voice engine is not available on this phone", e)
            } catch (e: OutOfMemoryError) {
                throw VoiceException("Not enough memory for the voice", e)
            }
        }.also { loaded = it }
    }

    private fun readVoice(folder: File, voice: String): FloatArray {
        val file = File(folder, "${voice.filter { it.isLetterOrDigit() || it == '_' }}.bin")
        if (!file.isFile) throw VoiceException("Voice $voice is not installed")
        return KokoroOnnx.readVoice(file)
    }

    companion object {
        /** Output sample rate: 24 kHz mono. */
        const val SAMPLE_RATE = KokoroFrontend.SAMPLE_RATE

        const val MODEL = "model_quantized.onnx"

        /** Voice ids installed in [folder] (`af_heart`, …), sorted. */
        fun voices(folder: File): List<String> =
            folder.listFiles { f -> f.isFile && f.name.endsWith(".bin") }.orEmpty().map { it.name.removeSuffix(".bin") }.sorted()
    }
}
