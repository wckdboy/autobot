package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Raw agent session log rows in the encrypted database. Payloads are opaque JSON. */
@Singleton
class SessionEventRepository @Inject constructor(
    private val database: DatabaseHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun load(sessionId: String): List<SessionEventRow> =
        withContext(io) { database.get().sessionEventDao().load(sessionId) }

    suspend fun append(rows: List<SessionEventRow>) {
        if (rows.isEmpty()) return
        withContext(io) { database.get().sessionEventDao().insertAll(rows) }
    }
}
