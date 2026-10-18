package dev.wckdboy.autobot.providers.remote

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A pairing code that arrived by link (`invincible://pair?…`, from the system camera reading a
 * node's QR code), waiting for the Providers screen to show it. It only fills in the pairing
 * sheet: nothing pairs until the user taps Pair.
 */
@Singleton
class NodePairingLinks @Inject constructor() {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code

    fun put(code: String) {
        _code.value = code
    }

    /** Takes the waiting code, if any. */
    fun take(): String? = _code.value.also { _code.value = null }
}
