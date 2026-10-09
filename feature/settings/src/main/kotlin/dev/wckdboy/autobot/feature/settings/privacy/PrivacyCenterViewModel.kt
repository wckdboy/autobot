package dev.wckdboy.autobot.feature.settings.privacy

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.network.AuditEntry
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.security.PanicWipe
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class AuditRow(
    val id: Long,
    val timeMillis: Long,
    val method: String,
    val host: String,
    val path: String,
    val detail: String,
    val blocked: Boolean,
)

@Immutable
data class PrivacyCenterUiState(
    val mode: NetworkMode = NetworkMode.Offline,
    val allowLoopbackWhenOffline: Boolean = true,
    val audit: List<AuditRow> = emptyList(),
    val wiping: Boolean = false,
)

@HiltViewModel
class PrivacyCenterViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val auditLog: AuditLog,
    private val panicWipe: PanicWipe,
) : ViewModel() {

    private val wiping = MutableStateFlow(false)

    val uiState: StateFlow<PrivacyCenterUiState> = combine(settings.settings, auditLog.entries, wiping) { s, entries, isWiping ->
        PrivacyCenterUiState(
            mode = s.networkMode,
            allowLoopbackWhenOffline = s.allowLoopbackWhenOffline,
            audit = entries.asReversed().map { it.toRow() },
            wiping = isWiping,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrivacyCenterUiState())

    fun setMode(mode: NetworkMode) {
        viewModelScope.launch { settings.setNetworkMode(mode) }
    }

    fun setAllowLoopback(allow: Boolean) {
        viewModelScope.launch { settings.setAllowLoopbackWhenOffline(allow) }
    }

    fun clearAudit() = auditLog.clear()

    /** Wipes all local data and keys, then kills the process. Requires the typed confirmation. */
    fun panicWipe(confirmation: String) {
        if (confirmation != WIPE_CONFIRMATION || wiping.value) return
        wiping.value = true
        viewModelScope.launch {
            try {
                panicWipe.wipe()
            } finally {
                panicWipe.killProcess()
            }
        }
    }

    private fun AuditEntry.toRow() = AuditRow(
        id = id,
        timeMillis = timeMillis,
        method = method,
        host = host,
        path = path,
        detail = buildList {
            add(route)
            tag?.let(::add)
            status?.let { add("HTTP $it") }
            bytesSent?.let { add("↑${it}B") }
            bytesReceived?.let { add("↓${it}B") }
            error?.let(::add)
        }.joinToString(" · "),
        blocked = blocked,
    )

    companion object {
        const val WIPE_CONFIRMATION = "WIPE"
    }
}
