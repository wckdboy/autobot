package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ApiKeyUpdate
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ProviderFactory
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Immutable
data class ProviderRow(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val model: String,
    val routing: ProviderRouting,
    val hasKey: Boolean,
)

/** Editor state. [apiKeyInput] is only held in memory and is redacted from [toString]. */
@Immutable
data class ProviderDraft(
    val id: String? = null,
    val kind: ProviderKind = ProviderKind.DEEPSEEK,
    val displayName: String = "",
    val baseUrl: String = "",
    val defaultModel: String = "",
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    val clearStoredKey: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val baseUrlValid: Boolean get() = baseUrl.trim().toHttpUrlOrNull() != null
    val canSave: Boolean get() = displayName.isNotBlank() && baseUrlValid && defaultModel.isNotBlank()

    override fun toString(): String =
        "ProviderDraft(id=$id, kind=$kind, displayName=$displayName, baseUrl=$baseUrl, model=$defaultModel, " +
            "routing=$routing, apiKeyInput=${if (apiKeyInput.isEmpty()) "" else Secret.REDACTED}, hasStoredKey=$hasStoredKey)"

    companion object {
        fun fromPreset(kind: ProviderKind): ProviderDraft {
            val preset = ProviderPresets.forKind(kind)
            return ProviderDraft(
                kind = kind,
                displayName = preset.displayName,
                baseUrl = preset.baseUrl,
                defaultModel = preset.defaultModel,
            )
        }
    }
}

@Immutable
sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

@Immutable
data class ProvidersUiState(
    val providers: List<ProviderRow> = emptyList(),
    val editor: ProviderDraft? = null,
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repository: ProviderRepository,
    private val providerFactory: ProviderFactory,
) : ViewModel() {

    private val editor = MutableStateFlow<ProviderDraft?>(null)
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val saving = MutableStateFlow(false)
    private var testJob: Job? = null

    private val rows = repository.observeProviders().map { list ->
        list.map { p ->
            ProviderRow(
                id = p.id,
                name = p.displayName,
                kind = p.kind,
                baseUrl = p.baseUrl,
                model = p.defaultModel,
                routing = p.routing,
                hasKey = p.apiKeyRef != null,
            )
        }
    }

    val uiState: StateFlow<ProvidersUiState> = combine(rows, editor, test, saving) { r, e, t, s ->
        ProvidersUiState(providers = r, editor = e, test = t, saving = s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    fun startAdd(kind: ProviderKind = ProviderKind.DEEPSEEK) {
        resetTest()
        editor.value = ProviderDraft.fromPreset(kind)
    }

    fun startEdit(id: String) {
        resetTest()
        viewModelScope.launch {
            val provider = repository.getProvider(id) ?: return@launch
            editor.value = ProviderDraft(
                id = provider.id,
                kind = provider.kind,
                displayName = provider.displayName,
                baseUrl = provider.baseUrl,
                defaultModel = provider.defaultModel,
                routing = provider.routing,
                hasStoredKey = repository.hasApiKey(provider),
            )
        }
    }

    /** Switching kind on a new provider re-applies that kind's preset. */
    fun changeKind(kind: ProviderKind) {
        editor.update { current ->
            current ?: return@update null
            if (current.isNew) {
                ProviderDraft.fromPreset(kind).copy(apiKeyInput = current.apiKeyInput, routing = current.routing)
            } else {
                current.copy(kind = kind)
            }
        }
        resetTest()
    }

    fun updateDraft(transform: (ProviderDraft) -> ProviderDraft) {
        editor.update { it?.let(transform) }
        resetTest()
    }

    fun dismissEditor() {
        resetTest()
        editor.value = null
    }

    fun save() {
        val draft = editor.value ?: return
        if (!draft.canSave || saving.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                val existing = draft.id?.let { repository.getProvider(it) }
                val provider = draft.toProvider(existing)
                val keyUpdate = when {
                    draft.apiKeyInput.isNotBlank() -> ApiKeyUpdate.Set(Secret(draft.apiKeyInput.trim()))
                    draft.clearStoredKey -> ApiKeyUpdate.Clear
                    else -> ApiKeyUpdate.Keep
                }
                repository.save(provider, keyUpdate)
                editor.value = null
            } finally {
                saving.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (editor.value?.id == id) editor.value = null
        }
    }

    /** Calls `GET /models` through the policy-enforcing client (blocked while Offline). */
    fun testConnection() {
        val draft = editor.value ?: return
        if (!draft.baseUrlValid) {
            test.value = TestState.Failure("Invalid base URL")
            return
        }
        testJob?.cancel()
        test.value = TestState.Running
        testJob = viewModelScope.launch {
            val existing = draft.id?.let { repository.getProvider(it) }
            val provider = draft.toProvider(existing)
            val key = when {
                draft.apiKeyInput.isNotBlank() -> Secret(draft.apiKeyInput.trim())
                draft.clearStoredKey -> null
                existing != null -> repository.apiKey(existing)
                else -> null
            }
            test.value = when (val result = providerFactory.create(provider, key).testConnection()) {
                is ConnectionTestResult.Success ->
                    TestState.Success(result.modelCount?.let { "Connected · $it models available" } ?: "Connected")
                is ConnectionTestResult.Failure -> TestState.Failure(result.message)
            }
        }
    }

    private fun resetTest() {
        testJob?.cancel()
        test.value = TestState.Idle
    }

    private fun ProviderDraft.toProvider(existing: Provider?) = Provider(
        id = id ?: repository.newProviderId(),
        kind = kind,
        displayName = displayName.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        defaultModel = defaultModel.trim(),
        routing = routing,
        apiKeyRef = existing?.apiKeyRef,
    )
}
