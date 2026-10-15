package dev.wckdboy.autobot.feature.chat.voice

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.wckdboy.autobot.shared.voice.VoiceText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why a [Speaker] cannot talk; [message] is shown to the user as is. */
class SpeakerUnavailable(message: String) : Exception(message)

/**
 * Reads text aloud on the device. Read-aloud and voice mode only talk to this, so another engine
 * (a neural voice) plugs in as a second implementation bound into the set in [VoiceModule] and
 * picked by [Speakers.pick]. Implementations must never synthesize over the network.
 */
interface Speaker {
    /** Stable id for the setting that picks the engine (`system`, …). */
    val id: String

    /** Whether it can speak right now (engine up, an offline voice or model installed). */
    suspend fun isAvailable(): Boolean

    /**
     * Speaks one plain-text piece (see [read]) and returns once it has been spoken or [stop]ped.
     * Cancelling the caller stops it too. Throws [SpeakerUnavailable] with a user-facing reason.
     */
    suspend fun speak(text: String)

    /** Hint: [text] is the next piece; an engine with synthesis latency can start on it now. */
    fun prepare(text: String) = Unit

    /** Stops speaking now; a suspended [speak] returns. */
    fun stop()
}

/** Reads [markdown] sentence by sentence: speech starts after the first one and stops between any two. */
suspend fun Speaker.read(markdown: String) {
    val chunks = VoiceText.chunks(VoiceText.speakable(markdown))
    chunks.forEachIndexed { i, chunk ->
        // stop() ends the piece being spoken; a cancelled caller must not start the next one.
        currentCoroutineContext().ensureActive()
        chunks.getOrNull(i + 1)?.let(::prepare)
        speak(chunk)
    }
}

/** The installed speech engines. */
@Singleton
class Speakers @Inject constructor(private val all: Set<@JvmSuppressWildcards Speaker>) {
    /** The engine with [id] (a setting, later), else the system one. */
    fun pick(id: String? = null): Speaker = all.firstOrNull { it.id == id } ?: all.first { it.id == SystemSpeaker.ID }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds
    @IntoSet
    abstract fun system(speaker: SystemSpeaker): Speaker
}
