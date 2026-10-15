package dev.wckdboy.autobot.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.wckdboy.autobot.core.data.model.VaultChunkRow
import dev.wckdboy.autobot.core.data.model.VaultChunkText
import dev.wckdboy.autobot.core.data.model.VaultDocumentRow
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_documents ORDER BY updatedAt DESC")
    fun observeDocuments(): Flow<List<VaultDocumentRow>>

    @Query("SELECT * FROM vault_documents ORDER BY updatedAt DESC")
    suspend fun documents(): List<VaultDocumentRow>

    @Query("SELECT docId, idx, text, startOffset, endOffset, page FROM vault_chunks WHERE docId = :docId ORDER BY idx")
    suspend fun chunks(docId: String): List<VaultChunkText>

    @Query("SELECT vector FROM vault_chunks WHERE docId = :docId ORDER BY idx")
    suspend fun vectors(docId: String): List<ByteArray>

    @Upsert
    suspend fun upsertDocument(row: VaultDocumentRow)

    @Insert
    suspend fun insertChunks(rows: List<VaultChunkRow>)

    @Query("DELETE FROM vault_chunks WHERE docId = :docId")
    suspend fun deleteChunks(docId: String)

    @Query("DELETE FROM vault_documents WHERE id = :id")
    suspend fun deleteDocument(id: String): Int

    /** Replaces a document and all its passages at once. */
    @Transaction
    suspend fun put(document: VaultDocumentRow, chunks: List<VaultChunkRow>) {
        deleteChunks(document.id)
        upsertDocument(document)
        insertChunks(chunks)
    }

    @Transaction
    suspend fun delete(id: String): Int {
        deleteChunks(id)
        return deleteDocument(id)
    }

    @Query("DELETE FROM vault_chunks")
    suspend fun deleteAllChunks()

    @Query("DELETE FROM vault_documents")
    suspend fun deleteAllDocuments()

    @Transaction
    suspend fun deleteAll() {
        deleteAllChunks()
        deleteAllDocuments()
    }
}
