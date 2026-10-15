package dev.wckdboy.autobot.feature.settings.mcp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.ApiKeyUpdate
import dev.wckdboy.autobot.core.data.McpServerRepository
import dev.wckdboy.autobot.core.data.model.McpServer
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.data.toSpec
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.OkHttpTransport
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.feature.settings.providers.TestState
import dev.wckdboy.autobot.shared.mcp.McpEndpoint
import dev.wckdboy.autobot.shared.mcp.McpHub
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

@Immutable
data class McpServerRow(
    val id: String,
    val name: String,
    val url: String,
    val host: String,
    val routing: ProviderRouting,
    val enabled: Boolean,
    val trusted: Boolean,
    val hasToken: Boolean,
)

/** Editor state. [tokenInput] is only held in memory and is redacted from [toString]. */
@Immutable
data class McpServerDraft(
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val tokenInput: String = "",
    val hasStoredToken: Boolean = false,
    val clearStoredToken: Boolean = false,
) {
    val isNew: Boolean get() = id == null
    val urlValid: Boolean get() = McpEndpoint.parse(url) != null
    val canSave: Boolean get() = name.isNotBlank() && urlValid

    override fun toString(): String =
        "McpServerDraft(id=$id, name=$name, url=$url, routing=$routing, enabled=$enabled, trusted=$trusted, " +
            "tokenInput=${if (tokenInput.isEmpty()) "" else Secret.REDACTED}, hasStoredToken=$hasStoredToken)"
}

@Immutable
data class McpServersUiState(
    val servers: List<McpServerRow> = emptyList(),
    val editor: McpServerDraft? = null,
    val test: TestState = TestState.Idle,
    val saving: Boolean = false,
)

@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val repository: McpServerRepository,
    private val clients: HttpClientFactory,
) : ViewModel() {

    private val editor = MutableStateFlow<McpServerDraft?>(null)
    private val test = MutableStateFlow<TestState>(TestState.Idle)
    private val saving = MutableStateFlow(false)
    private var testJob: Job? = null

    private val rows = repository.observeServers().map { list ->
        list.map { s ->
            McpServerRow(
                id = s.id,
                name = s.name,
                url = s.url,
                host = McpEndpoint.hostOf(s.url).orEmpty(),
                routing = s.routing,
                enabled = s.enabled,
                trusted = s.trusted,
                hasToken = s.tokenRef != null,
            )
        }
    }

    val uiState: StateFlow<McpServersUiState> = combine(rows, editor, test, saving) { r, e, t, s ->
        McpServersUiState(servers = r, editor = e, test = t, saving = s)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), McpServersUiState())

    fun startAdd() {
        resetTest()
        editor.value = McpServerDraft()
    }

    fun startEdit(id: String) {
        resetTest()
        viewModelScope.launch {
            val server = repository.get(id) ?: return@launch
            editor.value = McpServerDraft(
                id = server.id,
                name = server.name,
                url = server.url,
                routing = server.routing,
                enabled = server.enabled,
                trusted = server.trusted,
                hasStoredToken = repository.hasToken(server),
            )
        }
    }

    fun updateDraft(transform: (McpServerDraft) -> McpServerDraft) {
        editor.update { it?.let(transform) }
        resetTest()
    }

    fun dismissEditor() {
        resetTest()
        editor.value = null
    }

    /** Enables or disables a server from the list. */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val server = repository.get(id) ?: return@launch
            repository.save(server.copy(enabled = enabled))
        }
    }

    fun save() {
        val draft = editor.value ?: return
        if (!draft.canSave || saving.value) return
        saving.value = true
        viewModelScope.launch {
            try {
                val existing = draft.id?.let { repository.get(it) }
                val tokenUpdate = when {
                    draft.tokenInput.isNotBlank() -> ApiKeyUpdate.Set(Secret(draft.tokenInput.trim()))
                    draft.clearStoredToken -> ApiKeyUpdate.Clear
                    else -> ApiKeyUpdate.Keep
                }
                repository.save(draft.toServer(existing), tokenUpdate)
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

    /** `initialize` + `tools/list` through the policy-enforcing client (blocked while Offline). */
    fun testConnection() {
        val draft = editor.value ?: return
        if (!draft.urlValid) {
            test.value = TestState.Failure("Invalid URL")
            return
        }
        testJob?.cancel()
        test.value = TestState.Running
        testJob = viewModelScope.launch {
            val existing = draft.id?.let { repository.get(it) }
            val token = when {
                draft.tokenInput.isNotBlank() -> draft.tokenInput.trim()
                draft.clearStoredToken -> null
                existing != null -> repository.token(existing)?.reveal()
                else -> null
            }
            val result = McpHub.test(OkHttpTransport(clients), draft.toServer(existing).toSpec(), token)
            test.value = result.fold(
                onSuccess = { count -> TestState.Success("Connected · $count tools available") },
                onFailure = { TestState.Failure(McpHub.describe(it)) },
            )
        }
    }

    private fun resetTest() {
        testJob?.cancel()
        test.value = TestState.Idle
    }

    private fun McpServerDraft.toServer(existing: McpServer?) = McpServer(
        id = id ?: repository.newId(),
        name = name.trim(),
        url = url.trim(),
        routing = routing,
        enabled = enabled,
        trusted = trusted,
        tokenRef = existing?.tokenRef,
        updatedAt = existing?.updatedAt ?: 0,
    )
}
