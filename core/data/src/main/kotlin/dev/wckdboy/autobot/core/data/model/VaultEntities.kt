package dev.wckdboy.autobot.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A document in the private vault (see `:shared` `VaultDocument`); [type] is a `VaultFileType` name. */
@Entity(tableName = "vault_documents", indices = [Index("updatedAt")])
data class VaultDocumentRow(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val sizeBytes: Long,
    val contentHash: String,
    val chunks: Int,
    val pages: Int,
    val model: String,
    val dims: Int,
    val addedAt: Long,
    val updatedAt: Long,
    val source: String?,
)

/**
 * One passage of a vault document with its embedding ([vector], `VectorCodec` format: a few
 * hundred bytes). One row per passage keeps every row far below the cursor window.
 */
@Entity(tableName = "vault_chunks", primaryKeys = ["docId", "idx"])
class VaultChunkRow(
    val docId: String,
    val idx: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val page: Int?,
    val vector: ByteArray,
)

/** A passage without its vector (what search results and re-indexing read). */
data class VaultChunkText(
    val docId: String,
    val idx: Int,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val page: Int?,
)
