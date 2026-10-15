package dev.wckdboy.autobot.shared.tts

/**
 * Stands in for spaCy's `en_core_web_sm` tokenizer and tagger in misaki: words and punctuation
 * with Penn Treebank tags. Punctuation is split off as spaCy does; contractions and hyphenated
 * words stay whole (misaki's sub-tokens rejoin them anyway). The tags only steer a few readings
 * (`a`, `the`, `to`, `used`, `read`, noun/verb heteronyms, proper-noun spelling), so closed-class
 * word lists and the previous tag stand in for the statistical tagger.
 */
internal object EnglishTokenizer {
    private const val OPENING = "\"“‘«([{"
    private const val CLOSING = "\"”’»)]}"
    private const val CURRENCY = "$£€"

    /** Characters that are always a token of their own. */
    private const val SPLIT = "\"“”«»()[]{};!?…—–*~"

    private val INITIALS = Regex("^(\\p{L}\\.)+$")

    fun tokenize(text: String): List<MToken> {
        val pieces = mutableListOf<Pair<String, String>>()
        val chunks = Regex("\\S+").findAll(text).toList()
        for ((n, m) in chunks.withIndex()) {
            val split = splitChunk(m.value)
            val space = if (n + 1 < chunks.size) " " else ""
            split.forEachIndexed { i, piece -> pieces += piece to (if (i == split.size - 1) space else "") }
        }
        val tokens = mutableListOf<MToken>()
        for ((i, p) in pieces.withIndex()) {
            val (piece, ws) = p
            val prevSpace = i == 0 || pieces[i - 1].second.isNotEmpty()
            tokens += MToken(piece, punctTag(piece, prevSpace) ?: "", ws)
        }
        tagWords(tokens)
        return tokens
    }

    /** Splits a whitespace-free chunk into word and punctuation pieces. */
    private fun splitChunk(chunk: String): List<String> {
        val lead = mutableListOf<String>()
        val trail = ArrayDeque<String>()
        var s = chunk
        while (s.isNotEmpty()) {
            val c = s[0]
            when {
                s.startsWith("...") -> { lead += "..."; s = s.substring(3) }
                c in OPENING || c in SPLIT -> { lead += c.toString(); s = s.substring(1) }
                c in CURRENCY && s.length > 1 -> { lead += c.toString(); s = s.substring(1) }
                c == '\'' && s.length > 1 && s.endsWith("'") && s.length > 2 -> { lead += "'"; s = s.substring(1) }
                s.startsWith("--") -> { lead += "--"; s = s.substring(2) }
                c == '-' && s.length > 1 && !s[1].isDigit() && s[1] != '.' -> { lead += "-"; s = s.substring(1) }
                else -> break
            }
        }
        while (s.isNotEmpty()) {
            val c = s.last()
            when {
                s.endsWith("...") && s.length > 3 -> { trail.addFirst("..."); s = s.dropLast(3) }
                c in CLOSING || c in SPLIT || c in ",:%" -> { trail.addFirst(c.toString()); s = s.dropLast(1) }
                c == '.' && s.length > 1 && !INITIALS.matches(s) -> { trail.addFirst("."); s = s.dropLast(1) }
                c == '\'' && s.length > 1 && lead.contains("'") -> { trail.addFirst("'"); s = s.dropLast(1) }
                s.endsWith("--") && s.length > 2 -> { trail.addFirst("--"); s = s.dropLast(2) }
                c == '-' && s.length > 1 -> { trail.addFirst("-"); s = s.dropLast(1) }
                else -> break
            }
        }
        val middle = mutableListOf<String>()
        if (s.isNotEmpty()) {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val between = i > 0 && i < s.length - 1
                val infix = when {
                    s.startsWith("--", i) && between -> "--"
                    s.startsWith("...", i) && between -> "..."
                    c in SPLIT -> c.toString()
                    // A comma or colon splits words but not numbers (1,000 · 3:30).
                    (c == ',' || c == ':' || c == ';') && !(between && s[i - 1].isDigit() && s[i + 1].isDigit()) -> c.toString()
                    else -> null
                }
                if (infix != null) {
                    if (sb.isNotEmpty()) middle += sb.toString().also { sb.clear() }
                    middle += infix
                    i += infix.length
                } else {
                    sb.append(c)
                    i++
                }
            }
            if (sb.isNotEmpty()) middle += sb.toString()
        }
        return lead + middle + trail
    }

    /** The tag of a punctuation or symbol piece, or null for a word. */
    private fun punctTag(p: String, prevSpace: Boolean): String? = when (p) {
        ".", "!", "?" -> "."
        "," -> ","
        ":", ";", "—", "–", "-", "--", "…", "..." -> ":"
        "“", "‘", "«" -> "``"
        "”", "’", "»" -> "''"
        "\"", "'" -> if (prevSpace) "``" else "''"
        "(", "[", "{" -> "-LRB-"
        ")", "]", "}" -> "-RRB-"
        "$", "£", "€" -> "$"
        "%" -> "NN"
        "*", "~", "#" -> "NFP"
        else -> if (p.none { it.isLetterOrDigit() }) "NFP" else null
    }

    private val DT = setOf("a", "an", "the", "this", "these", "those", "some", "any", "each", "every", "no", "all", "both", "another", "either", "neither")
    private val PRP = setOf(
        "i", "you", "he", "she", "it", "we", "they", "me", "him", "us", "them", "myself", "yourself", "himself", "herself", "itself",
        "ourselves", "yourselves", "themselves",
    )
    private val PRP_POSSESSIVE = setOf("my", "your", "his", "its", "our", "their", "her")
    private val IN = setOf(
        "of", "in", "on", "at", "for", "with", "from", "by", "about", "as", "into", "over", "under", "after", "before", "between",
        "through", "during", "without", "within", "against", "among", "across", "behind", "beyond", "upon", "near", "since", "until",
        "than", "because", "if", "while", "although", "though", "whether", "per", "via", "despite", "toward", "towards", "around",
        "along", "onto", "unlike", "like", "vs", "vs.", "versus",
    )
    private val CC = setOf("and", "or", "but", "nor", "yet", "plus")
    private val MD = setOf("will", "would", "can", "could", "shall", "should", "may", "might", "must", "cannot")
    private val WP = setOf("what", "which", "who", "whom", "whose")
    private val WRB = setOf("when", "where", "why", "how")
    private val RB = setOf(
        "not", "very", "also", "just", "too", "never", "always", "often", "here", "now", "then", "really", "already", "still", "even",
        "only", "quite", "rather", "again", "away", "back", "soon", "later", "ever", "perhaps", "maybe", "almost", "once", "twice",
        "together", "instead", "anyway", "else", "however", "therefore", "thus", "indeed", "sometimes", "usually", "yesterday", "today",
        "tomorrow", "tonight", "there",
    )
    private val UH = setOf("hello", "hi", "hey", "oh", "yes", "yeah", "ok", "okay", "wow", "please", "thanks", "bye", "goodbye", "ah", "um", "uh")
    private val CD = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
        "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
        "ninety", "hundred", "thousand", "million", "billion", "trillion", "dozen",
    )
    private val VERBS = mapOf(
        "is" to "VBZ", "are" to "VBP", "am" to "VBP", "was" to "VBD", "were" to "VBD", "be" to "VB", "been" to "VBN", "being" to "VBG",
        "has" to "VBZ", "have" to "VBP", "had" to "VBD", "do" to "VBP", "does" to "VBZ", "did" to "VBD", "done" to "VBN", "doing" to "VBG",
        "let" to "VB", "said" to "VBD", "says" to "VBZ", "get" to "VB", "got" to "VBD", "go" to "VBP", "went" to "VBD", "gone" to "VBN",
    )

    private fun isWord(tk: MToken) = tk.tag.isEmpty()

    private fun tagWords(tokens: List<MToken>) {
        var sentenceStart = true
        var prev = ""
        for ((i, tk) in tokens.withIndex()) {
            if (!isWord(tk)) {
                if (tk.tag == ".") sentenceStart = true
                if (tk.tag != "``" && tk.tag != "''") prev = tk.tag
                continue
            }
            val next = tokens.getOrNull(i + 1)
            // An imperative follows "please" ("please record it").
            tk.tag = tagOf(tk.text, if (tokens.getOrNull(i - 1)?.text?.lowercase() == "please") "MD" else prev, next, sentenceStart)
            prev = tk.tag
            sentenceStart = false
        }
    }

    private fun tagOf(word: String, prev: String, next: MToken?, sentenceStart: Boolean): String {
        if (word.any { it.isDigit() }) {
            return if (word.any { it.isLetter() } && !Lexicon.isNumber(word, true)) "NN" else "CD"
        }
        val lower = word.lowercase()
        val base = lower.substringBefore('\'').substringBefore('’')
        val letters = word.filter { it.isLetter() }
        val nextIsWordEnd = next == null || (!isWord(next) && next.tag != "$")
        return when {
            word == "I" -> "PRP"
            letters.length >= 2 && letters == letters.uppercase() && letters != letters.lowercase() -> "NNP"
            lower == "to" -> "TO"
            base == "that" -> when {
                // Demonstrative ("that's", "repeat that."), relative ("all that glitters", "the book that…"), or complementizer.
                base != lower || !isWord(next ?: return "DT") || sentenceStart -> "DT"
                prev == "DT" || prev == "NN" || prev == "NNS" -> "WDT"
                next.text.lowercase() in PRP || next.text.lowercase() in DT -> "IN"
                else -> "DT"
            }
            (lower == "by" || lower == "in") && nextIsWordEnd -> "RB"
            lower == "her" -> if (next != null && isWord(next) && next.text.lowercase() !in IN && next.text.lowercase() !in CC) "PRP$" else "PRP"
            lower in DT -> "DT"
            lower in PRP || base in PRP -> "PRP"
            lower in PRP_POSSESSIVE -> "PRP$"
            lower in IN -> "IN"
            lower in CC -> "CC"
            lower in MD || (base in MD && base != lower) -> "MD"
            base in VERBS -> VERBS.getValue(base)
            lower in WP -> "WP"
            lower in WRB -> "WRB"
            lower in UH -> "UH"
            lower in RB -> "RB"
            lower in CD -> "CD"
            word[0].isUpperCase() && !sentenceStart -> "NNP"
            lower.endsWith("n't") -> "VBP"
            prev == "TO" || prev == "MD" -> "VB"
            lower.endsWith("ing") && lower.length > 4 -> "VBG"
            lower.endsWith("ly") && lower.length > 4 -> "RB"
            prev == "PRP" || prev == "NNP" || prev == "NNS" || prev == "NN" || prev == "WP" -> when {
                lower.endsWith("ed") -> "VBD"
                lower.endsWith("s") && !lower.endsWith("ss") -> "VBZ"
                prev == "PRP" -> "VBP"
                else -> "NN"
            }
            lower.endsWith("ed") && (prev.startsWith("VB") || prev == "RB") -> "VBN"
            lower.endsWith("ed") && prev != "DT" && prev != "PRP$" -> "VBD"
            lower.endsWith("s") && !lower.endsWith("ss") && lower.length > 3 -> "NNS"
            else -> "NN"
        }
    }
}
