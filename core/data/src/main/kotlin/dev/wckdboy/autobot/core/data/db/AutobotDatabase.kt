package dev.wckdboy.autobot.core.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.core.security.KeyManager
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [Conversation::class, Message::class, Provider::class, SessionEventRow::class, GalleryItem::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AutobotDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun sessionEventDao(): SessionEventDao
    abstract fun galleryDao(): GalleryDao

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
