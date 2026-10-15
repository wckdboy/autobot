package dev.wckdboy.autobot.shared.audiogen

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin tokenizer against Hugging Face `tokenizers` (0.22) running t5-base's `tokenizer.json`:
 * `audiogen/t5_expected.json` holds its ids for the prompts, `t5_vocab_subset.tsv` every piece
 * those prompts could use (Viterbi only looks at substrings of each word, so the subset segments
 * exactly like the full vocabulary).
 */
class T5TokenizerTest {
    private fun resource(name: String) = javaClass.getResource("/audiogen/$name")!!.readText()

    private val special = mapOf("<pad>" to 0, "</s>" to 1, "<unk>" to 2) + (0..99).associate { "<extra_id_$it>" to 32099 - it }

    private val subset by lazy {
        val rows = resource("t5_vocab_subset.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t', limit = 3) }
        val pieces = rows.associate { (id, score, piece) -> piece to (id.toInt() to score.toDouble()) }
        T5Tokenizer(pieces, special, T5Tokenizer.UNK, rows.minOf { it[1].toDouble() } - 10.0)
    }

    private val expected by lazy {
        Json.parseToJsonElement(resource("t5_expected.json")).jsonArray.map { e ->
            val o = e.jsonObject
            o.getValue("text").jsonPrimitive.content to o.getValue("ids").jsonArray.map { it.jsonPrimitive.int }.toIntArray()
        }
    }

    private fun assertMatches(t: T5Tokenizer) {
        val wrong = expected.filter { (text, ids) -> !t.ids(text).contentEquals(ids) }
            .map { (text, ids) -> "$text\n  hf:     ${ids.toList()}\n  kotlin: ${t.ids(text).toList()} ${t.pieces(text)}" }
        assertTrue("${wrong.size}/${expected.size} differ:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun matchesHuggingFace() = assertMatches(subset)

    @Test
    fun knownIds() {
        assertArrayEquals(intArrayOf(209, 2577, 272, 6218, 5256, 629, 5253, 6494, 1), subset.ids("128 BPM tech house drum loop"))
        // Characters without a piece: one <unk> per run.
        assertArrayEquals(intArrayOf(27645, 549, 4309, 4611, 1499, 11, 3, 2, 1), subset.ids("ＦＵＬＬ　ＷＩＤＴＨ text and 日本語"))
        assertArrayEquals(intArrayOf(1), subset.ids(""))
    }

    @Test
    fun padsAndTruncatesToTheEncoderLength() {
        val short = subset.encode("Glitchy bass design")
        assertEquals(StableAudio.TEXT_TOKENS, short.ids.size)
        assertArrayEquals(intArrayOf(350, 4250, 11971, 7981, 408, 1), short.ids.copyOf(6))
        assertTrue(short.ids.drop(6).all { it == T5Tokenizer.PAD })
        assertEquals(6, short.length)
        assertArrayEquals(IntArray(64) { if (it < 6) 1 else 0 }, short.mask)

        val long = subset.encode("an extremely long prompt ".repeat(30))
        assertEquals(64, long.length)
        assertEquals(T5Tokenizer.EOS, long.ids.last())
        assertArrayEquals(subset.ids("an extremely long prompt ".repeat(30)).copyOf(63), long.ids.copyOf(63))
    }

    @Test
    fun normalizesLikeSentencePiece() {
        assertEquals("Lo-fi hip-hop beat with vinyl", T5Tokenizer.normalize("  Lo-fi \u0007  hip-hop\tbeat\nwith​vinyl "))
        assertEquals("FULL WIDTH fine", T5Tokenizer.normalize("ＦＵＬＬ　ＷＩＤＴＨ ﬁne"))
    }

    /** The full `tokenizer.json` when `-Paudiogen.tokenizer=<path>` (or the model folder) is given. */
    @Test
    fun fullTokenizerMatches() {
        val file = (System.getProperty("audiogen.tokenizer") ?: System.getProperty("audiogen.dir")?.let { "$it/tokenizer.json" })?.let(::File)
        assumeTrue("set -Paudiogen.dir to a model folder with tokenizer.json", file?.isFile == true)
        val start = System.nanoTime()
        val full = T5Tokenizer.load(file!!.path)
        println("t5 tokenizer.json parsed in %.0f ms".format((System.nanoTime() - start) / 1e6))
        assertMatches(full)
    }
}
