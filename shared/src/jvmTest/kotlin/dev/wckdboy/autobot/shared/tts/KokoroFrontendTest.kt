package dev.wckdboy.autobot.shared.tts

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The Kotlin port against misaki itself: `tts/<lang>_expected.tsv` are misaki 0.9.4's outputs
 * (`G2P(trf=False, fallback=None, unk='')`, as Kokoro runs it minus espeak) for those sentences,
 * and `tts/<lang>_gold.json` / `<lang>_silver.json` the lexicon entries misaki touched while reading them.
 */
class KokoroFrontendTest {
    private fun resource(name: String) = javaClass.getResource("/tts/$name")!!.readText()

    private val frontend by lazy {
        KokoroFrontend.parse(
            resource("kokoro_config.json"), resource("us_gold.json"), resource("us_silver.json"), resource("gb_gold.json"), resource("gb_silver.json"),
        )
    }

    private fun expected(name: String) = resource(name).lines().filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    private fun assertMatchesMisaki(f: KokoroFrontend, cases: List<Pair<String, String>>, voice: String) {
        val wrong = cases.mapNotNull { (text, ps) -> f.phonemes(text, voice).takeIf { it != ps }?.let { "$text\n  misaki: $ps\n  kotlin: $it" } }
        assertTrue("${wrong.size}/${cases.size} differ from misaki:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun americanMatchesMisaki() = assertMatchesMisaki(frontend, expected("us_expected.tsv"), "af_heart")

    @Test
    fun britishMatchesMisaki() = assertMatchesMisaki(frontend, expected("gb_expected.tsv"), "bf_emma")

    @Test
    fun idsFollowKokoroVocab() {
        val chunk = frontend.chunks("Hello world.").single()
        assertEquals("həlˈO wˈɜɹld.", chunk.phonemes)
        assertArrayEquals(intArrayOf(50, 83, 54, 156, 31, 16, 65, 156, 87, 123, 54, 46, 4), chunk.ids)
        assertEquals(chunk.phonemes.length - 1, chunk.styleIndex)
        // ɾ is written T for Kokoro v1.0, and every phoneme misaki writes has an id.
        val ps = frontend.phonemes("The meeting is on the 3rd of March.")
        assertTrue('T' in ps && 'ɾ' !in ps)
        expected("us_expected.tsv").forEach { (_, ps) -> assertTrue(ps, ps.all { it in frontend.vocab }) }
    }

    @Test
    fun numbersReadLikeNum2words() {
        assertEquals("one hundred and twenty-three", EnglishNumbers.cardinal(123))
        assertEquals("one thousand and five", EnglishNumbers.cardinal(1005))
        assertEquals("one million, two hundred and fifty thousand", EnglishNumbers.cardinal(1_250_000))
        assertEquals("two thousand, one hundred", EnglishNumbers.cardinal(2100))
        assertEquals("twenty-first", EnglishNumbers.ordinal(21))
        assertEquals("one hundred and twelfth", EnglishNumbers.ordinal(112))
        assertEquals("ninetieth", EnglishNumbers.ordinal(90))
        assertEquals("nineteen eighty-four", EnglishNumbers.year(1984))
        assertEquals("two thousand and five", EnglishNumbers.year(2005))
        assertEquals("twenty ten", EnglishNumbers.year(2010))
        assertEquals("nineteen hundred", EnglishNumbers.year(1900))
        assertEquals("nineteen oh-five", EnglishNumbers.year(1905))
        assertEquals("two point five", EnglishNumbers.decimal("2.50"))
        assertEquals("three", EnglishNumbers.decimal("3.0"))
        assertEquals("zero point zero five", EnglishNumbers.decimal("0.05"))
    }

    @Test
    fun normalizesWhatMisakiDoesNotRead() {
        fun n(s: String) = TextNormalizer.normalize(s)
        assertEquals("See example dot com for details.", n("See https://www.example.com/docs?a=1 for details."))
        assertEquals("Write to jane dot doe at mail dot example dot com", n("Write to jane.doe@mail.example.com"))
        assertEquals("Doctor Smith lives on Baker Street.", n("Dr. Smith lives on Baker St."))
        assertEquals("Saint Louis is far.", n("St. Louis is far."))
        assertEquals("It's on March 5th, 2024.", n("It's on 2024-03-05."))
        assertEquals("Due March 5th, 2024 at 3 oh 7 PM", n("Due 3/5/2024 at 3:07 p.m."))
        assertEquals("Meet me at 10 o'clock or 9 AM", n("Meet me at 10:00 or 9am"))
        assertEquals("June 1st is fine", n("June 1 is fine"))
        assertEquals("It weighs 5 kilograms and costs \$2.5 million", n("It weighs 5kg and costs \$2.5M"))
        assertEquals("pages 10 to 20, for example the intro", n("pages 10-20, e.g. the intro"))
        assertEquals("it's 25 degrees Celsius", n("it's 25°C 🌞"))
        assertEquals("one half cup, number 1", n("1/2 cup, #1"))
        assertEquals("Wait… what", n("Wait... what"))
        assertEquals("€5 or 500 yen", n("5€ or ¥500"))
    }

    @Test
    fun readsWordsTheLexiconLacks() {
        // Rules stand in for espeak: OOV words are spoken (not dropped), stressed, in Kokoro's vocab.
        listOf("Kokoro", "Autobot", "Zorblax", "quantization").forEach { word ->
            val ps = frontend.phonemes(word)
            assertTrue("$word → '$ps'", ps.isNotEmpty() && 'ˈ' in ps && ps.all { it in frontend.vocab })
        }
        // An unknown acronym is spelled; a compound of known words keeps both readings.
        assertEquals("ˌɛnˌɛsˈA", frontend.phonemes("NSA"))
        val compound = frontend.phonemes("applestore")
        assertTrue(compound, compound.startsWith("ˈæpᵊl") && compound.endsWith("stˌɔɹ"))
    }

    @Test
    fun splitsSentencesAndLongOnesAtPunctuation() {
        assertEquals(
            listOf("Hello world.", "Is this the U.S.A. or the UK?", "Mister Smith said hi!", "New line here"),
            KokoroFrontend.sentences("Hello world. Is this the U.S.A. or the UK? Mr. Smith said hi!\nNew line here"),
        )
        val text = "The quick brown fox jumps over the lazy dog, and the lazy dog jumps over the quick brown fox; then the fox naps."
        val chunks = frontend.chunks(text, maxTokens = 60)
        assertTrue(chunks.toString(), chunks.size >= 2)
        chunks.forEach { assertTrue(it.phonemes, it.phonemes.length <= 60 && it.ids.isNotEmpty()) }
        // Cut after punctuation, never mid-word: the pieces rejoin to the whole sentence.
        assertTrue(chunks.first().text, chunks.first().text.endsWith(",") || chunks.first().text.endsWith(";"))
        assertEquals(frontend.phonemes(text), chunks.joinToString(" ") { it.phonemes })
        assertTrue(frontend.chunks("…").toString(), frontend.chunks("…").isEmpty())
    }

    @Test
    fun vocabComesFromConfig() {
        val vocab = KokoroVocab.parse(resource("kokoro_config.json"))
        assertArrayEquals(intArrayOf(156, 157, 4, 16, 177, 36), vocab.encode("ˈˌ. ᵻT❓"))
    }

    /**
     * With the full misaki lexicons (`-Pkokoro.dir=<folder with us_gold.json…>`): a larger corpus,
     * and loading speed.
     */
    @Test
    fun fullLexicons() {
        val dir = System.getProperty("kokoro.dir")?.takeIf { File(it, KokoroFrontend.US_GOLD).exists() }
        assumeTrue("set -Pkokoro.dir to a folder with the misaki lexicons", dir != null)
        val start = System.nanoTime()
        val full = KokoroFrontend.load(dir!!)
        val loadMs = (System.nanoTime() - start) / 1_000_000
        assertMatchesMisaki(full, expected("us_expected.tsv"), "af_heart")
        assertMatchesMisaki(full, expected("gb_expected.tsv"), "bm_george")
        val corpus = javaClass.getResource("/tts/corpus_expected.tsv")?.readText()?.lines()?.filter { it.isNotBlank() }
            ?.map { it.substringBefore('\t') to it.substringAfter('\t') }
        assertNotNull(corpus)
        val diffs = corpus!!.filter { (text, ps) -> full.phonemes(text) != ps }
        diffs.forEach { (text, ps) -> println("$text\n  misaki: $ps\n  kotlin: ${full.phonemes(text)}") }
        val same = corpus.size - diffs.size
        val t = System.nanoTime()
        corpus.forEach { full.chunks(it.first) }
        val perSentenceUs = (System.nanoTime() - t) / 1000 / corpus.size
        println("kokoro frontend: lexicons loaded in $loadMs ms, $same/${corpus.size} corpus sentences identical to misaki, $perSentenceUs µs per sentence")
        assertTrue("$same/${corpus.size} identical to misaki", same * 100 >= corpus.size * 95)
    }
}
