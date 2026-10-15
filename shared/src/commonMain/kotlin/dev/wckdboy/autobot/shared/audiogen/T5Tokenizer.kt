package dev.wckdboy.autobot.shared.audiogen

import dev.wckdboy.autobot.shared.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/** Token ids for the text encoder, padded to a fixed length, and which of them are real. */
class T5Tokens(val ids: IntArray, val mask: IntArray) {
    /** Real tokens, including the closing `</s>`. */
    val length: Int get() = mask.count { it != 0 }
}

/**
 * T5's SentencePiece unigram tokenizer (`google-t5/t5-base` `tokenizer.json`, Apache-2.0), as
 * Hugging Face's fast tokenizer runs it: the special tokens (`</s>`, `<extra_id_N>`, …) are taken
 * literally, the rest is normalized (SentencePiece's `nmt_nfkc`: control characters dropped,
 * spaces unified, NFKC), split at whitespace, each word prefixed with `▁` and cut into pieces by
 * Viterbi over the piece scores; characters without a piece become one `<unk>` per run. `</s>`
 * closes the sequence. Pure and thread-safe after construction.
 */
class T5Tokenizer(
    /** Piece → (id, log-probability). */
    private val pieces: Map<String, Pair<Int, Double>>,
    /** Special tokens matched verbatim in the text (content → id). */
    private val special: Map<String, Int> = emptyMap(),
    private val unkId: Int = UNK,
    /** Score of an unknown character: the lowest piece score minus 10, as SentencePiece. */
    private val unkScore: Double = (pieces.values.minOfOrNull { it.second } ?: 0.0) - 10.0,
) {
    /** Longest piece in code points. */
    private val maxPiece = pieces.keys.maxOfOrNull { codePoints(it) } ?: 1
    private val specialPattern = special.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.let { keys -> Regex(keys.joinToString("|") { Regex.escape(it) }) }

    /** Ids for [text] closed with `</s>`, without padding or truncation. */
    fun ids(text: String): IntArray {
        val out = ArrayList<Int>()
        var at = 0
        specialPattern?.findAll(text)?.forEach { m ->
            encodeText(text.substring(at, m.range.first), out)
            out += special.getValue(m.value)
            at = m.range.last + 1
        }
        encodeText(text.substring(at), out)
        out += EOS
        return out.toIntArray()
    }

    /**
     * [text] as exactly [maxLength] ids (Hugging Face's `truncation=True, padding="max_length"`):
     * longer text keeps its first `maxLength - 1` tokens and `</s>`, shorter is padded with `<pad>`.
     */
    fun encode(text: String, maxLength: Int = StableAudio.TEXT_TOKENS): T5Tokens {
        require(maxLength >= 2) { "maxLength is too small" }
        var ids = ids(text)
        if (ids.size > maxLength) ids = ids.copyOf(maxLength).also { it[maxLength - 1] = EOS }
        val padded = IntArray(maxLength) { if (it < ids.size) ids[it] else PAD }
        return T5Tokens(padded, IntArray(maxLength) { if (it < ids.size) 1 else 0 })
    }

    /** The pieces of [text] (debugging and tests). */
    fun pieces(text: String): List<String> {
        val byId = pieces.entries.associate { it.value.first to it.key }
        return ids(text).map { byId[it] ?: special.entries.firstOrNull { e -> e.value == it }?.key ?: if (it == EOS) "</s>" else "<unk>" }
    }

    private fun encodeText(text: String, out: MutableList<Int>) {
        for (word in normalize(text).split(' ')) {
            if (word.isNotEmpty()) viterbi(SPACE + word, out)
        }
    }

    /** Best segmentation of [word] (Hugging Face's `Unigram::encode_optimized`). */
    private fun viterbi(word: String, out: MutableList<Int>) {
        // Code-point boundaries as string indices.
        val bounds = ArrayList<Int>(word.length + 1)
        var i = 0
        while (i < word.length) {
            bounds += i
            i += if (word[i].isHighSurrogate() && i + 1 < word.length && word[i + 1].isLowSurrogate()) 2 else 1
        }
        bounds += word.length
        val n = bounds.size - 1
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        val id = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (start in 0 until n) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue
            var single = false
            for (len in 1..minOf(maxPiece, n - start)) {
                val piece = pieces[word.substring(bounds[start], bounds[start + len])] ?: continue
                if (len == 1) single = true
                val score = best[start] + piece.second
                val end = start + len
                if (id[end] == -1 || score > best[end]) {
                    best[end] = score
                    from[end] = start
                    id[end] = piece.first
                }
            }
            if (!single) {
                val score = best[start] + unkScore
                val end = start + 1
                if (id[end] == -1 || score > best[end]) {
                    best[end] = score
                    from[end] = start
                    id[end] = unkId
                }
            }
        }
        val path = ArrayList<Int>()
        var end = n
        while (end > 0) {
            path += id[end]
            end = from[end]
        }
        path.reverse()
        // Runs of unknown characters become one <unk>.
        for ((k, token) in path.withIndex()) {
            if (token == unkId && k > 0 && path[k - 1] == unkId) continue
            out += token
        }
    }

    companion object {
        const val PAD = 0
        const val EOS = 1
        const val UNK = 2
        private const val SPACE = "▁"

        /** Reads Hugging Face's `tokenizer.json` (Unigram model) for T5. */
        @Throws(Exception::class)
        fun parse(tokenizerJson: String): T5Tokenizer {
            val root = Json.parseToJsonElement(tokenizerJson).jsonObject
            val model = root["model"]?.jsonObject ?: error("tokenizer.json has no model")
            require(model["type"]?.jsonPrimitive?.content.let { it == null || it == "Unigram" }) { "not a unigram tokenizer" }
            val vocab = model["vocab"]?.jsonArray ?: error("tokenizer.json has no vocab")
            val pieces = HashMap<String, Pair<Int, Double>>(vocab.size * 2)
            val special = HashMap<String, Int>()
            (root["added_tokens"] as? JsonArray)?.forEach { t ->
                val o = t.jsonObject
                special[o.getValue("content").jsonPrimitive.content] = o.getValue("id").jsonPrimitive.int
            }
            vocab.forEachIndexed { index, entry ->
                val (piece, score) = entry.jsonArray
                val p = piece.jsonPrimitive.content
                // The special tokens' own entries (<pad>, </s>, <unk>) never match text.
                if (p !in special) pieces[p] = index to score.jsonPrimitive.double
            }
            val unk = (model["unk_id"] as? kotlinx.serialization.json.JsonPrimitive)?.int ?: UNK
            val minScore = vocab.minOf { it.jsonArray[1].jsonPrimitive.double }
            return T5Tokenizer(pieces, special, unk, minScore - 10.0)
        }

        /** Reads `tokenizer.json` from [path]. */
        @Throws(Exception::class)
        fun load(path: String): T5Tokenizer = parse(SystemFileSystem.read(path.toPath()) { readUtf8() })

        /** SentencePiece's `nmt_nfkc` (control characters dropped, other spaces to ' ', NFKC), then single spaces. */
        fun normalize(text: String): String {
            val cleaned = buildString(text.length) {
                for (c in text) {
                    when {
                        c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == ' ' || c in '​'..'‏' ||
                            c == ' ' || c == ' ' || c == '﻿' -> append(' ')
                        c in '\u0001'..'\u0008' || c == '\u000B' || c in '\u000E'..'\u001F' || c == '\u007F' ||
                            c == '\u008F' || c == '\u009F' -> Unit
                        else -> append(c)
                    }
                }
            }
            return buildString {
                for (word in nfkc(cleaned).split(' ', '\t', '\n', '\r', '\u000B', '\u000C', '\u0085', ' ', ' ', ' ',
                    ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　')) {
                    if (word.isEmpty()) continue
                    if (isNotEmpty()) append(' ')
                    append(word)
                }
            }
        }

        private fun codePoints(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
                n++
            }
            return n
        }
    }
}

/** Unicode NFKC (compatibility composition): `java.text.Normalizer` on Android, Foundation on iOS. */
internal expect fun nfkc(text: String): String
