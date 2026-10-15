package dev.wckdboy.autobot.feature.chat.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A text-to-speech voice as [OfflineVoices] sees it. */
data class VoiceOption(
    val name: String,
    val locale: Locale,
    val networkRequired: Boolean,
    val installed: Boolean,
    /** [Voice.QUALITY_VERY_LOW]…[Voice.QUALITY_VERY_HIGH]. */
    val quality: Int,
    /** [Voice.LATENCY_VERY_LOW]…[Voice.LATENCY_VERY_HIGH]. */
    val latency: Int,
)

/** Picks the voice read-aloud uses: on-device only, nearest to the phone's language. */
object OfflineVoices {
    const val NONE = "No offline voice installed — add one in Android Settings › Text-to-speech"

    /**
     * The best installed voice that needs no network: the [preferred] locale, else its language,
     * else English, else any; then higher quality, then lower latency. Null when there is none.
     */
    fun pick(voices: Collection<VoiceOption>, preferred: Locale): VoiceOption? =
        voices.filter { !it.networkRequired && it.installed }.maxWithOrNull(
            compareBy<VoiceOption> { rank(it.locale, preferred) }.thenBy { it.quality }.thenByDescending { it.latency }.thenByDescending { it.name },
        )

    private fun rank(locale: Locale, preferred: Locale): Int = when {
        locale.language == preferred.language && locale.country == preferred.country -> 3
        locale.language == preferred.language -> 2
        locale.language == "en" -> 1
        else -> 0
    }
}

/**
 * The phone's text-to-speech engine, restricted to on-device synthesis: it speaks only with a
 * voice that needs no network and asks the engine for embedded synthesis on every utterance.
 * With no offline voice installed it refuses ([OfflineVoices.NONE]) rather than stream text out.
 */
@Singleton
class SystemSpeaker @Inject constructor(@ApplicationContext private val context: Context) : Speaker {
    override val id = ID

    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** The voice chosen for the current locale (voices change rarely; [stop] forgets it). */
    @Volatile
    private var chosen: Pair<Locale, Voice>? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) { pending[utteranceId]?.complete(Unit) }
        override fun onStop(utteranceId: String, interrupted: Boolean) { pending[utteranceId]?.complete(Unit) }
        override fun onError(utteranceId: String, errorCode: Int) { failed(utteranceId) }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) { failed(utteranceId) }

        private fun failed(utteranceId: String) {
            pending[utteranceId]?.completeExceptionally(SpeakerUnavailable("Text-to-speech failed"))
        }
    }

    private suspend fun engine(): TextToSpeech = mutex.withLock {
        tts?.let { return it }
        val ready = CompletableDeferred<Int>()
        val engine = withContext(Dispatchers.Main) { TextToSpeech(context) { ready.complete(it) } }
        if (withTimeoutOrNull(10_000) { ready.await() } != TextToSpeech.SUCCESS) {
            engine.shutdown()
            throw SpeakerUnavailable("Text-to-speech is not available")
        }
        engine.setOnUtteranceProgressListener(listener)
        tts = engine
        engine
    }

    private fun offlineVoice(engine: TextToSpeech): Voice? {
        val locale = Locale.getDefault()
        chosen?.takeIf { it.first == locale }?.let { return it.second }
        val voices = runCatching { engine.voices }.getOrNull().orEmpty().filterNotNull()
        val options = voices.associateBy { v ->
            VoiceOption(
                name = v.name,
                locale = v.locale,
                networkRequired = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
                quality = v.quality,
                latency = v.latency,
            )
        }
        val voice = OfflineVoices.pick(options.keys, locale)?.let(options::get) ?: return null
        chosen = locale to voice
        return voice
    }

    override suspend fun isAvailable(): Boolean = runCatching { offlineVoice(engine()) != null }.getOrDefault(false)

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        val engine = engine()
        val voice = offlineVoice(engine) ?: throw SpeakerUnavailable(OfflineVoices.NONE)
        if (engine.voice?.name != voice.name && engine.setVoice(voice) != TextToSpeech.SUCCESS) throw SpeakerUnavailable(OfflineVoices.NONE)
        // The engine's own guard as well: never fall back to network synthesis for this utterance.
        @Suppress("DEPRECATION")
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
            putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        try {
            if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) throw SpeakerUnavailable("Text-to-speech failed")
            done.await()
        } catch (e: CancellationException) {
            engine.stop()
            throw e
        } finally {
            pending.remove(id)
        }
    }

    override fun stop() {
        tts?.stop()
        pending.values.forEach { it.complete(Unit) }
        chosen = null
    }

    companion object {
        const val ID = "system"
    }
}
