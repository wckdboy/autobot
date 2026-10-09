package dev.wckdboy.autobot

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.wckdboy.autobot.core.data.settings.SettingsSync
import dev.wckdboy.autobot.core.security.AppLockManager
import javax.inject.Inject

@HiltAndroidApp
class AutobotApplication : Application() {

    @Inject
    lateinit var appLockManager: AppLockManager

    @Inject
    lateinit var settingsSync: SettingsSync

    override fun onCreate() {
        super.onCreate()
        appLockManager.install()
        settingsSync.start()
    }
}
