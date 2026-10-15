package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ApiKeyUpdate
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ModelsApi
import dev.wckdboy.autobot.providers.remote.ProviderFactory
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.RemoteModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Immutable
data class ProviderRow(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val presetName: String,
    val baseUrl: String,
    val model: String,
    val routing: ProviderRouting,
    val hasKey: Boolean,
    /** `42 models · fetched 3 min ago`, or null before the first fetch. */
    val models: String? = null,
    /** Its preset needs an API key. */
    val requiresKey: Boolean = false,
    /** Conversations set to this provider. */
    val chats: Int = 0,
)

/** Editor state. [apiKeyInput] is only held in memory and is redacted from [toString]. */
@Immutable
data class ProviderDraft(
    val id: String? = null,
    val presetId: String = ProviderPresets.CUSTOM,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    val displayName: String = "",
    val baseUrl: String = "",
    val defaultModel: String = "",
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    val clearStoredKey: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val preset: ProviderPreset get() = ProviderPresets.resolve(presetId, kind, baseUrl)
    val baseUrlValid: Boolean get() = baseUrl.trim().toHttpUrlOrNull() != null
    /** Why the base URL cannot work: the kill switch refuses cleartext to anything but loopback. */
    val cleartextProblem: String? get() = cleartextProblem(baseUrl)
    val canSave: Boolean get() = displayName.isNotBlank() && baseUrlValid && cleartextProblem == null && defaultModel.isNotBlank()

    /** The preset needs a key and none is typed or kept. */
    val missingKey: Boolean get() = preset.requiresApiKey && apiKeyInput.isBlank() && (!hasStoredKey || clearStoredKey)

    override fun toString(): String =
        "ProviderDraft(id=$id, preset=$presetId, displayName=$displayName, baseUrl=$baseUrl, model=$defaultModel, " +
            "routing=$routing, apiKeyInput=${if (apiKeyInput.isEmpty()) "" else Secret.REDACTED}, hasStoredKey=$hasStoredKey)"

    companion object {
        /** `http://` to a host other than loopback: blocked on every request. */
        fun cleartextProblem(url: String): String? {
            val parsed = url.trim().toHttpUrlOrNull() ?: return null
            if (parsed.isHttps || Loopback.isLoopback(parsed.host)) return null
            return "Use https — plain http only works for servers on this phone; use Tailscale or a reverse proxy"
        }

        fun fromPreset(preset: ProviderPreset): ProviderDraft = ProviderDraft(
            presetId = preset.id,
            kind = preset.kind,
            displayName = preset.displayName,
            baseUrl = preset.baseUrl,
            defaultModel = preset.defaultModel,
        )
    }
}

/** The model list shown in the editor: fetched (with its age) or the preset's offline list. */
@Immutable
data class EditorModels(
    val models: List<RemoteModel> = emptyList(),
    val fetchedAt: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

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
    /** The "add provider" preset search, or null when the picker is closed. */
    val pickerQuery: String? = null,
    val editor: ProviderDraft? = null,
    val models: EditorModels = EditorModels(),
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
    /** Why the last save failed (the editor stays open). */
    val error: String? = null,
    val now: Long = 0,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repository: ProviderRepository,
    conversations: ConversationRepository,
    private val providerFactory: ProviderFactory,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val picker = MutableStateFlow<String?>(null)
    private val editor = MutableStateFlow<ProviderDraft?>(null)
    private val models = MutableStateFlow(EditorModels())
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var testJob: Job? = null
    private var modelsJob: Job? = null

    private val rows = combine(repository.observeProviders(), repository.observeModelCaches(), conversations.observeConversations()) { list, caches, chats ->
        val now = System.currentTimeMillis()
        val usage = chats.groupingBy { it.providerId }.eachCount()
        list.map { p ->
            ProviderRow(
                id = p.id,
                name = p.displayName,
                kind = p.kind,
                presetName = p.preset().displayName,
                baseUrl = p.baseUrl,
                model = p.defaultModel,
                routing = p.routing,
                hasKey = p.apiKeyRef != null,
                models = caches[p.id]?.let { "${it.models.size} models · ${it.ageLabel(now)}" },
                requiresKey = p.preset().requiresApiKey,
                chats = usage[p.id] ?: 0,
            )
        }
    }

    val uiState: StateFlow<ProvidersUiState> = combine(
        combine(rows, picker, editor) { r, p, e -> Triple(r, p, e) },
        models,
        test,
        saving,
        error,
    ) { (r, p, e), m, t, s, err ->
        ProvidersUiState(providers = r, pickerQuery = p, editor = e, models = m, test = t, saving = s, error = err, now = System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    /** Opens the preset search ("add provider"). */
    fun startAdd() {
        resetTest()
        picker.value = ""
    }

    fun search(query: String) {
        picker.value = query
    }

    fun dismissPicker() {
        picker.value = null
    }

    /** A preset was picked: open the editor with its defaults and its offline model list. */
    fun choosePreset(presetId: String) {
        val preset = ProviderPresets.byId(presetId) ?: return
        if (preset.kind == ProviderKind.LOCAL) return
        picker.value = null
        resetTest()
        editor.value = ProviderDraft.fromPreset(preset)
        models.value = EditorModels(offline(preset))
    }

    fun startEdit(id: String) {
        resetTest()
        viewModelScope.launch {
            val provider = repository.getProvider(id) ?: return@launch
            val preset = provider.preset()
            editor.value = ProviderDraft(
                id = provider.id,
                presetId = preset.id,
                kind = provider.kind,
                displayName = provider.displayName,
                baseUrl = provider.baseUrl,
                defaultModel = provider.defaultModel,
                routing = provider.routing,
                hasStoredKey = repository.hasApiKey(provider),
            )
            models.value = repository.modelCache(provider.id)?.let { EditorModels(it.models, it.fetchedAt) } ?: EditorModels(offline(preset))
        }
    }

    fun updateDraft(transform: (ProviderDraft) -> ProviderDraft) {
        editor.update { it?.let(transform) }
        resetTest()
    }

    fun dismissEditor() {
        resetTest()
        error.value = null
        modelsJob?.cancel()
        editor.value = null
    }

    fun save() {
        val draft = editor.value ?: return
        if (!draft.canSave || saving.value) return
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val existing = draft.id?.let { repository.getProvider(it) }
                val keyUpdate = when {
                    draft.apiKeyInput.isNotBlank() -> ApiKeyUpdate.Set(Secret(draft.apiKeyInput.trim()))
                    draft.clearStoredKey -> ApiKeyUpdate.Clear
                    else -> ApiKeyUpdate.Keep
                }
                val stored = repository.save(draft.toProvider(existing), keyUpdate)
                val fetched = models.value.takeIf { it.fetchedAt != null && it.models.isNotEmpty() }
                when {
                    // A list fetched in the editor (test / refresh) with the same key is kept as is.
                    fetched != null && keyUpdate !is ApiKeyUpdate.Clear -> repository.saveModelCache(stored.id, ModelCache(fetched.fetchedAt!!, fetched.models))
                    // A new key, or a provider without keys: fetch its list in the background.
                    keyUpdate is ApiKeyUpdate.Set || !stored.preset().requiresApiKey -> appScope.launch { refreshStored(stored) }
                }
                editor.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "Could not save: ${e.message ?: e::class.simpleName}"
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

    /** Calls the models endpoint through the policy-enforcing client (blocked while Offline). */
    fun testConnection() {
        val draft = editor.value ?: return
        if (!draft.baseUrlValid) {
            test.value = TestState.Failure("Invalid base URL")
            return
        }
        testJob?.cancel()
        test.value = TestState.Running
        testJob = viewModelScope.launch {
            val result = draftProvider(draft).testConnection()
            test.value = when (result) {
                is ConnectionTestResult.Success -> {
                    if (result.modelCount != null) accept(result.models)
                    TestState.Success(result.summary())
                }
                is ConnectionTestResult.Failure -> TestState.Failure(result.message)
            }
        }
    }

    /** "Refresh" in the editor: fetch the list with the draft's key and base URL. */
    fun refreshModels() {
        val draft = editor.value ?: return
        if (!draft.baseUrlValid || draft.preset.modelsApi == ModelsApi.NONE) return
        modelsJob?.cancel()
        models.update { it.copy(loading = true, error = null) }
        modelsJob = viewModelScope.launch {
            when (val result = draftProvider(draft).listModels()) {
                is ModelListResult.Success -> accept(result.models)
                is ModelListResult.Failure -> models.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    private fun accept(list: List<RemoteModel>) {
        models.value = EditorModels(list, System.currentTimeMillis())
        // Servers whose model is whatever is loaded (llama.cpp, vLLM, LM Studio) start without one.
        editor.update { d -> if (d != null && d.defaultModel.isBlank() && list.isNotEmpty()) d.copy(defaultModel = list.first().id) else d }
    }

    private suspend fun draftProvider(draft: ProviderDraft) = run {
        val existing = draft.id?.let { repository.getProvider(it) }
        val key = when {
            draft.apiKeyInput.isNotBlank() -> Secret(draft.apiKeyInput.trim())
            draft.clearStoredKey -> null
            existing != null -> repository.apiKey(existing)
            else -> null
        }
        providerFactory.create(draft.toProvider(existing), key)
    }

    private suspend fun refreshStored(provider: Provider) {
        val result = providerFactory.create(provider).listModels()
        if (result is ModelListResult.Success && result.models.isNotEmpty() && provider.preset().modelsApi != ModelsApi.NONE) {
            repository.saveModelCache(provider.id, ModelCache(System.currentTimeMillis(), result.models))
        }
    }

    private fun offline(preset: ProviderPreset) = preset.suggestedModels.map { RemoteModel(it) }

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
        presetId = presetId,
    )
}
