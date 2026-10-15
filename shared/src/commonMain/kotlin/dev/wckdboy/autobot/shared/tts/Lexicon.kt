package dev.wckdboy.autobot.shared.tts

// A port of misaki's English lexicon (hexgrad/misaki `en.py` @ fba12365, Apache-2.0): gold and
// silver dictionaries, stress rules, -s / -ed / -ing stems and number reading. Function and
// branch names follow the Python so the two can be compared line by line.

internal const val PRIMARY_STRESS = 'ˈ'
internal const val SECONDARY_STRESS = 'ˌ'
private const val STRESSES = "ˌˈ"
internal const val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ"
internal const val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ"
private const val DIPHTHONGS = "AIOQWYʤʧ"
private const val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ"
internal const val PUNCTS = ";:,.!?—…\"“”"
internal const val NON_QUOTE_PUNCTS = ";:,.!?—…"
internal const val SUBTOKEN_JUNKS = "',-._‘’/"

internal val CURRENCIES = mapOf("$" to ("dollar" to "cent"), "£" to ("pound" to "pence"), "€" to ("euro" to "cent"))
private val ORDINALS = setOf("st", "nd", "rd", "th")
private val ADD_SYMBOLS = mapOf("." to "dot", "/" to "slash")
internal val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")

/** Stress of a lookup: misaki's `None, -2, -1, -0.5, 0, 0.5, 1, 2`. */
internal typealias Stress = Double

internal fun stressWeight(ps: String?): Int = ps?.sumOf { if (it in DIPHTHONGS) 2 else 1 } ?: 0

/** Moves every stress mark in [ps] to just before the next vowel (misaki's `restress`). */
private fun restress(ps: String): String {
    val positions = ps.indices.map { it.toDouble() }.toMutableList()
    for (i in ps.indices) {
        if (ps[i] !in STRESSES) continue
        val j = (i until ps.length).firstOrNull { ps[it] in VOWELS } ?: continue
        positions[i] = j - 0.5
    }
    return ps.indices.sortedWith(compareBy({ positions[it] }, { it })).map { ps[it] }.joinToString("")
}

internal fun applyStress(ps: String?, stress: Stress?): String? {
    if (ps == null || stress == null) return ps
    val hasPrimary = PRIMARY_STRESS in ps
    val hasAny = ps.any { it in STRESSES }
    return when {
        stress < -1 -> ps.replace(PRIMARY_STRESS.toString(), "").replace(SECONDARY_STRESS.toString(), "")
        stress == -1.0 || (stress in setOf(0.0, -0.5) && hasPrimary) ->
            ps.replace(SECONDARY_STRESS.toString(), "").replace(PRIMARY_STRESS, SECONDARY_STRESS)
        stress in setOf(0.0, 0.5, 1.0) && !hasAny -> if (ps.none { it in VOWELS }) ps else restress(SECONDARY_STRESS + ps)
        stress >= 1 && !hasPrimary && SECONDARY_STRESS in ps -> ps.replace(SECONDARY_STRESS, PRIMARY_STRESS)
        stress > 1 && !hasAny -> if (ps.none { it in VOWELS }) ps else restress(PRIMARY_STRESS + ps)
        else -> ps
    }
}

/** What follows the word being read (misaki's `TokenContext`). */
internal data class TokenContext(val futureVowel: Boolean? = null, val futureTo: Boolean = false)

/** A phoneme string and its rating (4 gold, 3 silver or rule, 1 fallback). */
internal data class Ps(val ps: String, val rating: Int)

/** One lexicon entry: a pronunciation, or pronunciations by part-of-speech tag (`DEFAULT` always present). */
internal sealed interface Entry {
    class One(val ps: String) : Entry
    class ByTag(val byTag: Map<String, String?>) : Entry
}

private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }
private fun isAlpha(s: String) = s.isNotEmpty() && s.all { it.isLetter() }
private fun isLexiconChar(c: Char) = c == '\'' || c == '-' || c in 'A'..'Z' || c in 'a'..'z'
private fun String.capitalized() = if (isEmpty()) this else this[0].uppercase() + substring(1).lowercase()

/**
 * misaki's `Lexicon` for American ([british] false) or British English: [golds] and [silvers]
 * are its `*_gold.json` / `*_silver.json`, grown with capitalized and lower-case variants.
 */
internal class Lexicon(val british: Boolean, golds: Map<String, Entry>, silvers: Map<String, Entry>) {
    private val golds = grow(golds)
    private val silvers = grow(silvers)
    private val capStresses = 0.5 to 2.0

    fun hasGold(word: String) = word in golds

    private fun gold(word: String): String = when (val e = golds[word]) {
        is Entry.One -> e.ps
        is Entry.ByTag -> e.byTag["DEFAULT"]!!
        null -> error("missing gold entry $word")
    }

    fun getNNP(word: String): Ps? {
        val parts = word.filter { it.isLetter() }.map { c ->
            when (val e = golds[c.uppercase()]) {
                is Entry.One -> e.ps
                is Entry.ByTag -> e.byTag["DEFAULT"]
                null -> null
            } ?: return null
        }
        val ps = applyStress(parts.joinToString(""), 0.0)!!
        val at = ps.lastIndexOf(SECONDARY_STRESS)
        return Ps(if (at < 0) ps else ps.substring(0, at) + PRIMARY_STRESS + ps.substring(at + 1), 3)
    }

    private fun getSpecialCase(word: String, tag: String, stress: Stress?, ctx: TokenContext): Ps? {
        val stripped = word.trim('.')
        return when {
            tag == "ADD" && word in ADD_SYMBOLS -> lookup(ADD_SYMBOLS.getValue(word), null, -0.5, ctx)
            word in SYMBOLS -> lookup(SYMBOLS.getValue(word), null, null, ctx)
            '.' in stripped && isAlpha(word.replace(".", "")) && word.split('.').maxOf { it.length } < 3 -> getNNP(word)
            word == "a" || word == "A" -> Ps(if (tag == "DT") "ɐ" else "ˈA", 4)
            word == "am" || word == "Am" || word == "AM" -> when {
                tag.startsWith("NN") -> getNNP(word)
                ctx.futureVowel == null || word != "am" || (stress != null && stress > 0) -> Ps(gold("am"), 4)
                else -> Ps("ɐm", 4)
            }
            word == "an" || word == "An" || word == "AN" ->
                if (word == "AN" && tag.startsWith("NN")) getNNP(word) else Ps("ɐn", 4)
            word == "I" && tag == "PRP" -> Ps("${SECONDARY_STRESS}I", 4)
            (word == "by" || word == "By" || word == "BY") && parentTag(tag) == "ADV" -> Ps("bˈI", 4)
            word == "to" || word == "To" || (word == "TO" && (tag == "TO" || tag == "IN")) -> Ps(
                when (ctx.futureVowel) {
                    null -> gold("to")
                    false -> "tə"
                    true -> "tʊ"
                },
                4,
            )
            word == "in" || word == "In" || (word == "IN" && tag != "NNP") ->
                Ps((if (ctx.futureVowel == null || tag != "IN") PRIMARY_STRESS.toString() else "") + "ɪn", 4)
            word == "the" || word == "The" || (word == "THE" && tag == "DT") -> Ps(if (ctx.futureVowel == true) "ði" else "ðə", 4)
            tag == "IN" && VS.matches(word) -> lookup("versus", null, null, ctx)
            word == "used" || word == "Used" || word == "USED" -> {
                val e = golds["used"] as Entry.ByTag
                Ps((if ((tag == "VBD" || tag == "JJ") && ctx.futureTo) e.byTag["VBD"] else e.byTag["DEFAULT"])!!, 4)
            }
            else -> null
        }
    }

    fun isKnown(word: String): Boolean = when {
        word in golds || word in SYMBOLS || word in silvers -> true
        !isAlpha(word) || !word.all(::isLexiconChar) -> false
        word.length == 1 -> true
        word == word.uppercase() && word.lowercase() in golds -> true
        else -> word.substring(1) == word.substring(1).uppercase()
    }

    fun lookup(word0: String, tag0: String?, stress: Stress?, ctx: TokenContext?): Ps? {
        var word = word0
        var tag = tag0
        var isNNP: Boolean? = null
        if (word == word.uppercase() && word !in golds) {
            word = word.lowercase()
            isNNP = tag == "NNP"
        }
        var entry = golds[word]
        var rating = 4
        if (entry == null && isNNP != true) {
            entry = silvers[word]
            rating = 3
        }
        var ps: String? = when (entry) {
            is Entry.One -> entry.ps
            is Entry.ByTag -> {
                val m = entry.byTag
                if (ctx != null && ctx.futureVowel == null && "None" in m) {
                    tag = "None"
                } else if (tag !in m) {
                    tag = parentTag(tag)
                }
                if (tag in m) m[tag] else m["DEFAULT"]
            }
            null -> null
        }
        if (ps == null || (isNNP == true && PRIMARY_STRESS !in ps)) return getNNP(word)
        return Ps(applyStress(ps, stress)!!, rating)
    }

    private fun s(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "ptkfθ" -> stem + "s"
        stem.last() in "szʃʒʧʤ" -> stem + (if (british) "ɪ" else "ᵻ") + "z"
        else -> stem + "z"
    }

    fun stemS(word: String, tag: String?, stress: Stress?, ctx: TokenContext?): Ps? {
        if (word.length < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) && isKnown(word.dropLast(2)) -> word.dropLast(2)
            word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y") -> word.dropLast(3) + "y"
            else -> return null
        }
        val found = lookup(stem, tag, stress, ctx) ?: return null
        return s(found.ps)?.let { Ps(it, found.rating) }
    }

    private fun ed(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        stem.last() in "pkfθʃsʧ" -> stem + "t"
        stem.last() == 'd' -> stem + (if (british) "ɪ" else "ᵻ") + "d"
        stem.last() != 't' -> stem + "d"
        british || stem.length < 2 -> stem + "ɪd"
        stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
        else -> stem + "ᵻd"
    }

    fun stemEd(word: String, tag: String?, stress: Stress?, ctx: TokenContext?): Ps? {
        if (word.length < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && isKnown(word.dropLast(1)) -> word.dropLast(1)
            word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2)) -> word.dropLast(2)
            else -> return null
        }
        val found = lookup(stem, tag, stress, ctx) ?: return null
        return ed(found.ps)?.let { Ps(it, found.rating) }
    }

    private fun ing(stem: String?): String? = when {
        stem.isNullOrEmpty() -> null
        british -> if (stem.last() in "əː") null else stem + "ɪŋ"
        stem.length > 1 && stem.last() == 't' && stem[stem.length - 2] in US_TAUS -> stem.dropLast(1) + "ɾɪŋ"
        else -> stem + "ɪŋ"
    }

    fun stemIng(word: String, tag: String?, stress: Stress?, ctx: TokenContext?): Ps? {
        if (word.length < 5 || !word.endsWith("ing")) return null
        val stem = when {
            word.length > 5 && isKnown(word.dropLast(3)) -> word.dropLast(3)
            isKnown(word.dropLast(3) + "e") -> word.dropLast(3) + "e"
            word.length > 5 && DOUBLED_ING.containsMatchIn(word) && isKnown(word.dropLast(4)) -> word.dropLast(4)
            else -> return null
        }
        val found = lookup(stem, tag, stress, ctx) ?: return null
        return ing(found.ps)?.let { Ps(it, found.rating) }
    }

    fun getWord(word0: String, tag: String, stress: Stress?, ctx: TokenContext): Ps? {
        getSpecialCase(word0, tag, stress, ctx)?.let { return it }
        var word = word0
        val wl = word.lowercase()
        if (word.length > 1 && isAlpha(word.replace("'", "")) && word != wl && (tag != "NNP" || word.length > 7) &&
            word !in golds && word !in silvers && (word == word.uppercase() || word.substring(1) == word.substring(1).lowercase()) &&
            (wl in golds || wl in silvers || stemS(wl, tag, stress, ctx) != null || stemEd(wl, tag, stress, ctx) != null || stemIng(wl, tag, stress, ctx) != null)
        ) {
            word = wl
        }
        return when {
            isKnown(word) -> lookup(word, tag, stress, ctx)
            word.endsWith("s'") && isKnown(word.dropLast(2) + "'s") -> lookup(word.dropLast(2) + "'s", tag, stress, ctx)
            word.endsWith("'") && isKnown(word.dropLast(1)) -> lookup(word.dropLast(1), tag, stress, ctx)
            else -> stemS(word, tag, stress, ctx) ?: stemEd(word, tag, stress, ctx) ?: stemIng(word, tag, stress ?: 0.5, ctx)
        }
    }

    private fun getNumber(word0: String, currency: String?, isHead: Boolean): Ps? {
        val suffix = NUMBER_SUFFIX.find(word0)?.value
        var word = if (suffix != null) word0.dropLast(suffix.length) else word0
        val result = mutableListOf<Ps?>()
        if (word.startsWith("-")) {
            result += lookup("minus", null, null, null)
            word = word.substring(1)
        }
        fun extend(num: String, escape: Boolean = false) {
            val words = if (escape) num else EnglishNumbers.cardinal(num.toLong())
            for (w in words.split(NON_LETTERS)) {
                if (w != "and") result += lookup(w, null, if (w == "point") -2.0 else null, null)
            }
        }
        when {
            isDigits(word) && suffix in ORDINALS -> extend(EnglishNumbers.ordinal(word.toLong()), escape = true)
            result.isEmpty() && word.length == 4 && currency !in CURRENCIES && isDigits(word) -> extend(EnglishNumbers.year(word.toLong()), escape = true)
            !isHead && '.' !in word -> {
                val num = word.replace(",", "")
                when {
                    num.isEmpty() -> Unit
                    num[0] == '0' || num.length > 3 -> num.forEach { extend(it.toString()) }
                    num.length == 3 && !num.endsWith("00") -> {
                        extend(num.substring(0, 1))
                        if (num[1] == '0') {
                            result += lookup("O", null, -2.0, null)
                            extend(num.substring(2))
                        } else {
                            extend(num.substring(1))
                        }
                    }
                    else -> extend(num)
                }
            }
            word.count { it == '.' } > 1 || !isHead -> {
                for (num in word.replace(",", "").split('.')) {
                    when {
                        num.isEmpty() -> Unit
                        num[0] == '0' || (num.length != 2 && num.substring(1).any { it != '0' }) -> num.forEach { extend(it.toString()) }
                        else -> extend(num)
                    }
                }
            }
            currency in CURRENCIES && isCurrency(word) -> {
                val units = CURRENCIES.getValue(currency!!)
                var pairs = word.replace(",", "").split('.').zip(listOf(units.first, units.second)).map { (n, unit) -> (n.toLongOrNull() ?: 0L) to unit }
                if (pairs.size > 1) {
                    if (pairs[1].first == 0L) pairs = pairs.take(1) else if (pairs[0].first == 0L) pairs = pairs.drop(1)
                }
                pairs.forEachIndexed { i, (num, unit) ->
                    if (i > 0) result += lookup("and", null, null, null)
                    extend(num.toString())
                    result += if (num != 1L && num != -1L && unit != "pence") stemS(unit + "s", null, null, null) else lookup(unit, null, null, null)
                }
            }
            else -> {
                val text = when {
                    isDigits(word) -> EnglishNumbers.cardinal(word.toLong())
                    '.' !in word -> word.replace(",", "").toLongOrNull()?.let { if (suffix in ORDINALS) EnglishNumbers.ordinal(it) else EnglishNumbers.cardinal(it) }
                    else -> word.replace(",", "").let { w ->
                        if (w.startsWith(".")) {
                            "point " + w.substring(1).map { EnglishNumbers.cardinal(it.digitToInt().toLong()) }.joinToString(" ")
                        } else {
                            EnglishNumbers.decimal(w)
                        }
                    }
                } ?: return null
                extend(text, escape = true)
            }
        }
        val found = result.filterNotNull()
        if (found.isEmpty()) return null
        val ps = found.joinToString(" ") { it.ps }
        val rating = found.minOf { it.rating }
        return when (suffix) {
            "s", "'s" -> s(ps)
            "ed", "'d" -> ed(ps)
            "ing" -> ing(ps)
            else -> ps
        }?.let { Ps(it, rating) }
    }

    private fun appendCurrency(ps: String, currency: String?): String {
        val unit = CURRENCIES[currency ?: return ps] ?: return ps
        val plural = stemS(unit.first + "s", null, null, null)?.ps ?: return ps
        return "$ps $plural"
    }

    /** Phonemes for one token (misaki's `Lexicon.__call__`), or null when it is not in the lexicon. */
    operator fun invoke(tk: MToken, ctx: TokenContext): Ps? {
        val word = (tk.alias ?: tk.text).replace('‘', '\'').replace('’', '\'')
        val stress = if (word == word.lowercase()) null else if (word == word.uppercase()) capStresses.second else capStresses.first
        getWord(word, tk.tag, stress, ctx)?.let { return Ps(applyStress(appendCurrency(it.ps, tk.currency), tk.stress)!!, it.rating) }
        if (isNumber(word, tk.isHead)) {
            return getNumber(word, tk.currency, tk.isHead)?.let { Ps(applyStress(it.ps, tk.stress)!!, it.rating) }
        }
        return null
    }

    companion object {
        private val VS = Regex("(?i)vs\\.?")
        private val DOUBLED_ING = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")
        private val NUMBER_SUFFIX = Regex("[a-z']+$")
        private val NON_LETTERS = Regex("[^a-z]+")
        private val SUFFIXES = listOf("ing", "'d", "ed", "'s", "st", "nd", "rd", "th", "s")

        fun parentTag(tag: String?): String? = when {
            tag == null -> null
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("ADV") || tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADJ") || tag.startsWith("JJ") -> "ADJ"
            else -> tag
        }

        /** misaki's `is_currency` (its `set(cents) == {0}` never holds, so only short cents count). */
        private fun isCurrency(word: String): Boolean = when {
            '.' !in word -> true
            word.count { it == '.' } > 1 -> false
            else -> word.split('.')[1].length < 3
        }

        fun isNumber(word0: String, isHead: Boolean): Boolean {
            if (word0.none { it in '0'..'9' }) return false
            var word = word0
            SUFFIXES.firstOrNull { word.endsWith(it) }?.let { word = word.dropLast(it.length) }
            return word.withIndex().all { (i, c) -> c in '0'..'9' || c == ',' || c == '.' || (isHead && i == 0 && c == '-') }
        }

        /** misaki's `grow_dictionary`: adds `Word` for `word` and `word` for `Word` (the given keys win). */
        private fun grow(d: Map<String, Entry>): Map<String, Entry> {
            val out = HashMap<String, Entry>(d.size * 2)
            for ((k, v) in d) {
                if (k.length < 2) continue
                val lower = k.lowercase()
                if (k == lower) {
                    val cap = k.capitalized()
                    if (k != cap) out[cap] = v
                } else if (k == lower.capitalized()) {
                    out[lower] = v
                }
            }
            out.putAll(d)
            return out
        }
    }
}

/**
 * Reads misaki's lexicon JSON (`{"word": "phonemes" | {"TAG": "phonemes" | null, …}}`) without a
 * general JSON tree: the files are several MB and read once per voice load.
 */
internal object LexiconJson {
    fun parse(json: String): Map<String, Entry> {
        val p = Parser(json)
        val out = HashMap<String, Entry>(120_000)
        p.expect('{')
        if (p.peek() == '}') return out
        while (true) {
            val key = p.string()
            p.expect(':')
            out[key] = if (p.peek() == '{') {
                p.expect('{')
                val m = HashMap<String, String?>(4)
                if (p.peek() != '}') {
                    while (true) {
                        val tag = p.string()
                        p.expect(':')
                        m[tag] = if (p.peek() == 'n') p.nul() else p.string()
                        if (p.peek() == ',') p.expect(',') else break
                    }
                }
                p.expect('}')
                Entry.ByTag(m)
            } else {
                Entry.One(p.string())
            }
            if (p.peek() == ',') p.expect(',') else break
        }
        p.expect('}')
        return out
    }

    private class Parser(val s: String) {
        var i = 0

        fun peek(): Char {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) throw IllegalArgumentException("unexpected end of lexicon")
            return s[i]
        }

        fun expect(c: Char) {
            if (peek() != c) throw IllegalArgumentException("expected '$c' at $i in lexicon")
            i++
        }

        fun nul(): String? {
            if (!s.startsWith("null", i)) throw IllegalArgumentException("bad value at $i in lexicon")
            i += 4
            return null
        }

        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
