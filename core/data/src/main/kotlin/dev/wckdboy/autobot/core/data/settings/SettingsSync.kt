package dev.wckdboy.autobot.core.data.settings

import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.security.AppLockManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Mirrors persisted settings into the in-memory enforcement points ([NetworkPolicy],
 * [AppLockManager]). Both fail closed (Offline / Locked) until the first value arrives.
 */
@Singleton
class SettingsSync @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkPolicy: NetworkPolicy,
    private val httpClientFactory: HttpClientFactory,
    private val appLockManager: AppLockManager,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Starts mirroring. Idempotent; call from `Application.onCreate`. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                settingsRepository.settings
                    .map { it.networkMode to it.allowLoopbackWhenOffline }
                    .distinctUntilChanged()
                    .collect { (mode, allowLoopback) ->
                        networkPolicy.setAllowLoopbackWhenOffline(allowLoopback)
                        networkPolicy.setMode(mode)
                        httpClientFactory.evictAll()
                    }
            }
            launch(Dispatchers.Main.immediate) {
                settingsRepository.settings
                    .map { it.appLockEnabled to it.lockTimeoutMillis }
                    .distinctUntilChanged()
                    .collect { (enabled, timeout) -> appLockManager.configure(enabled, timeout) }
            }
        }
    }
}
