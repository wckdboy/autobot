package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.Message
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Process-memory-only storage for incognito conversations. Nothing here ever reaches Room, disk
 * or backups; everything disappears with the process or on [clear].
 */
@Singleton
class IncognitoSession @Inject constructor() {

    private data class Thread(val conversation: Conversation, val messages: List<Message>)

    private val threads = MutableStateFlow<Map<String, Thread>>(emptyMap())

    /** Incognito conversations, most recent first. */
    val conversations: StateFlow<List<Conversation>> get() = _conversations.asStateFlow()
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())

    fun isIncognito(conversationId: String): Boolean = conversationId.startsWith(ID_PREFIX)

    fun create(conversation: Conversation) {
        require(isIncognito(conversation.id)) { "Incognito ids must start with $ID_PREFIX" }
        mutate { it + (conversation.id to Thread(conversation, emptyList())) }
    }

    fun observeConversation(id: String): Flow<Conversation?> =
        threads.map { it[id]?.conversation }.distinctUntilChanged()

    fun observeMessages(id: String): Flow<List<Message>> =
        threads.map { it[id]?.messages.orEmpty() }.distinctUntilChanged()

    fun conversation(id: String): Conversation? = threads.value[id]?.conversation

    fun messages(id: String): List<Message> = threads.value[id]?.messages.orEmpty()

    fun updateConversation(conversation: Conversation) = mutate { map ->
        val thread = map[conversation.id] ?: return@mutate map
        map + (conversation.id to thread.copy(conversation = conversation))
    }

    fun upsertMessage(message: Message) = mutate { map ->
        val thread = map[message.conversationId] ?: return@mutate map
        val others = thread.messages.filterNot { it.id == message.id }
        map + (message.conversationId to thread.copy(messages = (others + message).sortedBy { it.createdAt }))
    }

    fun delete(conversationId: String) = mutate { it - conversationId }

    fun clear() = mutate { emptyMap() }

    private fun mutate(block: (Map<String, Thread>) -> Map<String, Thread>) {
        threads.update(block)
        _conversations.value = threads.value.values.map { it.conversation }.sortedByDescending { it.updatedAt }
    }

    companion object {
        const val ID_PREFIX = "incognito-"
    }
}
