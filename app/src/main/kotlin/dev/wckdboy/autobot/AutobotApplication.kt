package dev.wckdboy.autobot

import android.app.Application
import android.os.Trace
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeBook
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.settings.SettingsSync
import dev.wckdboy.autobot.core.diffusion.OnDeviceBackendSync
import dev.wckdboy.autobot.core.models.AppVisibility
import dev.wckdboy.autobot.core.models.ModelDownloader
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    lateinit var visibility: Lazy<AppVisibility>

    @Inject
    @ApplicationScope
    lateinit var scope: Lazy<CoroutineScope>

    @Inject
    lateinit var engineMemory: Lazy<EngineMemoryCoordinator>

    @Inject
    lateinit var database: Lazy<DatabaseHolder>

    @Inject
    lateinit var attachments: Lazy<AttachmentRepository>

    @Inject
    lateinit var studio: Lazy<StudioHost>

    @Inject
    lateinit var recipes: Lazy<RecipeBook>

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        // A trace section, so startup traces (Perfetto, Macrobenchmark) show what this costs.
        Trace.beginSection("Autobot.onCreate")
        // Before any activity starts: download notifications are only posted while the app is not on screen.
        visibility.get().install(this)
        val started = System.currentTimeMillis()
        val scope = scope.get()
        // Open (decrypt, migrate) the database now, in parallel with the settings DataStore load,
        // instead of after the activity's first frame: MainActivity's gate then finds it ready.
        // Failures land in DatabaseHolder.state, which the activity shows as before.
        scope.launch(Dispatchers.IO) {
            database.get().check()
            // Decrypted attachment copies a crashed request left behind (privacy).
            runCatching { attachments.get().clearPlainCopies(before = started) }
        }
        // On the main thread, before the first activity: the lock observes the process lifecycle
        // and must be armed (it starts locked) before anything shows; engines get their memory
        // hooks before any job can start.
        appLockManager.get().install()
        engineMemory.get().install()
        settingsSync.get().start()
        // The rest only follows data in the background: building those objects (the provider,
        // model, backend and Studio repositories, HTTP clients, Studio engines) is not main-thread
        // work and does not need to finish before the first frame. Each starts on its own, so one
        // that fails to build does not keep the others from starting.
        scope.launch { localProviderSync.get().start(scope) }
        scope.launch { onDeviceBackendSync.get().start(scope) }
        scope.launch { downloader.get().recoverAfterRestart() }
        // The Studio queue: engines registered, then jobs queued before the app closed run on.
        scope.launch { studio.get().start() }
        // Recipes: next runs brought up to date (time zone, missed runs) and handed to WorkManager,
        // after start-up has settled.
        scope.launch {
            delay(RECIPES_DELAY_MS)
            runCatching { recipes.get().reconcile() }
        }
        Trace.endSection()
    }

    private companion object {
        const val RECIPES_DELAY_MS = 3_000L
    }
}
