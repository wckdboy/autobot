package dev.wckdboy.autobot.core.security

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.security.panic.FileWipeHost
import dev.wckdboy.autobot.core.security.panic.PinLock
import dev.wckdboy.autobot.core.security.panic.PinLockStore
import dev.wckdboy.autobot.core.security.panic.PinMacKey
import dev.wckdboy.autobot.core.security.panic.WipeFiles
import dev.wckdboy.autobot.shared.panic.PinVerifier
import dev.wckdboy.autobot.shared.panic.WipeLanding
import dev.wckdboy.autobot.shared.panic.WipePlan
import dev.wckdboy.autobot.shared.panic.WipeRunner
import dev.wckdboy.autobot.shared.panic.WipeStep
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/**
 * The registry of downloaded models, as text the new install can adopt after a wipe that keeps
 * models (catalog metadata only: ids, titles, files, hashes). Bound by the models module.
 */
fun interface KeptModelsSource {
    suspend fun export(): String?
}

/** Summary of a completed wipe. Contains no user data. */
data class WipeReport(val deletedPaths: Int, val failedPaths: Int)

/**
 * Irreversibly destroys the app's data, in [WipePlan] order with a crash-safety journal:
 *
 * 1. the journal is written and synced (from then on the wipe always completes),
 * 2. every Keystore key and wrapped key file and every stored secret is destroyed, so any
 *    ciphertext left behind is noise,
 * 3. running work stops and storage closes ([WipeParticipant]s),
 * 4. models are parked when kept, then everything else under the app's data folder is deleted
 *    ([WipeFiles]),
 * 5. models come back with their registry, the lock record is reset for the landing.
 *
 * The lock screen's wipes ([wipeAndRestart]) stop after step 2 and restart the process: the
 * next start finishes the rest in [resumeIfPending] before anything opens, so no decrypted state
 * survives in memory and the screen returns at once. Nothing here logs.
 */
@Singleton
class PanicWipe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyManager: KeyManager,
    private val secretStore: SecretStore,
    private val participants: Set<@JvmSuppressWildcards WipeParticipant>,
    private val keptModels: Set<@JvmSuppressWildcards KeptModelsSource>,
    private val store: PinLockStore,
    private val pinKey: PinMacKey,
    private val pinLock: PinLock,
) {
    private val files = WipeFiles(
        dataDir = context.dataDir,
        filesDir = context.filesDir,
        extraDirs = listOfNotNull(context.externalCacheDir, context.getExternalFilesDir(null)),
    )

    private val host = FileWipeHost(
        files = files,
        store = store,
        pinKey = pinKey,
        destroyKeys = {
            // Each on its own: one failure must not leave the other keys behind.
            runCatching { keyManager.destroyAllKeys() }
            runCatching { secretStore.deleteAll() }
        },
        stopWork = { participants.sortedBy { it.order }.forEach { runCatching { it.beforeWipe() } } },
        deleteDatabases = {
            context.databaseList().forEach { runCatching { context.deleteDatabase(it) } }
            // Posted notifications (a finished download, an image) would outlive the data they name.
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
        },
        finished = {
            keyManager.reopen()
            pinLock.reload()
        },
    )

    /** The settings wipe: everything, models too, then the caller ends the process ([killProcess]). */
    suspend fun wipe(): WipeReport = withContext(Dispatchers.IO) {
        WipeRunner(host).run(WipePlan.journal(WipeLanding.FIRST_RUN, keepModels = false), resumed = false)
        WipeReport(deletedPaths = files.deleted, failedPaths = files.failed)
    }

    /**
     * A wipe from the lock screen (failed-unlock limit, or the duress PIN with [carriedPin], its
     * verifier, for a [WipeLanding.QUIET] landing): commits, destroys the keys and restarts the
     * app, which finishes the wipe before anything opens. Never returns.
     */
    suspend fun wipeAndRestart(landing: WipeLanding, carriedPin: PinVerifier? = null): Nothing {
        val record = store.read()
        val keep = !record.wipeModels
        val registry = if (keep) runCatching { keptModels.firstOrNull()?.export() }.getOrNull() else null
        val journal = WipePlan.journal(landing, keep, registry, carriedPin.takeIf { landing == WipeLanding.QUIET }, record.biometrics)
        withContext(Dispatchers.IO) { WipeRunner(host).run(journal, resumed = false, through = WipeStep.DESTROY_KEYS) }
        restartProcess()
    }

    /**
     * Finishes a wipe a kill (or [wipeAndRestart]) interrupted. Call first thing at process start,
     * before the database, DataStore or any repository opens. Returns whether a wipe ran.
     */
    fun resumeIfPending(): Boolean {
        val journal = store.journal() ?: return false
        runBlocking(Dispatchers.IO) { WipeRunner(host).run(journal, resumed = true) }
        return true
    }

    /** Ends every process of the app and starts it again without a transition. */
    fun restartProcess(): Nothing {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { context.startActivity(launch, ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()) }
        }
        killProcess()
    }

    /** Terminates every process of the app (the engine processes too), this one last. */
    fun killProcess(): Nothing {
        val me = Process.myPid()
        runCatching {
            context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.filter { it.uid == Process.myUid() && it.pid != me }
                ?.forEach { Process.killProcess(it.pid) }
        }
        Process.killProcess(me)
        exitProcess(0)
    }

    internal val panicDir: File get() = files.panicDir
}
