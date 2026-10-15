package dev.wckdboy.autobot.shared.vault

import dev.wckdboy.autobot.shared.IoDispatcher
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The host's encrypted vault storage for apps that are not Kotlin (iOS: the `EncryptedStore`).
 * Records are opaque strings the host seals as they are: documents and chunk lists as JSON,
 * vectors as base64. Called on a background thread.
 */
interface VaultStoreBridge {
    /** Every document record (JSON). */
    fun documents(): List<String>
    fun chunks(id: String): String?
    fun vectors(id: String): String?

    /** Writes the three records of one document, replacing an earlier version. */
    fun put(id: String, document: String, chunks: String, vectors: String)
    fun remove(id: String): Boolean
    fun removeAll()
}

/**
 * The host's on-device embedding engine (iOS: llama.cpp in AutobotEngines). Called on a
 * background thread and may block. [embed] returns one base64 string of little-endian float32
 * values per text, or null on failure (with [lastError] saying why).
 */
interface EmbedderBridge {
    fun modelId(): String?
    fun embed(texts: List<String>): List<String>?
    fun lastError(): String?
}

private val VaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** [VaultStore] over a host [VaultStoreBridge]. */
@OptIn(ExperimentalEncodingApi::class)
class BridgedVaultStore(private val bridge: VaultStoreBridge) : VaultStore {
    private val chunkList = ListSerializer(VaultChunk.serializer())

    override suspend fun documents(): List<VaultDocument> = withContext(IoDispatcher) {
        bridge.documents().mapNotNull { runCatching { VaultJson.decodeFromString(VaultDocument.serializer(), it) }.getOrNull() }
    }

    override suspend fun chunks(docId: String): List<VaultChunk> = withContext(IoDispatcher) {
        bridge.chunks(docId)?.let { runCatching { VaultJson.decodeFromString(chunkList, it) }.getOrNull() }.orEmpty()
    }

    override suspend fun vectors(docId: String): ByteArray? = withContext(IoDispatcher) {
        bridge.vectors(docId)?.let { runCatching { Base64.decode(it) }.getOrNull() }
    }

    override suspend fun put(document: VaultDocument, chunks: List<VaultChunk>, vectors: ByteArray) = withContext(IoDispatcher) {
        bridge.put(
            document.id,
            VaultJson.encodeToString(VaultDocument.serializer(), document),
            VaultJson.encodeToString(chunkList, chunks),
            Base64.encode(vectors),
        )
    }

    override suspend fun delete(docId: String): Boolean = withContext(IoDispatcher) { bridge.remove(docId) }

    override suspend fun deleteAll() = withContext(IoDispatcher) { bridge.removeAll() }
}

/** [Embedder] over a host [EmbedderBridge]. */
@OptIn(ExperimentalEncodingApi::class)
class BridgedEmbedder(private val bridge: EmbedderBridge) : Embedder {
    override suspend fun modelId(): String? = withContext(IoDispatcher) { bridge.modelId() }

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(IoDispatcher) {
        val out = bridge.embed(texts) ?: error(bridge.lastError() ?: "the embedding engine failed")
        out.map { floats(Base64.decode(it)) }
    }

    companion object {
        /** Little-endian float32 values, as the host encodes them. */
        fun floats(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 4) { i ->
            val o = i * 4
            Float.fromBits(
                (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24),
            )
        }
    }
}

/** A document row for Swift (the shared [VaultDocument] with plain fields). */
class VaultDocumentInfo(
    val id: String,
    val name: String,
    val type: String,
    val sizeBytes: Long,
    val chunks: Int,
    val pages: Int,
    val updatedAt: Long,
    val current: Boolean,
)

/** A search result for Swift: where it is and a one-line snippet. */
class VaultHitInfo(val docId: String, val name: String, val position: String, val snippet: String, val score: Double)

/** Progress of an add or re-index: [name] is the document, [done] of [total] passages embedded. */
fun interface VaultProgressListener {
    fun onProgress(name: String, done: Int, total: Int)
}

/**
 * The vault for the iOS app: one [Vault] over the host's store and embedder, with callback
 * methods (they return at once; work runs on this object's scope and callbacks arrive on a
 * background thread). [AgentBridge.setVault] hands the same vault to the agent.
 */
class VaultHost(store: VaultStoreBridge, embedder: EmbedderBridge) {
    val vault = Vault(BridgedVaultStore(store), BridgedEmbedder(embedder))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    fun documents(callback: (List<VaultDocumentInfo>, String?, Int) -> Unit) {
        scope.launch {
            val status = vault.status()
            val docs = vault.documents().map { d ->
                VaultDocumentInfo(d.id, d.name, d.type.label, d.sizeBytes, d.chunks, d.pages, d.updatedAt, d.model == status.modelId)
            }
            callback(docs, status.modelId, status.stale)
        }
    }

    /**
     * Indexes files one after another: [names] with their [types] (`VaultFileType` names),
     * sizes, sources and pages. [done] gets one line per file that was not added cleanly.
     */
    fun add(
        names: List<String>,
        types: List<String>,
        sizes: List<Long>,
        sources: List<String>,
        pages: List<List<String>>,
        progress: VaultProgressListener,
        done: (List<String>) -> Unit,
    ) {
        work = scope.launch {
            val problems = ArrayList<String>()
            for (i in names.indices) {
                val type = VaultFileType.entries.firstOrNull { it.name == types[i] } ?: VaultFileType.TEXT
                val result = vault.add(VaultText(names[i], type, sizes[i], pages[i], sources[i])) { d, t -> progress.onProgress(names[i], d, t) }
                when (result.status) {
                    AddStatus.ADDED, AddStatus.UPDATED, AddStatus.UNCHANGED -> Unit
                    else -> problems += "${names[i]}: ${result.message ?: result.status.name.lowercase()}"
                }
                if (result.status == AddStatus.NO_MODEL) break
            }
            done(problems)
        }
    }

    fun reindex(progress: VaultProgressListener, done: (Int) -> Unit) {
        work = scope.launch { done(vault.reindexStale { name, d, t -> progress.onProgress(name, d, t) }) }
    }

    /** Stops a running add or re-index after the current batch. */
    fun cancelWork() {
        work?.cancel()
    }

    fun search(query: String, limit: Int, callback: (List<VaultHitInfo>, String?) -> Unit) {
        scope.launch {
            val result = runCatching { vault.search(query, limit) }
            val hits = result.getOrDefault(emptyList()).map { h ->
                VaultHitInfo(h.document.id, h.document.name, Vault.position(h), VaultRules.snippet(h.chunk.text, Vault.SNIPPET_CHARS), h.score)
            }
            callback(hits, result.exceptionOrNull()?.message)
        }
    }

    fun remove(id: String, done: () -> Unit) {
        scope.launch {
            vault.remove(id)
            done()
        }
    }

    fun clear(done: () -> Unit) {
        scope.launch {
            vault.clear()
            done()
        }
    }
}
