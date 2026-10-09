package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.ModelRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Raw rows of the `models` table; `:core:models` owns the meaning of the fields. */
@Singleton
class ModelRowRepository @Inject constructor(
    private val database: DatabaseHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observe(): Flow<List<ModelRow>> = flow { emitAll(database.get().modelDao().observeAll()) }.flowOn(io)

    suspend fun get(id: String): ModelRow? = withContext(io) { database.get().modelDao().get(id) }

    suspend fun all(): List<ModelRow> = withContext(io) { database.get().modelDao().all() }

    suspend fun upsert(row: ModelRow) = withContext(io) { database.get().modelDao().upsert(row) }

    suspend fun updateProgress(id: String, downloaded: Long, status: String, error: String?) = withContext(io) {
        database.get().modelDao().updateProgress(id, downloaded, status, error, System.currentTimeMillis())
    }

    suspend fun delete(id: String) = withContext(io) { database.get().modelDao().delete(id) }
}
