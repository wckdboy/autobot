package dev.wckdboy.autobot.core.data.db

import android.content.Context
import android.util.Log
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
import dev.wckdboy.autobot.core.security.KeyManager
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Conversation::class, Message::class, Provider::class, SessionEventRow::class, GalleryItem::class, ModelRow::class,
        BenchmarkRow::class, McpServer::class, MemoryNoteRow::class, ProviderModelsRow::class,
    ],
    version = 8,
    exportSchema = true,
    // 4 → 5 adds `mcp_servers`, 5 → 6 `memory_notes` (local memory), 6 → 7 `providers.presetId` and
    // `provider_models` (fetched model lists), 7 → 8 `providers.helperModel`; existing data is untouched.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8),
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

    companion object {
        const val NAME = "autobot.db"
    }
}

/** Whether the encrypted database could be opened (see [DatabaseHolder.state]). */
sealed interface DatabaseState {
    data object Unknown : DatabaseState
    data object Open : DatabaseState

    /** It cannot be opened: key lost (e.g. a restored backup without its Keystore key) or file corrupt. */
    data class Failed(val reason: String) : DatabaseState
}

/** Thrown instead of a raw SQLCipher/Keystore error when the database cannot be used. */
class DatabaseUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Lazily opens the SQLCipher-encrypted Room database.
 *
 * Opening is deferred to first use (always from a background dispatcher in the repositories) so
 * the Keystore unwrap of the passphrase and native library loading never run on the main thread.
 * Opening is verified right away: a database that cannot be decrypted or read moves [state] to
 * [DatabaseState.Failed] (the app then offers [reset]) and every use throws
 * [DatabaseUnavailableException] instead of crashing deep inside a query.
 */
@Singleton
class DatabaseHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
) {
    @Volatile
    private var instance: AutobotDatabase? = null

    /** Set by a panic wipe: the keys are gone, so nothing may open (and recreate) the database. */
    @Volatile
    private var wiped = false

    private val _state = MutableStateFlow<DatabaseState>(DatabaseState.Unknown)
    val state: StateFlow<DatabaseState> = _state.asStateFlow()

    fun get(): AutobotDatabase = instance ?: synchronized(this) {
        if (wiped) throw DatabaseUnavailableException("The database was wiped")
        (_state.value as? DatabaseState.Failed)?.let { throw DatabaseUnavailableException(it.reason) }
        instance ?: open().also { instance = it }
    }

    /** Opens the database now (off the main thread) so [state] is known before screens use it. */
    fun check(): DatabaseState {
        runCatching { get() }
        return _state.value
    }

    /** Closes the database if it was opened. */
    fun closeIfOpen() = synchronized(this) {
        runCatching { instance?.close() }
        instance = null
    }

    /** Before a panic wipe: closes the database and refuses to reopen it. */
    fun closeForWipe() = synchronized(this) {
        wiped = true
        closeIfOpen()
    }

    /**
     * Deletes the unreadable database and its key and starts an empty one. Conversations, providers
     * and the gallery index stored in it are lost (they could not be read anyway).
     */
    fun reset(): DatabaseState = synchronized(this) {
        closeIfOpen()
        context.deleteDatabase(AutobotDatabase.NAME)
        keyManager.resetDatabaseKey()
        _state.value = DatabaseState.Unknown
        check()
    }

    private fun open(): AutobotDatabase {
        val db = try {
            System.loadLibrary("sqlcipher")
            val passphrase = keyManager.databasePassphrase()
            Room.databaseBuilder(context, AutobotDatabase::class.java, AutobotDatabase.NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
                // Room opens lazily: open (decrypt, migrate) here, where a failure can be handled.
                .also { it.openHelper.writableDatabase }
        } catch (e: Exception) {
            fail(e)
        } catch (e: UnsatisfiedLinkError) {
            fail(e)
        }
        _state.value = DatabaseState.Open
        return db
    }

    private fun fail(e: Throwable): Nothing {
        Log.e(TAG, "cannot open the database", e)
        val reason = when {
            e is GeneralSecurityException || e.cause is GeneralSecurityException -> "Its key is no longer in this phone's keystore"
            e.javaClass.simpleName.contains("SQLite") -> "The file is damaged or not readable"
            else -> e.javaClass.simpleName
        }
        _state.value = DatabaseState.Failed(reason)
        throw DatabaseUnavailableException("Cannot open the encrypted database: $reason", e)
    }

    private companion object {
        const val TAG = "DatabaseHolder"
    }
}
