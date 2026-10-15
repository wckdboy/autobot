package dev.wckdboy.autobot.feature.chat.voice

import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.tts.LocalVoice
import dev.wckdboy.autobot.engine.tts.VoiceException
import dev.wckdboy.autobot.engine.tts.VoicePlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach

/** What [KokoroSpeaker] drives: the installed voice, the synthesizer and the audio output. */
internal interface KokoroEngine {
    suspend fun folder(): File?

    /** Speech for [text], sentence by sentence (playback starts after the first). */
    fun stream(folder: File, text: String): Flow<FloatArray>

    /** All of [text] at once (a piece prepared while another plays). */
    suspend fun whole(folder: File, text: String): FloatArray

    suspend fun play(audio: Flow<FloatArray>)

    fun stopPlayback()
}

/**
 * Kokoro-82M, the downloadable neural voice (Models › voice): ONNX on the CPU, entirely on the
 * device. While one piece plays, [prepare] synthesizes the next, so long answers have no gaps.
 *
 * Prepared pieces are kept by text until [speak] consumes them: [Speaker.read] hints piece i+1
 * before speaking piece i, so a single "next" slot would drop (and re-synthesize) every piece.
 * A prepared piece only starts synthesizing once the current one has its first audio, so it
 * never delays the start of speech.
 */
@Singleton
class KokoroSpeaker internal constructor(private val engine: KokoroEngine) : Speaker {

    @Inject
    constructor(library: ModelLibrary, voice: LocalVoice) : this(LocalKokoroEngine(library, voice))

    override val id = ID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Prepared pieces by text, oldest first; guarded by itself. */
    private val prepared = LinkedHashMap<String, Deferred<FloatArray>>()

    override suspend fun isAvailable(): Boolean = engine.folder() != null

    override fun prepare(text: String) {
        synchronized(prepared) {
            if (text in prepared) return
            prepared[text] = scope.async(start = CoroutineStart.LAZY) {
                val dir = engine.folder() ?: throw SpeakerUnavailable(MISSING)
                engine.whole(dir, text)
            }
            while (prepared.size > MAX_PREPARED) {
                val oldest = prepared.keys.first()
                prepared.remove(oldest)?.cancel()
            }
        }
    }

    /** Lets the prepared pieces synthesize (the current one is under way). */
    private fun startPrepared() = synchronized(prepared) { prepared.values.forEach { it.start() } }

    override suspend fun speak(text: String) {
        val ready = synchronized(prepared) { prepared.remove(text) }
        try {
            val audio = if (ready != null) {
                ready.start()
                val pcm = ready.await()
                startPrepared()
                flowOf(pcm)
            } else {
                engine.stream(engine.folder() ?: throw SpeakerUnavailable(MISSING), text).onEach { startPrepared() }
            }
            engine.play(audio)
        } catch (e: CancellationException) {
            ready?.cancel()
            engine.stopPlayback()
            throw e
        } catch (e: SpeakerUnavailable) {
            throw e
        } catch (e: VoiceException) {
            throw SpeakerUnavailable("The voice failed: ${e.message}")
        } catch (e: Exception) {
            throw SpeakerUnavailable("The voice failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            throw SpeakerUnavailable("The voice engine is not available on this phone")
        } catch (e: OutOfMemoryError) {
            throw SpeakerUnavailable("Not enough memory for the voice")
        }
    }

    override fun stop() {
        synchronized(prepared) {
            prepared.values.forEach { it.cancel() }
            prepared.clear()
        }
        engine.stopPlayback()
    }

    companion object {
        const val ID = "kokoro"
        const val VOICE = "af_heart"
        private const val MISSING = "The voice model is missing. Get one in Models › voice"

        /** Pieces kept ready ahead of the one playing. */
        private const val MAX_PREPARED = 2
    }
}

private class LocalKokoroEngine(private val library: ModelLibrary, private val voice: LocalVoice) : KokoroEngine {
    private val player = VoicePlayer()

    override suspend fun folder(): File? = library.all()
        .firstOrNull { it.isReady && it.kind == ModelKind.VOICE && it.engine == EngineKind.ONNX }
        ?.paths?.get(FileRole.MODEL)?.let { File(it).parentFile }

    override fun stream(folder: File, text: String): Flow<FloatArray> = voice.synthesize(folder, text, KokoroSpeaker.VOICE)

    override suspend fun whole(folder: File, text: String): FloatArray = voice.synthesizeAll(folder, text, KokoroSpeaker.VOICE)

    override suspend fun play(audio: Flow<FloatArray>) = player.play(audio)

    override fun stopPlayback() = player.stop()
}
