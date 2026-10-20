package dev.wckdboy.autobot.feature.settings.providers

import dev.wckdboy.autobot.providers.remote.NodeConnections
import dev.wckdboy.autobot.providers.remote.NodePairingLinks
import dev.wckdboy.autobot.shared.invincible.JvmNodeCrypto
import dev.wckdboy.autobot.shared.invincible.NodeBlockedException
import dev.wckdboy.autobot.shared.invincible.PairedNode
import dev.wckdboy.autobot.shared.invincible.PairingCode
import dev.wckdboy.autobot.shared.invincible.fingerprint
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ApiKeyUpdate
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.providers.remote.ApiKeys
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
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
import kotlinx.coroutines.flow.map
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.network.NetworkMode
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
    /** Why its model list could not be fetched after the last save (a rejected key, the kill switch). */
    val problem: String? = null,
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
    /** Subagents and summaries; empty = the chat's own model. */
    val helperModel: String = "",
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val apiKeyInput: String = "",
    val hasStoredKey: Boolean = false,
    val clearStoredKey: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val preset: ProviderPreset get() = ProviderPresets.resolve(presetId, kind, baseUrl)
    val baseUrlValid: Boolean get() = baseUrl.trim().toHttpUrlOrNull() != null
    /** A paired Invincible node: its address and key come from pairing, not from the editor. */
    val isNode: Boolean get() = kind == ProviderKind.INVINCIBLE_NODE
    /** Why the base URL cannot work: the kill switch refuses cleartext to anything but loopback (a node's URL is only a name). */
    val cleartextProblem: String? get() = if (isNode) null else cleartextProblem(baseUrl)
    val canSave: Boolean get() = saveProblem == null

    /** Why Save is disabled, said next to it (null: it can save). */
    val saveProblem: String? get() = when {
        displayName.isBlank() -> "Give the provider a name"
        baseUrl.isBlank() -> "Enter the base URL"
        !baseUrlValid -> "The base URL is not a valid URL"
        cleartextProblem != null -> cleartextProblem
        defaultModel.isBlank() -> "Pick or type a model"
        else -> null
    }

    /** A typed key that cannot be sent (a space, a stray character), shown as it is typed. */
    val keyProblem: String? get() = apiKeyInput.takeIf { it.isNotBlank() }?.let { ApiKeys.problem(ApiKeys.clean(it)) }

    /** The preset needs a key and none is typed or kept. */
    val missingKey: Boolean get() = preset.requiresApiKey && apiKeyInput.isBlank() && (!hasStoredKey || clearStoredKey)

    override fun toString(): String =
        "ProviderDraft(id=$id, preset=$presetId, displayName=$displayName, baseUrl=$baseUrl, model=$defaultModel, helper=$helperModel, " +
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
            helperModel = preset.helperModel.orEmpty(),
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

/** The "pair with my node" sheet: the pasted (or linked) code and this phone's name on the node. */
@Immutable
data class PairingDraft(
    val code: String = "",
    val deviceName: String = "",
    val running: Boolean = false,
    val error: String? = null,
) {
    val parsed: PairingCode? get() = PairingCode.parse(code)
    /** The node's key as `invincible pair` prints it, to compare before pairing. */
    val fingerprint: String? get() = parsed?.let { fingerprint(it.nodeKey) }
    val problem: String? get() = when {
        code.isBlank() -> "Scan the node's QR code with the camera app, or paste its code"
        parsed == null -> "Not an Invincible pairing code"
        deviceName.isBlank() -> "Name this phone"
        else -> null
    }

    override fun toString(): String = "PairingDraft(code=${if (code.isEmpty()) "" else Secret.REDACTED}, deviceName=$deviceName, running=$running)"
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
    val pairing: PairingDraft? = null,
    /** Offline mode is on: pairing and a cloud provider can't reach the network (the sheets say so, with a way out). */
    val offline: Boolean = false,
)

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repository: ProviderRepository,
    conversations: ConversationRepository,
    private val providerFactory: ProviderFactory,
    @ApplicationScope private val appScope: CoroutineScope,
    private val nodes: NodeConnections,
    private val pairingLinks: NodePairingLinks,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val pairing = MutableStateFlow<PairingDraft?>(null)


    private val picker = MutableStateFlow<String?>(null)
    private val editor = MutableStateFlow<ProviderDraft?>(null)
    private val models = MutableStateFlow(EditorModels())
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val saving = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val problems = MutableStateFlow<Map<String, String>>(emptyMap())
    private var testJob: Job? = null
    private var modelsJob: Job? = null

    private val rows = combine(repository.observeProviders(), repository.observeModelCaches(), conversations.observeConversations(), problems) { list, caches, chats, issues ->
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
                models = caches[p.id]?.let { "${counted(it.models.size, "model")} · ${it.ageLabel(now)}" },
                requiresKey = p.preset().requiresApiKey,
                chats = usage[p.id] ?: 0,
                problem = issues[p.id],
            )
        }
    }

    val uiState: StateFlow<ProvidersUiState> = combine(
        combine(rows, picker, editor) { r, p, e -> Triple(r, p, e) },
        combine(models, test) { m, t -> m to t },
        combine(saving, settings.settings.map { it.networkMode == NetworkMode.Offline }) { s, off -> s to off },
        error,
        pairing,
    ) { (r, p, e), (m, t), (s, off), err, pair ->
        ProvidersUiState(
            providers = r, pickerQuery = p, editor = e, models = m, test = t, saving = s, error = err,
            now = System.currentTimeMillis(), pairing = pair, offline = off,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProvidersUiState())

    init {
        // A pairing link (the camera read a node's QR code) opens the sheet, filled in. Below
        // every property it touches: with a link already waiting this runs during construction.
        viewModelScope.launch {
            pairingLinks.code.collect { if (it != null) pairingLinks.take()?.let(::openPairing) }
        }
    }

    /** Opens the preset search ("add provider"). */
    /** "Allow network" from a sheet's offline line: direct connections, as Privacy Center's Direct. */
    fun allowNetwork() {
        viewModelScope.launch { runCatching { settings.setNetworkMode(NetworkMode.Direct) } }
    }

    fun startAdd() {

        resetTest()
        picker.value = ""
    }

    /** The picker's search text (pushed once typing pauses; ignored once the picker closed). */
    fun search(query: String) {
        picker.update { if (it == null) null else query }
    }

    fun dismissPicker() {
        picker.value = null
    }

    /** A preset was picked: open the editor with its defaults and its offline model list. */
    fun choosePreset(presetId: String) {
        val preset = ProviderPresets.byId(presetId) ?: return
        if (preset.kind == ProviderKind.LOCAL) return
        if (preset.kind == ProviderKind.INVINCIBLE_NODE) {
            picker.value = null
            openPairing("")
            return
        }
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
                helperModel = provider.helperModel.orEmpty(),
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
        val key = ApiKeys.clean(draft.apiKeyInput)
        ApiKeys.problem(key)?.let {
            error.value = it
            return
        }
        saving.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val existing = draft.id?.let { repository.getProvider(it) }
                val keyUpdate = when {
                    key.isNotEmpty() -> ApiKeyUpdate.Set(Secret(key))
                    draft.clearStoredKey -> ApiKeyUpdate.Clear
                    else -> ApiKeyUpdate.Keep
                }
                val stored = repository.save(draft.toProvider(existing), keyUpdate)
                val fetched = models.value.takeIf { it.fetchedAt != null && it.models.isNotEmpty() }
                problems.update { it - stored.id }
                when {
                    // A list fetched in the editor (test / refresh) with the same key is kept as is.
                    fetched != null && keyUpdate !is ApiKeyUpdate.Clear -> repository.saveModelCache(stored.id, ModelCache(fetched.fetchedAt!!, fetched.models))
                    // A new key, or a provider without keys: fetch its list in the background.
                    keyUpdate is ApiKeyUpdate.Set || !stored.preset().requiresApiKey -> appScope.launch {
                        try {
                            refreshStored(stored)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problems.update { it + (stored.id to "Couldn't list models: ${userMessage(e)}") }
                        }
                    }
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
        viewModelScope.launchSafely({ error.value = "Could not delete: ${userMessage(it)}" }) {
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
            val result = try {
                draftProvider(draft).testConnection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                test.value = TestState.Failure(userMessage(e))
                return@launch
            }
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
            val result = try {
                draftProvider(draft).listModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                models.update { it.copy(loading = false, error = userMessage(e)) }
                return@launch
            }
            when (result) {
                is ModelListResult.Success -> accept(result.models)
                is ModelListResult.Failure -> models.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    private fun accept(list: List<RemoteModel>) {
        models.value = EditorModels(list, System.currentTimeMillis())
        // Servers whose model is whatever is loaded (llama.cpp, vLLM, LM Studio) start without one.
        // Never one whose provider trains on prompts: that takes the user's own choice.
        editor.update { d ->
            val first = list.firstOrNull { d != null && ModelDataPolicies.autoPickable(d.presetId, it.id) }
            if (d != null && d.defaultModel.isBlank() && first != null) d.copy(defaultModel = first.id) else d
        }
    }

    private suspend fun draftProvider(draft: ProviderDraft) = run {
        val existing = draft.id?.let { repository.getProvider(it) }
        val key = when {
            draft.apiKeyInput.isNotBlank() -> Secret(ApiKeys.clean(draft.apiKeyInput))
            draft.clearStoredKey -> null
            existing != null -> repository.apiKey(existing)
            else -> null
        }
        providerFactory.create(draft.toProvider(existing), key)
    }

    /** Fetches a saved provider's models; a failure (most often a rejected key) shows on its row. */
    private suspend fun refreshStored(provider: Provider) {
        val result = providerFactory.create(provider).listModels()
        problems.update { if (result is ModelListResult.Failure) it + (provider.id to "Couldn't list models: ${result.message}") else it - provider.id }
        if (result is ModelListResult.Success && result.models.isNotEmpty() && provider.preset().modelsApi != ModelsApi.NONE) {
            repository.saveModelCache(provider.id, ModelCache(System.currentTimeMillis(), result.models))
        }
    }

    private fun offline(preset: ProviderPreset) = preset.suggestedModels.map { RemoteModel(it) }

    /** Opens the pairing sheet with [code] (empty: to paste one). */
    fun openPairing(code: String) {
        picker.value = null
        editor.value = null
        pairing.value = PairingDraft(code = code, deviceName = android.os.Build.MODEL.orEmpty().ifBlank { "Autobot" })
    }

    fun updatePairing(transform: (PairingDraft) -> PairingDraft) {
        pairing.update { it?.let(transform)?.copy(error = null) }
    }

    fun dismissPairing() {
        pairing.value = null
    }

    /**
     * Pairs with the node in the sheet's code: a Noise handshake over the network policy (the
     * kill switch and routes apply; offline refuses), then the node is saved as a provider with
     * its pairing stored encrypted where an API key would be.
     */
    fun pair() {
        val draft = pairing.value ?: return
        val code = draft.parsed
        if (draft.problem != null || code == null || draft.running) return
        pairing.value = draft.copy(running = true, error = null)
        viewModelScope.launch {
            try {
                val paired = PairedNode.pair(JvmNodeCrypto, code, draft.deviceName.trim(), nodes.connector(ProviderRouting.INHERIT, "pairing"))
                val provider = Provider(
                    id = repository.newProviderId(),
                    kind = ProviderKind.INVINCIBLE_NODE,
                    displayName = paired.name,
                    baseUrl = ProviderPresets.NODE_BASE_URL,
                    defaultModel = "auto",
                    routing = ProviderRouting.INHERIT,
                    apiKeyRef = null,
                    presetId = ProviderPresets.INVINCIBLE,
                    helperModel = null,
                )
                val stored = repository.save(provider, ApiKeyUpdate.Set(Secret(paired.toSecret())))
                pairing.value = null
                appScope.launch {
                    try {
                        refreshStored(stored)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problems.update { it + (stored.id to "Couldn't list models: ${userMessage(e)}") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NodeBlockedException) {
                pairing.update { it?.copy(running = false, error = "Blocked by the network settings: ${e.message}") }
            } catch (e: Exception) {
                pairing.update { it?.copy(running = false, error = "Pairing failed: ${userMessage(e)} (the code works once, for 10 minutes)") }
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
        presetId = presetId,
        helperModel = helperModel.trim().takeIf { it.isNotEmpty() && it != defaultModel.trim() },
    )
}
