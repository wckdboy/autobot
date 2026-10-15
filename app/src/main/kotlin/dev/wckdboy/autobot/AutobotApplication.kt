package dev.wckdboy.autobot

import android.app.Application
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.settings.SettingsSync
import dev.wckdboy.autobot.core.diffusion.OnDeviceBackendSync
import dev.wckdboy.autobot.core.models.ModelDownloader
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class AutobotApplication : Application() {

    // Lazy: the engine processes (":llm", ":sd") run this class too, and must not open the
    // database, DataStore or start syncs.
    @Inject
    lateinit var appLockManager: Lazy<AppLockManager>

    @Inject
    lateinit var settingsSync: Lazy<SettingsSync>

    @Inject
    lateinit var localProviderSync: Lazy<LocalProviderSync>

    @Inject
    lateinit var onDeviceBackendSync: Lazy<OnDeviceBackendSync>

    @Inject
    lateinit var downloader: Lazy<ModelDownloader>

    @Inject
    @ApplicationScope
    lateinit var scope: Lazy<CoroutineScope>

    @Inject
    lateinit var engineMemory: Lazy<EngineMemoryCoordinator>

    @Inject
    lateinit var database: Lazy<DatabaseHolder>

    @Inject
    lateinit var attachments: Lazy<AttachmentRepository>

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        val started = System.currentTimeMillis()
        // Open (decrypt, migrate) the database now, in parallel with the settings DataStore load,
        // instead of after the activity's first frame: MainActivity's gate then finds it ready.
        // Failures land in DatabaseHolder.state, which the activity shows as before.
        scope.get().launch(Dispatchers.IO) {
            database.get().check()
            // Decrypted attachment copies a crashed request left behind (privacy).
            runCatching { attachments.get().clearPlainCopies(before = started) }
        }
        appLockManager.get().install()
        engineMemory.get().install()
        settingsSync.get().start()
        localProviderSync.get().start(scope.get())
        onDeviceBackendSync.get().start(scope.get())
        scope.get().launch { downloader.get().recoverAfterRestart() }
    }
}
