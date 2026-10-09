package dev.wckdboy.autobot.feature.chat.list

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.feature.chat.toPrivacyStatus
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ConversationItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val updatedAt: Long,
    val incognito: Boolean,
    val running: Boolean = false,
)

@Immutable
data class ChatListUiState(
    val conversations: List<ConversationItem> = emptyList(),
    val incognito: Boolean = false,
    val privacyStatus: PrivacyStatus = PrivacyStatus.OFFLINE,
    val hasProviders: Boolean = true,
    val loading: Boolean = true,
)

sealed interface ChatListEvent {
    data class OpenChat(val conversationId: String) : ChatListEvent
}

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val host: AgentHost,
    settingsRepository: SettingsRepository,
    networkPolicy: NetworkPolicy,
) : ViewModel() {

    /** `null` until the user toggles; falls back to the persisted default. */
    private val incognitoOverride = MutableStateFlow<Boolean?>(null)
    private val events = Channel<ChatListEvent>(Channel.BUFFERED)
    val navigationEvents: Flow<ChatListEvent> = events.receiveAsFlow()

    private val inputs = combine(settingsRepository.settings, incognitoOverride, networkPolicy.mode) { s, o, m -> Triple(s, o, m) }

    val uiState: StateFlow<ChatListUiState> = combine(
        conversations.observeConversations(),
        providers.observeProviders(),
        host.running,
        inputs,
    ) { list, providerList, running, (settings, override, mode) ->
        val providerNames = providerList.associate { it.id to it.displayName }
        ChatListUiState(
            conversations = list.map { c ->
                ConversationItem(
                    id = c.id,
                    title = c.title,
                    subtitle = listOfNotNull(c.providerId?.let(providerNames::get), c.model).joinToString(" · "),
                    updatedAt = c.updatedAt,
                    incognito = conversations.isIncognito(c.id),
                    running = c.id in running,
                )
            },
            incognito = override ?: settings.incognitoByDefault,
            privacyStatus = mode.toPrivacyStatus(),
            hasProviders = providerList.isNotEmpty(),
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatListUiState())

    fun setIncognito(enabled: Boolean) {
        incognitoOverride.value = enabled
    }

    fun newChat() {
        viewModelScope.launch {
            val provider = providers.observeProviders().first().firstOrNull()
            val conversation = conversations.createConversation(
                incognito = uiState.value.incognito,
                providerId = provider?.id,
                model = provider?.defaultModel,
            )
            events.send(ChatListEvent.OpenChat(conversation.id))
        }
    }

    /** Stops the session's agent before its log rows cascade away with the conversation. */
    fun delete(conversationId: String) {
        viewModelScope.launch {
            host.discard(conversationId)
            conversations.deleteConversation(conversationId)
        }
    }
}
