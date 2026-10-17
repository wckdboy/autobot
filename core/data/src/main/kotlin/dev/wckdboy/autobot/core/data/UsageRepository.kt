package dev.wckdboy.autobot.core.data

import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.UsagePlanRow
import dev.wckdboy.autobot.core.data.model.UsageRecordRow
import dev.wckdboy.autobot.shared.usage.UsageJson
import dev.wckdboy.autobot.shared.usage.UsageLedger
import dev.wckdboy.autobot.shared.usage.UsagePlan
import dev.wckdboy.autobot.shared.usage.UsageRecord
import dev.wckdboy.autobot.shared.usage.UsageStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

/**
 * The spend and quota board's storage in the encrypted database: usage buckets and the price
 * table and windows. [ledger] is the app's one ledger over it (the agent's meter writes to it,
 * the Usage screen and the export read it). A panic wipe deletes it with the database.
 */
@Singleton
class UsageRepository @Inject constructor(
    private val database: DatabaseHolder,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope scope: CoroutineScope,
) : UsageStore {
    val ledger = UsageLedger(this, scope)

    override suspend fun records(): List<UsageRecord> = withContext(io) { database.get().usageDao().all().map { it.toRecord() } }

    override suspend fun put(records: List<UsageRecord>) = withContext(io) { database.get().usageDao().upsert(records.map { it.toRow() }) }

    override suspend fun remove(ids: List<String>) = withContext(io) {
        val dao = database.get().usageDao()
        ids.chunked(MAX_ARGS).forEach { dao.delete(it) }
    }

    override suspend fun removeAll() = withContext(io) { database.get().usageDao().deleteAll() }

    override suspend fun plan(): UsagePlan? = withContext(io) { UsageJson.decodePlan(database.get().usageDao().plan(PLAN_ID)?.json) }

    override suspend fun savePlan(plan: UsagePlan) = withContext(io) { database.get().usageDao().savePlan(UsagePlanRow(PLAN_ID, UsageJson.encode(plan))) }

    companion object {
        private const val PLAN_ID = "plan"

        /** SQLite's bound-argument limit, with room to spare. */
        private const val MAX_ARGS = 500

        fun UsageRecordRow.toRecord() = UsageRecord(
            start, span, provider, model, providerName, presetId, onDevice, input, output, cacheRead, cacheWrite, reasoning, requests, unreported,
        )

        fun UsageRecord.toRow() = UsageRecordRow(
            id, start, span, provider, model, providerName, presetId, onDevice, input, output, cacheRead, cacheWrite, reasoning, requests, unreported,
        )
    }
}
