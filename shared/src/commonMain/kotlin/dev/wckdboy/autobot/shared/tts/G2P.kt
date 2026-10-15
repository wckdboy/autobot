package dev.wckdboy.autobot.shared.tts

// misaki's English G2P (`en.py` `G2P.__call__`): tokens are split into sub-tokens, looked up in
// the lexicon right to left (so "the" and "to" see the next sound), multi-part words resolved
// longest-span first, and stress balanced across their parts. spaCy's tokenizer and tagger are
// replaced by [EnglishTokenizer]; espeak-ng (GPL) by [Fallback].

/** misaki's `MToken` with its `_` extension fields. */
internal class MToken(
    var text: String,
    var tag: String,
    var whitespace: String,
    var phonemes: String? = null,
    var isHead: Boolean = true,
    var alias: String? = null,
    var stress: Stress? = null,
    var currency: String? = null,
    var numFlags: String = "",
    var prespace: Boolean = false,
    var rating: Int? = null,
) {
    fun copy(text: String) = MToken(text, tag, "", isHead = true, numFlags = numFlags, stress = stress)
}

/** One read word: what was written, its phonemes (empty when silent) and the space after it. */
internal data class Phonemized(val text: String, val phonemes: String, val whitespace: String)

private val PUNCT_TAGS = setOf(".", ",", "-LRB-", "-RRB-", "``", "\"\"", "''", ":", "$", "#", "NFP")
private val PUNCT_TAG_PHONEMES = mapOf("-LRB-" to "(", "-RRB-" to ")", "``" to "“", "\"\"" to "”", "''" to "”")

private val SUBTOKEN = Regex(
    "^['‘’]+|\\p{Lu}(?=\\p{Lu}\\p{Ll})|(?:^-)?(?:\\d?[,.]?\\d)+|[-_]+|['‘’]{2,}|\\p{L}*?(?:['‘’]\\p{L})*?\\p{Ll}(?=\\p{Lu})|" +
        "\\p{L}+(?:['‘’]\\p{L})*|[^-_\\p{L}'‘’\\d]|['‘’]+$",
)

internal fun subtokenize(word: String): List<String> = SUBTOKEN.findAll(word).map { it.value }.filter { it.isNotEmpty() }.toList()

private fun isAsciiLetters(s: String) = s.all { it.lowercaseChar() in 'a'..'z' }

internal fun mergeTokens(tokens: List<MToken>, unk: String? = null): MToken {
    val stresses = tokens.mapNotNull { it.stress }.toSet()
    val currencies = tokens.mapNotNull { it.currency }
    val ratings = tokens.map { it.rating }
    val phonemes = unk?.let {
        val sb = StringBuilder()
        for (tk in tokens) {
            if (tk.prespace && sb.isNotEmpty() && !sb.last().isWhitespace() && !tk.phonemes.isNullOrEmpty()) sb.append(' ')
            sb.append(tk.phonemes ?: unk)
        }
        sb.toString()
    }
    val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
    val tag = tokens.maxByOrNull { tk -> tk.text.sumOf { c -> if (c == c.lowercaseChar()) 1 else 2 } }!!.tag
    return MToken(
        text = text,
        tag = tag,
        whitespace = tokens.last().whitespace,
        phonemes = phonemes,
        isHead = tokens.first().isHead,
        stress = if (stresses.size == 1) stresses.first() else null,
        currency = currencies.maxOrNull(),
        numFlags = tokens.flatMap { it.numFlags.toList() }.distinct().sorted().joinToString(""),
        prespace = tokens.first().prespace,
        rating = if (null in ratings) null else ratings.filterNotNull().minOrNull(),
    )
}

/** A word of [G2P.retokenize]: one token, or sub-tokens written together. */
private sealed interface Word {
    class One(val tk: MToken) : Word
    class Group(val tks: MutableList<MToken>) : Word
}

/** English text to phonemes with misaki's rules; [fallback] reads words the lexicon lacks. */
internal class G2P(private val lexicon: Lexicon, private val fallback: Fallback? = Fallback(lexicon), private val unk: String = "") {

    fun phonemize(text: String): List<Phonemized> {
        val tokens = EnglishTokenizer.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val words = retokenize(tokens)
        var ctx = TokenContext()
        for (w in words.asReversed()) {
            when (w) {
                is Word.One -> {
                    val tk = w.tk
                    if (tk.phonemes == null) lexicon(tk, ctx)?.let { tk.phonemes = it.ps; tk.rating = it.rating }
                    if (tk.phonemes == null && fallback != null) fallback(tk.alias ?: tk.text)?.let { tk.phonemes = it.ps; tk.rating = it.rating }
                    ctx = tokenContext(ctx, tk.phonemes, tk)
                }
                is Word.Group -> ctx = resolveGroup(w.tks, ctx)
            }
        }
        return words.map { w ->
            val tk = when (w) {
                is Word.One -> w.tk
                is Word.Group -> mergeTokens(w.tks, unk)
            }
            val ps = (tk.phonemes ?: unk).replace('ɾ', 'T').replace('ʔ', 't')
            Phonemized(tk.text, ps, tk.whitespace)
        }
    }

    /** The span loop of `G2P.__call__` for a multi-part word; returns the context for the word before it. */
    private fun resolveGroup(w: MutableList<MToken>, ctx0: TokenContext): TokenContext {
        var ctx = ctx0
        var left = 0
        var right = w.size
        var shouldFallback = false
        while (left < right) {
            val span = w.subList(left, right)
            val tk = if (span.any { it.alias != null || it.phonemes != null }) null else mergeTokens(span)
            val found = tk?.let { lexicon(it, ctx) }
            if (found != null) {
                w[left].phonemes = found.ps
                w[left].rating = found.rating
                for (x in w.subList(left + 1, right)) {
                    x.phonemes = ""
                    x.rating = found.rating
                }
                ctx = tokenContext(ctx, found.ps, tk)
                right = left
                left = 0
            } else if (left + 1 < right) {
                left++
            } else {
                right--
                val last = w[right]
                if (last.phonemes == null) {
                    if (last.text.all { it in SUBTOKEN_JUNKS }) {
                        last.phonemes = ""
                        last.rating = 3
                    } else if (fallback != null) {
                        shouldFallback = true
                        break
                    }
                }
                left = 0
            }
        }
        if (shouldFallback) {
            val tk = mergeTokens(w)
            val found = fallback!!(tk.text)
            w[0].phonemes = found?.ps
            w[0].rating = found?.rating
            for (j in 1 until w.size) {
                w[j].phonemes = ""
                w[j].rating = found?.rating
            }
        } else {
            resolveTokens(w)
        }
        return ctx
    }

    private fun retokenize(tokens: List<MToken>): List<Word> {
        val words = mutableListOf<Word>()
        var currency: String? = null
        for ((i, token) in tokens.withIndex()) {
            val tks = if (token.alias == null && token.phonemes == null) {
                subtokenize(token.text).map { token.copy(it) }.ifEmpty { listOf(token) }
            } else {
                listOf(token)
            }
            tks.last().whitespace = token.whitespace
            for ((j, tk) in tks.withIndex()) {
                if (tk.alias != null || tk.phonemes != null) {
                    // Already read.
                } else if (tk.tag == "$" && tk.text in CURRENCIES) {
                    currency = tk.text
                    tk.phonemes = ""
                    tk.rating = 4
                } else if (tk.tag == ":" && (tk.text == "-" || tk.text == "–")) {
                    tk.phonemes = "—"
                    tk.rating = 3
                } else if (tk.tag in PUNCT_TAGS && !isAsciiLetters(tk.text)) {
                    tk.phonemes = PUNCT_TAG_PHONEMES[tk.tag] ?: tk.text.filter { it in PUNCTS }
                    tk.rating = 4
                } else if (currency != null) {
                    if (tk.tag != "CD") {
                        currency = null
                    } else if (j + 1 == tks.size && (i + 1 == tokens.size || tokens[i + 1].tag != "CD")) {
                        tk.currency = currency
                    }
                } else if (j > 0 && j < tks.size - 1 && tk.text == "2" && (tks[j - 1].text.last().toString() + tks[j + 1].text.first()).all { it.isLetter() }) {
                    tk.alias = "to"
                }
                val last = words.lastOrNull()
                if (tk.alias != null || tk.phonemes != null) {
                    words += Word.One(tk)
                } else if (last is Word.Group && last.tks.last().whitespace.isEmpty()) {
                    tk.isHead = false
                    last.tks += tk
                } else {
                    words += if (tk.whitespace.isNotEmpty()) Word.One(tk) else Word.Group(mutableListOf(tk))
                }
            }
        }
        return words.map { if (it is Word.Group && it.tks.size == 1) Word.One(it.tks[0]) else it }
    }

    companion object {
        fun tokenContext(ctx: TokenContext, ps: String?, token: MToken): TokenContext {
            var vowel = ctx.futureVowel
            if (!ps.isNullOrEmpty()) {
                val c = ps.firstOrNull { it in VOWELS || it in CONSONANTS || it in NON_QUOTE_PUNCTS }
                if (c != null) vowel = if (c in NON_QUOTE_PUNCTS) null else c in VOWELS
            }
            val futureTo = token.text == "to" || token.text == "To" || (token.text == "TO" && (token.tag == "TO" || token.tag == "IN"))
            return TokenContext(vowel, futureTo)
        }

        fun resolveTokens(tokens: List<MToken>) {
            val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
            val prespace = ' ' in text || '/' in text ||
                text.filter { it !in SUBTOKEN_JUNKS }.map { c -> if (c.isLetter()) 0 else if (c in '0'..'9') 1 else 2 }.toSet().size > 1
            for ((i, tk) in tokens.withIndex()) {
                if (tk.phonemes == null) {
                    if (i == tokens.size - 1 && tk.text.length == 1 && tk.text[0] in NON_QUOTE_PUNCTS) {
                        tk.phonemes = tk.text
                        tk.rating = 3
                    } else if (tk.text.all { it in SUBTOKEN_JUNKS }) {
                        tk.phonemes = ""
                        tk.rating = 3
                    }
                } else if (i > 0) {
                    tk.prespace = prespace
                }
            }
            if (prespace) return
            val indices = tokens.withIndex().filter { !it.value.phonemes.isNullOrEmpty() }
                .map { (i, tk) -> Triple(PRIMARY_STRESS in tk.phonemes!!, stressWeight(tk.phonemes), i) }
            if (indices.size == 2 && tokens[indices[0].third].text.length == 1) {
                val i = indices[1].third
                tokens[i].phonemes = applyStress(tokens[i].phonemes, -0.5)
                return
            }
            if (indices.size < 2 || indices.count { it.first } <= (indices.size + 1) / 2) return
            val demote = indices.sortedWith(compareBy<Triple<Boolean, Int, Int>>({ it.first }, { it.second }, { it.third })).take(indices.size / 2)
            for ((_, _, i) in demote) tokens[i].phonemes = applyStress(tokens[i].phonemes, -0.5)
        }
    }
}
