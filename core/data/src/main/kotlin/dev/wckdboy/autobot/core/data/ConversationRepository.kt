package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.ConversationDao
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.ChatFolderRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.ConversationSearchRow
import dev.wckdboy.autobot.core.data.model.ConversationTextRow
import dev.wckdboy.autobot.core.data.model.Message
import dev.wckdboy.autobot.shared.chats.ChatNames
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

    /** The incognito chats (process memory only, no database read). */
    fun incognitoConversations(): List<Conversation> = incognito.conversations.value

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

    // Organization: renaming, pinning, archiving, folders. Incognito chats keep these in memory.

    /** Renames [id] (blank names are ignored). A renamed chat keeps its name: auto-titling only replaces [DEFAULT_TITLE]. */
    suspend fun rename(id: String, title: String) {
        val name = ChatNames.title(title) ?: return
        edit(listOf(id), { it.copy(title = name) }) { dao, _ -> dao.rename(id, name) }
    }

    suspend fun setPinned(ids: Collection<String>, pinned: Boolean) =
        edit(ids, { it.copy(pinned = pinned) }) { dao, stored -> dao.setPinned(stored, pinned) }

    suspend fun setArchived(ids: Collection<String>, archived: Boolean) =
        edit(ids, { it.copy(archived = archived) }) { dao, stored -> dao.setArchived(stored, archived) }

    /** Moves chats into [folderId] (null: out of any folder). */
    suspend fun moveToFolder(ids: Collection<String>, folderId: String?) =
        edit(ids, { it.copy(folderId = folderId) }) { dao, stored -> dao.setFolder(stored, folderId) }

    private suspend fun edit(
        ids: Collection<String>,
        ephemeral: (Conversation) -> Conversation,
        stored: suspend (ConversationDao, List<String>) -> Unit,
    ) {
        val (memory, persistent) = ids.distinct().partition(incognito::isIncognito)
        memory.forEach { id -> incognito.conversation(id)?.let { incognito.updateConversation(ephemeral(it)) } }
        if (persistent.isNotEmpty()) withContext(io) { stored(database.get().conversationDao(), persistent) }
    }

    fun observeFolders(): Flow<List<ChatFolderRow>> = flow { emitAll(database.get().chatFolderDao().observeAll()) }.flowOn(io)

    suspend fun folders(): List<ChatFolderRow> = withContext(io) { database.get().chatFolderDao().all() }

    /** Creates a folder, or returns null when the name is empty or taken ([ChatNames.folderProblem]). */
    suspend fun createFolder(name: String): ChatFolderRow? = withContext(io) {
        val dao = database.get().chatFolderDao()
        if (ChatNames.folderProblem(name, dao.all().map { it.name }) != null) return@withContext null
        ChatFolderRow(UUID.randomUUID().toString(), ChatNames.folderName(name), clock()).also { dao.upsert(it) }
    }

    suspend fun renameFolder(id: String, name: String): Boolean = withContext(io) {
        val dao = database.get().chatFolderDao()
        val folder = dao.get(id) ?: return@withContext false
        if (ChatNames.folderProblem(name, dao.all().filter { it.id != id }.map { it.name }) != null) return@withContext false
        dao.upsert(folder.copy(name = ChatNames.folderName(name)))
        true
    }

    /** Deletes a folder; its chats stay, outside any folder. */
    suspend fun deleteFolder(id: String) {
        withContext(io) {
            val db = database.get()
            db.conversationDao().clearFolder(id)
            db.chatFolderDao().delete(id)
        }
        incognito.conversations.value.filter { it.folderId == id }.forEach { incognito.updateConversation(it.copy(folderId = null)) }
    }

    // Search text (see `:shared` ChatSearch), fed from the session log by the agent runtime.

    suspend fun searchText(id: String): String? = withContext(io) { database.get().conversationDao().searchText(id) }

    suspend fun putSearchText(id: String, text: String) = withContext(io) {
        val db = database.get()
        // A chat deleted meanwhile has no row to attach the text to.
        if (db.conversationDao().get(id) != null) db.conversationDao().upsertSearchText(ConversationTextRow(id, text))
    }

    /** Stored (not incognito) chats. */
    suspend fun storedCount(): Int = withContext(io) { database.get().conversationDao().count() }

    suspend fun unindexedConversations(): List<String> = withContext(io) { database.get().conversationDao().unindexed() }

    suspend fun searchCandidates(likePattern: String): List<ConversationSearchRow> =
        withContext(io) { database.get().conversationDao().searchCandidates(likePattern) }

    /** Every stored and incognito conversation id (for "Delete all chats"). */
    suspend fun allIds(): List<String> =
        incognito.conversations.value.map { it.id } + withContext(io) { database.get().conversationDao().allIds() }

    companion object {
        const val DEFAULT_TITLE = ChatNames.DEFAULT_TITLE
    }
}
