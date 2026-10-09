package dev.wckdboy.autobot.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single in-memory source of truth for the current egress policy.
 *
 * The persisted value lives in the settings DataStore; it is mirrored here at startup. Until that
 * happens the policy is [NetworkMode.Offline], so the app fails closed.
 */
@Singleton
class NetworkPolicy @Inject constructor() {

    private val _mode = MutableStateFlow<NetworkMode>(NetworkMode.Offline)

    /** The global network mode. Defaults to [NetworkMode.Offline]. */
    val mode: StateFlow<NetworkMode> = _mode.asStateFlow()

    private val _allowLoopbackWhenOffline = MutableStateFlow(true)

    /**
     * When `true` (default) requests to loopback hosts (`localhost`, `127.0.0.0/8`, `::1`) are
     * allowed while [NetworkMode.Offline], so on-device servers like Ollama keep working.
     */
    val allowLoopbackWhenOffline: StateFlow<Boolean> = _allowLoopbackWhenOffline.asStateFlow()

    private val _socksFallback = MutableStateFlow(NetworkMode.Socks5(DEFAULT_SOCKS_HOST, DEFAULT_SOCKS_PORT))

    /**
     * SOCKS5 endpoint used for providers that force SOCKS5 routing while the global mode is not
     * [NetworkMode.Socks5]. Updated whenever a SOCKS5 global mode is applied.
     */
    val socksFallback: StateFlow<NetworkMode.Socks5> = _socksFallback.asStateFlow()

    fun setMode(mode: NetworkMode) {
        if (mode is NetworkMode.Socks5) _socksFallback.value = mode
        _mode.value = mode
    }

    fun setAllowLoopbackWhenOffline(allow: Boolean) {
        _allowLoopbackWhenOffline.value = allow
    }

    fun setSocksFallback(endpoint: NetworkMode.Socks5) {
        _socksFallback.value = endpoint
    }

    companion object {
        const val DEFAULT_SOCKS_HOST = "127.0.0.1"
        const val DEFAULT_SOCKS_PORT = 1080
    }
}
