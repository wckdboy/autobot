package dev.wckdboy.autobot.shared.tts

import dev.wckdboy.autobot.shared.SystemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * One piece of speech for Kokoro: [ids] are its token ids (without the `0` pads the model adds at
 * both ends), [phonemes] the misaki phoneme string they come from.
 */
class KokoroChunk(val text: String, val phonemes: String, val ids: IntArray) {
    /** Row of the voice pack (`voices/<name>.bin`, 510 × 256 floats) to use: Kokoro takes `pack[len(phonemes) - 1]`. */
    val styleIndex: Int get() = (phonemes.length - 1).coerceIn(0, KokoroFrontend.VOICE_ROWS - 1)

    override fun toString() = "KokoroChunk($text → $phonemes)"
}

/** Kokoro's phoneme vocabulary (`config.json` › `vocab`): one id per phoneme character. */
class KokoroVocab(private val ids: Map<Char, Int>) {
    /** Ids for [phonemes]; characters Kokoro has no id for are dropped, as Kokoro does. */
    fun encode(phonemes: String): IntArray = phonemes.mapNotNull { ids[it] }.toIntArray()

    operator fun contains(c: Char) = c in ids

    companion object {
        private val entry = Regex("\"((?:[^\"\\\\]|\\\\.)+)\"\\s*:\\s*(\\d+)")

        /** Reads the `vocab` object of Kokoro's `config.json`. */
        fun parse(configJson: String): KokoroVocab {
            val start = configJson.indexOf("\"vocab\"")
            require(start >= 0) { "config.json has no vocab" }
            val body = configJson.substring(configJson.indexOf('{', start), configJson.indexOf('}', start) + 1)
            val ids = entry.findAll(body).associate { m -> unescape(m.groupValues[1]) to m.groupValues[2].toInt() }
            require(ids.size > 50) { "config.json vocab is too small" }
            return KokoroVocab(ids.filterKeys { it.length == 1 }.mapKeys { it.key[0] })
        }

        private fun unescape(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c != '\\') {
                    append(c)
                    continue
                }
                when (val e = s[i++]) {
                    'u' -> { append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    'n' -> append('\n')
                    't' -> append('\t')
                    else -> append(e)
                }
            }
        }
    }
}

/**
 * Kokoro-82M's English text frontend, shared by both apps: [TextNormalizer], then misaki's G2P
 * (lexicon from the downloaded `us_*.json` / `gb_*.json`, rules for words it lacks), then
 * Kokoro's vocabulary. [chunks] splits text into sentences, and sentences longer than the model
 * takes at punctuation (Kokoro's "waterfall"), so speech can start after the first one.
 * Pure and thread-safe after construction; British voices (`b…`) read with the British lexicon.
 */
class KokoroFrontend private constructor(
    private val american: Lazy<G2P>,
    private val british: Lazy<G2P>?,
    val vocab: KokoroVocab,
) {
    // The British lexicon loads on first use; one that cannot be read leaves British voices on the American one.
    private fun g2p(voice: String): G2P =
        british?.takeIf { isBritish(voice) }?.let { runCatching { it.value }.getOrNull() } ?: american.value

    /** [sentence] read by [g2p]; never throws (see [G2P.phonemize]). */
    private fun phonemize(g2p: G2P, sentence: String): List<Phonemized> = try {
        g2p.phonemize(sentence)
    } catch (_: Exception) {
        emptyList()
    }

    /** The phoneme string for [text] as misaki writes it (after normalization), for tests and debugging. */
    fun phonemes(text: String, voice: String = DEFAULT_VOICE): String =
        sentences(text).joinToString(" ") { s -> psOf(phonemize(g2p(voice), s)) }

    /**
     * [text] as speech pieces of at most [maxTokens] phonemes (510 for the full model; less for
     * engines with a smaller fixed input, at least 16), in order. Pieces with nothing to say are
     * skipped. Total: any text gives pieces or none, never an exception (the host calls this from
     * Swift, where an exception ends the app).
     */
    fun chunks(text: String, voice: String = DEFAULT_VOICE, maxTokens: Int = MAX_TOKENS): List<KokoroChunk> {
        val max = maxTokens.coerceAtLeast(16)
        val g2p = g2p(voice)
        return sentences(text).flatMap { sentence ->
            try {
                split(phonemize(g2p, sentence), max)
            } catch (_: Exception) {
                emptyList()
            }
        }.mapNotNull { (graphemes, ps) ->
            val trimmed = ps.take(max)
            val ids = vocab.encode(trimmed)
            if (ids.none { it > LAST_PUNCT_ID }) null else KokoroChunk(graphemes, trimmed, ids)
        }
    }

    /** Kokoro's `en_tokenize`: cuts a sentence's words where the phonemes would pass [max], at the strongest punctuation. */
    private fun split(tokens: List<Phonemized>, max: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var tks = mutableListOf<Phonemized>()
        var count = 0
        for (t in tokens) {
            var next = t.phonemes + if (t.whitespace.isNotEmpty()) " " else ""
            val nextCount = count + next.trimEnd().length
            if (nextCount > max && tks.isNotEmpty()) {
                val z = waterfall(tks, nextCount, max)
                out += textOf(tks.subList(0, z)) to psOf(tks.subList(0, z))
                tks = tks.subList(z, tks.size).toMutableList()
                count = psOf(tks).length
                if (tks.isEmpty()) next = next.trimStart()
            }
            tks += t
            count += next.length
        }
        if (tks.isNotEmpty()) out += textOf(tks) to psOf(tks)
        return out.filter { it.second.isNotEmpty() }
    }

    private fun waterfall(tokens: List<Phonemized>, nextCount: Int, max: Int): Int {
        for (marks in WATERFALL) {
            var z = tokens.indexOfLast { it.phonemes.length == 1 && it.phonemes[0] in marks }
            if (z < 0) continue
            z++
            if (z < tokens.size && tokens[z].phonemes in BUMPS) z++
            if (nextCount - psOf(tokens.subList(0, z)).length <= max) return z
        }
        return tokens.size
    }

    companion object {
        /** Kokoro's output rate: 24 kHz mono. */
        const val SAMPLE_RATE = 24_000

        /** Phonemes the model takes per piece (its context is 512 with the two pads). */
        const val MAX_TOKENS = 510

        /** Rows in a voice pack: one style per phoneme count. */
        const val VOICE_ROWS = 510

        /** Floats per style vector. */
        const val STYLE_DIM = 256

        const val DEFAULT_VOICE = "af_heart"

        const val CONFIG = "config.json"
        const val US_GOLD = "us_gold.json"
        const val US_SILVER = "us_silver.json"
        const val GB_GOLD = "gb_gold.json"
        const val GB_SILVER = "gb_silver.json"

        /** Ids up to the space (16) are punctuation: a piece with only those says nothing. */
        private const val LAST_PUNCT_ID = 16
        private val WATERFALL = listOf("!.?…", ":;", ",—")
        private val BUMPS = setOf(")", "”")

        /** British voices (`bf_…`, `bm_…`) speak British English. */
        fun isBritish(voice: String) = voice.startsWith("b")

        /** Loads the frontend from a Kokoro voice folder ([CONFIG] and the misaki lexicons); the British lexicon on first use. */
        @Throws(Throwable::class)
        fun load(dir: String): KokoroFrontend = load(dir, SystemFileSystem)

        /** [load] from [fileSystem] (tests). */
        fun load(dir: String, fileSystem: FileSystem): KokoroFrontend {
            val root = dir.toPath()
            fun read(name: String) = fileSystem.read(root / name) { readUtf8() }
            val hasBritish = fileSystem.exists(root / GB_GOLD) && fileSystem.exists(root / GB_SILVER)
            return KokoroFrontend(
                lazy { G2P(Lexicon(false, LexiconJson.parse(read(US_GOLD)), LexiconJson.parse(read(US_SILVER)))) },
                if (hasBritish) lazy { G2P(Lexicon(true, LexiconJson.parse(read(GB_GOLD)), LexiconJson.parse(read(GB_SILVER)))) } else null,
                KokoroVocab.parse(read(CONFIG)),
            ).also { it.american.value }
        }

        /** The frontend from file contents (tests; small lexicons). */
        fun parse(config: String, usGold: String, usSilver: String, gbGold: String? = null, gbSilver: String? = null): KokoroFrontend =
            KokoroFrontend(
                lazyOf(G2P(Lexicon(false, LexiconJson.parse(usGold), LexiconJson.parse(usSilver)))),
                if (gbGold != null && gbSilver != null) lazy { G2P(Lexicon(true, LexiconJson.parse(gbGold), LexiconJson.parse(gbSilver))) } else null,
                KokoroVocab.parse(config),
            )

        private fun psOf(tokens: List<Phonemized>) = tokens.joinToString("") { it.phonemes + if (it.whitespace.isNotEmpty()) " " else "" }.trim()

        private fun textOf(tokens: List<Phonemized>) = tokens.joinToString("") { it.text + it.whitespace }.trim()

        private val INITIAL = Regex("^(?:\\p{L}\\.)+$")

        /** [text], normalized, as sentences: split at line breaks and at `.` `!` `?` `…` before a space and a non-lowercase word. */
        fun sentences(text: String): List<String> {
            val out = mutableListOf<String>()
            val normalized = try {
                TextNormalizer.normalize(text)
            } catch (_: Exception) {
                text
            }
            for (line in normalized.split('\n')) {
                var start = 0
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (c == '.' || c == '!' || c == '?' || c == '…') {
                        var end = i + 1
                        while (end < line.length && line[end] in ".!?…\"'”’)]") end++
                        val next = line.substring(end).trimStart().firstOrNull()
                        val word = line.substring(line.lastIndexOf(' ', i).let { it + 1 }.coerceAtMost(i), i + 1)
                        val atSpace = end >= line.length || line[end].isWhitespace()
                        if (atSpace && (next == null || !next.isLowerCase()) && !(c == '.' && INITIAL.matches(word) && word.length <= 2)) {
                            line.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
                            start = end
                        }
                        i = end
                    } else {
                        i++
                    }
                }
                line.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out
        }
    }
}
