package dev.wckdboy.autobot.shared.memory

import kotlin.math.ln
import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * One note in the user's local memory. [sessionId] is the conversation the agent saved it from
 * (null for notes the user wrote or imported). The same shape is exported in `.autobot` bundles.
 */
@Serializable
data class MemoryNote(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String? = null,
)

/**
 * Where the notes live: Android a Room table in the SQLCipher database, iOS the encrypted store
 * (through [MemoryStoreBridge]). Notes are few and small, so ranking reads them all.
 */
interface MemoryStore {
    suspend fun all(): List<MemoryNote>
    suspend fun get(id: String): MemoryNote?
    suspend fun upsert(note: MemoryNote)
    suspend fun delete(id: String): Boolean
    suspend fun deleteAll()
}

/** A note with its relevance to a query (higher is better). */
class ScoredNote(val note: MemoryNote, val score: Double)

/**
 * Orders notes by relevance to a query. [KeywordRanker] is lexical; an embedding ranker (or a
 * blend of both) can implement the same interface once on-device embeddings exist.
 */
interface MemoryRanker {
    /** At most [limit] notes with a positive score, best first. */
    suspend fun rank(query: String, notes: List<MemoryNote>, limit: Int): List<ScoredNote>
}

/** Okapi BM25 over note text and tags (tags count double). Ties go to the newer note. */
object KeywordRanker : MemoryRanker {
    private const val K1 = 1.2
    private const val B = 0.75

    override suspend fun rank(query: String, notes: List<MemoryNote>, limit: Int): List<ScoredNote> = score(query, notes, limit)

    fun score(query: String, notes: List<MemoryNote>, limit: Int): List<ScoredNote> {
        val terms = MemoryRules.tokens(query).distinct()
        if (terms.isEmpty() || notes.isEmpty() || limit <= 0) return emptyList()
        val docs = notes.map { note -> MemoryRules.tokens(note.text) + note.tags.flatMap { tag -> MemoryRules.tokens(tag).let { it + it } } }
        val avg = docs.sumOf { it.size }.toDouble() / docs.size
        val df = terms.associateWith { term -> docs.count { term in it } }
        return notes.indices.mapNotNull { i ->
            val doc = docs[i]
            if (doc.isEmpty()) return@mapNotNull null
            val counts = doc.groupingBy { it }.eachCount()
            val score = terms.sumOf { term ->
                val tf = counts[term] ?: return@sumOf 0.0
                val n = df.getValue(term)
                val idf = ln(1 + (notes.size - n + 0.5) / (n + 0.5))
                idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * doc.size / avg))
            }
            if (score > 0) ScoredNote(notes[i], score) else null
        }
            .sortedWith(compareByDescending<ScoredNote> { it.score }.thenByDescending { it.note.updatedAt })
            .take(limit)
    }
}

/** Normalisation, duplicate detection and size limits shared by the tools, the UIs and import. */
object MemoryRules {
    const val MAX_TEXT = 1_000
    const val MAX_TAGS = 8
    const val MAX_TAG_LENGTH = 32
    const val MAX_NOTES = 500

    /** Token-set overlap (Jaccard) at or above which two notes count as the same memory. */
    const val DUPLICATE_SIMILARITY = 0.85

    private val SPACES = Regex("[ \\t\\x0B\\f\\r]+")
    private val BLANK_LINES = Regex("\\n{3,}")
    private val TAG_DROP = Regex("[^\\p{L}\\p{N}_-]+")
    private val ID = Regex("[A-Za-z0-9-]{1,64}")

    private val STOPWORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "do", "does", "for", "from", "has", "have", "how", "i", "if",
        "in", "into", "is", "it", "its", "me", "my", "of", "on", "or", "our", "so", "that", "the", "their", "them", "then",
        "there", "these", "they", "this", "to", "was", "we", "were", "what", "when", "where", "which", "who", "why", "will",
        "with", "you", "your", "can", "could", "should", "would", "about", "please", "user",
    )

    /** Trimmed text with runs of spaces collapsed, at most [MAX_TEXT] characters; null when blank. */
    fun normalizeText(text: String): String? {
        val clean = text.replace("\r\n", "\n").lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n\n")
            .trim()
        return clean.take(MAX_TEXT).trimEnd().ifEmpty { null }
    }

    /** Lower-case tags without `#`, spaces as `-`, other punctuation dropped; distinct, capped. */
    fun normalizeTags(tags: List<String>): List<String> = tags.asSequence()
        .map { it.trim().trimStart('#').lowercase().replace(' ', '-').replace(TAG_DROP, "").trim('-').take(MAX_TAG_LENGTH) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TAGS)
        .toList()

    /** Comma- or space-separated tags as typed in a text field. */
    fun parseTags(input: String): List<String> = normalizeTags(input.split(',', ' ', '\n'))

    /** Lower-case word tokens without stopwords; a plural `s` is folded so "cats" finds "cat". */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            var t = word.toString()
            word.clear()
            if (t in STOPWORDS || (t.length < 2 && !t[0].isDigit())) return
            if (t.length > 3 && t.endsWith('s') && !t.endsWith("ss")) t = t.dropLast(1)
            out += t
        }
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) word.append(c) else flush()
        }
        flush()
        return out
    }

    /** The existing note that says the same thing as [text], if any. */
    fun findDuplicate(text: String, notes: List<MemoryNote>, ignoreId: String? = null): MemoryNote? {
        val key = tokens(text).toSet()
        val plain = comparable(text)
        return notes.firstOrNull { note ->
            if (note.id == ignoreId) return@firstOrNull false
            if (comparable(note.text) == plain) return@firstOrNull true
            val other = tokens(note.text).toSet()
            if (key.isEmpty() || other.isEmpty()) return@firstOrNull false
            val overlap = key.intersect(other).size.toDouble() / key.union(other).size
            overlap >= DUPLICATE_SIMILARITY
        }
    }

    private fun comparable(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /** Case-insensitive substring match on text or tags (the Memory screen's search field). */
    fun matches(note: MemoryNote, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return q.split(' ').filter { it.isNotEmpty() }.all { part ->
            note.text.lowercase().contains(part) || note.tags.any { it.contains(part.trimStart('#')) }
        }
    }

    /** Notes for the Memory screen: filtered by [query], newest first. */
    fun filter(notes: List<MemoryNote>, query: String): List<MemoryNote> =
        notes.filter { matches(it, query) }.sortedByDescending { it.updatedAt }

    /** Ids are short (the model quotes them) and safe as file names and export entries. */
    fun newId(random: Random = Random.Default): String =
        "m-" + (1..10).map { HEX[random.nextInt(HEX.length)] }.joinToString("")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** A note as stored after an import: normalised, or null when it is unusable. */
    fun sanitize(note: MemoryNote): MemoryNote? {
        if (!isValidId(note.id)) return null
        val text = normalizeText(note.text) ?: return null
        return note.copy(text = text, tags = normalizeTags(note.tags))
    }

    private const val HEX = "0123456789abcdef"
}
