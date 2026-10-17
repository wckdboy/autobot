package dev.wckdboy.autobot.shared.redaction

import dev.wckdboy.autobot.agent.core.Lock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A text with its personal data replaced, and the placeholders it now carries. */
class Redacted(val text: String, val placeholders: Set<String>)

/**
 * The placeholders of one chat: each personal value gets a stable placeholder (`[PERSON_1]`,
 * `[EMAIL_2]`) the first time it is seen, and keeps it for the rest of the session, so the same
 * value reads the same in every request and the model can refer to it. [restore] turns the
 * placeholders back into the values, on the device.
 *
 * Detection ([PiiDetector]) seeds the vocabulary; every later occurrence of a known value is
 * replaced too, even where detection alone would miss it ("Hannah" opening a sentence after
 * "my sister Hannah" was seen). [addTerms] adds candidates (the user's memory facts, from the
 * taint index) that get a placeholder only once they actually occur in outgoing text.
 *
 * Thread-safe: a chat's subagents share its vault.
 */
class RedactionVault {

    /**
     * [forms]: how the value was written so far ("DE89 3704 …" and "DE893704…" are one IBAN).
     * [literal]: the value is placeholder-shaped text the user wrote themselves ("[PERSON_1]").
     */
    private class Entry(
        val kind: PiiKind,
        val value: String,
        val placeholder: String?,
        val digits: String?,
        val forms: MutableSet<String> = mutableSetOf(value),
        val literal: Boolean = false,
    )

    private val lock = Lock()
    /** Normalised value → entry ([key]). Candidates have no placeholder yet. */
    private val entries = LinkedHashMap<String, Entry>()
    private val byPlaceholder = HashMap<String, Entry>()
    private val counters = IntArray(PiiKind.entries.size)
    /** Bumped whenever the vocabulary changes; cached results of an older version are redone. */
    private var version = 0

    private class Cached(val version: Int, val result: Redacted)

    /** Recent results by input text (a chat resends its whole history on every request). */
    private val cache = HashMap<String, Cached>()

    /** Placeholders handed out so far. */
    val size: Int get() = lock.withLock { byPlaceholder.size }

    /**
     * The kind of [placeholder] (`[EMAIL_1]`), if this vault handed it out for personal data
     * (not for placeholder-shaped text the user wrote, which hides nothing).
     */
    fun kindOf(placeholder: String): PiiKind? = lock.withLock { byPlaceholder[placeholder]?.takeUnless { it.literal }?.kind }

    /** The value behind [placeholder], if this vault handed it out. */
    fun valueOf(placeholder: String): String? = lock.withLock { byPlaceholder[placeholder]?.value }

    /** Adds candidates: [kind] values that are replaced (and numbered) once they occur. */
    fun addTerms(terms: Collection<Pair<PiiKind, String>>) = lock.withLock {
        for ((kind, value) in terms) candidate(kind, value)
    }

    /** [text] with every known and newly detected personal value replaced. */
    fun redact(text: String, names: Boolean = true): Redacted {
        if (text.isBlank()) return Redacted(text, emptySet())
        lock.withLock { cache[text]?.takeIf { it.version == version }?.let { return it.result } }
        // Detection is pure and runs outside the lock; numbering and replacement run under it.
        val spans = PiiDetector.detect(text, names)
        val literals = if ('[' in text || '%' in text) PLACEHOLDER.findAll(text).toList() else emptyList()
        return lock.withLock {
            // Placeholder-shaped text the user wrote comes first: its numbers are never handed out.
            for (m in literals) literal(m)
            for (span in spans) register(span.kind, span.value)
            val result = replace(text)
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[text] = Cached(version, result)
            result
        }
    }

    /**
     * Tool-call arguments (a JSON object as text): string values are redacted one by one, so a
     * value's JSON escapes never hide a boundary; text that is not JSON is redacted as is.
     */
    fun redactJson(arguments: String): Redacted {
        val element = runCatching { Json.parseToJsonElement(arguments) }.getOrNull() ?: return redact(arguments)
        val used = LinkedHashSet<String>()
        var changed = false
        fun walk(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
            is JsonArray -> JsonArray(e.map { walk(it) })
            is JsonPrimitive -> if (!e.isString) e else {
                val r = redact(e.content)
                used += r.placeholders
                if (r.text != e.content) {
                    changed = true
                    JsonPrimitive(r.text)
                } else {
                    e
                }
            }
        }
        val out = walk(element)
        return Redacted(if (changed) out.toString() else arguments, used)
    }

    /** [text] with this vault's placeholders turned back into their values (unknown ones stay). */
    fun restore(text: String, json: Boolean = false): String {
        if ('[' !in text && '%' !in text) return text
        return PLACEHOLDER.replace(text) { m ->
            val encoded = m.groupValues[1] != "["
            val value = valueOf("[${m.groupValues[2]}_${m.groupValues[3]}]") ?: return@replace m.value
            val plain = if (encoded) percentEncode(value) else value
            if (json) jsonEscape(plain) else plain
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun key(kind: PiiKind, value: String): String = when {
        kind == PiiKind.EMAIL -> "e:" + value.lowercase()
        kind == PiiKind.IBAN -> "i:" + value.filterNot { it == ' ' }.uppercase()
        (kind.numeric || kind == PiiKind.PRIVATE) && value.none { it.isLetter() } && value.any { it.isDigit() } -> "d:" + value.filter { it.isDigit() }
        else -> "w:" + value
    }

    private fun digitsOf(kind: PiiKind, value: String): String? =
        value.filter { it.isDigit() }.takeIf { (kind.numeric || kind == PiiKind.PRIVATE) && value.none { c -> c.isLetter() } && it.length >= MIN_DIGITS }

    /** Gives [value] a placeholder now (detected in outgoing text). Call under [lock]. */
    private fun register(kind: PiiKind, value: String): Entry {
        val k = key(kind, value)
        entries[k]?.let { existing ->
            if (existing.forms.add(value)) version++
            if (existing.placeholder != null) return existing
            return number(k, existing)
        }
        val entry = number(k, Entry(kind, value, null, digitsOf(kind, value)))
        // The parts of a full name get their own placeholders when they appear alone.
        if (kind == PiiKind.PERSON) {
            val parts = value.split(' ').filter { it.length >= 3 && it[0].isUpperCase() && it.lowercase() !in PiiDetector.NOT_NAMES }
            if (parts.size > 1) parts.forEach { candidate(PiiKind.PERSON, it) }
        }
        return entry
    }

    /**
     * Placeholder-shaped text in the input ([m], `[PERSON_1]` in a template the user pasted).
     * Its number is reserved so no personal value gets it; when it already names a value, the
     * text gets a placeholder of its own, so the model can tell the two apart and the answer
     * restores each to what it was. URL-encoded ones only reserve their number. Call under [lock].
     */
    private fun literal(m: MatchResult) {
        val kind = PiiKind.of(m.groupValues[2]) ?: return
        val n = m.groupValues[3].toIntOrNull() ?: return
        val bracket = "[${kind.tag}_$n]"
        val owner = byPlaceholder[bracket]
        if (owner == null && counters[kind.ordinal] < n) counters[kind.ordinal] = n
        if (m.groupValues[1] != "[") return
        val k = "l:$bracket"
        if (k in entries) return
        if (owner == null) {
            // Kept as written: it stands for itself.
            val entry = Entry(kind, bracket, bracket, null, literal = true)
            entries[k] = entry
            byPlaceholder[bracket] = entry
            version++
        } else if (!owner.literal) {
            number(k, Entry(kind, bracket, null, null, literal = true))
        }
    }

    private fun number(k: String, entry: Entry): Entry {
        val n = ++counters[entry.kind.ordinal]
        val numbered = Entry(entry.kind, entry.value, "[${entry.kind.tag}_$n]", entry.digits, entry.forms, entry.literal)
        entries[k] = numbered
        byPlaceholder[numbered.placeholder!!] = numbered
        version++
        return numbered
    }

    private fun candidate(kind: PiiKind, raw: String) {
        val value = raw.trim()
        if (value.length < MIN_TERM || value.length > MAX_TERM) return
        val k = key(kind, value)
        if (k in entries) return
        entries[k] = Entry(kind, value, null, digitsOf(kind, value))
        version++
    }

    private class Hit(val start: Int, val end: Int, val key: String)

    /** Replaces every occurrence of a known value (longest first, no overlaps). Call under [lock]. */
    private fun replace(text: String): Redacted {
        val guards = PiiDetector.Guards(text)
        val hits = ArrayList<Hit>()
        for ((k, e) in entries) for (form in e.forms) {
            val ignoreCase = e.kind == PiiKind.EMAIL
            var from = 0
            while (true) {
                val at = text.indexOf(form, from, ignoreCase)
                if (at < 0) break
                from = at + 1
                val end = at + form.length
                // A literal placeholder is bracketed: it counts wherever it is written.
                if (e.literal) {
                    hits += Hit(at, end, k)
                    continue
                }
                if (!bounded(text, at, end)) continue
                if (e.kind.wordy || (e.kind == PiiKind.PRIVATE && e.digits == null)) {
                    if (guards.blocked(at, end)) continue
                }
                hits += Hit(at, end, k)
            }
        }
        // The same number written differently ("+49 171 2345678" / "+49 1712345678").
        if (entries.values.any { it.digits != null }) {
            val byDigits = entries.entries.filter { it.value.digits != null }.associate { it.value.digits!! to it.key }
            for (m in DIGIT_RUN.findAll(text)) {
                val digits = m.value.filter { it.isDigit() }
                val k = byDigits[digits] ?: continue
                if (bounded(text, m.range.first, m.range.last + 1)) hits += Hit(m.range.first, m.range.last + 1, k)
            }
        }
        if (hits.isEmpty()) return Redacted(text, emptySet())
        hits.sortWith(compareBy<Hit> { it.start }.thenByDescending { it.end })
        val out = StringBuilder(text.length)
        val used = LinkedHashSet<String>()
        var cursor = 0
        for (hit in hits) {
            if (hit.start < cursor) continue
            val entry = entries[hit.key]!!.let { if (it.placeholder == null) number(hit.key, it) else it }
            out.append(text, cursor, hit.start).append(entry.placeholder)
            used += entry.placeholder!!
            cursor = hit.end
        }
        out.append(text, cursor, text.length)
        return Redacted(out.toString(), used)
    }

    /** Not part of a longer word or number. */
    private fun bounded(text: String, start: Int, end: Int): Boolean {
        val before = if (start > 0) text[start - 1] else ' '
        val after = if (end < text.length) text[end] else ' '
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }

    companion object {
        private const val MAX_CACHED = 512
        private const val MIN_TERM = 3
        private const val MAX_TERM = 120
        private const val MIN_DIGITS = 4

        /** A placeholder, also URL-encoded (`%5BPERSON_1%5D`, as the model may put it in a link). */
        internal val PLACEHOLDER = Regex(
            """(\[|%5[Bb])(${PiiKind.entries.joinToString("|") { it.tag }})_(\d{1,5})(?:\]|%5[Dd])""",
        )
        private val DIGIT_RUN = Regex("""\+?\d(?:[ \-./]?\d)*""")
        internal val TAGS = PiiKind.entries.map { it.tag }

        fun jsonEscape(value: String): String {
            val out = StringBuilder(value.length + 8)
            for (c in value) when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
            return out.toString()
        }

        fun percentEncode(value: String): String {
            val out = StringBuilder()
            for (b in value.encodeToByteArray()) {
                val c = (b.toInt() and 0xff)
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
            return out.toString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

/**
 * Restores placeholders in a streamed channel (answer text, reasoning, or one tool call's
 * arguments) whose chunks may split a placeholder anywhere: a tail that could still become a
 * placeholder (`[PER`, `[EMAIL_1`, `%5BCARD_2%5`) is held back until the next chunk decides.
 * [json] escapes restored values for a JSON string (tool-call arguments).
 */
class PlaceholderStream(private val vault: RedactionVault, private val json: Boolean = false) {
    private var carry = ""

    /** What can be shown of [delta] now. */
    fun push(delta: String): String {
        val s = carry + delta
        if (s.isEmpty()) return ""
        val hold = holdFrom(s)
        carry = s.substring(hold)
        return vault.restore(s.substring(0, hold), json)
    }

    /** Whatever is still held back, restored (at the end of the stream). */
    fun flush(): String {
        val rest = carry
        carry = ""
        return if (rest.isEmpty()) "" else vault.restore(rest, json)
    }

    /** Start of a tail of [s] that could still grow into a placeholder, or `s.length`. */
    private fun holdFrom(s: String): Int {
        val from = maxOf(0, s.length - MAX_PLACEHOLDER)
        for (i in from until s.length) {
            val c = s[i]
            if ((c == '[' || c == '%') && partial(s, i)) return i
        }
        return s.length
    }

    companion object {
        /** `%5BACCOUNT_99999%5D`. */
        private const val MAX_PLACEHOLDER = 20

        /** Whether `s.substring(at)` is an unfinished placeholder. */
        internal fun partial(s: String, at: Int): Boolean {
            var i = at
            val encoded: Boolean
            if (s[i] == '[') {
                encoded = false
                i++
            } else {
                // %, %5, %5B
                if (i + 1 >= s.length) return true
                if (s[i + 1] != '5') return false
                if (i + 2 >= s.length) return true
                if (s[i + 2] != 'B' && s[i + 2] != 'b') return false
                encoded = true
                i += 3
            }
            val start = i
            while (i < s.length && s[i] in 'A'..'Z') i++
            val letters = s.substring(start, i)
            if (i == s.length) return RedactionVault.TAGS.any { it.startsWith(letters) }
            if (letters !in RedactionVault.TAGS || s[i] != '_') return false
            i++
            val digits = i
            while (i < s.length && s[i].isDigit() && i - digits < 5) i++
            if (i == s.length) return true
            if (i == digits || !encoded) return false
            // Encoded close: %, %5 (a complete %5D would not be partial).
            val rest = s.substring(i)
            return rest.length < 3 && "%5D".startsWith(rest.uppercase())
        }
    }
}
