package dev.wckdboy.autobot.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.Provider
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
