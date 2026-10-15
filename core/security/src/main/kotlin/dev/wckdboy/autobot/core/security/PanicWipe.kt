package dev.wckdboy.autobot.core.security

import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A component that must release resources (close databases, clear in-memory caches) before a
 * panic wipe deletes the files underneath it. Contributed via Hilt multibindings.
 */
fun interface WipeParticipant {
    suspend fun beforeWipe()

    /**
     * Lower runs first: work that writes (agents, downloads) stops at [STOP_WORK] before
     * storage closes at [CLOSE_STORAGE], so nothing reopens a database whose keys are gone.
     */
    val order: Int get() = STOP_WORK

    companion object {
        const val STOP_WORK = 0
        const val CLOSE_STORAGE = 100

        fun ordered(order: Int, block: suspend () -> Unit): WipeParticipant = object : WipeParticipant {
            override val order = order

            override suspend fun beforeWipe() = block()
        }
    }
}

/** Summary of a completed wipe. Contains no user data. */
data class WipeReport(val deletedPaths: Int, val failedPaths: Int)

/**
 * Irreversibly destroys all local app state:
 *
 * 1. lets every [WipeParticipant] close its resources,
 * 2. deletes all Keystore aliases and wrapped key files (making any surviving ciphertext useless),
 * 3. deletes every database, DataStore file, secret, cache and no-backup file.
 *
 * Callers are expected to terminate the process afterwards via [killProcess] so no in-memory
 * state survives.
 */
@Singleton
class PanicWipe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val secretStore: SecretStore,
    private val participants: Set<@JvmSuppressWildcards WipeParticipant>,
) {
    suspend fun wipe(): WipeReport = withContext(Dispatchers.IO) {
        participants.sortedBy { it.order }.forEach { runCatching { it.beforeWipe() } }

        // Each step on its own: one failure must not leave the rest of the data behind.
        runCatching { keyManager.destroyAllKeys() }
        runCatching { secretStore.deleteAll() }

        var deleted = 0
        var failed = 0
        fun delete(file: File?) {
            if (file == null || !file.exists()) return
            if (runCatching { file.deleteRecursively() }.getOrDefault(false)) deleted++ else failed++
        }

        context.databaseList().forEach { name ->
            if (runCatching { context.deleteDatabase(name) }.getOrDefault(false)) deleted++ else failed++
        }
        val dataDir = context.dataDir
        delete(File(context.filesDir, "datastore"))
        delete(File(dataDir, "shared_prefs"))
        delete(File(dataDir, "databases"))
        delete(context.filesDir)
        delete(context.cacheDir)
        delete(context.codeCacheDir)
        delete(context.noBackupFilesDir)
        WipeReport(deletedPaths = deleted, failedPaths = failed)
    }

    /** Terminates the app process immediately. */
    fun killProcess(): Nothing {
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
