package dev.wckdboy.autobot.shared.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 16_000
private const val WORD_MS = 320
private const val GAP_MS = 120
private const val PAUSE_MS = 900

private val VOCAB = listOf(
    "please", "remind", "me", "tomorrow", "morning", "to", "call", "the", "dentist", "about", "my",
    "appointment", "and", "also", "buy", "fresh", "bread", "on", "way", "home", "from", "work",
)

/**
 * Synthetic speech: each word is a tone whose loudness encodes the word, so [FakeWhisper] can
 * "recognize" it. A word cut by the end of a window comes out as a fragment, like whisper's
 * unstable last word.
 */
private object Synth {
    fun word(i: Int): FloatArray {
        val amp = 0.2f + 0.02f * i
        return FloatArray(RATE * WORD_MS / 1000) { n -> (amp * sin(2 * PI * 220 * n / RATE)).toFloat() }
    }

    fun quiet(ms: Int) = FloatArray(RATE * ms / 1000) { n -> if (n % 7 == 0) 0.004f else -0.003f }

    /** Sentences of word indices, a pause after each. */
    fun speech(sentences: List<List<Int>>, leadMs: Int = 600): FloatArray {
        val parts = mutableListOf(quiet(leadMs))
        sentences.forEach { s ->
            s.forEachIndexed { k, w ->
                parts += word(w)
                if (k < s.lastIndex) parts += quiet(GAP_MS)
            }
            parts += quiet(PAUSE_MS)
        }
        return parts.reduce { a, b -> a + b }
    }
}

private object FakeWhisper {
    fun transcribe(samples: FloatArray): String {
        val frame = RATE / 100
        val words = mutableListOf<String>()
        var runFrames = 0
        var runPeak = 0f
        fun endRun(atEnd: Boolean) {
            if (runFrames == 0) return
            val i = ((runPeak - 0.2f) / 0.02f).roundToInt().coerceIn(0, VOCAB.lastIndex)
            val full = runFrames * 10 >= WORD_MS - 20
            words += if (full || !atEnd) VOCAB[i] else VOCAB[i].take(3)
            runFrames = 0
            runPeak = 0f
        }
        var f = 0
        while ((f + 1) * frame <= samples.size) {
            var peak = 0f
            for (n in f * frame until (f + 1) * frame) peak = maxOf(peak, abs(samples[n]))
            if (peak > 0.1f) {
                runFrames++
                runPeak = maxOf(runPeak, peak)
            } else {
                endRun(atEnd = false)
            }
            f++
        }
        endRun(atEnd = true)
        return words.joinToString(" ")
    }
}

/** Plays [audio] in real time through a [StreamingDictation] with an engine that takes [costMs] per pass. */
private class Simulation(val audio: FloatArray, config: StreamingDictation.Config = StreamingDictation.Config(), val costMs: (StreamingDictation.Pass) -> Long) {
    val dictation = StreamingDictation(config)
    val partials = mutableListOf<Pair<Long, DictationText>>()
    var maxHeld = 0
    var passes = 0
    var maxPassMs = 0L

    fun run(): String {
        val chunk = RATE / 10
        var inFlight: StreamingDictation.Pass? = null
        var doneAt = 0L
        var pos = 0
        while (pos < audio.size) {
            val n = minOf(chunk, audio.size - pos)
            dictation.append(audio.copyOfRange(pos, pos + n))
            pos += n
            val now = pos * 1000L / RATE
            maxHeld = maxOf(maxHeld, dictation.heldSamples)
            val current = inFlight
            if (current != null && now >= doneAt) {
                val text = dictation.onResult(current, FakeWhisper.transcribe(current.samples), "en")
                partials += now to text
                inFlight = null
            }
            if (inFlight == null) {
                dictation.nextPass()?.let {
                    inFlight = it
                    passes++
                    maxPassMs = maxOf(maxPassMs, it.durationMs)
                    doneAt = now + costMs(it)
                }
            }
        }
        val last = dictation.finish() ?: return dictation.result
        return dictation.onFinal(last, FakeWhisper.transcribe(last.samples))
    }
}

class StreamingDictationTest {
    private val sentence1 = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
    private val sentence2 = listOf(9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21)

    private fun words(vararg s: List<Int>) = s.toList().flatten().joinToString(" ") { VOCAB[it] }

    @Test
    fun liveTextGrowsAndTheFinalTranscriptIsComplete() {
        val sim = Simulation(Synth.speech(listOf(sentence1, sentence2))) { 150 + it.durationMs / 20 }
        val final = sim.run()
        assertEquals(words(sentence1, sentence2), final)
        assertTrue("partials ${sim.partials.size}", sim.partials.size >= 5)
        // Committed text only ever grows.
        sim.partials.map { it.second.committed }.zipWithNext().forEach { (a, b) -> assertTrue("'$a' -> '$b'", b.startsWith(a)) }
        // Fragments ("tom" of "tomorrow") stay tentative; they are never committed.
        assertTrue(sim.partials.all { p -> Stitch.words(p.second.committed).all { it in VOCAB } })
    }

    @Test
    fun firstPartialArrivesWithinAboutASecondOfSpeech() {
        val costMs = 200L
        val sim = Simulation(Synth.speech(listOf(sentence1))) { costMs }
        sim.run()
        val onset = sim.dictation.firstSpeechAtMs
        val first = sim.partials.first { !it.second.isEmpty }.first
        val ttfp = first - onset
        println("time to first partial (simulated engine $costMs ms/pass, 100 ms audio chunks): $ttfp ms")
        assertTrue("ttfp $ttfp", ttfp <= 450 + costMs + 200)
    }

    @Test
    fun aLongMonologueKeepsMemoryAndPassesBounded() {
        // ~70 s: the two sentences over and over, with pauses.
        val sentences = List(8) { if (it % 2 == 0) sentence1 else sentence2 }
        val sim = Simulation(Synth.speech(sentences)) { 150 + it.durationMs / 20 }
        val final = sim.run()
        assertEquals(sentences.joinToString(" ") { s -> s.joinToString(" ") { VOCAB[it] } }, final)
        val maxWindow = StreamingDictation.Config().maxWindowMs
        assertTrue("held ${sim.maxHeld}", sim.maxHeld <= RATE * (maxWindow + 1_000) / 1000)
        // Windows close at pauses: no pass is anywhere near the max.
        assertTrue("longest pass ${sim.maxPassMs} ms", sim.maxPassMs < 8_000)
    }

    @Test
    fun speechWithoutPausesIsCutAtAQuietMomentAndNothingIsLost() {
        // 40 s with only short gaps between words: the window must close by itself.
        val words = List(90) { it % VOCAB.size }
        val sim = Simulation(Synth.speech(listOf(words))) { 150 + it.durationMs / 20 }
        val final = sim.run()
        assertEquals(words.joinToString(" ") { VOCAB[it] }, final)
        // One window plus the audio that arrived while it was closed.
        assertTrue("held ${sim.maxHeld}", sim.maxHeld <= RATE * 23)
    }

    @Test
    fun silenceRunsNoPassAndKeepsOnlyThePreroll() {
        val d = StreamingDictation()
        repeat(100) { d.append(Synth.quiet(100)) }
        assertNull(d.nextPass())
        assertTrue(d.heldSamples <= RATE)
        assertNull(d.finish())
        assertEquals("", d.result)
    }

    @Test
    fun aSlowEngineIsNeverAskedTwiceAtOnce() {
        val d = StreamingDictation()
        val audio = Synth.speech(listOf(sentence1))
        var pass: StreamingDictation.Pass? = null
        var asked = 0
        audio.toList().chunked(1600).forEach { c ->
            d.append(c.toFloatArray())
            val p = d.nextPass()
            if (p != null) {
                assertNull("second pass while one runs", pass)
                pass = p
                asked++
            }
        }
        assertEquals(1, asked)
        // The stale result after the user stopped is ignored; the final pass decides.
        val last = d.finish()!!
        d.onResult(pass!!, "garbage")
        assertEquals(words(sentence1), d.onFinal(last, FakeWhisper.transcribe(last.samples)))
    }

    @Test
    fun locksTheLanguageOnceWordsAreHeard() {
        val d = StreamingDictation()
        d.append(Synth.speech(listOf(sentence1)).copyOfRange(0, RATE * 2))
        val p = d.nextPass()!!
        assertNull(p.language)
        d.onResult(p, "please remind me", "de")
        assertEquals("de", d.language)
    }

    @Test
    fun whisperNoiseMarkersAreNotText() {
        val d = StreamingDictation()
        d.append(Synth.speech(listOf(sentence1)).copyOfRange(0, RATE * 2))
        val p = d.nextPass()!!
        assertTrue(d.onResult(p, "[BLANK_AUDIO]").isEmpty)
    }
}

class LocalAgreementTest {
    @Test
    fun commitsWhatTwoHypothesesAgreeOn() {
        val a = LocalAgreement()
        assertEquals("" to "please rem", a.update("please rem"))
        assertEquals("Please," to "remind me", a.update("Please, remind me"))
        assertEquals("Please, remind me" to "tomorrow", a.update("please remind me tomorrow"))
        // A revision of committed words does not change them.
        assertEquals("Please, remind me" to "to tomorrow morning", a.update("Please remind me to tomorrow morning"))
    }

    @Test
    fun keepsTheLatestSpellingOfAgreedWords() {
        val a = LocalAgreement()
        a.update("hello world foo")
        assertEquals("Hello, world" to "bar", a.update("Hello, world bar"))
    }
}

class StitchTest {
    @Test
    fun dropsWordsRepeatedAtTheSeam() {
        assertEquals("call the dentist about my appointment", Stitch.join("call the dentist about", "dentist about my appointment"))
        assertEquals("buy fresh bread tomorrow", Stitch.join("buy fresh bread", "bread tomorrow")) // one long word
        assertEquals("no no", Stitch.join("no", "no")) // short words may be real repeats
        assertEquals("a b", Stitch.join("  a ", " b  "))
        assertEquals("b", Stitch.join("", "b"))
    }
}

class VoiceActivityTest {
    @Test
    fun aNoisyRoomRaisesTheGate() {
        val v = VoiceActivity()
        repeat(400) { v.isSpeech(0.04f) }
        assertFalse(v.isSpeech(0.06f))
        assertTrue(v.isSpeech(0.2f))
    }
}
