package dev.wckdboy.autobot.feature.chat.voice

import android.os.SystemClock
import dev.wckdboy.autobot.engine.speech.LocalSpeech
import dev.wckdboy.autobot.engine.speech.SpeechException
import dev.wckdboy.autobot.engine.speech.SpeechText
import dev.wckdboy.autobot.shared.voice.DictationText
import dev.wckdboy.autobot.shared.voice.StreamingDictation
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The microphone and whisper as [LiveDictation] uses them (a fake in tests). */
internal interface DictationEngine {
    /** Captures 16 kHz mono until [stopped]; [onAudio] gets each buffer (reused). */
    suspend fun capture(stopped: () -> Boolean, onAudio: (FloatArray, Int) -> Unit)

    /** Transcribes [samples] in [language] (null: detect); [partial]: a live partial pass. */
    suspend fun transcribe(samples: FloatArray, language: String?, partial: Boolean = false): SpeechText
}

/** [LocalSpeech] with the chat's speech model. */
internal class LocalDictationEngine(private val speech: LocalSpeech, private val modelPath: String) : DictationEngine {
    override suspend fun capture(stopped: () -> Boolean, onAudio: (FloatArray, Int) -> Unit) = speech.capture(stopped, onAudio = onAudio)

    override suspend fun transcribe(samples: FloatArray, language: String?, partial: Boolean): SpeechText =
        speech.transcribe(modelPath, samples, language ?: "auto", partial)
}

/**
 * Streaming dictation on Android: captures the microphone and keeps one whisper pass running
 * over the shared [StreamingDictation] window, so [run]'s `onText` sees the words while the user
 * speaks. When capture stops, the pass under way is cancelled and one last pass over what is left
 * gives the final transcript. Audio stays in memory (one window) and is never stored.
 */
internal class LiveDictation(
    private val engine: DictationEngine,
    private val work: CoroutineContext = Dispatchers.Default,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** Milliseconds from the first speech to the first words on screen (last run; null: none). */
    @Volatile
    var firstPartialMs: Long? = null
        private set

    /**
     * Dictates until [stopped]. [preroll] is audio heard just before (barge-in: the user's first
     * words). [onLevel] gets each buffer's peak on the capture thread; [onText] the live text.
     * Returns the final transcript ("" when nothing was said).
     */
    suspend fun run(
        stopped: () -> Boolean,
        preroll: FloatArray? = null,
        onLevel: (Float) -> Unit = {},
        onText: (DictationText) -> Unit = {},
    ): String = withContext(work) {
        val dictation = StreamingDictation()
        val lock = Any()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var speechAt = -1L
        firstPartialMs = null
        preroll?.let { synchronized(lock) { dictation.append(it) } }
        coroutineScope {
            // One pass at a time, started whenever new audio makes one due.
            val worker = launch {
                for (signal in wake) {
                    val pass = synchronized(lock) { dictation.nextPass() } ?: continue
                    val heard = try {
                        engine.transcribe(pass.samples, pass.language, pass.isPartial)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(lock) { dictation.onFailed(pass) }
                        continue
                    }
                    val text = synchronized(lock) { dictation.onResult(pass, heard.text, heard.language) }
                    if (firstPartialMs == null && !text.isEmpty && speechAt >= 0) firstPartialMs = clock() - speechAt
                    onText(text)
                }
            }
            try {
                engine.capture(stopped) { samples, count ->
                    var peak = 0f
                    for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(samples[i]))
                    synchronized(lock) {
                        dictation.append(samples, count)
                        if (speechAt < 0 && dictation.firstSpeechAtMs >= 0) speechAt = clock()
                    }
                    onLevel(peak)
                    wake.trySend(Unit)
                }
            } finally {
                wake.close()
                // The pass under way only matters until the last one starts: stop it now.
                worker.cancelAndJoin()
            }
        }
        val last = synchronized(lock) { dictation.finish() } ?: return@withContext dictation.result
        val heard = try {
            finalText(last)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "" // keeps what the partials heard
        }
        synchronized(lock) { dictation.onFinal(last, heard) }
    }

    /** The last pass; once more if it met the cancel meant for the pass before it. */
    private suspend fun finalText(pass: StreamingDictation.Pass): String {
        try {
            return engine.transcribe(pass.samples, pass.language).text
        } catch (e: SpeechException) {
            if (e.message != "cancelled") throw e
        }
        return engine.transcribe(pass.samples, pass.language).text
    }
}
