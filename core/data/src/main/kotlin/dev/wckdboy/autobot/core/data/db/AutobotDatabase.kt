package dev.wckdboy.autobot.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.model.BenchmarkRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.McpServer
import dev.wckdboy.autobot.core.data.model.MemoryNoteRow
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.ModelRow
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderModelsRow
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.core.data.model.VaultChunkRow
import dev.wckdboy.autobot.core.data.model.VaultDocumentRow
import dev.wckdboy.autobot.core.security.KeyManager
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Conversation::class, Message::class, Provider::class, SessionEventRow::class, GalleryItem::class, ModelRow::class,
        BenchmarkRow::class, McpServer::class, MemoryNoteRow::class, ProviderModelsRow::class, VaultDocumentRow::class, VaultChunkRow::class,
    ],
    version = 9,
    exportSchema = true,
    // 4 → 5 adds `mcp_servers`, 5 → 6 `memory_notes` (local memory), 6 → 7 `providers.presetId` and
    // `provider_models` (fetched model lists), 7 → 8 `providers.helperModel`, 8 → 9 `vault_documents` and
    // `vault_chunks` (private vault); existing data is untouched.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
    ],
)
abstract class AutobotDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun providerModelsDao(): ProviderModelsDao
    abstract fun sessionEventDao(): SessionEventDao
    abstract fun galleryDao(): GalleryDao
    abstract fun modelDao(): ModelDao
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun memoryDao(): MemoryDao
    abstract fun vaultDao(): VaultDao

    companion object {
        const val NAME = "autobot.db"
    }
}

/**
 * Lazily opens the SQLCipher-encrypted Room database.
 *
 * Opening is deferred to first use (always from a background dispatcher in the repositories) so
 * the Keystore unwrap of the passphrase and native library loading never run on the main thread.
 */
@Singleton
class DatabaseHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
) {
    @Volatile
    private var instance: AutobotDatabase? = null

    fun get(): AutobotDatabase = instance ?: synchronized(this) {
        instance ?: build().also { instance = it }
    }

    /** Closes the database if it was opened. Used before a panic wipe. */
    fun closeIfOpen() = synchronized(this) {
        instance?.close()
        instance = null
    }

    private fun build(): AutobotDatabase {
        System.loadLibrary("sqlcipher")
        val passphrase = keyManager.databasePassphrase()
        return Room.databaseBuilder(context, AutobotDatabase::class.java, AutobotDatabase.NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .build()
    }
}
