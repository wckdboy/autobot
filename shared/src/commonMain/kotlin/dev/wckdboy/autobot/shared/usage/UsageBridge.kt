package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.shared.IoDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The host's encrypted storage for usage, for apps that are not Kotlin (iOS: the
 * `EncryptedStore`). Rows are JSON text the host stores as it is; called on a background thread.
 */
interface UsageStoreBridge {
    /** Every stored bucket's JSON. */
    fun all(): List<String>

    /** Stores [json] under [id], replacing a row with that id. */
    fun put(id: String, json: String)
    fun remove(ids: List<String>)
    fun removeAll()

    /** The plan's JSON, or null when none was saved. */
    fun plan(): String?
    fun savePlan(json: String)
}

/** The JSON form of usage rows and plans, in the store, over the bridge and in exports. */
object UsageJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(record: UsageRecord): String = json.encodeToString(UsageRecord.serializer(), record)

    fun decodeRecord(text: String): UsageRecord? = runCatching { json.decodeFromString(UsageRecord.serializer(), text) }.getOrNull()

    fun encode(plan: UsagePlan): String = json.encodeToString(UsagePlan.serializer(), plan)

    fun decodePlan(text: String?): UsagePlan? = text?.let { runCatching { json.decodeFromString(UsagePlan.serializer(), it) }.getOrNull() }

    fun encode(report: UsageReport): String = json.encodeToString(UsageReport.serializer(), report)
}

/** [UsageStore] over a host [UsageStoreBridge]. Rows that no longer decode are skipped. */
class BridgedUsageStore(private val bridge: UsageStoreBridge) : UsageStore {
    override suspend fun records(): List<UsageRecord> = withContext(IoDispatcher) { bridge.all().mapNotNull(UsageJson::decodeRecord) }

    override suspend fun put(records: List<UsageRecord>) = withContext(IoDispatcher) { records.forEach { bridge.put(it.id, UsageJson.encode(it)) } }

    override suspend fun remove(ids: List<String>) = withContext(IoDispatcher) { bridge.remove(ids) }

    override suspend fun removeAll() = withContext(IoDispatcher) { bridge.removeAll() }

    override suspend fun plan(): UsagePlan? = withContext(IoDispatcher) { UsageJson.decodePlan(bridge.plan()) }

    override suspend fun savePlan(plan: UsagePlan) = withContext(IoDispatcher) { bridge.savePlan(UsageJson.encode(plan)) }
}

/** Receives the Usage screen's report as JSON ([UsageReport]) whenever usage or the plan changes. */
fun interface UsageListener {
    fun onReport(json: String)
}
