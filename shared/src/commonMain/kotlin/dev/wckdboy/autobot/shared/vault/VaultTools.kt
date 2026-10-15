package dev.wckdboy.autobot.shared.vault

import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.ContextOrder
import dev.wckdboy.autobot.agent.core.prompt.PromptContext
import dev.wckdboy.autobot.agent.core.prompt.SectionOrder
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.int
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.shared.IoDispatcher
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What [Vault.add] did. */
enum class AddStatus {
    ADDED,
    UPDATED,

    /** The same source with the same text is already indexed with the current model. */
    UNCHANGED,

    /** No text could be read from the file. */
    EMPTY,

    /** The file, or the vault with it, would exceed the passage limits. */
    TOO_LARGE,

    /** No embedding model is installed. */
    NO_MODEL,
    FAILED,
}

class AddResult(val status: AddStatus, val document: VaultDocument? = null, val message: String? = null)

/** What the Vault screen shows above the documents. */
class VaultStatus(
    /** The ready embedding model, null when none is installed. */
    val modelId: String?,
    val documents: Int,
    val chunks: Int,
    /** Documents indexed with another model (skipped by search until re-indexed). */
    val stale: Int,
)

/**
 * The private vault: the user's files, split into passages, embedded and searched on this phone
 * (ROADMAP Phase 2.5, item 5). Both the agent's `vault_search` and the Vault screens use it.
 * Writes are serialised; search reads a cached in-memory index that every write drops.
 */
class Vault(
    val store: VaultStore,
    private val embedder: Embedder,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val writeLock = Mutex()
    private val indexLock = Mutex()
    private var cached: Pair<String, VectorIndex>? = null

    suspend fun documents(): List<VaultDocument> = withContext(IoDispatcher) { store.documents().sortedByDescending { it.updatedAt } }

    @kotlin.concurrent.Volatile
    private var listed: Pair<Long, List<VaultDocument>>? = null

    /**
     * [documents] for the per-turn `vault` context: cached until a write through this vault (or
     * after [LIST_TTL_MS], for changes made around it), so a turn doesn't re-read the store.
     */
    suspend fun cachedDocuments(): List<VaultDocument> {
        val now = clock()
        listed?.takeIf { now - it.first in 0 until LIST_TTL_MS }?.let { return it.second }
        return documents().also { listed = now to it }
    }

    suspend fun chunks(docId: String): List<VaultChunk> = withContext(IoDispatcher) { store.chunks(docId) }

    suspend fun status(): VaultStatus {
        val model = embedder.modelId()
        val docs = documents()
        val dims = model?.let { dimsFor(it) }
        return VaultStatus(model, docs.size, docs.sumOf { it.chunks }, docs.count { model == null || !isCurrent(it, model, dims) })
    }

    /**
     * Indexes [text] (replacing the document from the same source). [onProgress] gets embedded
     * and total passages after each batch.
     */
    suspend fun add(text: VaultText, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): AddResult = writeLock.withLock {
        try {
            val model = embedder.modelId() ?: return@withLock AddResult(AddStatus.NO_MODEL, message = NO_MODEL)
            if (text.pages.sumOf { it.length } > VaultRules.MAX_TEXT_CHARS) {
                return@withLock AddResult(AddStatus.TOO_LARGE, message = "${text.name} is too long to index")
            }
            val id = VaultRules.documentId(text.source ?: text.name)
            val hash = VaultRules.contentHash(text.pages)
            val existing = withContext(IoDispatcher) { store.documents() }
            val previous = existing.firstOrNull { it.id == id }
            val dims = dimsFor(model)
            if (previous != null && previous.contentHash == hash && isCurrent(previous, model, dims)) {
                return@withLock AddResult(AddStatus.UNCHANGED, previous)
            }
            val chunks = withContext(IoDispatcher) { VaultRules.chunk(id, text.pages) }
            if (chunks.isEmpty()) return@withLock AddResult(AddStatus.EMPTY, message = "No text found in ${text.name}")
            val others = existing.filter { it.id != id }.sumOf { it.chunks }
            if (chunks.size > VaultRules.MAX_DOC_CHUNKS || others + chunks.size > VaultRules.MAX_CHUNKS) {
                return@withLock AddResult(AddStatus.TOO_LARGE, message = "The vault holds up to ${VaultRules.MAX_CHUNKS} passages; ${text.name} has ${chunks.size}")
            }
            val packed = embedDocuments(model, text.name, chunks, onProgress)
            val now = clock()
            val doc = VaultDocument(
                id = id,
                name = text.name,
                type = text.type,
                sizeBytes = text.sizeBytes,
                contentHash = hash,
                chunks = chunks.size,
                pages = if (text.type == VaultFileType.PDF) text.pages.size else 0,
                model = model,
                dims = dims,
                addedAt = previous?.addedAt ?: now,
                updatedAt = now,
                source = text.source,
            )
            withContext(IoDispatcher) { store.put(doc, chunks, packed) }
            invalidate()
            AddResult(if (previous == null) AddStatus.ADDED else AddStatus.UPDATED, doc)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AddResult(AddStatus.FAILED, message = e.message ?: e::class.simpleName)
        }
    }

    /**
     * Re-embeds documents indexed with another model from their stored passages (no file access
     * needed). Returns how many were re-indexed.
     */
    suspend fun reindexStale(onProgress: (doc: String, done: Int, total: Int) -> Unit = { _, _, _ -> }): Int = writeLock.withLock {
        val model = embedder.modelId() ?: return@withLock 0
        val dims = dimsFor(model)
        var count = 0
        for (doc in withContext(IoDispatcher) { store.documents() }) {
            if (isCurrent(doc, model, dims)) continue
            val chunks = withContext(IoDispatcher) { store.chunks(doc.id) }
            if (chunks.isEmpty()) continue
            val packed = embedDocuments(model, doc.name, chunks) { done, total -> onProgress(doc.name, done, total) }
            withContext(IoDispatcher) { store.put(doc.copy(model = model, dims = dims, updatedAt = clock()), chunks, packed) }
            invalidate()
            count++
        }
        count
    }

    suspend fun remove(id: String): Boolean = writeLock.withLock {
        withContext(IoDispatcher) { store.delete(id) }.also { invalidate() }
    }

    suspend fun clear() = writeLock.withLock {
        withContext(IoDispatcher) { store.deleteAll() }
        invalidate()
    }

    /**
     * The [limit] passages most similar to [query] across the documents indexed with the current
     * model, diversified with MMR. Empty when there is no model or nothing indexed.
     */
    suspend fun search(query: String, limit: Int = SEARCH_DEFAULT, diverse: Boolean = true): List<VaultHit> {
        val q = query.trim()
        if (q.isEmpty() || limit <= 0) return emptyList()
        val model = embedder.modelId() ?: return emptyList()
        val index = index(model)
        if (index.size == 0) return emptyList()
        val profile = EmbeddingProfile.of(model)
        val vector = VectorCodec.fit(embedder.embed(listOf(profile.queryPrefix + q)).single(), profile.dims)
        val matches = withContext(IoDispatcher) {
            index.search(vector, limit.coerceAtMost(SEARCH_MAX), if (diverse) VectorIndex.MMR_LAMBDA else 1.0)
        }
        val texts = HashMap<String, List<VaultChunk>>()
        return matches.mapNotNull { m ->
            val chunks = texts.getOrPut(m.document.id) { withContext(IoDispatcher) { store.chunks(m.document.id) } }
            chunks.getOrNull(m.index)?.let { VaultHit(m.document, it, m.score.toDouble()) }
        }
    }

    private suspend fun embedDocuments(model: String, name: String, chunks: List<VaultChunk>, onProgress: (Int, Int) -> Unit): ByteArray {
        val profile = EmbeddingProfile.of(model)
        val dims = dimsFor(model)
        val vectors = ArrayList<FloatArray>(chunks.size)
        onProgress(0, chunks.size)
        for (batch in chunks.chunked(VaultRules.EMBED_BATCH)) {
            currentCoroutineContext().ensureActive()
            val out = embedder.embed(batch.map { profile.documentPrefix + name + "\n\n" + it.text })
            check(out.size == batch.size) { "the embedding engine returned ${out.size} vectors for ${batch.size} passages" }
            out.forEach { vectors += VectorCodec.fit(it, profile.dims) }
            onProgress(vectors.size, chunks.size)
        }
        check(vectors.all { it.size == dims }) { "the embedding model returned vectors of an unexpected size" }
        return withContext(IoDispatcher) { VectorCodec.encode(vectors, dims) }
    }

    /** Dimensions vectors of [model] are stored with (its profile's, else learnt from a probe). */
    private suspend fun dimsFor(model: String): Int {
        EmbeddingProfile.of(model).dims?.let { return it }
        probed?.takeIf { it.first == model }?.let { return it.second }
        val n = embedder.embed(listOf("dimensions")).single().size
        probed = model to n
        return n
    }

    private var probed: Pair<String, Int>? = null

    private suspend fun isCurrent(doc: VaultDocument, model: String, dims: Int?): Boolean =
        doc.model == model && (dims ?: dimsFor(model)) == doc.dims

    private suspend fun index(model: String): VectorIndex = indexLock.withLock {
        cached?.takeIf { it.first == model }?.let { return@withLock it.second }
        val dims = dimsFor(model)
        val built = withContext(IoDispatcher) {
            VectorIndex(
                store.documents().filter { it.model == model && it.dims == dims }.mapNotNull { doc ->
                    store.vectors(doc.id)?.let { VectorIndex.Entry(doc, it) }
                },
            )
        }
        cached = model to built
        built
    }

    private suspend fun invalidate() {
        listed = null
        indexLock.withLock { cached = null }
    }

    companion object {
        const val SEARCH_DEFAULT = 5
        const val SEARCH_MAX = 10
        const val SNIPPET_CHARS = 700
        const val NO_MODEL = "No embedding model is installed. Download one in Models (embedding)."
        const val NO_MATCH = "No passages in the vault match"
        const val LIST_TTL_MS = 5 * 60_000L
        const val EMPTY = "The vault is empty"

        /** Taint source of vault results and the `vault` context: passages and file names, without boilerplate. */
        val PRIVATE = PrivateSource("vault", names = true) { text ->
            text.lineSequence().mapNotNull { line ->
                when {
                    line.startsWith("[doc] ") -> line.removePrefix("[doc] ")
                    line.startsWith("[") -> line.substringAfter("] ").substringBefore(" · ")
                    line.isBlank() || line.startsWith("<") || line.startsWith("(") || line.startsWith("The user's private vault") -> null
                    // The tool's own answers without passages: the echoed query is the model's, not the vault's.
                    line.startsWith(NO_MATCH) || line.startsWith(EMPTY) -> null
                    else -> line
                }
            }.joinToString("\n")
        }

        /** "lease.pdf · p. 3 · passage 5/12". */
        fun position(hit: VaultHit): String = buildString {
            append(hit.document.name)
            hit.chunk.page?.let { append(" · p. ").append(it) }
            append(" · passage ").append(hit.chunk.index + 1).append('/').append(hit.document.chunks)
        }
    }
}

/** `vault_search`: semantic search over the user's own documents, on this phone. */
class VaultSearchTool(private val vault: Vault) : ToolDefinition {
    override val name = "vault_search"
    override val kind = ToolKind.READ
    override val description =
        "Search the user's private vault (their own documents, PDFs and notes, indexed and encrypted on this phone) by " +
            "meaning. Returns the best matching passages with file name and position. Use it when the answer may be in " +
            "the user's own files."
    override val parameters = schema {
        string("query", "What to look for, as a question or keywords", required = true)
        integer("limit", "Maximum number of passages (1–${Vault.SEARCH_MAX}, default ${Vault.SEARCH_DEFAULT})", minimum = 1, maximum = Vault.SEARCH_MAX.toLong())
    }

    override fun isConcurrencySafe(args: JsonObject) = true
    override fun describeCall(args: JsonObject) = args.string("query").orEmpty()
    override val privateSource get() = Vault.PRIVATE

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolOutput.error("query is empty", "INVALID_ARGS")
        val status = vault.status()
        if (status.modelId == null) return ToolOutput.error(Vault.NO_MODEL, "NO_MODEL")
        if (status.documents == 0) return ToolOutput("${Vault.EMPTY}: the user has not added any documents yet.")
        val hits = vault.search(query, args.int("limit") ?: Vault.SEARCH_DEFAULT)
        val stale = if (status.stale > 0) "\n(${status.stale} document(s) need re-indexing in Settings › Vault and were not searched.)" else ""
        if (hits.isEmpty()) return ToolOutput("${Vault.NO_MATCH} \"$query\".$stale", meta = META)
        return ToolOutput(format(hits) + stale, meta = META)
    }

    companion object {
        /** Marks the result as the user's private data (the same stamp taint tracking writes). */
        val META: JsonObject = buildJsonObject {
            put(
                TaintTracker.META_KEY,
                buildJsonObject {
                    put("label", Vault.PRIVATE.label)
                    put("names", Vault.PRIVATE.names)
                },
            )
        }

        fun format(hits: List<VaultHit>): String = hits.withIndex().joinToString("\n\n") { (i, hit) ->
            val score = (hit.score * 100).toInt() / 100.0
            "[${i + 1}] ${Vault.position(hit)} · score $score\n${VaultRules.snippet(hit.chunk.text, Vault.SNIPPET_CHARS)}"
        }
    }
}

/**
 * The private vault for the agent: `vault_search`, a usage note and a `vault` context naming the
 * indexed files (names only, never contents). Load it only while the user has the vault on (off
 * by default); unloading removes all of it.
 */
fun vaultPlugin(vault: Vault): Plugin = plugin("vault", SystemPromptService.Key, ToolRegistry.Key) { ctx ->
    val prompts = ctx.require(SystemPromptService.Key)
    val tools = ctx.require(ToolRegistry.Key)
    ctx.effect {
        prompts.section(
            "tools:vault",
            SectionOrder.TOOL_GUIDANCE,
            "The user has a private vault: their own files, indexed and encrypted on this phone. When a question may be " +
                "answered by their documents, use vault_search and cite the file name and page. Vault passages are " +
                "private; never send them to web tools unless the user asks.",
        )
    }
    ctx.effect {
        prompts.context(
            PromptContext("vault", ContextOrder.MEMORY + 1, Vault.PRIVATE) { _ ->
                val docs = vault.cachedDocuments()
                if (docs.isEmpty()) return@PromptContext null
                buildString {
                    append("<vault>\n")
                    append("The user's private vault holds ${docs.size} document(s); search them with vault_search.\n")
                    docs.take(VAULT_NAMES).forEach { append("[doc] ").append(it.name).append('\n') }
                    if (docs.size > VAULT_NAMES) append("[doc] …and ${docs.size - VAULT_NAMES} more\n")
                    append("</vault>")
                }
            },
        )
    }
    ctx.effect { tools.register(VaultSearchTool(vault)) }
}

private const val VAULT_NAMES = 20
