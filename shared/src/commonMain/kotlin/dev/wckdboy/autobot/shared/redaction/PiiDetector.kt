package dev.wckdboy.autobot.shared.redaction

/**
 * What the redaction gateway hides from a cloud provider. [tag] is the placeholder's name
 * (`[PERSON_1]`) and the key in a receipt's counts; [label] / [plural] read in the receipt.
 */
enum class PiiKind(val tag: String, val label: String, val plural: String) {
    PERSON("PERSON", "name", "names"),
    EMAIL("EMAIL", "email", "emails"),
    PHONE("PHONE", "phone number", "phone numbers"),
    ADDRESS("ADDRESS", "address", "addresses"),
    CARD("CARD", "card number", "card numbers"),
    IBAN("IBAN", "IBAN", "IBANs"),
    ACCOUNT("ACCOUNT", "account number", "account numbers"),
    PRIVATE("PRIVATE", "memory fact", "memory facts"),
    ;

    /** "1 name", "3 emails". */
    fun count(n: Int): String = "$n ${if (n == 1) label else plural}"

    /** Matched by its value's digits (a phone written with other spacing is the same phone). */
    internal val numeric: Boolean get() = this == PHONE || this == CARD || this == ACCOUNT

    /** Plain words: not looked for inside code or links, where such words are rarely personal. */
    internal val wordy: Boolean get() = this == PERSON || this == ADDRESS

    companion object {
        fun of(tag: String): PiiKind? = entries.firstOrNull { it.tag == tag }
    }
}

/** One finding in a text: [value] is `text.substring(start, end)`. */
data class PiiSpan(val start: Int, val end: Int, val kind: PiiKind, val value: String)

/**
 * Deterministic detection of personal data in outgoing text (ROADMAP Phase 2.5, item 2), the
 * same on both platforms:
 *
 * - **Rules** (high precision): emails, IBANs (country length + mod-97), card numbers (Luhn and
 *   a card prefix), phone numbers (international, North American and national formats, or after
 *   a cue such as "phone:"), account numbers after a cue ("account no.", "Kundennummer", "SSN"),
 *   social security numbers, street addresses (English, German, French, Spanish/Italian forms).
 * - **Name heuristics**: honorifics ("Dr. Weber"), relation and naming cues ("my sister Hannah",
 *   "named Lea"), greetings and sign-offs, and a list of common first names (with the surname
 *   that follows). Ambiguous first names (Will, May, Mark…) count only with a cue or surname.
 *
 * False-positive guards: nothing inside links is a name, phone, card or address, and inside code
 * (fenced blocks, inline code) only emails and IBANs count. Dates, times, version numbers and IP
 * addresses never read as phones; example domains and `git@` never read as emails.
 */
object PiiDetector {

    fun detect(text: String, names: Boolean = true): List<PiiSpan> {
        if (text.isEmpty()) return emptyList()
        val guards = Guards(text)
        val found = ArrayList<Candidate>()
        emails(text, found)
        ibans(text, found)
        if (guards.any) {
            cards(text, guards, found)
            accounts(text, guards, found)
            phones(text, guards, found)
            addresses(text, guards, found)
            if (names) people(text, guards, found)
        } else {
            cards(text, null, found)
            accounts(text, null, found)
            phones(text, null, found)
            addresses(text, null, found)
            if (names) people(text, null, found)
        }
        return resolve(text, found)
    }

    // ---------------------------------------------------------------------------------------
    // Guards: code and links

    /** Ranges of code (fenced blocks, inline code) and links in a text. */
    internal class Guards(text: String) {
        val code: List<IntRange>
        val links: List<IntRange>

        init {
            val code = ArrayList<IntRange>()
            FENCE.findAll(text).forEach { code += it.range }
            INLINE_CODE.findAll(text).forEach { m -> if (code.none { m.range.first in it }) code += m.range }
            this.code = code
            links = LINK.findAll(text).map { it.range }.toList()
        }

        val any: Boolean get() = code.isNotEmpty() || links.isNotEmpty()

        fun inCode(start: Int, end: Int) = code.any { start <= it.last && end - 1 >= it.first }
        fun inLink(start: Int, end: Int) = links.any { start <= it.last && end - 1 >= it.first }
        fun blocked(start: Int, end: Int) = inCode(start, end) || inLink(start, end)
    }

    private class Candidate(val start: Int, val end: Int, val kind: PiiKind, val priority: Int)

    private fun MutableList<Candidate>.add(text: String, start: Int, end: Int, kind: PiiKind, priority: Int) {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && (text[e - 1].isWhitespace() || text[e - 1] == ',')) e--
        if (e > s) add(Candidate(s, e, kind, priority))
    }

    /** Non-overlapping findings, the most reliable first (rules beat heuristics), then the longest. */
    private fun resolve(text: String, found: List<Candidate>): List<PiiSpan> {
        val taken = ArrayList<Candidate>()
        for (c in found.sortedWith(compareBy<Candidate> { it.priority }.thenByDescending { it.end - it.start }.thenBy { it.start })) {
            if (taken.none { c.start < it.end && it.start < c.end }) taken += c
        }
        return taken.sortedBy { it.start }.map { PiiSpan(it.start, it.end, it.kind, text.substring(it.start, it.end)) }
    }

    // ---------------------------------------------------------------------------------------
    // Rules

    private fun emails(text: String, out: MutableList<Candidate>) {
        for (m in EMAIL.findAll(text)) {
            val value = m.value
            val local = value.substringBefore('@').lowercase()
            val domain = value.substringAfter('@').lowercase()
            val tld = domain.substringAfterLast('.')
            if (tld in FILE_EXTENSIONS || local in SERVICE_LOCALS) continue
            if (EXAMPLE_DOMAINS.any { domain == it || domain.endsWith(".$it") } || domain.endsWith(".invalid") || domain.endsWith(".test") || domain.endsWith(".localhost")) continue
            out.add(text, m.range.first, m.range.last + 1, PiiKind.EMAIL, P_EMAIL)
        }
    }

    private fun ibans(text: String, out: MutableList<Candidate>) {
        for (m in IBAN_CANDIDATE.findAll(text)) {
            val country = m.value.take(2)
            val length = IBAN_LENGTHS[country] ?: continue
            // Map compact characters back to their positions (the groups may be spaced).
            var count = 0
            var end = -1
            val compact = StringBuilder()
            for (i in m.range) {
                val c = text[i]
                if (c == ' ') continue
                compact.append(c)
                if (++count == length) {
                    end = i + 1
                    break
                }
            }
            if (end < 0) continue
            if (end < text.length && text[end].isLetterOrDigit()) continue
            if (Iban.valid(compact.toString())) out.add(text, m.range.first, end, PiiKind.IBAN, P_IBAN)
        }
    }

    private fun cards(text: String, guards: Guards?, out: MutableList<Candidate>) {
        for (m in CARD_CANDIDATE.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (guards?.blocked(start, end) == true) continue
            val digits = m.value.filter { it.isDigit() }
            if (digits.length !in 13..19 || digits[0] !in '2'..'6' || digits.all { it == digits[0] }) continue
            if (Luhn.valid(digits)) out.add(text, start, end, PiiKind.CARD, P_CARD)
        }
    }

    private fun accounts(text: String, guards: Guards?, out: MutableList<Candidate>) {
        for (m in ACCOUNT_CUED.findAll(text)) {
            val group = m.groups[1] ?: continue
            val start = group.range.first
            val end = group.range.last + 1
            if (guards?.inCode(start, end) == true) continue
            val digits = group.value.count { it.isDigit() }
            if (digits < 4 || (digits == 4 && group.value.length == 4 && YEAR.matches(group.value))) continue
            out.add(text, start, end, PiiKind.ACCOUNT, P_ACCOUNT)
        }
        for (m in SSN.findAll(text)) {
            if (guards?.blocked(m.range.first, m.range.last + 1) == true) continue
            out.add(text, m.range.first, m.range.last + 1, PiiKind.ACCOUNT, P_ACCOUNT)
        }
    }

    private fun phones(text: String, guards: Guards?, out: MutableList<Candidate>) {
        fun accept(start: Int, end: Int, minDigits: Int, maxDigits: Int, priority: Int = P_PHONE) {
            if (guards?.blocked(start, end) == true) return
            val value = text.substring(start, end)
            val digits = value.count { it.isDigit() }
            if (digits !in minDigits..maxDigits) return
            if (DATE_LIKE.containsMatchIn(value) || IP_LIKE.matches(value.trim()) || TIME_LIKE.matches(value.trim())) return
            out.add(text, start, end, PiiKind.PHONE, priority)
        }
        PHONE_INTL.findAll(text).forEach { accept(it.range.first, it.range.last + 1, 8, 15) }
        PHONE_NANP_PAREN.findAll(text).forEach { accept(it.range.first, it.range.last + 1, 10, 10) }
        PHONE_NANP.findAll(text).forEach { accept(it.range.first, it.range.last + 1, 10, 10) }
        PHONE_NATIONAL.findAll(text).forEach { accept(it.range.first, it.range.last + 1, 9, 12) }
        PHONE_NATIONAL_PLAIN.findAll(text).forEach { accept(it.range.first, it.range.last + 1, 10, 11) }
        PHONE_CUED.findAll(text).forEach { m -> m.groups[1]?.let { accept(it.range.first, it.range.last + 1, 7, 15, P_PHONE_CUED) } }
    }

    private fun addresses(text: String, guards: Guards?, out: MutableList<Candidate>) {
        for (regex in ADDRESS_PATTERNS) for (m in regex.findAll(text)) {
            if (guards?.blocked(m.range.first, m.range.last + 1) == true) continue
            out.add(text, m.range.first, m.range.last + 1, PiiKind.ADDRESS, P_ADDRESS)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Name heuristics

    private fun people(text: String, guards: Guards?, out: MutableList<Candidate>) {
        fun name(range: IntRange?, priority: Int) {
            if (range == null || range.isEmpty()) return
            var start = range.first
            var end = range.last + 1
            // A possessive belongs to the sentence, not the name.
            if (end - start > 2 && (text.startsWith("'s", end - 2) || text.startsWith("’s", end - 2))) end -= 2
            if (guards?.blocked(start, end) == true) return
            val value = text.substring(start, end)
            val words = value.split(' ', '\n', '\t').filter { it.isNotEmpty() }
            fun skippable(word: String) = word.lowercase().trimEnd('.').let { it in NOT_NAMES || it in HONORIFICS }
            if (words.isEmpty() || words.all { skippable(it) || it.lowercase() in PARTICLES }) return
            // "Dear Ms Abernathy", "Hi Team Lead": the name starts after honorifics and furniture.
            for (word in words) {
                if (!skippable(word)) break
                start = text.indexOf(word, start) + word.length
                while (start < end && text[start].isWhitespace()) start++
            }
            if (start >= end) return
            out.add(text, start, end, PiiKind.PERSON, priority)
        }
        HONORIFIC.findAll(text).forEach { name(it.groups[1]?.range, P_NAME_CUED) }
        RELATION.findAll(text).forEach { name(it.groups[1]?.range, P_NAME_CUED) }
        NAMED.findAll(text).forEach { name(it.groups[1]?.range, P_NAME_CUED) }
        GREETING.findAll(text).forEach { name(it.groups[1]?.range, P_NAME_CUED) }
        SIGN_OFF.findAll(text).forEach { name(it.groups[1]?.range, P_NAME_CUED) }
        SELF.findAll(text).forEach { m ->
            val g = m.groups[1] ?: return@forEach
            val words = g.value.split(' ')
            // "I'm Hannah", "this is Lea Wolf"; not "I'm Sorry" or "this is Important".
            if (words.size >= 2 || words[0].lowercase() in FIRST_NAMES) name(g.range, P_NAME_CUED)
        }
        // First names from the list, with the surname that follows.
        for (m in CAPITALISED.findAll(text)) {
            val word = m.value
            val lower = word.lowercase().removeSuffix("'s").removeSuffix("’s")
            if (lower !in FIRST_NAMES) continue
            val surname = surnameAfter(text, m.range.last + 1)
            if (surname == null && lower in AMBIGUOUS_FIRST_NAMES) continue
            // A name that opens a sentence is fine ("Hannah called."), unless the next word shows
            // it is a heading or a command ("Max tokens").
            name(m.range.first until (surname ?: (m.range.last + 1)), P_NAME_LIST)
        }
    }

    /** End of a surname (with particles: "van der Berg") right after a first name ending at [at], or null. */
    private fun surnameAfter(text: String, at: Int): Int? {
        var i = at
        var end: Int? = null
        var hops = 0
        while (hops < 4) {
            if (i >= text.length || text[i] != ' ') break
            val m = WORD_AT.matchAt(text, i + 1) ?: break
            val word = m.value
            val lower = word.lowercase()
            if (lower in PARTICLES) {
                i = m.range.last + 1
                hops++
                continue
            }
            if (!word[0].isUpperCase() || word.length < 2 || lower in NOT_NAMES || lower in SURNAME_BLOCK || word.all { it.isUpperCase() }) break
            end = m.range.last + 1
            break
        }
        return end
    }

    // ---------------------------------------------------------------------------------------
    // Patterns

    private const val P_EMAIL = 0
    private const val P_IBAN = 1
    private const val P_CARD = 2
    private const val P_ACCOUNT = 3
    private const val P_PHONE_CUED = 4
    private const val P_PHONE = 5
    private const val P_ADDRESS = 6
    private const val P_NAME_CUED = 7
    private const val P_NAME_LIST = 8

    private val FENCE = Regex("""(?s)(```|~~~).*?(?:\1|$)""")
    private val INLINE_CODE = Regex("""`[^`\n]+`""")
    private val LINK = Regex("""(?i)\b(?:https?|ftp|file|wss?)://[^\s<>"'`)\]]+|\bwww\.[^\s<>"'`)\]]+""")

    private val EMAIL = Regex("""(?<![\p{L}\p{N}._%+-])[\p{L}\p{N}][\p{L}\p{N}._%+-]*@[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?(?:\.[\p{L}\p{N}](?:[\p{L}\p{N}-]*[\p{L}\p{N}])?)*\.\p{L}{2,24}(?![\p{L}\p{N}-])""")
    private val FILE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "pdf", "heic", "ico", "bmp")
    private val SERVICE_LOCALS = setOf("git", "noreply", "no-reply", "donotreply", "do-not-reply", "mailer-daemon", "postmaster")
    private val EXAMPLE_DOMAINS = setOf("example.com", "example.org", "example.net", "example.edu", "example")

    private val IBAN_CANDIDATE = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{2}(?: ?[A-Z0-9]){10,32}""")
    private val CARD_CANDIDATE = Regex("""(?<![\w.\-])\d(?:[ \-]?\d){12,18}(?![\w\-]|\.\d)""")

    private const val ACCOUNT_CUES = """account|acct|a/c|konto(?:nummer)?|kontonr|routing|sort\s+code|customer|kunden(?:nummer|nr)|member(?:ship)?|policy|contract|vertrags?(?:nummer|nr)|passport|reisepass|licen[cs]e|driver'?s\s+licen[cs]e|ssn|social\s+security|tax\s*id|steuer[- ]?id|steuernummer|steuer-?identifikationsnummer|national\s+insurance|nino|bsn|svnr|sozialversicherungsnummer|personalausweis|id\s+card"""
    private val ACCOUNT_CUED = Regex(
        """(?i)\b(?:$ACCOUNT_CUES)\b\.?\s*(?:number|num|no\.?|nr\.?|#|id)?\s*(?:is|:|=|#)?\s*((?-i:[A-Z]{1,4})[\- ]?\d(?:[\d\-]|\s(?=\d)){3,30}|\d(?:[\d\-]|\s(?=\d)){3,30}(?-i:[A-Z])?|(?-i:[A-Z0-9]{0,4})\d(?-i:[A-Z0-9]){3,20})(?![\p{L}\p{N}])""",
    )
    private val SSN = Regex("""(?<![\w\-])\d{3}-\d{2}-\d{4}(?![\w\-])""")
    private val YEAR = Regex("""(?:19|20)\d\d""")

    private val PHONE_INTL = Regex("""(?<![\w+])\+\d{1,3}(?:[ .\-]?\(\d{1,4}\))?(?:[ .\-]?\d{1,5}){2,5}(?![\w]|[.\-]\d)""")
    private val PHONE_NANP_PAREN = Regex("""(?<![\w)])\(\d{3}\) ?\d{3}[ .\-]\d{4}(?![\w]|[.\-]\d)""")
    private val PHONE_NANP = Regex("""(?<![\w.\-/])\d{3}([.\-])\d{3}\1\d{4}(?![\w]|[.\-/]\d)""")
    private val PHONE_NATIONAL = Regex("""(?<![\w.\-+/:])0\d{2,4}(?:[ /\-]\d{2,8}){1,3}(?![\w]|[.\-/:]\d)""")
    private val PHONE_NATIONAL_PLAIN = Regex("""(?<![\w.\-+/:])0[1-9]\d{8,9}(?![\w]|[.\-/:]\d)""")
    private val PHONE_CUED = Regex(
        """(?i)\b(?:phone|tel|telephone|mobile|mob|cell|cellphone|handy|telefon|telefonnummer|whatsapp|signal|fax|call\s+me\s+(?:at|on)|text\s+me\s+(?:at|on)|reach\s+me\s+(?:at|on))\b\.?\s*(?:number|no\.?|nr\.?|#)?\s*[:=]?\s*(\+?\(?\d[\d ()./\-]{5,20}\d)(?![\w])""",
    )
    private val DATE_LIKE = Regex("""\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{4}\b""")
    private val IP_LIKE = Regex("""\d{1,3}(?:\.\d{1,3}){3}""")
    private val TIME_LIKE = Regex("""\d{1,2}[:.]\d{2}(?:[:.]\d{2})?""")

    private const val EN_STREETS = """Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr|Court|Ct|Way|Place|Pl|Terrace|Parkway|Pkwy|Circle|Cir|Highway|Hwy|Square|Sq|Trail|Close|Crescent|Row|Mews|Gardens"""
    private const val CAP = """[\p{Lu}][\p{L}'’\-]*"""
    private val ADDRESS_PATTERNS = listOf(
        // 221B Baker Street, Apt 4, London / 1600 Pennsylvania Ave NW, Washington, DC 20500
        Regex("""(?<![\w.])\d{1,5}[A-Za-z]?\s+(?:$CAP\.?\s+){1,4}(?:$EN_STREETS)\b\.?(?:\s+(?:NW|NE|SW|SE|N|S|E|W)\b)?(?:,?\s*(?:Apt|Apartment|Suite|Ste|Unit|Flat|#)\.?\s*[A-Za-z0-9\-]+)?(?:,\s*$CAP(?:\s$CAP){0,2}(?:,?\s*[A-Z]{2})?(?:\s+\d{5}(?:-\d{4})?)?)?"""),
        // Hauptstraße 12a, 10115 Berlin
        Regex("""(?<![\p{L}])[\p{Lu}][\p{L}\-]*(?:straße|strasse|str\.|gasse|weg|allee|platz|damm|ufer|chaussee|pfad)\s*\d{1,3}\s?[a-zA-Z]?\b(?:,?\s*\d{4,5}\s+$CAP)?"""),
        // Berliner Straße 5, Am Alten Weg 3
        Regex("""(?<![\p{L}])$CAP\s+(?:Straße|Strasse|Str\.|Weg|Allee|Platz|Gasse|Ring|Damm|Ufer|Chaussee)\s+\d{1,3}\s?[a-zA-Z]?\b(?:,?\s*\d{4,5}\s+$CAP)?"""),
        // 12 rue de la Paix, 75002 Paris
        Regex("""(?<![\w.])\d{1,4}(?:\s?(?:bis|ter))?,?\s+(?:rue|avenue|boulevard|bd|chemin|place|impasse|allée|quai|route)\s+(?:(?:de|du|des|la|le|l['’]|d['’])\s*)*$CAP(?:\s+$CAP){0,3}(?:,?\s*\d{5}\s+$CAP)?"""),
        // Calle Mayor 5, Via Roma 10, Rua Augusta 274
        Regex("""\b(?:Calle|Avenida|Avda\.|Plaza|Paseo|Via|Viale|Piazza|Corso|Rua|Travessa)\s+(?:(?:de|del|la|las|los|dei|della|di|do|da|dos)\s+)*$CAP(?:\s+$CAP){0,3},?\s*(?:n[º°o]\.?\s*)?\d{1,4}\b"""),
    )

    private const val NAME = """[\p{Lu}][\p{Ll}'’\-]*(?:[\p{Lu}][\p{Ll}'’\-]+)?"""
    private const val FULL_NAME = """$NAME(?:\s+(?:(?:van|von|de|der|den|da|di|du|la|le|del|bin|al|ten|ter)\s+)*$NAME){0,2}"""
    private val HONORIFIC = Regex("""\b(?:Mr|Mrs|Ms|Mx|Miss|Dr|Prof|Sir|Dame|Herr|Frau|Mme|Mlle|Sr|Sra|Srta|Signor|Signora|Dott)\.?\s+($FULL_NAME)""")
    private val RELATION = Regex(
        """(?i:\b(?:my|our|his|her|their|mein(?:e|em|en)?|unser(?:e|em|en)?)\s+(?:wife|husband|partner|girlfriend|boyfriend|fianc[eé]e?|son|daughter|mother|mom|mum|father|dad|brother|sister|friend|best\s+friend|boss|manager|colleague|coworker|co-worker|neighbou?r|doctor|dentist|therapist|lawyer|landlord|landlady|cousin|uncle|aunt|grandma|grandpa|grandmother|grandfather|niece|nephew|kid|child|baby|ex|roommate|flatmate|teacher|tutor|frau|mann|freund|freundin|tochter|sohn|mutter|vater|bruder|schwester|chef|chefin|kollege|kollegin|nachbar|nachbarin|oma|opa)\s*,?\s+)($FULL_NAME)""",
    )
    private val NAMED = Regex("""(?i:\b(?:named|called|name\s+is|name's|heißt|heisst|ich\s+heiße|mein\s+name\s+ist|je\s+m'appelle|me\s+llamo|mi\s+chiamo)\s+)($FULL_NAME)""")
    private val SELF = Regex("""(?i:\b(?:I\s+am|I'm|I’m|this\s+is|ich\s+bin)\s+)($NAME(?:\s+$NAME)?)""")
    private val GREETING = Regex("""(?m)^\s*(?:Hi|Hello|Hey|Dear|Hallo|Liebe|Lieber|Moin|Servus|Bonjour|Salut|Hola|Ciao|Olá)\s+($FULL_NAME)\s*(?:[,!]|$)""")
    private val SIGN_OFF = Regex(
        """(?im)^\s*(?:best|regards|best\s+regards|kind\s+regards|warm\s+regards|cheers|thanks|thank\s+you|many\s+thanks|sincerely|yours|love|viele\s+grüße|liebe\s+grüße|beste\s+grüße|grüße|lg|vg|mfg|mit\s+freundlichen\s+grüßen|cordialement|bisous|saludos|un\s+saludo|cordiali\s+saluti)\s*[,!.]?\s*\n\s*((?-i:$FULL_NAME))\s*$""",
    )
    private val CAPITALISED = Regex("""(?<![\p{L}\p{N}_@./\-])[\p{Lu}][\p{Ll}]+(?:['’\-][\p{L}]+)?(?![\p{L}\p{N}_@]|\.[\p{L}])""")
    private val WORD_AT = Regex("""[\p{L}][\p{L}'’\-]*""")

    private fun Regex.matchAt(text: String, index: Int): MatchResult? =
        if (index >= text.length) null else find(text, index)?.takeIf { it.range.first == index }

    private val HONORIFICS = setOf(
        "mr", "mrs", "ms", "mx", "miss", "dr", "prof", "sir", "dame", "herr", "frau", "mme", "mlle", "sr", "sra", "srta",
        "signor", "signora", "dott",
    )

    private val PARTICLES = setOf("van", "von", "de", "der", "den", "da", "di", "du", "la", "le", "del", "bin", "al", "ten", "ter")

    /** Capitalised words that are not names (sentence furniture, calendar, roles, places in prose). */
    internal val NOT_NAMES = setOf(
        "the", "a", "an", "and", "or", "but", "if", "so", "to", "of", "in", "on", "at", "by", "for", "with", "from", "as",
        "is", "are", "was", "be", "it", "its", "this", "that", "these", "those", "there", "here", "then", "than", "when",
        "where", "what", "which", "who", "why", "how", "while", "will", "would", "should", "could", "can", "may", "might",
        "have", "has", "had", "not", "no", "yes", "ok", "okay", "all", "any", "each", "every", "some", "many", "most",
        "more", "much", "very", "only", "just", "even", "also", "after", "before", "about", "above", "below", "because",
        "please", "thanks", "thank", "hello", "hi", "hey", "dear", "team", "everyone", "folks", "guys", "sir", "madam",
        "user", "users", "you", "your", "yours", "we", "our", "they", "their", "he", "she", "him", "her", "his", "my", "me",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march",
        "april", "may", "june", "july", "august", "september", "october", "november", "december", "today", "tomorrow",
        "yesterday", "morning", "evening", "night", "week", "weekend", "month", "year", "note", "notes", "todo", "done",
        "none", "true", "false", "null", "sorry", "sure", "great", "good", "fine", "important", "best", "regards", "cheers",
        "street", "road", "avenue", "lane", "city", "town", "doctor", "manager", "admin", "support", "customer", "client",
        "service", "account", "bank", "card", "phone", "email", "address", "name", "password", "project", "meeting",
        "hallo", "liebe", "lieber", "herr", "frau", "danke", "bitte", "und", "oder", "der", "die", "das", "ein", "eine",
        "ich", "du", "sie", "wir", "ihr", "mein", "dein", "heute", "morgen", "gestern",
    )

    /** Words that follow a first name but are not surnames ("Max Tokens", "Grace Period"). */
    private val SURNAME_BLOCK = setOf(
        "tokens", "token", "period", "size", "value", "mode", "level", "street", "road", "avenue", "lane", "drive", "way",
        "university", "college", "school", "hospital", "bank", "group", "inc", "ltd", "gmbh", "corp", "foundation", "park",
        "hotel", "airport", "station", "church", "museum", "river", "lake", "mountain", "island", "bay", "city", "county",
        "state", "day", "week", "month", "year", "studio", "pro", "max", "mini", "plus", "ultra", "lite", "edition",
    )

    /** First names that are also common words: they count only with a surname or a cue. */
    private val AMBIGUOUS_FIRST_NAMES = setOf(
        "will", "may", "june", "april", "august", "mark", "bill", "grace", "hope", "joy", "faith", "rose", "summer",
        "art", "max", "jack", "frank", "sue", "pat", "rob", "jean", "ken", "don", "ray", "dawn", "iris", "amber", "ruby",
        "jade", "crystal", "chase", "hunter", "jordan", "paris", "georgia", "florence", "eve", "carol", "dean", "drew",
        "gene", "guy", "miles", "penny", "sky", "angel", "rich", "victor", "lily", "violet", "olive", "ivy", "page",
        "harmony", "sage", "river", "storm", "august", "christian", "ali", "major", "earl", "duke", "king", "prince",
        "noble", "mason", "cash", "bishop", "ginger", "holly", "heather", "autumn", "winter", "melody", "precious",
        "sandy", "misty", "lee", "nova", "luna", "stella", "aurora", "eden", "zion", "jan", "min", "jun", "li", "saga",
        "mercedes", "austin", "virginia", "victoria", "madison", "julia", "sunita", "page", "ren", "aoi",
    )

    /** Common first names (lower case) across the languages the app is used in. */
    internal val FIRST_NAMES: Set<String> = """
        james john robert michael william david richard joseph thomas charles christopher daniel matthew anthony mark
        donald steven paul andrew joshua kenneth kevin brian george timothy ronald edward jason jeffrey ryan jacob gary
        nicholas eric jonathan stephen larry justin scott brandon benjamin samuel gregory alexander patrick frank
        raymond jack dennis jerry tyler aaron jose adam nathan henry douglas zachary peter kyle noah ethan jeremy
        walter christian keith roger terry austin sean gerald carl harold dylan arthur lawrence jordan jesse bryan
        billy bruce gabriel joe logan alan juan albert willie elijah wayne randy vincent mason roy ralph bobby russell
        bradley philip eugene oliver liam lucas leo oscar hugo felix theo elias jonas finn luca luis carlos miguel
        mary patricia jennifer linda elizabeth barbara susan jessica sarah karen lisa nancy betty sandra margaret ashley
        kimberly emily donna michelle carol amanda melissa deborah stephanie dorothy rebecca sharon laura cynthia amy
        kathleen angela shirley brenda emma anna pamela nicole samantha katherine christine helen debra rachel carolyn
        janet maria catherine heather diane olivia julie joyce victoria ruth virginia lauren kelly christina joan evelyn
        judith andrea hannah megan cheryl jacqueline martha madison teresa gloria sara janice ann kathryn abigail sophia
        frances jean alice judy isabella julia grace amber denise danielle marilyn beverly charlotte natalie theresa
        diana brittany doris kayla alexis lori marie mia ava ella chloe zoe lily nora clara alina elena sofia lena
        lea leonie lara laura johanna paula marlene greta ida frieda mathilda emilia luisa marie sophie charlotte
        lukas leon paul ben jonas elias noah finn luis felix maximilian julian moritz niklas tim jan tom philipp
        sebastian florian tobias stefan andreas markus matthias christian michael thomas daniel alexander martin jens
        ludwig uwe jürgen klaus wolfgang dieter manfred helmut horst bernd frank torsten sven jörg ralf heike sabine petra
        monika ursula renate birgit claudia susanne andrea stefanie katrin nadine anja silke tanja kerstin ute
        jean pierre jacques louis antoine nicolas camille julien mathieu guillaume baptiste léa manon chloé inès
        jade louise juliette margaux céline aurélie mathilde amélie élodie marion océane
        alejandro javier pablo diego sergio manuel antonio francisco jorge alberto fernando raúl lucía carmen isabel
        pilar rosa dolores mercedes francesca giulia chiara giorgia martina alessia marco giuseppe giovanni francesco
        alessandro matteo lorenzo andrea davide simone federico riccardo stefano
        joão pedro tiago rafael bruno beatriz mariana inês ana catarina
        sven lars erik nils anders magnus johan karl olaf astrid ingrid sigrid freya maja elsa linnea saga
        jan piet kees joost daan bram sanne femke lotte anouk
        ivan dmitri sergei alexei mikhail nikolai olga natasha tatiana svetlana irina ekaterina anastasia
        piotr tomasz krzysztof agnieszka katarzyna magdalena
        mohammed muhammad ahmed ali omar hassan hussein khalid youssef ibrahim fatima aisha layla zainab mariam
        yusuf mehmet mustafa emre elif zeynep ayşe
        raj rahul amit vikram arjun rohan priya ananya divya kavya pooja neha deepa sunita
        wei li ming jun hao yan mei ling xin yuki haruto sota yuto hina sakura aoi ren min seo jiho
        kwame kofi amara chidi ngozi tunde
        """.trim().split(Regex("""\s+""")).toSet()

    /** Two-letter country code → IBAN length (SWIFT IBAN registry). */
    internal val IBAN_LENGTHS: Map<String, Int> = """
        AD24 AE23 AL28 AT20 AZ28 BA20 BE16 BG22 BH22 BR29 BY28 CH21 CR22 CY28 CZ24 DE22 DK18 DO28 EE20 EG29 ES24
        FI18 FO18 FR27 GB22 GE22 GI23 GL18 GR27 GT28 HR21 HU28 IE22 IL23 IQ23 IS26 IT27 JO30 KW30 KZ20 LB28 LC32
        LI21 LT20 LU20 LV21 MC27 MD24 ME22 MK19 MR27 MT31 MU30 NL18 NO15 PK24 PL28 PS29 PT25 QA29 RO24 RS22 SA24
        SC31 SE24 SI19 SK24 SM27 ST25 SV28 TL23 TN24 TR26 UA29 VA22 VG24 XK20
        """.trim().split(Regex("""\s+""")).associate { it.take(2) to it.drop(2).toInt() }
}

/** Luhn checksum (card numbers). */
object Luhn {
    fun valid(digits: String): Boolean {
        if (digits.length < 2 || digits.any { !it.isDigit() }) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }
}

/** IBAN validation: known country, its length, and the ISO 7064 mod-97 check. */
object Iban {
    fun valid(raw: String): Boolean {
        val iban = raw.filterNot { it == ' ' }.uppercase()
        val length = PiiDetector.IBAN_LENGTHS[iban.take(2)] ?: return false
        if (iban.length != length || !iban.all { it.isLetterOrDigit() && it.code < 128 } || !iban[2].isDigit() || !iban[3].isDigit()) return false
        val moved = iban.substring(4) + iban.substring(0, 4)
        var rem = 0
        for (c in moved) {
            if (c.isDigit()) {
                rem = (rem * 10 + (c - '0')) % 97
            } else {
                val v = c - 'A' + 10
                rem = (rem * 100 + v) % 97
            }
        }
        return rem == 1
    }
}
