package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.MemoryNoteRow
import dev.wckdboy.autobot.shared.memory.Memory
import dev.wckdboy.autobot.shared.memory.MemoryNote
import dev.wckdboy.autobot.shared.memory.MemoryStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Local memory notes in the encrypted database: the [MemoryStore] behind the agent's memory
 * tools and the Memory screen. Screen edits go through [memory] as well, so they get the same
 * normalisation and duplicate handling as the agent's.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val database: DatabaseHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MemoryStore {
    val memory = Memory(this)

    /** Newest first. */
    fun observe(): Flow<List<MemoryNote>> =
        flow { emitAll(database.get().memoryDao().observeAll()) }.map { rows -> rows.map { it.toNote() } }.flowOn(io)

    override suspend fun all(): List<MemoryNote> = withContext(io) { database.get().memoryDao().all().map { it.toNote() } }

    override suspend fun get(id: String): MemoryNote? = withContext(io) { database.get().memoryDao().get(id)?.toNote() }

    override suspend fun upsert(note: MemoryNote) = withContext(io) { database.get().memoryDao().upsert(note.toRow()) }

    override suspend fun delete(id: String): Boolean = withContext(io) { database.get().memoryDao().delete(id) > 0 }

    override suspend fun deleteAll() = withContext(io) { database.get().memoryDao().deleteAll() }

    companion object {
        fun MemoryNoteRow.toNote() = MemoryNote(id, text, tags.split(',').filter { it.isNotEmpty() }, createdAt, updatedAt, sessionId)

        fun MemoryNote.toRow() = MemoryNoteRow(id, text, tags.joinToString(","), createdAt, updatedAt, sessionId)
    }
}
