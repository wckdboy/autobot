package dev.wckdboy.autobot

import android.app.Application
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.settings.SettingsSync
import dev.wckdboy.autobot.core.diffusion.OnDeviceBackendSync
import dev.wckdboy.autobot.core.models.ModelDownloader
import dev.wckdboy.autobot.core.security.AppLockManager
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
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
    lateinit var studio: Lazy<StudioHost>

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        appLockManager.get().install()
        engineMemory.get().install()
        settingsSync.get().start()
        localProviderSync.get().start(scope.get())
        onDeviceBackendSync.get().start(scope.get())
        scope.get().launch { downloader.get().recoverAfterRestart() }
        // The Studio queue: engines registered, then jobs queued before the app closed run on.
        studio.get().start()
    }
}
