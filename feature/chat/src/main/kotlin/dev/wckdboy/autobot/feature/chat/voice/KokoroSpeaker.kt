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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf

/**
 * Kokoro-82M, the downloadable neural voice (Models › voice): ONNX on the CPU, entirely on the
 * device. While one piece plays, [prepare] synthesizes the next, so long answers have no gaps.
 */
@Singleton
class KokoroSpeaker @Inject constructor(
    private val library: ModelLibrary,
    private val voice: LocalVoice,
) : Speaker {
    override val id = ID

    private val player = VoicePlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var next: Pair<String, Deferred<FloatArray>>? = null

    private suspend fun folder(): File? = library.all()
        .firstOrNull { it.isReady && it.kind == ModelKind.VOICE && it.engine == EngineKind.ONNX }
        ?.paths?.get(FileRole.MODEL)?.let { File(it).parentFile }

    override suspend fun isAvailable(): Boolean = folder() != null

    override fun prepare(text: String) {
        next?.second?.cancel()
        next = text to scope.async {
            val dir = folder() ?: throw SpeakerUnavailable(MISSING)
            voice.synthesizeAll(dir, text, VOICE)
        }
    }

    override suspend fun speak(text: String) {
        val ready = next?.takeIf { it.first == text }?.second
        next = null
        try {
            val audio = if (ready != null) {
                flowOf(ready.await())
            } else {
                voice.synthesize(folder() ?: throw SpeakerUnavailable(MISSING), text, VOICE)
            }
            player.play(audio)
        } catch (e: CancellationException) {
            player.stop()
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
        next?.second?.cancel()
        next = null
        player.stop()
    }

    companion object {
        const val ID = "kokoro"
        const val VOICE = "af_heart"
        private const val MISSING = "The voice model is missing. Get one in Models › voice"
    }
}
