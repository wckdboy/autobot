package dev.wckdboy.autobot.shared.voice

import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.SessionItem

// Voice mode's pure logic, shared by both apps: what to read aloud and in which pieces, when the
// speaker has finished talking, and when the agent's turn is over. The audio itself (microphone,
// whisper.cpp, text-to-speech) stays in each app; nothing here leaves the device.

/** Markdown → text a speech engine can read, and that text in sentence-sized pieces. */
object VoiceText {
    /** Longest piece handed to a speech engine: speech starts sooner and stop stays responsive. */
    const val MAX_CHUNK = 240

    /** Pieces shorter than this are merged into the previous one (fewer pauses), except the first. */
    private const val MIN_CHUNK = 24

    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val html = Regex("</?[A-Za-z][^>\\n]*>")
    private val rule = Regex("^\\s*([-*_]\\s*){3,}$")
    private val tableDivider = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val heading = Regex("^\\s*#{1,6}\\s+")
    private val quote = Regex("^\\s*(>\\s*)+")
    private val bullet = Regex("^\\s*([-*+]|\\d{1,3}[.)])\\s+(\\[[ xX]]\\s+)?")
    private val bold = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val boldUnderscore = Regex("__([^_\\n]+)__")
    private val italic = Regex("\\*([^*\\n]+)\\*")
    private val italicUnderscore = Regex("(^|[^A-Za-z0-9])_([^_\\n]+)_")
    private val strike = Regex("~~([^~\\n]+)~~")
    private val inlineCode = Regex("`([^`\\n]+)`")
    private val stray = Regex("[*`~]+")
    private val spaces = Regex("[ \\t]+")

    /** Whisper's markers for "nothing said": `[BLANK_AUDIO]`, `(music)`, `[ Silence ]`. */
    private val noise = Regex("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*")

    /**
     * Plain text for [markdown]: code blocks become "code block.", links their text, URLs "link";
     * emphasis, headings, quotes, bullets and table pipes go. Headings, list items and table rows
     * end with a full stop so the engine pauses and [chunks] splits there. Lines are kept.
     */
    fun speakable(markdown: String): String {
        var s = markdown.replace("\r\n", "\n")
        s = fence.replace(s, "\ncode block.\n")
        s = image.replace(s, "$1")
        s = link.replace(s, "$1")
        s = url.replace(s, "link")
        s = html.replace(s, "")
        val lines = s.split('\n').mapNotNull { raw ->
            if (rule.matches(raw) || tableDivider.matches(raw)) return@mapNotNull null
            var line = raw
            var item = false
            if (heading.containsMatchIn(line)) {
                line = heading.replace(line, "")
                item = true
            }
            line = quote.replace(line, "")
            if (bullet.containsMatchIn(line)) {
                line = bullet.replace(line, "")
                item = true
            }
            if (line.count { it == '|' } >= 2) {
                line = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                item = true
            }
            line = bold.replace(line, "$1")
            line = boldUnderscore.replace(line, "$1")
            line = italic.replace(line, "$1")
            line = italicUnderscore.replace(line, "$1$2")
            line = strike.replace(line, "$1")
            line = inlineCode.replace(line, "$1")
            line = stray.replace(line, "")
            line = spaces.replace(line, " ").trim()
            when {
                line.isEmpty() -> null
                item && line.last() !in ".!?:;,…" -> "$line."
                else -> line
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Splits [text] (from [speakable]) into sentences, each at most [maxChars]: at sentence ends
     * (not after abbreviations, initials or before a lowercase word) and line breaks; over-long
     * sentences at a comma or semicolon, else a space. Short pieces join the one before them.
     */
    fun chunks(text: String, maxChars: Int = MAX_CHUNK): List<String> {
        // A limit below one would never shorten a piece (or cut before its start).
        val max = maxChars.coerceAtLeast(1)
        return merge(sentenceList(text, max), max, keepFirst = true)
    }

    /** [text]'s sentences (line by line), each at most [maxChars], before [chunks] merges the short ones. */
    internal fun sentenceList(text: String, maxChars: Int = MAX_CHUNK): List<String> {
        val max = maxChars.coerceAtLeast(1)
        return text.split('\n').flatMap { sentences(it.trim()) }.flatMap { split(it, max) }
    }

    /**
     * Joins pieces shorter than [MIN_CHUNK] to the one before them while the result fits [max].
     * [keepFirst]: the first piece stays on its own (speech starts sooner).
     */
    internal fun merge(pieces: List<String>, max: Int, keepFirst: Boolean): List<String> {
        val out = mutableListOf<String>()
        for (s in pieces) {
            val last = out.lastOrNull()
            if (last != null && (out.size > 1 || !keepFirst) && minOf(s.length, last.length) < MIN_CHUNK && last.length + 1 + s.length <= max) {
                out[out.size - 1] = "$last $s"
            } else {
                out += s
            }
        }
        return out
    }

    /** What whisper heard, without its non-speech markers; empty when nothing was said. */
    fun transcript(raw: String): String = spaces.replace(noise.replace(raw, " "), " ").trim().let { t ->
        if (t.none { it.isLetterOrDigit() }) "" else t
    }

    private val abbreviations = setOf(
        "e.g", "i.e", "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "no", "fig", "approx", "dept", "est", "inc", "ltd", "co", "cf",
    )

    private val dotted = Regex("^(\\p{L}{1,2}\\.)+\\p{L}{1,2}$")

    private fun sentences(paragraph: String): List<String> {
        if (paragraph.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            val wide = c == '。' || c == '！' || c == '？'
            if (c == '.' || c == '!' || c == '?' || c == '…' || wide) {
                var end = i + 1
                while (end < paragraph.length && paragraph[end] in ".!?…\"'”’)]") end++
                val atBoundary = end >= paragraph.length || paragraph[end].isWhitespace() || wide
                if (atBoundary && (c != '.' || isSentenceEnd(paragraph, start, i, end))) {
                    paragraph.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                    start = end
                }
                i = end
            } else {
                i++
            }
        }
        paragraph.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    /** A full stop at [dot] ends a sentence unless it closes an abbreviation or initial, or a lowercase word follows. */
    private fun isSentenceEnd(text: String, start: Int, dot: Int, end: Int): Boolean {
        var w = dot
        while (w > start && !text[w - 1].isWhitespace()) w--
        val word = text.substring(w, dot).trimStart('(', '"', '\'', '“', '‘').lowercase()
        if (word in abbreviations) return false
        if (word.length == 1 && word[0].isLetter()) return false
        // Dotted acronyms and short forms: "U.S.", "a.m.", "Ph.D.".
        if (dotted.matches(word)) return false
        val next = text.substring(end).trimStart().firstOrNull() ?: return true
        return !next.isLowerCase()
    }

    private fun split(sentence: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            val cut = listOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf(" — "))
                .maxOrNull()?.takeIf { it > maxChars / 3 }?.let { it + 1 }
                ?: window.lastIndexOf(' ').takeIf { it > 0 }
                ?: maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

/** What [EndOfSpeech] makes of the microphone so far. */
enum class SpeechVerdict {
    /** Still waiting for speech, or the speaker is mid-sentence. */
    LISTENING,

    /** Speech was heard and has been followed by enough silence: stop and transcribe. */
    ENDED,

    /** Nothing was said: stop without transcribing. */
    NO_SPEECH,
}

/**
 * Hands-free end-of-speech detection from the recorder's per-buffer peak levels (0…1). Speech is
 * [minSpeechMs] of sound above the gate; the gate is [threshold], raised to three times the
 * noise floor measured over the first moments (capped at 0.3). Recording ends after [silenceMs]
 * of quiet following speech, or with [SpeechVerdict.NO_SPEECH] when nobody spoke for [noSpeechMs].
 * Feed it every level with a monotonic timestamp; one instance per recording.
 */
class EndOfSpeech(
    private val threshold: Float = 0.05f,
    private val silenceMs: Long = 1_200,
    private val minSpeechMs: Long = 200,
    private val noSpeechMs: Long = 8_000,
) {
    private var startedAt = -1L
    private var floor = Float.MAX_VALUE
    private var runStart = -1L
    private var lastLoud = -1L
    private var heard = false
    private var calibrate = true

    /** The gate in use (for tests and the level meter). */
    val gate: Float get() = maxOf(threshold, minOf(if (floor == Float.MAX_VALUE) 0f else floor * 3, 0.3f))

    /**
     * The user is already talking when recording starts (a barge-in: their first words are the
     * preroll). Speech counts as heard, so quiet from now on ends the recording, and the first
     * moments are not taken for the noise floor (they are the user's voice, which would raise the
     * gate above it). Call before the first level.
     */
    fun primeHeard() {
        heard = true
        calibrate = false
    }

    fun onLevel(peak: Float, atMs: Long): SpeechVerdict {
        if (startedAt < 0) {
            startedAt = atMs
            if (heard) lastLoud = atMs
        }
        val elapsed = atMs - startedAt
        if (calibrate && elapsed <= CALIBRATION_MS) floor = minOf(floor, peak)
        if (peak >= gate) {
            if (runStart < 0) runStart = atMs
            lastLoud = atMs
            if (atMs - runStart >= minSpeechMs) heard = true
        } else if (!heard && runStart >= 0 && atMs - lastLoud > GAP_MS) {
            // A click or a cough, not speech.
            runStart = -1
        }
        return when {
            heard && atMs - lastLoud >= silenceMs -> SpeechVerdict.ENDED
            !heard && elapsed >= noSpeechMs -> SpeechVerdict.NO_SPEECH
            else -> SpeechVerdict.LISTENING
        }
    }

    private companion object {
        const val CALIBRATION_MS = 300L

        /** Quiet shorter than this between loud buffers still counts as one stretch of speech. */
        const val GAP_MS = 300L
    }
}

/**
 * Follows one voice-mode turn: from the moment the prompt is sent ([baseline] = the transcript
 * keys before it) until the agent is idle again. The prompt is queued until a turn picks it up,
 * so the turn counts only once that prompt shows up in the transcript.
 */
class VoiceTurn(private val baseline: Set<String>, private val startedAtMs: Long) {
    private var sawRunning = false
    private val announced = mutableSetOf<String>()

    /** How the turn ended: [reply] is the answer to read aloud (null: nothing to read). */
    data class Finished(val reply: String?)

    /**
     * Call on every state change. Returns [Finished] once the agent has run the prompt and stopped
     * (or never picked it up within [TIMEOUT_MS] while idle); null while the turn is under way.
     */
    fun update(items: List<SessionItem>, running: Boolean, nowMs: Long): Finished? {
        val fresh = items.filter { it.key !in baseline }
        val prompt = fresh.indexOfLast { it is SessionItem.User }
        if (prompt < 0) return if (!running && nowMs - startedAtMs >= TIMEOUT_MS) Finished(null) else null
        if (running) {
            sawRunning = true
            return null
        }
        val after = fresh.subList(prompt + 1, fresh.size)
        if (!sawRunning && after.isEmpty()) return null
        // The last answer, unless it was stopped (the user stopped it) or an error followed it.
        val reply = after.indexOfLast { it is SessionItem.Assistant && it.text.isNotBlank() }
        val answer = (after.getOrNull(reply) as? SessionItem.Assistant)?.takeIf { a ->
            !a.interrupted && after.drop(reply + 1).none { it is SessionItem.Notice && it.kind == NoticeKind.ERROR }
        }
        return Finished(answer?.text)
    }

    private val stepsSeen = mutableSetOf<String>()

    /**
     * The answers of this turn's steps committed to [items] since the last call, in order, each
     * once (for [ReadAloudStream.stepDone]): a step's last words may never have been in a live
     * frame. Interrupted answers are left out.
     */
    fun committedSteps(items: List<SessionItem>): List<String> {
        val fresh = items.filter { it.key !in baseline }
        val prompt = fresh.indexOfLast { it is SessionItem.User }
        if (prompt < 0) return emptyList()
        return fresh.subList(prompt + 1, fresh.size)
            .filterIsInstance<SessionItem.Assistant>()
            .filter { !it.interrupted && it.text.isNotBlank() && stepsSeen.add(it.key) }
            .map { it.text }
    }

    /** Whether this turn's prompt is in [items] (a reply streaming before it belongs to an earlier turn). */
    fun promptSeen(items: List<SessionItem>): Boolean = items.any { it is SessionItem.User && it.key !in baseline }

    /** True when [waiting] (approval / question call ids) holds one not announced yet. */
    fun needsAnnouncement(waiting: Collection<String>): Boolean = announced.addAll(waiting)

    companion object {
        /** Idle this long without the prompt appearing: give up (the send failed). */
        const val TIMEOUT_MS = 15_000L
    }
}
