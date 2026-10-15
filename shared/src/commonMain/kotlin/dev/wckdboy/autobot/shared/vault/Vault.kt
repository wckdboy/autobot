package dev.wckdboy.autobot.shared.vault

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * One file in the user's private vault. Its text is split into [chunks] passages, each embedded
 * on the phone with [model] ([dims] dimensions). A document whose model or dimensions differ from
 * the current embedding model is stale: it is skipped by search until it is re-indexed (from its
 * stored passages, the file is not needed again).
 */
@Serializable
data class VaultDocument(
    val id: String,
    val name: String,
    val type: VaultFileType,
    /** Size of the original file in bytes. */
    val sizeBytes: Long,
    /** Hash of the extracted text: adding the same file again with unchanged text does nothing. */
    val contentHash: String,
    val chunks: Int,
    /** Pages of a PDF (0 for other files). */
    val pages: Int = 0,
    val model: String,
    val dims: Int,
    val addedAt: Long,
    val updatedAt: Long,
    /** Where the file came from (Android content URI, iOS file name): the same source replaces the document. */
    val source: String? = null,
)

@Serializable
enum class VaultFileType(val label: String) {
    TEXT("text"),
    MARKDOWN("markdown"),
    PDF("pdf"),

    /** A photo or scan, read with on-device OCR. */
    IMAGE("image"),
}

/** One passage of a document: [start]…[end] are character offsets into its extracted text. */
@Serializable
data class VaultChunk(
    val docId: String,
    val index: Int,
    val text: String,
    val start: Int,
    val end: Int,
    /** 1-based page of a PDF where the passage starts; null for files without pages. */
    val page: Int? = null,
)

/**
 * Text extracted from a file by the host (plain text, PDF text per page, OCR). [pages] has one
 * entry for files without pages.
 */
class VaultText(
    val name: String,
    val type: VaultFileType,
    val sizeBytes: Long,
    val pages: List<String>,
    val source: String? = null,
)

/**
 * Where the vault lives: Android tables in the SQLCipher database, iOS rows sealed in the
 * encrypted store (through [VaultStoreBridge]). Vectors are packed per document ([VectorCodec]),
 * index-aligned with its chunks.
 */
interface VaultStore {
    suspend fun documents(): List<VaultDocument>
    suspend fun chunks(docId: String): List<VaultChunk>

    /** The packed vectors of [docId] ([VectorCodec.size] bytes per chunk), or null. */
    suspend fun vectors(docId: String): ByteArray?

    /** Writes a document with all its chunks and vectors, replacing any earlier version. */
    suspend fun put(document: VaultDocument, chunks: List<VaultChunk>, vectors: ByteArray)
    suspend fun delete(docId: String): Boolean
    suspend fun deleteAll()
}

/**
 * On-device text embeddings (llama.cpp in embedding mode): Android through the `:llm` engine
 * process, iOS through AutobotEngines. Never a network call.
 */
interface Embedder {
    /** Catalog id of the ready embedding model, or null when none is installed. */
    suspend fun modelId(): String?

    /** One L2-normalised vector per text, pooled the way the model was trained (no prefixes added). */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * How a model wants its inputs: queries and documents get different task prefixes, and models
 * trained with Matryoshka loss can be cut to [dims] dimensions (then re-normalised) to halve the
 * index without a noticeable loss.
 */
data class EmbeddingProfile(val queryPrefix: String, val documentPrefix: String, val dims: Int? = null) {
    companion object {
        /** Qwen3-Embedding: an instruction on the query side only; MRL down to 32 dimensions. */
        val QWEN3 = EmbeddingProfile(
            queryPrefix = "Instruct: Given a question or keywords, retrieve passages from the user's documents that answer it\nQuery: ",
            documentPrefix = "",
            dims = 512,
        )
        val NOMIC = EmbeddingProfile("search_query: ", "search_document: ", dims = 512)
        val E5 = EmbeddingProfile("query: ", "passage: ")
        val PLAIN = EmbeddingProfile("", "")

        fun of(modelId: String): EmbeddingProfile {
            val id = modelId.lowercase()
            return when {
                "qwen3-embedding" in id -> QWEN3
                "nomic-embed" in id -> NOMIC
                "e5-" in id -> E5
                else -> PLAIN
            }
        }
    }
}

/** A passage that matches a query, with its document; [score] is the cosine similarity. */
class VaultHit(val document: VaultDocument, val chunk: VaultChunk, val score: Double)

/** Limits, ids, hashing and the chunker, shared by both apps and the tool. */
object VaultRules {
    /** Target passage length in (estimated) tokens, and how much consecutive passages share. */
    const val CHUNK_TOKENS = 512
    const val OVERLAP_TOKENS = 64

    /** Brute-force search stays fast (a few ms per 10k passages) up to this size. */
    const val MAX_CHUNKS = 50_000
    const val MAX_DOC_CHUNKS = 10_000
    const val MAX_TEXT_CHARS = 8_000_000

    /** Passages embedded per engine call. */
    const val EMBED_BATCH = 8

    private const val HEX = "0123456789abcdef"

    /** Stable id for a source (the same file added again replaces its document). */
    fun documentId(source: String): String = "d-" + hex(fnv64(source)).take(12)

    fun contentHash(pages: List<String>): String {
        var h = FNV_OFFSET
        for (page in pages) {
            h = fnv64(page, h)
            h = (h xor 0x1EL) * FNV_PRIME
        }
        return hex(h)
    }

    /**
     * Estimated tokens of [text] without the model's tokenizer: about four characters per token
     * for alphabetic scripts, one per character for CJK, kana and hangul. Errs on the long side so
     * passages stay inside the model's window.
     */
    fun estimateTokens(text: CharSequence, from: Int = 0, to: Int = text.length): Int {
        var dense = 0
        var other = 0
        for (i in from until to) {
            val c = text[i]
            if (isDense(c)) dense++ else other++
        }
        return dense + (other + 3) / 4
    }

    private fun isDense(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
    }

    /**
     * Splits a document's pages into passages of about [maxTokens] tokens that overlap by about
     * [overlapTokens]. Paragraphs stay whole when they fit; longer ones break at sentences, then
     * at word boundaries. Pages are joined with a blank line, and each passage records the page
     * it starts on. Offsets refer to that joined text.
     */
    fun chunk(
        docId: String,
        pages: List<String>,
        maxTokens: Int = CHUNK_TOKENS,
        overlapTokens: Int = OVERLAP_TOKENS,
    ): List<VaultChunk> {
        val text = StringBuilder()
        val pageStarts = IntArray(pages.size)
        pages.forEachIndexed { i, page ->
            if (i > 0) text.append("\n\n")
            pageStarts[i] = text.length
            text.append(page.replace("\r\n", "\n").replace('\r', '\n'))
        }
        val units = units(text, maxTokens)
        if (units.isEmpty()) return emptyList()

        val out = ArrayList<VaultChunk>()
        var first = 0
        while (first < units.size) {
            var last = first
            var tokens = units[first].tokens
            while (last + 1 < units.size && tokens + units[last + 1].tokens <= maxTokens) {
                last++
                tokens += units[last].tokens
            }
            val start = units[first].start
            val end = units[last].end
            val page = if (pages.size > 1) pageOf(pageStarts, start) + 1 else null
            out += VaultChunk(docId, out.size, text.substring(start, end), start, end, page)
            if (last + 1 >= units.size) break
            // The next passage starts with the trailing units that fit in the overlap.
            var next = last + 1
            var shared = 0
            while (next - 1 > first && shared + units[next - 1].tokens <= overlapTokens) {
                next--
                shared += units[next].tokens
            }
            first = next
        }
        return out
    }

    private class Unit(val start: Int, val end: Int, val tokens: Int)

    /** Paragraphs, else sentences, else word-bounded pieces, none above [maxTokens]; blank space dropped. */
    private fun units(text: CharSequence, maxTokens: Int): List<Unit> {
        val out = ArrayList<Unit>()
        for ((ps, pe) in spans(text, 0, text.length, ::paragraphEnd)) {
            if (estimateTokens(text, ps, pe) <= maxTokens) {
                out += Unit(ps, pe, estimateTokens(text, ps, pe))
                continue
            }
            for ((ss, se) in spans(text, ps, pe, ::sentenceEnd)) {
                val t = estimateTokens(text, ss, se)
                if (t <= maxTokens) out += Unit(ss, se, t) else hardSplit(text, ss, se, maxTokens, out)
            }
        }
        return out
    }

    /** Trimmed non-blank spans of [from]…[to] cut where [cut] says a piece ends. */
    private fun spans(text: CharSequence, from: Int, to: Int, cut: (CharSequence, Int, Int) -> Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = from
        while (i < to) {
            val end = cut(text, i, to)
            var s = i
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) out += s to e
            i = max(end, i + 1)
        }
        return out
    }

    /** End of the paragraph starting at [from]: after the next blank line. */
    private fun paragraphEnd(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            if (text[i] == '\n') {
                var j = i + 1
                while (j < to && (text[j] == ' ' || text[j] == '\t')) j++
                if (j < to && text[j] == '\n') {
                    while (j < to && text[j].isWhitespace()) j++
                    return j
                }
            }
            i++
        }
        return to
    }

    /** End of the sentence starting at [from]: after `.`, `!`, `?` (and CJK stops) plus whitespace, or a line break. */
    private fun sentenceEnd(text: CharSequence, from: Int, to: Int): Int {
        var i = from
        while (i < to) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c == '。' || c == '！' || c == '？') return i + 1
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == to || text[i + 1].isWhitespace())) return i + 1
            i++
        }
        return to
    }

    /** Pieces of at most [maxTokens], broken at the last whitespace before the limit when there is one. */
    private fun hardSplit(text: CharSequence, from: Int, to: Int, maxTokens: Int, out: MutableList<Unit>) {
        var s = from
        while (s < to) {
            var e = s
            var dense = 0
            var other = 0
            while (e < to) {
                if (isDense(text[e])) dense++ else other++
                if (dense + (other + 3) / 4 > maxTokens) break
                e++
            }
            if (e == s) e = s + 1
            if (e < to) {
                var b = e
                while (b > s + 1 && !text[b - 1].isWhitespace()) b--
                if (b > s + (e - s) / 2) e = b
            }
            var ts = s
            var te = e
            while (ts < te && text[ts].isWhitespace()) ts++
            while (te > ts && text[te - 1].isWhitespace()) te--
            if (te > ts) out += Unit(ts, te, estimateTokens(text, ts, te))
            s = e
        }
    }

    private fun pageOf(starts: IntArray, offset: Int): Int {
        var page = 0
        for (i in starts.indices) if (starts[i] <= offset) page = i
        return page
    }

    /** The passage shortened to [max] characters on one line, for tool results and the search screen. */
    fun snippet(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    private fun fnv64(text: String, seed: Long = FNV_OFFSET): Long {
        var h = seed
        for (c in text) {
            h = (h xor (c.code and 0xFF).toLong()) * FNV_PRIME
            h = (h xor (c.code ushr 8).toLong()) * FNV_PRIME
        }
        return h
    }

    private fun hex(value: Long): String {
        val sb = StringBuilder(16)
        for (shift in 60 downTo 0 step 4) sb.append(HEX[((value ushr shift) and 0xF).toInt()])
        return sb.toString()
    }
}

/**
 * Vectors as stored: a float32 scale (little-endian) and the components as signed bytes, so a
 * 512-dimension vector takes 516 bytes. Cosine similarity of normalised vectors is their dot
 * product; int8 keeps it within about 0.01 of the float result.
 */
object VectorCodec {
    fun size(dims: Int): Int = 4 + dims

    /** [v] cut to [dims] (when set and shorter) and L2-normalised. */
    fun fit(v: FloatArray, dims: Int?): FloatArray {
        val n = if (dims != null && dims in 1 until v.size) dims else v.size
        var sum = 0.0
        for (i in 0 until n) sum += v[i].toDouble() * v[i]
        val norm = sqrt(sum).toFloat()
        val inv = if (norm > 0f) 1f / norm else 0f
        return FloatArray(n) { v[it] * inv }
    }

    fun encode(vectors: List<FloatArray>, dims: Int): ByteArray {
        val stride = size(dims)
        val out = ByteArray(stride * vectors.size)
        vectors.forEachIndexed { i, v ->
            require(v.size == dims) { "vector has ${v.size} dimensions, expected $dims" }
            var peak = 0f
            for (x in v) peak = max(peak, kotlin.math.abs(x))
            val scale = if (peak > 0f) peak / 127f else 1f
            val base = i * stride
            val bits = scale.toRawBits()
            out[base] = bits.toByte()
            out[base + 1] = (bits ushr 8).toByte()
            out[base + 2] = (bits ushr 16).toByte()
            out[base + 3] = (bits ushr 24).toByte()
            for (d in 0 until dims) out[base + 4 + d] = (v[d] / scale).roundToInt().coerceIn(-127, 127).toByte()
        }
        return out
    }

    fun count(packed: ByteArray, dims: Int): Int = packed.size / size(dims)

    /** Dot product of [query] with the [index]th vector of [packed]. */
    fun dot(query: FloatArray, packed: ByteArray, index: Int, dims: Int): Float {
        val base = index * size(dims)
        val bits = (packed[base].toInt() and 0xFF) or ((packed[base + 1].toInt() and 0xFF) shl 8) or
            ((packed[base + 2].toInt() and 0xFF) shl 16) or ((packed[base + 3].toInt() and 0xFF) shl 24)
        var sum = 0f
        val o = base + 4
        for (d in 0 until min(dims, query.size)) sum += query[d] * packed[o + d]
        return sum * Float.fromBits(bits)
    }

    fun decode(packed: ByteArray, index: Int, dims: Int): FloatArray {
        val base = index * size(dims)
        val bits = (packed[base].toInt() and 0xFF) or ((packed[base + 1].toInt() and 0xFF) shl 8) or
            ((packed[base + 2].toInt() and 0xFF) shl 16) or ((packed[base + 3].toInt() and 0xFF) shl 24)
        val scale = Float.fromBits(bits)
        return FloatArray(dims) { packed[base + 4 + it] * scale }
    }
}

/**
 * Brute-force cosine search over every current document's vectors (exact, no index structure to
 * keep in sync), with optional maximal marginal relevance so the results are not five copies of
 * the same paragraph from overlapping passages.
 */
class VectorIndex(private val docs: List<Entry>) {
    class Entry(val document: VaultDocument, val vectors: ByteArray)

    class Match(val document: VaultDocument, val index: Int, val score: Float)

    val size: Int get() = docs.sumOf { VectorCodec.count(it.vectors, it.document.dims) }

    /**
     * The [limit] best passages for [query] (normalised, with the documents' dimensions). With
     * [mmrLambda] below 1, picks from the best `4 × limit` balancing relevance against similarity
     * to passages already picked.
     */
    fun search(query: FloatArray, limit: Int, mmrLambda: Double = 1.0): List<Match> {
        if (limit <= 0) return emptyList()
        val pool = if (mmrLambda < 1.0) limit * MMR_POOL else limit
        val best = ArrayList<Match>(pool + 1)
        var floor = Float.NEGATIVE_INFINITY
        for (entry in docs) {
            val dims = entry.document.dims
            if (dims != query.size) continue
            val n = VectorCodec.count(entry.vectors, dims)
            for (i in 0 until n) {
                val score = VectorCodec.dot(query, entry.vectors, i, dims)
                if (best.size >= pool && score <= floor) continue
                insert(best, Match(entry.document, i, score), pool)
                if (best.size >= pool) floor = best.last().score
            }
        }
        if (mmrLambda >= 1.0 || best.size <= limit) return best.take(limit)
        return mmr(best, limit, mmrLambda)
    }

    private fun insert(list: ArrayList<Match>, m: Match, cap: Int) {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].score >= m.score) lo = mid + 1 else hi = mid
        }
        list.add(lo, m)
        if (list.size > cap) list.removeAt(list.size - 1)
    }

    private fun mmr(candidates: List<Match>, limit: Int, lambda: Double): List<Match> {
        val byDoc = docs.associateBy { it.document.id }
        val vectors = candidates.map { c -> VectorCodec.decode(byDoc.getValue(c.document.id).vectors, c.index, c.document.dims) }
        val picked = ArrayList<Int>()
        val left = candidates.indices.toMutableList()
        while (picked.size < limit && left.isNotEmpty()) {
            var bestIdx = -1
            var bestValue = Double.NEGATIVE_INFINITY
            for (i in left) {
                var redundancy = 0.0
                for (p in picked) redundancy = max(redundancy, dot(vectors[i], vectors[p]))
                val value = lambda * candidates[i].score - (1 - lambda) * redundancy
                if (value > bestValue) {
                    bestValue = value
                    bestIdx = i
                }
            }
            picked += bestIdx
            left.remove(bestIdx)
        }
        return picked.map { candidates[it] }
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        const val MMR_POOL = 4
        const val MMR_LAMBDA = 0.7
    }
}
