package dev.wckdboy.autobot.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.wckdboy.autobot.core.data.model.BenchmarkRow
import dev.wckdboy.autobot.core.data.model.ChatFolderRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.ConversationSearchRow
import dev.wckdboy.autobot.core.data.model.ConversationTextRow
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.McpServer
import dev.wckdboy.autobot.core.data.model.MemoryNoteRow
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.ModelRow
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderModelsRow
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<Conversation?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): Conversation?

    @Upsert
    suspend fun upsert(conversation: Conversation)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT id FROM conversations")
    suspend fun allIds(): List<String>

    /** Renaming does not count as activity: [Conversation.updatedAt] stays. */
    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id IN (:ids)")
    suspend fun setPinned(ids: List<String>, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id IN (:ids)")
    suspend fun setArchived(ids: List<String>, archived: Boolean)

    @Query("UPDATE conversations SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun setFolder(ids: List<String>, folderId: String?)

    @Query("UPDATE conversations SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    /** Chats without searchable text yet (from before 1.5, or imported). */
    @Query("SELECT id FROM conversations WHERE id NOT IN (SELECT conversationId FROM conversation_text)")
    suspend fun unindexed(): List<String>

    @Query("SELECT text FROM conversation_text WHERE conversationId = :id")
    suspend fun searchText(id: String): String?

    @Upsert
    suspend fun upsertSearchText(row: ConversationTextRow)

    /** Chats whose title or text contains [pattern] (a `LIKE` pattern escaped with a backslash). */
    @Query(
        "SELECT c.id AS id, c.title AS title, t.text AS text FROM conversations c " +
            "LEFT JOIN conversation_text t ON t.conversationId = c.id " +
            "WHERE c.title LIKE :pattern ESCAPE '\\' OR t.text LIKE :pattern ESCAPE '\\'",
    )
    suspend fun searchCandidates(pattern: String): List<ConversationSearchRow>
}

@Dao
interface ChatFolderDao {
    @Query("SELECT * FROM chat_folders ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ChatFolderRow>>

    @Query("SELECT * FROM chat_folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<ChatFolderRow>

    @Query("SELECT * FROM chat_folders WHERE id = :id")
    suspend fun get(id: String): ChatFolderRow?

    @Upsert
    suspend fun upsert(row: ChatFolderRow)

    @Query("DELETE FROM chat_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_folders")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getForConversation(conversationId: String): List<Message>

    @Upsert
    suspend fun upsert(message: Message)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Provider>>

    @Query("SELECT * FROM providers WHERE id = :id")
    fun observe(id: String): Flow<Provider?>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun get(id: String): Provider?

    @Query("SELECT id FROM providers ORDER BY displayName COLLATE NOCASE ASC LIMIT 1")
    suspend fun firstId(): String?

    @Upsert
    suspend fun upsert(provider: Provider)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SessionEventDao {
    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun load(sessionId: String): List<SessionEventRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<SessionEventRow>)

    @Query("SELECT COUNT(*) FROM session_events WHERE sessionId = :sessionId")
    suspend fun count(sessionId: String): Int
}

@Dao
interface GalleryDao {
    @Query("SELECT * FROM gallery_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GalleryItem>>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    suspend fun get(id: String): GalleryItem?

    @Upsert
    suspend fun upsert(item: GalleryItem)

    @Query("UPDATE gallery_items SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("DELETE FROM gallery_items WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelRow>>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: String): ModelRow?

    @Query("SELECT * FROM models")
    suspend fun all(): List<ModelRow>

    @Upsert
    suspend fun upsert(row: ModelRow)

    @Query("UPDATE models SET downloadedBytes = :downloaded, status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, downloaded: Long, status: String, error: String?, now: Long)

    @Query("UPDATE models SET backend = :backend, updatedAt = :now WHERE id = :id")
    suspend fun setBackend(id: String, backend: String, now: Long)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BenchmarkDao {
    @Insert
    suspend fun insert(row: BenchmarkRow): Long

    @Query("SELECT * FROM benchmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BenchmarkRow>>

    @Query("SELECT * FROM benchmarks WHERE modelId = :modelId ORDER BY createdAt DESC")
    suspend fun forModel(modelId: String): List<BenchmarkRow>

    @Query("DELETE FROM benchmarks WHERE modelId = :modelId")
    suspend fun deleteForModel(modelId: String)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<McpServer>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun get(id: String): McpServer?

    @Upsert
    suspend fun upsert(server: McpServer)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MemoryNoteRow>>

    @Query("SELECT * FROM memory_notes ORDER BY updatedAt DESC")
    suspend fun all(): List<MemoryNoteRow>

    @Query("SELECT * FROM memory_notes WHERE id = :id")
    suspend fun get(id: String): MemoryNoteRow?

    @Upsert
    suspend fun upsert(row: MemoryNoteRow)

    @Query("DELETE FROM memory_notes WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM memory_notes")
    suspend fun deleteAll()
}

@Dao
interface ProviderModelsDao {
    @Query("SELECT * FROM provider_models")
    fun observeAll(): Flow<List<ProviderModelsRow>>

    @Query("SELECT * FROM provider_models WHERE providerId = :providerId")
    suspend fun get(providerId: String): ProviderModelsRow?

    @Upsert
    suspend fun upsert(row: ProviderModelsRow)

    @Query("DELETE FROM provider_models WHERE providerId = :providerId")
    suspend fun delete(providerId: String)
}
