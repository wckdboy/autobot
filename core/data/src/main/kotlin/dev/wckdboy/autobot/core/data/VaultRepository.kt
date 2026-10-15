package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.VaultChunkRow
import dev.wckdboy.autobot.core.data.model.VaultChunkText
import dev.wckdboy.autobot.core.data.model.VaultDocumentRow
import dev.wckdboy.autobot.shared.vault.VaultChunk
import dev.wckdboy.autobot.shared.vault.VaultDocument
import dev.wckdboy.autobot.shared.vault.VaultFileType
import dev.wckdboy.autobot.shared.vault.VaultStore
import dev.wckdboy.autobot.shared.vault.VectorCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The private vault's documents, passages and vectors in the encrypted database: the
 * [VaultStore] behind `vault_search` and the Vault screen.
 */
@Singleton
class VaultRepository @Inject constructor(
    private val database: DatabaseHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) : VaultStore {

    /** Newest first. */
    fun observeDocuments(): Flow<List<VaultDocument>> =
        flow { emitAll(database.get().vaultDao().observeDocuments()) }.map { rows -> rows.map { it.toDocument() } }.flowOn(io)

    override suspend fun documents(): List<VaultDocument> = withContext(io) { database.get().vaultDao().documents().map { it.toDocument() } }

    override suspend fun chunks(docId: String): List<VaultChunk> = withContext(io) { database.get().vaultDao().chunks(docId).map { it.toChunk() } }

    override suspend fun vectors(docId: String): ByteArray? = withContext(io) {
        val rows = database.get().vaultDao().vectors(docId)
        if (rows.isEmpty()) return@withContext null
        val out = ByteArray(rows.sumOf { it.size })
        var offset = 0
        for (v in rows) {
            v.copyInto(out, offset)
            offset += v.size
        }
        out
    }

    override suspend fun put(document: VaultDocument, chunks: List<VaultChunk>, vectors: ByteArray) = withContext(io) {
        database.get().vaultDao().put(document.toRow(), rows(chunks, vectors, document.dims))
    }

    override suspend fun delete(docId: String): Boolean = withContext(io) { database.get().vaultDao().delete(docId) > 0 }

    override suspend fun deleteAll() = withContext(io) { database.get().vaultDao().deleteAll() }

    companion object {
        fun VaultDocumentRow.toDocument() = VaultDocument(
            id, name, VaultFileType.entries.firstOrNull { it.name == type } ?: VaultFileType.TEXT, sizeBytes, contentHash, chunks, pages,
            model, dims, addedAt, updatedAt, source,
        )

        fun VaultDocument.toRow() = VaultDocumentRow(id, name, type.name, sizeBytes, contentHash, chunks, pages, model, dims, addedAt, updatedAt, source)

        fun VaultChunkText.toChunk() = VaultChunk(docId, idx, text, startOffset, endOffset, page)

        /** One row per passage, each with its slice of the packed vectors. */
        fun rows(chunks: List<VaultChunk>, vectors: ByteArray, dims: Int): List<VaultChunkRow> {
            val stride = VectorCodec.size(dims)
            require(vectors.size == stride * chunks.size) { "vectors do not match the passages" }
            return chunks.mapIndexed { i, c ->
                VaultChunkRow(c.docId, c.index, c.text, c.start, c.end, c.page, vectors.copyOfRange(i * stride, (i + 1) * stride))
            }
        }
    }
}
