package dev.wckdboy.autobot.feature.chat.voice

import dev.wckdboy.autobot.engine.speech.SpeechException
import dev.wckdboy.autobot.engine.speech.SpeechText
import dev.wckdboy.autobot.shared.voice.DictationText
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDictationTest {
    /** 0.4 s quiet, [speechMs] of loud tone, 1 s quiet, in 100 ms buffers, each after [pace] ms. */
    private class FakeEngine(
        private val speechMs: Int = 2_500,
        private val pace: Long = 15,
        private val fail: (Int) -> Exception? = { null },
    ) : DictationEngine {
        val calls = AtomicInteger()
        val sizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val partial: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

        override suspend fun capture(stopped: () -> Boolean, onAudio: (FloatArray, Int) -> Unit) {
            val total = (400 + speechMs + 1_000) / 100
            val buffer = FloatArray(1_600)
            for (i in 0 until total) {
                if (stopped()) return
                val ms = i * 100
                val loud = ms >= 400 && ms < 400 + speechMs
                for (n in buffer.indices) buffer[n] = if (loud) (0.4 * sin(2 * PI * 220 * n / 16_000)).toFloat() else 0.002f
                onAudio(buffer, buffer.size)
                delay(pace)
            }
        }

        /** One word per half second of audio, like a (very) simple recognizer. */
        override suspend fun transcribe(samples: FloatArray, language: String?, partial: Boolean): SpeechText {
            val call = calls.incrementAndGet()
            sizes += samples.size
            this.partial += partial
            fail(call)?.let { throw it }
            delay(20)
            return SpeechText((1..samples.size / 8_000).joinToString(" ") { "word$it" }, "en")
        }
    }

    private fun dictation(engine: DictationEngine) = LiveDictation(engine, Dispatchers.Default, clock = System::currentTimeMillis)

    @Test
    fun showsWordsWhileCapturingAndEndsWithAFinalTranscript() = runBlocking {
        val engine = FakeEngine()
        val partials = Collections.synchronizedList(mutableListOf<DictationText>())
        val live = dictation(engine)
        val final = withTimeout(10_000) { live.run(stopped = { false }, onText = { partials += it }) }
        assertTrue("partials $partials", partials.any { !it.isEmpty })
        assertTrue(final, final.startsWith("word1"))
        assertTrue(engine.calls.get() >= 2)
        assertTrue((live.firstPartialMs ?: Long.MAX_VALUE) < 5_000)
        // No pass is longer than the window ever was.
        assertTrue(engine.sizes.all { it <= 16_000 * 4 })
    }

    @Test
    fun stoppingEarlyRunsOneLastPassOverWhatIsLeft() = runBlocking {
        val engine = FakeEngine(speechMs = 6_000)
        var buffers = 0
        val final = withTimeout(10_000) {
            dictation(engine).run(stopped = { ++buffers > 30 })
        }
        // ~2.6 s of speech heard before the stop.
        assertTrue(final, final.isNotBlank())
        // Live passes run as partial passes (shorter encoder context); the last one, final, does not.
        assertTrue(engine.partial.toString(), engine.partial.dropLast(1).isNotEmpty() && engine.partial.dropLast(1).all { it })
        assertEquals(false, engine.partial.last())
    }

    @Test
    fun aFinalPassThatMetTheCancelIsRetried() = runBlocking {
        // Every partial fails (the engine was busy); the last pass is cancelled once, then works.
        val engine = FakeEngine(speechMs = 1_000, pace = 1) { call -> if (call == 1) SpeechException("cancelled") else null }
        val final = withTimeout(10_000) { dictation(engine).run(stopped = { false }) }
        assertTrue(final, final.isNotBlank())
    }

    @Test
    fun anEngineThatAlwaysFailsGivesNothingInsteadOfCrashing() = runBlocking {
        val engine = FakeEngine(speechMs = 1_000, pace = 1) { SpeechException("Speech engine failed: boom") }
        val final = withTimeout(10_000) { dictation(engine).run(stopped = { false }) }
        assertEquals("", final)
    }
}

class SpeechQueueTest {
    private class FakeSpeaker : Speaker {
        override val id = "fake"
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val prepared: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun isAvailable() = true

        override suspend fun speak(text: String) {
            spoken += text
            delay(5)
        }

        override fun prepare(text: String) {
            prepared += text
        }

        override fun stop() = Unit
    }

    @Test
    fun speaksPiecesAsTheyArriveUntilClosed() = runBlocking {
        val speaker = FakeSpeaker()
        val started = mutableListOf<Int>()
        val queue = SpeechQueue { started += it }
        queue.add(listOf("One.", "Two."))
        val job = async { queue.run(speaker) }
        delay(50)
        assertEquals(listOf("One.", "Two."), speaker.spoken.toList())
        // Still running: the answer is not complete.
        assertTrue(job.isActive)
        queue.add(listOf("Three."))
        queue.close()
        withTimeout(2_000) { job.await() }
        assertEquals(listOf("One.", "Two.", "Three."), speaker.spoken.toList())
        assertEquals(listOf("Two."), speaker.prepared.toList())
        assertEquals(listOf(0, 1, 2), started)
    }

    @Test
    fun aPieceAfterAWaitForTheAnswerIsAResume() = runBlocking {
        val speaker = FakeSpeaker()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = SpeechQueue(onResume = { events += "resume" }) { events += "piece$it" }
        queue.add(listOf("One.", "Two."))
        val job = async { queue.run(speaker) }
        delay(50)
        // The speaker went quiet waiting for more of the answer.
        queue.add(listOf("Three."))
        queue.close()
        withTimeout(2_000) { job.await() }
        assertEquals(listOf("piece0", "piece1", "resume", "piece2"), events.toList())
    }

    @Test
    fun anEmptyClosedQueueEndsAtOnce() = runBlocking {
        val queue = SpeechQueue()
        queue.close()
        withTimeout(1_000) { queue.run(FakeSpeaker()) }
    }
}
