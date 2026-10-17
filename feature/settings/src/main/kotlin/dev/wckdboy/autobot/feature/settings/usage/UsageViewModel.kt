package dev.wckdboy.autobot.feature.settings.usage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.UsageRepository
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.shared.usage.PriceEntry
import dev.wckdboy.autobot.shared.usage.SubscriptionWindow
import dev.wckdboy.autobot.shared.usage.UsageFormat
import dev.wckdboy.autobot.shared.usage.UsagePlan
import dev.wckdboy.autobot.shared.usage.UsageReport
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** A provider the window editor can pick. */
@Immutable
data class UsageProviderOption(val id: String, val name: String, val openCodeGo: Boolean)

@Immutable
data class UsageUiState(
    val report: UsageReport? = null,
    val providers: List<UsageProviderOption> = emptyList(),
    val message: String? = null,
)

/** What the Usage screen can do; [UsageViewModel] in the app, fakes in tests. */
interface UsageActions {
    /** Saves the price of [model] on [provider] from the editor's text fields (blank: unknown). */
    fun savePrice(provider: String, model: String, input: String, output: String, cacheRead: String, cacheWrite: String)
    fun saveWindow(window: SubscriptionWindow)
    fun deleteWindow(id: String)

    /** OpenCode Go's 5 h / week / month windows for [provider], limits left blank. */
    fun addOpenCodeGoWindows(provider: String)
    fun clearHistory()
    fun dismissMessage()
}

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val usage: UsageRepository,
    providers: ProviderRepository,
) : ViewModel(), UsageActions {
    private val message = MutableStateFlow<String?>(null)

    /** Once a minute, so "resets in" stays current while the screen is open. */
    private val ticks = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    val uiState: StateFlow<UsageUiState> = combine(
        flow { usage.ledger.load(); emitAll(usage.ledger.state.filterNotNull()) },
        providers.observeProviders(),
        ticks,
        message,
    ) { state, list, _, m ->
        val names = list.associate { it.id to it.displayName }
        UsageUiState(
            report = usage.ledger.report(state, names),
            providers = list.filter { it.kind != ProviderKind.LOCAL }.map { UsageProviderOption(it.id, it.displayName, it.preset().id == ProviderPresets.OPENCODE_GO) },
            message = m,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageUiState())

    private fun update(block: (UsagePlan) -> UsagePlan) = viewModelScope.launchSafely(::failed) { usage.ledger.savePlan(block(usage.ledger.plan)) }

    override fun savePrice(provider: String, model: String, input: String, output: String, cacheRead: String, cacheWrite: String) = update {
        it.withPrice(
            PriceEntry(provider, model, UsageFormat.parsePrice(input), UsageFormat.parsePrice(output), UsageFormat.parsePrice(cacheRead), UsageFormat.parsePrice(cacheWrite)),
        )
    }.let { }

    override fun saveWindow(window: SubscriptionWindow) = update { it.withWindow(window) }.let { }

    override fun deleteWindow(id: String) = update { it.withoutWindow(id) }.let { }

    override fun addOpenCodeGoWindows(provider: String) = update { plan ->
        UsagePlan.openCodeGoWindows(provider, System.currentTimeMillis()).fold(plan) { p, w -> if (p.windows.any { it.id == w.id }) p else p.withWindow(w) }
    }.let { }

    override fun clearHistory() = viewModelScope.launchSafely(::failed) { usage.ledger.clearHistory() }.let { }

    override fun dismissMessage() {
        message.value = null
    }

    private fun failed(e: Throwable) {
        message.value = userMessage(e)
    }
}
