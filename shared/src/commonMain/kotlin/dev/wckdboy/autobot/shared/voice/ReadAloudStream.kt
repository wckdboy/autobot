package dev.wckdboy.autobot.shared.voice

/**
 * Turns an answer that is still streaming into pieces to speak as soon as each sentence is
 * complete, so read-aloud starts a sentence after the answer starts instead of after it ends.
 *
 * Feed [update] the streamed markdown of the current step (it only grows; a text that does not
 * continue the last one is a new step) and [finish] the final answer when the turn ends. Each call
 * returns the new pieces, in order, never one twice. A sentence is handed out once the text after
 * it has started (so "3." waits for "14", and "e.g." for the next word), and only outside an open
 * code fence; finished paragraphs are read whole. The markdown goes through [speakable] (the chat
 * renderer's own on each platform), so code is never read, numbers and abbreviations stay whole,
 * and what is spoken is what is shown.
 *
 * Work per update is bounded by the open paragraph: finished paragraphs are not parsed again.
 */
class ReadAloudStream(
    private val speakable: (String) -> String = VoiceText::speakable,
    private val maxChars: Int = VoiceText.MAX_CHUNK,
) {
    /** The current step's markdown so far. */
    private var source = ""

    /** Where the paragraph still being written starts in [source]. */
    private var blockStart = 0

    /** Sentences of that paragraph already handed out. */
    private var spokenInBlock = 0

    /** Sentences handed out this turn (normalized), so the final answer does not repeat them. */
    private val spoken = HashSet<String>()

    /** Pieces handed out so far this turn. */
    var count: Int = 0
        private set

    /** The streamed text of the current step so far. Returns the pieces ready to speak. */
    fun update(text: String): List<String> {
        if (text.startsWith(source)) {
            source = text
            return emit(advance(final = false))
        }
        // A new step (the last one ended in tool calls): its text was complete.
        val rest = advance(final = true)
        start(text)
        return emit(rest + advance(final = false))
    }

    /**
     * The turn ended with [reply] (null: stopped or failed, nothing more to read). Returns the
     * rest: the unread end of the streamed text, or the reply's sentences not spoken yet when it
     * was not streamed as such.
     */
    fun finish(reply: String?): List<String> {
        if (reply == null) return emit(emptyList())
        if (reply.startsWith(source)) {
            source = reply
            return emit(advance(final = true))
        }
        val rest = emit(advance(final = true))
        start(reply)
        // Not a continuation of what streamed: read only what was not spoken already.
        return rest + emit(advance(final = true), skipSpoken = true)
    }

    private fun start(text: String) {
        source = text
        blockStart = 0
        spokenInBlock = 0
    }

    /** New sentences: every finished paragraph, then the open one's complete sentences (all if [final]). */
    private fun advance(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val end = paragraphEnd(source, blockStart) ?: break
            out += afterClause(sentences(source.substring(blockStart, end)).drop(spokenInBlock))
            blockStart = end
            spokenInBlock = 0
        }
        val open = source.substring(blockStart)
        if (open.isBlank()) return out
        val sentences = sentences(open)
        // The last sentence may still grow (or be "3." of "3.14"): it waits for the text after it.
        val ready = if (final) sentences.size else sentences.size - 1
        if (ready > spokenInBlock) {
            out += afterClause(sentences.subList(spokenInBlock, ready))
            spokenInBlock = ready
        } else if (!final && count == 0 && out.isEmpty() && clause == null) {
            // Nothing spoken yet and a long first sentence: start on its first clause.
            sentences.lastOrNull()?.let(::firstClause)?.let {
                clause = it
                out += it
            }
        }
        if (final) {
            blockStart = source.length
            spokenInBlock = 0
        }
        return out
    }

    /** The first clause already spoken of the sentence being written (null: none). */
    private var clause: String? = null

    /** [sentences] with the spoken first clause taken off the first one. */
    private fun afterClause(sentences: List<String>): List<String> {
        val spokenClause = clause ?: return sentences
        if (sentences.isEmpty()) return sentences
        clause = null
        val first = sentences.first()
        if (!first.startsWith(spokenClause)) return sentences
        val rest = first.substring(spokenClause.length).trim()
        return listOfNotNull(rest.takeIf { it.isNotEmpty() }) + sentences.drop(1)
    }

    /** "The forecast for Paris tomorrow is mostly sunny," of a long sentence whose next words have started. */
    private fun firstClause(sentence: String): String? {
        if (sentence.length < FIRST_CLAUSE_MIN * 2) return null
        val cut = sentence.indexOf(", ", FIRST_CLAUSE_MIN).takeIf { it in 0 until maxChars } ?: return null
        return sentence.substring(0, cut + 1)
    }

    private fun sentences(markdown: String): List<String> = VoiceText.sentenceList(speakable(markdown), maxChars)

    /** Joins short sentences and counts what goes out; [skipSpoken] drops those already spoken this turn. */
    private fun emit(sentences: List<String>, skipSpoken: Boolean = false): List<String> {
        val fresh = sentences.filter { spoken.add(normalize(it)) || !skipSpoken }
        val pieces = VoiceText.merge(fresh, maxChars.coerceAtLeast(1), keepFirst = count == 0)
        count += pieces.size
        return pieces
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    internal companion object {
        /** Shortest first clause spoken on its own (shorter ones wait for the sentence). */
        private const val FIRST_CLAUSE_MIN = 24

        private val fence = Regex("^ {0,3}(```|~~~)")

        /**
         * The end (exclusive, after the blank line) of the paragraph starting at [from], or null
         * while it is still open. Blank lines inside a code fence do not end it.
         */
        fun paragraphEnd(text: String, from: Int): Int? {
            var inFence = false
            var lineStart = from
            var sawContent = false
            while (lineStart < text.length) {
                val nl = text.indexOf('\n', lineStart)
                if (nl < 0) return null // the last line is still being written
                val line = text.substring(lineStart, nl)
                when {
                    fence.containsMatchIn(line) -> {
                        inFence = !inFence
                        sawContent = true
                    }
                    !inFence && line.isBlank() -> if (sawContent) return nl + 1
                    else -> sawContent = true
                }
                lineStart = nl + 1
            }
            return null
        }
    }
}
