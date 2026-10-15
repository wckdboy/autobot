package dev.wckdboy.autobot.feature.chat.voice

import dev.wckdboy.autobot.shared.voice.VoiceText
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class KokoroSpeakerTest {

    /** Counts every synthesis; "plays" by collecting the audio. */
    private class FakeEngine : KokoroEngine {
        val streamed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val whole: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val played = AtomicInteger()

        override suspend fun folder(): File = File("kokoro")

        override fun stream(folder: File, text: String): Flow<FloatArray> = flow {
            streamed += text
            emit(FloatArray(4))
        }

        override suspend fun whole(folder: File, text: String): FloatArray {
            whole += text
            return FloatArray(4)
        }

        override suspend fun play(audio: Flow<FloatArray>) {
            audio.collect { played.incrementAndGet() }
        }

        override fun stopPlayback() = Unit

        val synthesized: List<String> get() = streamed + whole
    }

    private val answer = """
        The first sentence of the answer is long enough to stand on its own.
        The second sentence follows it and is just as long as the first one.
        The third sentence closes the answer and is long enough as well, too.
        The fourth sentence is here so that more than one piece waits ahead.
    """.trimIndent()

    @Test
    fun readSynthesizesEverySentenceOnce() = runBlocking {
        val engine = FakeEngine()
        val chunks = VoiceText.chunks(VoiceText.speakable(answer))
        assertEquals(4, chunks.size)

        KokoroSpeaker(engine).read(answer)

        // Before the fix: 4 streamed + 3 prepared (all dropped) = 7 syntheses for 4 sentences.
        assertEquals(chunks.size, engine.synthesized.size)
        assertEquals(chunks.toSet(), engine.synthesized.toSet())
        // Only the first piece streams; every later one was prepared while the previous played.
        assertEquals(listOf(chunks.first()), engine.streamed.toList())
        assertEquals(chunks.size, engine.played.get())
    }

    @Test
    fun preparingTwiceSynthesizesOnce() = runBlocking {
        val engine = FakeEngine()
        val speaker = KokoroSpeaker(engine)
        speaker.prepare("one piece")
        speaker.prepare("one piece")
        speaker.speak("one piece")
        assertEquals(listOf("one piece"), engine.synthesized)
    }

    @Test
    fun stopDropsPreparedPieces() = runBlocking {
        val engine = FakeEngine()
        val speaker = KokoroSpeaker(engine)
        speaker.prepare("never spoken")
        speaker.stop()
        speaker.speak("spoken")
        assertEquals(listOf("spoken"), engine.synthesized)
    }
}
