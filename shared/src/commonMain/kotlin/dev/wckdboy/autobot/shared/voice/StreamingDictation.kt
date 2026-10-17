package dev.wckdboy.autobot.shared.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Streaming dictation, shared by both apps: live text while the user speaks, from a sliding
// window over the microphone that whisper.cpp transcribes again every ~0.7 s. The apps only
// capture audio and run the engine; windowing, voice activity, agreement and stitching are here.
// Audio lives in memory, in one bounded window, and is never stored or sent.

/** Live dictation text: [committed] will not change any more; [tentative] may still. */
data class DictationText(val committed: String = "", val tentative: String = "") {
    /** Both, joined. */
    val full: String get() = Stitch.join(committed, tentative)

    val isEmpty: Boolean get() = committed.isBlank() && tentative.isBlank()
}

/** Joins transcripts of consecutive audio windows. */
object Stitch {
    /** Longest run of repeated words dropped at a seam. */
    private const val MAX_OVERLAP = 6

    private val space = Regex("\\s+")

    fun words(text: String): List<String> = text.trim().split(space).filter { it.isNotEmpty() }

    /** A word as compared: lowercase letters and digits only ("Hello," == "hello"). */
    fun norm(word: String): String = word.lowercase().filter { it.isLetterOrDigit() }

    /**
     * [a] then [b] with single spaces, dropping the start of [b] when it repeats the end of [a]
     * (a word cut in two windows is transcribed twice). One repeated word is only dropped when it
     * is long enough not to be a real "the the" or "no, no".
     */
    fun join(a: String, b: String): String {
        val aw = words(a)
        val bw = words(b)
        if (aw.isEmpty()) return bw.joinToString(" ")
        if (bw.isEmpty()) return aw.joinToString(" ")
        var overlap = 0
        for (k in min(MAX_OVERLAP, min(aw.size, bw.size)) downTo 1) {
            val same = (0 until k).all { norm(aw[aw.size - k + it]).let { w -> w.isNotEmpty() && w == norm(bw[it]) } }
            if (same && (k > 1 || norm(bw[0]).length >= 5)) {
                overlap = k
                break
            }
        }
        return (aw + bw.drop(overlap)).joinToString(" ")
    }
}

/**
 * LocalAgreement-2 over word hypotheses of one growing audio window: a word is committed once two
 * consecutive transcriptions agree on it (and on everything before it); the rest is tentative.
 * Committed words keep the spelling of the latest hypothesis that confirmed them.
 */
class LocalAgreement {
    private var committed = emptyList<String>()
    private var previous = emptyList<String>()

    val committedText: String get() = committed.joinToString(" ")
    val tentativeText: String get() = previous.joinToString(" ")

    /** The latest whole-window hypothesis; returns (committed, tentative). */
    fun update(hypothesis: String): Pair<String, String> {
        val tail = afterCommitted(Stitch.words(hypothesis))
        var n = 0
        while (n < tail.size && n < previous.size && Stitch.norm(tail[n]) == Stitch.norm(previous[n])) n++
        committed = committed + tail.take(n)
        previous = tail.drop(n)
        return committedText to tentativeText
    }

    fun reset() {
        committed = emptyList()
        previous = emptyList()
    }

    /** The hypothesis' words after those that repeat [committed] (allowing a word more or less). */
    private fun afterCommitted(words: List<String>): List<String> {
        val c = committed.size
        if (c == 0) return words
        val last = Stitch.norm(committed.last())
        val tries = listOf(c) + (1..2).flatMap { listOf(c + it, c - it) }
        val k = tries.firstOrNull { it in 1..words.size && Stitch.norm(words[it - 1]) == last } ?: c
        return words.drop(min(k, words.size))
    }
}

/**
 * Voice activity from the peak level (0…1) of short frames: speech is a frame above the gate,
 * which is [threshold] or three times the noise floor (capped), the floor following the quietest
 * frames and rising slowly in a noisier room.
 */
class VoiceActivity(private val threshold: Float = 0.05f) {
    private var floor = Float.MAX_VALUE

    val gate: Float get() = max(threshold, min(if (floor == Float.MAX_VALUE) 0f else floor * 3, 0.3f))

    fun isSpeech(peak: Float): Boolean {
        val loud = peak >= gate
        floor = if (peak < floor) peak else floor + (peak - floor) * FLOOR_RISE
        return loud
    }

    private companion object {
        /** Per frame: a floor of 0.01 under steady 0.05 noise reaches it in ~4 s of 50 ms frames. */
        const val FLOOR_RISE = 0.01f
    }
}

/**
 * The sliding-window state machine behind streaming dictation. Feed it 16 kHz mono samples
 * ([append]); ask it for the next transcription to run ([nextPass], at most one in flight), give
 * it the result ([onResult]) and read [text]. At the end, [finish] gives the last pass to run (or
 * none when the text is already complete) and [onFinal] the final transcript.
 *
 * The window starts with speech (plus a short preroll) and closes when a transcription ends in a
 * pause: its text is then final and the next window starts after it. A window that reaches
 * [Config.maxWindowMs] without a pause is closed at its quietest recent moment. So memory is one
 * window (≤ ~25 s) and each pass costs at most one window, however long the user talks.
 */
class StreamingDictation(private val config: Config = Config()) {
    /** The default configuration (Swift sees no default arguments). */
    constructor() : this(Config())

    data class Config(
        val sampleRate: Int = 16_000,
        /** Speech heard before the first partial transcription (whisper needs a word or two). */
        val firstPassSpeechMs: Int = 450,
        /** New audio between partial transcriptions. */
        val stepMs: Int = 700,
        /** Quiet at the end of a transcribed window that closes it. */
        val pauseMs: Int = 600,
        /** Longest window before it is closed without a pause. */
        val maxWindowMs: Int = 20_000,
        /** Where to look back for the quietest moment when closing a long window. */
        val cutSearchMs: Int = 4_000,
        /** Audio kept before speech starts. */
        val prerollMs: Int = 300,
        /** Voice activity frame. */
        val frameMs: Int = 50,
        val threshold: Float = 0.05f,
    )

    enum class Kind {
        /** Transcribe the window so far for live text. */
        PARTIAL,

        /** Transcribe the window up to a cut; its text is final. */
        CLOSE,

        /** The last pass when the user stopped. */
        FINAL,
    }

    /** One transcription to run: [samples] (16 kHz mono), in [language] (null: detect). */
    class Pass internal constructor(
        val id: Int,
        val kind: Kind,
        val samples: FloatArray,
        val language: String?,
        internal val end: Long,
    ) {
        /** Audio length in milliseconds. */
        val durationMs: Long get() = samples.size * 1000L / 16_000
    }

    private val frame = config.sampleRate * config.frameMs / 1000
    private val vad = VoiceActivity(config.threshold)
    private val agreement = LocalAgreement()

    /** The current window's audio: absolute samples [windowStart, written). */
    private var buffer = FloatArray(config.sampleRate * 4)
    private var windowStart = 0L
    private var written = 0L

    /** Speech per frame since [windowStart] (frame i covers samples windowStart + i * frame). */
    private var speech = BooleanArray(256)
    private var peaks = FloatArray(256)
    private var frames = 0
    private var framePeak = 0f
    private var frameFill = 0

    /** Absolute sample just after the last speech frame in the window (-1: none yet). */
    private var lastSpeechEnd = -1L
    private var speechFrames = 0
    private var inFlight: Pass? = null
    private var lastPassEnd = -1L
    /** End of the last partial whose words were heard (-1: none in this window). */
    private var heardUpTo = -1L
    private var nextId = 1
    private var finalText = ""
    private var finished = false

    /** Language detected by the first transcription with words; later passes use it. */
    var language: String? = null
        private set

    /** Absolute time (ms of audio) when speech was first heard, or -1. */
    var firstSpeechAtMs: Long = -1
        private set

    /** Audio received so far, in milliseconds. */
    val audioMs: Long get() = written * 1000 / config.sampleRate

    /** The live text: closed windows and agreed words, then the tentative rest. */
    val text: DictationText
        get() = DictationText(Stitch.join(finalText, agreement.committedText), agreement.tentativeText)

    /** Samples held now (for tests: bounded by the window, not the dictation's length). */
    val heldSamples: Int get() = (written - windowStart).toInt()

    /** Appends [count] samples of 16 kHz mono audio. */
    fun append(samples: FloatArray, count: Int = samples.size) {
        if (finished || count <= 0) return
        // A transcription that never returns must not let the window grow without end.
        if (written - windowStart > ms(config.maxWindowMs + HARD_SLACK_MS)) advanceTo(written - ms(config.maxWindowMs))
        ensureRoom(count)
        samples.copyInto(buffer, (written - windowStart).toInt(), 0, count)
        for (i in 0 until count) {
            val s = samples[i]
            if (s.isFinite()) framePeak = max(framePeak, abs(s))
            if (++frameFill == frame) endFrame()
        }
        written += count
        if (speechFrames == 0) trimSilence()
    }

    private fun endFrame() {
        val loud = vad.isSpeech(framePeak)
        if (frames == speech.size) {
            speech = speech.copyOf(frames * 2)
            peaks = peaks.copyOf(frames * 2)
        }
        speech[frames] = loud
        peaks[frames] = framePeak
        frames++
        if (loud) {
            speechFrames++
            // The frame ends at the sample being appended now (computed from the frame count).
            lastSpeechEnd = windowStart + frames.toLong() * frame
            if (firstSpeechAtMs < 0) firstSpeechAtMs = (lastSpeechEnd - frame) * 1000 / config.sampleRate
        }
        framePeak = 0f
        frameFill = 0
    }

    /** The next transcription to run now, or null (one in flight, too little new audio, no speech). */
    fun nextPass(): Pass? {
        if (finished || inFlight != null || speechFrames == 0) return null
        // The last transcription heard everything before a pause that has now lasted long enough:
        // its text is final, without another pass.
        if (heardUpTo >= windowStart && endsInPause(heardUpTo)) {
            close("", heardUpTo)
            if (speechFrames == 0) return null
        }
        val length = written - windowStart
        if (length >= ms(config.maxWindowMs)) return start(Kind.CLOSE, quietCut())
        val speechMs = speechFrames.toLong() * config.frameMs
        val fresh = written - max(lastPassEnd, windowStart)
        val due = if (lastPassEnd < windowStart) speechMs >= config.firstPassSpeechMs else fresh >= ms(config.stepMs)
        // A pause after unheard speech: transcribe now, the window can close on it.
        val pausedAfterSpeech = lastSpeechEnd > lastPassEnd && written - lastSpeechEnd >= ms(config.pauseMs) && fresh > 0
        return if (due || pausedAfterSpeech) start(Kind.PARTIAL, written) else null
    }

    /**
     * The result of [pass] (whisper's raw text). Returns the live text. A result for a pass that
     * is no longer the one in flight (after [finish]) is ignored.
     */
    fun onResult(pass: Pass, raw: String, detectedLanguage: String? = null): DictationText {
        if (pass !== inFlight || pass.kind == Kind.FINAL) return text
        inFlight = null
        val heard = VoiceText.transcript(raw)
        if (language == null && !detectedLanguage.isNullOrBlank() && Stitch.words(heard).size >= 2) language = detectedLanguage
        when {
            pass.kind == Kind.CLOSE -> close(heard, pass.end)
            heard.isNotEmpty() && endsInPause(pass.end) -> close(heard, pass.end)
            else -> {
                agreement.update(heard)
                if (heard.isNotEmpty()) heardUpTo = pass.end
            }
        }
        return text
    }

    /** The transcription failed or was dropped: the next [nextPass] may retry. */
    fun onFailed(pass: Pass) {
        if (pass === inFlight) inFlight = null
    }

    /**
     * The user stopped: the last pass to run over what is left (null when nothing is left to
     * transcribe, or the last partial already covered all the speech; [result] is then final).
     */
    fun finish(): Pass? {
        if (finished) return null
        if (frameFill > 0) endFrame()
        finished = true
        inFlight = null
        val covered = heardUpTo >= lastSpeechEnd && heardUpTo >= windowStart
        if (speechFrames == 0 || covered) {
            if (speechFrames == 0) agreement.reset()
            closeWindowText("")
            return null
        }
        return Pass(nextId++, Kind.FINAL, window(written), language, written).also { inFlight = it }
    }

    /** The final transcript from the [finish] pass's [raw] text. */
    fun onFinal(pass: Pass, raw: String): String {
        if (pass === inFlight) {
            inFlight = null
            // An empty last pass keeps what the partials heard.
            closeWindowText(VoiceText.transcript(raw))
        }
        return finalText
    }

    /** The final transcript when [finish] returned null, or after [onFinal]. */
    val result: String get() = if (finished && inFlight == null) finalText else text.full

    private fun start(kind: Kind, end: Long): Pass {
        val pass = Pass(nextId++, kind, window(end), language, end)
        inFlight = pass
        if (kind == Kind.PARTIAL) lastPassEnd = end
        return pass
    }

    private fun window(end: Long): FloatArray = buffer.copyOfRange(0, (end - windowStart).toInt())

    private fun ms(value: Int): Long = value.toLong() * config.sampleRate / 1000

    /** Whether the audio before [end] is quiet for [Config.pauseMs] after speech. */
    private fun endsInPause(end: Long): Boolean {
        if (lastSpeechEnd < 0) return false
        val lastBefore = lastSpeechBefore(end)
        if (lastBefore < 0) return false
        // The quiet after the pass's last word: until the next speech frame, or until now.
        var f = ((lastBefore - windowStart) / frame).toInt()
        while (f < frames && !speech[f]) f++
        val quietEnd = if (f < frames) windowStart + f.toLong() * frame else written
        return quietEnd - lastBefore >= ms(config.pauseMs)
    }

    /** The end of the last speech frame that ends at or before [end] (-1: none). */
    private fun lastSpeechBefore(end: Long): Long {
        var f = min(frames, ((end - windowStart) / frame).toInt()) - 1
        while (f >= 0 && !speech[f]) f--
        return if (f < 0) -1 else windowStart + (f + 1L) * frame
    }

    /** Closes the window at [cut] with [heard] as its final text; the audio after it starts the next one. */
    private fun close(heard: String, cut: Long) {
        closeWindowText(heard)
        advanceTo(cut)
    }

    /** The window's text becomes final: its committed words (never revised) and [heard]'s rest. */
    private fun closeWindowText(heard: String) {
        if (heard.isNotEmpty()) agreement.update(heard)
        finalText = Stitch.join(finalText, Stitch.join(agreement.committedText, agreement.tentativeText))
        agreement.reset()
        heardUpTo = -1
    }

    /** The quietest frame boundary in the last [Config.cutSearchMs] of the window. */
    private fun quietCut(): Long {
        val from = max(0, frames - config.cutSearchMs / config.frameMs)
        var best = frames
        var bestPeak = Float.MAX_VALUE
        for (f in from until frames) {
            if (peaks[f] <= bestPeak) {
                bestPeak = peaks[f]
                best = f
            }
        }
        return windowStart + best.toLong() * frame
    }

    /** Drops audio before [cut] (frame aligned) and rebuilds the per-frame state for the rest. */
    private fun advanceTo(cut: Long) {
        val dropFrames = ((cut - windowStart) / frame).toInt().coerceIn(0, frames)
        val drop = dropFrames.toLong() * frame
        val keep = (written - windowStart - drop).toInt()
        buffer.copyInto(buffer, 0, drop.toInt(), drop.toInt() + keep)
        windowStart += drop
        speech.copyInto(speech, 0, dropFrames, frames)
        peaks.copyInto(peaks, 0, dropFrames, frames)
        frames -= dropFrames
        speechFrames = (0 until frames).count { speech[it] }
        lastSpeechEnd = if (speechFrames == 0) -1 else lastSpeechBefore(windowStart + frames.toLong() * frame)
    }

    /** No speech in the window: keep only the preroll. */
    private fun trimSilence() {
        val keepFrames = config.prerollMs / config.frameMs
        if (frames > keepFrames * 2) advanceTo(windowStart + (frames - keepFrames).toLong() * frame)
    }

    private fun ensureRoom(count: Int) {
        val needed = (written - windowStart).toInt() + count
        if (needed <= buffer.size) return
        // Grows up to a window and some slack; passes are serial, so this stays bounded.
        buffer = buffer.copyOf(max(needed, min(buffer.size * 2, ms(config.maxWindowMs + HARD_SLACK_MS).toInt())))
    }

    private companion object {
        /** Audio beyond a full window kept while a transcription is still running. */
        const val HARD_SLACK_MS = 10_000
    }
}
