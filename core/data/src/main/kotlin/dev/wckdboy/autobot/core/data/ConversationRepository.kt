package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.Message
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Conversations and messages. Transparently routes incognito conversations (id prefix
 * [IncognitoSession.ID_PREFIX]) to the in-memory [IncognitoSession] and everything else to the
 * encrypted Room database.
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val database: DatabaseHolder,
    private val incognito: IncognitoSession,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val clock: () -> Long = System::currentTimeMillis

    /** Persistent conversations followed by in-memory incognito ones, newest first. */
    fun observeConversations(): Flow<List<Conversation>> =
        flow { emitAll(database.get().conversationDao().observeAll()) }
            .flowOn(io)
            .combine(incognito.conversations) { stored, ephemeral ->
                (ephemeral + stored).sortedByDescending { it.updatedAt }
            }

    fun observeConversation(id: String): Flow<Conversation?> =
        if (incognito.isIncognito(id)) {
            incognito.observeConversation(id)
        } else {
            flow { emitAll(database.get().conversationDao().observe(id)) }.flowOn(io)
        }

    fun observeMessages(conversationId: String): Flow<List<Message>> =
        if (incognito.isIncognito(conversationId)) {
            incognito.observeMessages(conversationId)
        } else {
            flow { emitAll(database.get().messageDao().observeForConversation(conversationId)) }.flowOn(io)
        }

    fun isIncognito(conversationId: String): Boolean = incognito.isIncognito(conversationId)

    suspend fun createConversation(
        incognito: Boolean,
        providerId: String?,
        model: String?,
        title: String = DEFAULT_TITLE,
    ): Conversation {
        val now = clock()
        val id = (if (incognito) IncognitoSession.ID_PREFIX else "") + UUID.randomUUID().toString()
        val conversation = Conversation(id, title, providerId, model, createdAt = now, updatedAt = now)
        if (incognito) {
            this.incognito.create(conversation)
        } else {
            withContext(io) { database.get().conversationDao().upsert(conversation) }
        }
        return conversation
    }

    suspend fun getConversation(id: String): Conversation? =
        if (incognito.isIncognito(id)) {
            incognito.conversation(id)
        } else {
            withContext(io) { database.get().conversationDao().get(id) }
        }

    suspend fun updateConversation(conversation: Conversation) {
        if (incognito.isIncognito(conversation.id)) {
            incognito.updateConversation(conversation)
        } else {
            withContext(io) { database.get().conversationDao().upsert(conversation) }
        }
    }

    suspend fun getMessages(conversationId: String): List<Message> =
        if (incognito.isIncognito(conversationId)) {
            incognito.messages(conversationId)
        } else {
            withContext(io) { database.get().messageDao().getForConversation(conversationId) }
        }

    /** Inserts or replaces [message] and bumps its conversation's `updatedAt`. */
    suspend fun upsertMessage(message: Message) {
        if (incognito.isIncognito(message.conversationId)) {
            incognito.upsertMessage(message)
            incognito.conversation(message.conversationId)?.let {
                incognito.updateConversation(it.copy(updatedAt = clock()))
            }
        } else {
            withContext(io) {
                val db = database.get()
                db.messageDao().upsert(message)
                db.conversationDao().get(message.conversationId)?.let {
                    db.conversationDao().upsert(it.copy(updatedAt = clock()))
                }
            }
        }
    }

    suspend fun deleteConversation(id: String) {
        if (incognito.isIncognito(id)) {
            incognito.delete(id)
        } else {
            withContext(io) { database.get().conversationDao().delete(id) }
        }
    }

    fun newMessageId(): String = UUID.randomUUID().toString()

    companion object {
        const val DEFAULT_TITLE = "New chat"
    }
}
