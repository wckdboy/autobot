package dev.wckdboy.autobot.shared.tts

/**
 * Reads words misaki's lexicon lacks (Kokoro uses espeak-ng here, which is GPL): accents folded,
 * acronyms spelled, compounds and prefixed or suffixed forms of known words, and otherwise
 * English letter-to-sound rules ([LetterToSound]). Rated 1, as misaki rates its fallback.
 */
internal class Fallback(private val lexicon: Lexicon) {
    private val ctx = TokenContext()

    operator fun invoke(word: String): Ps? {
        val folded = fold(word)
        if (folded != word && folded.isNotEmpty()) known(folded)?.let { return Ps(it, 1) }
        val letters = folded.filter { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }.trim('\'')
        if (letters.none { it.isLetter() }) return null
        val bare = letters.filter { it.isLetter() }
        if ((bare.length in 2..5 && bare == bare.uppercase()) || (bare.length <= 4 && bare.lowercase().none { it in "aeiouy" })) {
            lexicon.getNNP(bare)?.let { return Ps(it.ps, 1) }
        }
        val w = letters.lowercase()
        (compound(w) ?: affixed(w) ?: LetterToSound.read(w))?.let { return Ps(it, 1) }
        return null
    }

    private fun known(part: String): String? =
        if (part.length < 2) null else lexicon.getWord(part, "NN", null, ctx)?.ps?.takeIf { it.isNotEmpty() && it.any { c -> c in VOWELS } }

    /** Two known words written together ("openweight"), the second one's stress lowered. */
    private fun compound(w: String): String? {
        if (w.length < 6 || '\'' in w) return null
        for (i in w.length - 3 downTo 3) {
            val left = known(w.substring(0, i)) ?: continue
            val right = known(w.substring(i)) ?: continue
            return left + applyStress(right, -0.5)
        }
        return null
    }

    /** A known word with a common prefix or suffix ("unfollowable" → un + follow + able). */
    private fun affixed(w: String): String? {
        for ((prefix, ps) in PREFIXES) {
            if (w.length - prefix.length < 3 || !w.startsWith(prefix)) continue
            val rest = w.substring(prefix.length)
            val stem = known(rest) ?: affixed(rest) ?: continue
            return ps + applyStress(stem, -0.5)
        }
        for ((suffix, ps) in SUFFIXES) {
            if (w.length - suffix.length < 3 || !w.endsWith(suffix)) continue
            val rest = w.dropLast(suffix.length)
            val stem = known(rest) ?: known(rest + "e") ?: rest.takeIf { it.endsWith("i") }?.let { known(it.dropLast(1) + "y") }
                ?: rest.takeIf { it.length > 3 && it.last() == it[it.length - 2] }?.let { known(it.dropLast(1)) }
                ?: continue
            return stem + ps
        }
        return null
    }

    private companion object {
        val PREFIXES = listOf(
            "counter" to "kˌWntəɹ", "under" to "ˌʌndəɹ", "super" to "sˌupəɹ", "inter" to "ˌɪntəɹ", "micro" to "mˌIkɹO", "hyper" to "hˌIpəɹ",
            "multi" to "mˌʌlti", "over" to "ˌOvəɹ", "anti" to "ˌænti", "auto" to "ˌɔTO", "semi" to "sˌɛmi", "post" to "pˌOst", "self" to "sˌɛlf",
            "non" to "nˌɑn", "pre" to "pɹˌi", "dis" to "dˌɪs", "mis" to "mˌɪs", "out" to "ˌWt", "sub" to "sˌʌb", "un" to "ˌʌn", "re" to "ɹˌi",
            "co" to "kˌO",
        )
        val SUFFIXES = listOf(
            "ization" to "əzˈAʃən", "ness" to "nəs", "less" to "ləs", "ment" to "mənt", "able" to "əbᵊl", "ible" to "əbᵊl", "ists" to "ɪsts",
            "ism" to "ˌɪzəm", "ist" to "ɪst", "izing" to "ˌIzɪŋ", "ized" to "ˌIzd", "izes" to "ˌIzᵻz", "ize" to "ˌIz", "like" to "lˌIk",
            "wise" to "wˌIz", "ful" to "fəl", "ers" to "əɹz", "est" to "əst", "ish" to "ɪʃ", "er" to "əɹ", "ly" to "li", "y" to "i",
        )

        private val ACCENTS = mapOf(
            'à' to 'a', 'á' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a', 'å' to 'a', 'ā' to 'a', 'ç' to 'c', 'è' to 'e', 'é' to 'e',
            'ê' to 'e', 'ë' to 'e', 'ē' to 'e', 'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i', 'ī' to 'i', 'ñ' to 'n', 'ò' to 'o',
            'ó' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o', 'ø' to 'o', 'ō' to 'o', 'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u',
            'ū' to 'u', 'ý' to 'y', 'ÿ' to 'y', 'š' to 's', 'ž' to 'z', 'č' to 'c', 'ł' to 'l', 'ß' to 's', 'æ' to 'a', 'œ' to 'o',
        )

        /** Latin letters without their accents, keeping case ("Café" → "Cafe"). */
        fun fold(word: String): String = buildString {
            for (c in word) {
                val lower = c.lowercaseChar()
                val plain = ACCENTS[lower]
                append(if (plain == null) c else if (c != lower) plain.uppercaseChar() else plain)
            }
        }
    }
}

/**
 * English spelling-to-sound rules for names and new words: digraphs and common endings first,
 * then vowels by syllable shape (magic e, open or closed syllable), primary stress on the first
 * syllable (or before `-tion` / `-ic`), unstressed short vowels reduced to schwa.
 */
internal object LetterToSound {
    private class Seg(val ps: String, val vowel: Boolean)

    /** Graphemes read the same everywhere, longest first. */
    private val FIXED = listOf(
        "tion" to "ʃən", "cial" to "ʃəl", "tial" to "ʃəl", "ture" to "ʧəɹ", "eigh" to "A", "ough" to "ɔ", "augh" to "ɔ",
        "tch" to "ʧ", "sch" to "sk", "igh" to "I", "dge" to "ʤ", "ous" to "əs", "que" to "k",
        "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w", "ck" to "k", "ng" to "ŋ", "nk" to "ŋk", "qu" to "kw",
        "dg" to "ʤ", "ai" to "A", "ay" to "A", "ei" to "A", "ee" to "i", "ea" to "i", "oa" to "O", "oo" to "u", "ou" to "W",
        "oi" to "Y", "oy" to "Y", "au" to "ɔ", "aw" to "ɔ", "ew" to "u", "ue" to "u", "ui" to "u", "ie" to "i",
    )
    private val VOWEL_PS = setOf("A", "I", "O", "W", "Y", "i", "u", "æ", "ɑ", "ə", "ɛ", "ɪ", "ʊ", "ʌ", "ɔ", "ɜɹ", "əɹ", "ɑɹ", "ɔɹ", "ɛɹ", "ɪɹ", "Iɹ", "jʊɹ", "ʃən", "ʃəl", "ʧəɹ", "əs", "ᵊl")

    private fun isV(c: Char?) = c != null && c in "aeiouy"
    private fun isC(c: Char?) = c != null && c.isLetter() && !isV(c)

    fun read(word: String): String? {
        val w = word.filter { it in 'a'..'z' }
        if (w.isEmpty()) return null
        val segs = mutableListOf<Seg>()
        var i = 0
        fun at(k: Int) = w.getOrNull(k)
        while (i < w.length) {
            val c = w[i]
            val rest = w.length - i
            val fixed = FIXED.firstOrNull { (g, _) -> w.startsWith(g, i) && !(g == "ch" && (at(i + 2) == 'r' || at(i + 2) == 'l')) }
            when {
                i == 0 && (w.startsWith("kn") || w.startsWith("gn") || w.startsWith("wr") || w.startsWith("ps")) -> {
                    segs += Seg(if (w[1] == 'r') "ɹ" else if (w[1] == 's') "s" else "n", false)
                    i += 2
                }
                w.startsWith("sion", i) -> { segs += Seg(if (isV(at(i - 1))) "ʒən" else "ʃən", true); i += 4 }
                w.startsWith("sure", i) && rest == 4 -> { segs += Seg("ʒəɹ", true); i += 4 }
                w.startsWith("mb", i) && rest == 2 -> { segs += Seg("m", false); i += 2 }
                w.startsWith("gh", i) -> { if (i == 0) segs += Seg("ɡ", false); i += 2 }
                w.startsWith("ey", i) && rest == 2 -> { segs += Seg("i", true); i += 2 }
                w.startsWith("ow", i) -> { segs += Seg(if (rest == 2 || !isV(at(i + 2)) && rest == 3 && at(i + 2) == 's') "O" else "W", true); i += 2 }
                w.startsWith("oe", i) && rest == 2 -> { segs += Seg("O", true); i += 2 }
                fixed != null -> {
                    segs += Seg(fixed.second, fixed.second in VOWEL_PS || fixed.second.any { it in VOWELS })
                    i += fixed.first.length
                }
                isV(c) && c != 'y' && at(i + 1) == 'r' && !isV(at(i + 2)) || isV(c) && c != 'y' && at(i + 1) == 'r' && at(i + 2) == 'e' && rest == 3 -> {
                    val magic = at(i + 2) == 'e' && rest == 3
                    val ps = when (c) {
                        'a' -> if (magic) "ɛɹ" else "ɑɹ"
                        'o' -> "ɔɹ"
                        'e' -> if (magic) "ɪɹ" else "ɜɹ"
                        'i' -> if (magic) "Iɹ" else "ɜɹ"
                        else -> if (magic) "jʊɹ" else "ɜɹ"
                    }
                    segs += Seg(ps, true)
                    i += if (magic) 3 else 2
                }
                c == 'e' && rest == 1 && segs.any { it.vowel } -> i++
                c == 'e' && rest == 2 && at(i + 1) == 's' && segs.any { it.vowel } && segs.lastOrNull()?.ps !in setOf("s", "z", "ʃ", "ʧ", "ʤ") -> {
                    segs += Seg("z", false)
                    i += 2
                }
                c == 'e' && rest == 2 && at(i + 1) == 'd' && segs.any { it.vowel } -> {
                    val last = segs.lastOrNull()?.ps
                    segs += if (last == "t" || last == "d") Seg("ᵻd", true) else Seg(if (last in setOf("p", "k", "f", "s", "ʃ", "ʧ")) "t" else "d", false)
                    i += 2
                }
                c == 'l' && at(i + 1) == 'e' && rest == 2 && isC(at(i - 1)) -> { segs += Seg("ᵊl", true); i += 2 }
                c == 'y' -> {
                    segs += when {
                        i == 0 || isV(at(i + 1)) -> Seg("j", false)
                        rest == 1 -> Seg(if (segs.count { it.vowel } == 0) "I" else "i", true)
                        else -> Seg("ɪ", true)
                    }
                    i++
                }
                isV(c) -> {
                    val magicE = isC(at(i + 1)) && at(i + 2) == 'e' && rest == 3
                    val open = isC(at(i + 1)) && isV(at(i + 2)) && at(i + 2) != 'e' || rest == 1
                    segs += Seg(vowel(c, magicE, open, rest == 1), true)
                    i++
                }
                c == 'c' -> { segs += Seg(if (at(i + 1) in setOf('e', 'i', 'y')) "s" else "k", false); i += if (at(i + 1) == 'c' && at(i + 2) !in setOf('e', 'i', 'y')) 2 else 1 }
                c == 'g' -> { segs += Seg(if (at(i + 1) in setOf('e', 'i', 'y') && i > 0) "ʤ" else "ɡ", false); i += if (at(i + 1) == 'g') 2 else 1 }
                c == 'x' -> { segs += Seg(if (i == 0) "z" else "ks", false); i++ }
                c == 's' -> {
                    val voiced = (rest == 1 && segs.lastOrNull()?.let { it.vowel || it.ps in setOf("b", "d", "ɡ", "v", "m", "n", "l", "ɹ", "ŋ") } == true) ||
                        (isV(at(i - 1)) && isV(at(i + 1)) && i > 0)
                    segs += Seg(if (voiced) "z" else "s", false)
                    i += if (at(i + 1) == 's') 2 else 1
                }
                else -> {
                    segs += Seg(CONSONANT[c] ?: "", false)
                    i += if (at(i + 1) == c) 2 else 1
                }
            }
        }
        val vowels = segs.indices.filter { segs[it].vowel }
        if (vowels.isEmpty()) return segs.joinToString("") { it.ps }.ifEmpty { null }
        val stressed = when {
            vowels.size >= 3 && (w.endsWith("tion") || w.endsWith("sion") || w.endsWith("ic") || w.endsWith("ity")) -> vowels[vowels.size - 2]
            else -> vowels[0]
        }
        return buildString {
            for ((k, s) in segs.withIndex()) {
                if (k == stressed) append(PRIMARY_STRESS)
                append(if (k != stressed && s.vowel && vowels.size > 1) reduce(s.ps) else s.ps)
            }
        }
    }

    private fun vowel(c: Char, magicE: Boolean, open: Boolean, final: Boolean): String = when {
        magicE -> when (c) { 'a' -> "A"; 'e' -> "i"; 'i' -> "I"; 'o' -> "O"; else -> "u" }
        final -> when (c) { 'a' -> "ə"; 'e' -> "i"; 'i' -> "i"; 'o' -> "O"; else -> "u" }
        open -> when (c) { 'a' -> "ɑ"; 'e' -> "ɛ"; 'i' -> "i"; 'o' -> "O"; else -> "u" }
        else -> when (c) { 'a' -> "æ"; 'e' -> "ɛ"; 'i' -> "ɪ"; 'o' -> "ɑ"; else -> "ʌ" }
    }

    private fun reduce(ps: String): String = when (ps) {
        "æ", "ʌ", "ɑ", "ɛ" -> "ə"
        "ɜɹ" -> "əɹ"
        else -> ps
    }

    private val CONSONANT = mapOf(
        'b' to "b", 'd' to "d", 'f' to "f", 'h' to "h", 'j' to "ʤ", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n", 'p' to "p",
        'q' to "k", 'r' to "ɹ", 't' to "t", 'v' to "v", 'w' to "w", 'z' to "z",
    )
}
