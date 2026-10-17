package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.shared.memory.Memory
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where usage buckets and the plan live: Android a Room table in the SQLCipher database, iOS the
 * encrypted store (through [UsageStoreBridge]). Wiped with everything else by a panic wipe.
 */
interface UsageStore {
    suspend fun records(): List<UsageRecord>

    /** Inserts or replaces rows by [UsageRecord.id]. */
    suspend fun put(records: List<UsageRecord>)
    suspend fun remove(ids: List<String>)
    suspend fun removeAll()
    suspend fun plan(): UsagePlan?
    suspend fun savePlan(plan: UsagePlan)
}

/** The ledger's contents: every kept bucket, and the price table and windows. */
class UsageState(val records: List<UsageRecord>, val plan: UsagePlan)

@OptIn(ExperimentalTime::class)
private fun systemNow(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Usage on this phone: buckets of tokens per provider and model, the price table and the
 * subscription windows, cached in memory over a [UsageStore]. Writes are serialized; the store
 * is read once, on first use, when old fine buckets are folded into days and buckets older than
 * [RETENTION_DAYS] are dropped.
 */
class UsageLedger(
    private val store: UsageStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::systemNow,
    private val zone: () -> ZoneRules = ::systemZone,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<UsageState?>(null)

    /** Null until [load] read the store. */
    val state: StateFlow<UsageState?> = _state.asStateFlow()

    /** The plan now (empty before [load]). */
    val plan: UsagePlan get() = _state.value?.plan ?: UsagePlan()

    suspend fun load(): UsageState = mutex.withLock { loaded() }

    /** Reads the store once (under [mutex]); compacts and prunes what it read. */
    private suspend fun loaded(): UsageState {
        _state.value?.let { return it }
        val read = runCatching { store.records() }.getOrDefault(emptyList())
        val (kept, removed, added) = compact(read, clock(), zone())
        if (removed.isNotEmpty()) runCatching { store.remove(removed) }
        if (added.isNotEmpty()) runCatching { store.put(added) }
        val state = UsageState(kept, runCatching { store.plan() }.getOrNull() ?: UsagePlan())
        _state.value = state
        return state
    }

    /** Adds [record] to its bucket (in the background; never fails the caller). */
    fun add(record: UsageRecord) {
        scope.launch {
            mutex.withLock {
                val state = loaded()
                val existing = state.records.indexOfFirst { it.id == record.id }
                val merged = if (existing >= 0) state.records[existing] + record else record
                val records = if (existing >= 0) state.records.toMutableList().also { it[existing] = merged } else state.records + merged
                _state.value = UsageState(records, state.plan)
                runCatching { store.put(listOf(merged)) }
            }
        }
    }

    suspend fun savePlan(plan: UsagePlan) = mutex.withLock {
        val state = loaded()
        store.savePlan(plan)
        _state.value = UsageState(state.records, plan)
    }

    /** Forgets every bucket (the price table and windows stay). */
    suspend fun clearHistory() = mutex.withLock {
        val state = loaded()
        store.removeAll()
        _state.value = UsageState(emptyList(), state.plan)
    }

    /**
     * Joins imported buckets and plan: buckets with a new id are added, ones this phone has are
     * kept as they are; the plan merges as in [UsagePlan.merge]. Returns how many were added.
     */
    suspend fun import(records: List<UsageRecord>, plan: UsagePlan?): Int = mutex.withLock {
        val state = loaded()
        val ids = state.records.map { it.id }.toHashSet()
        val fresh = records.filter { it.id !in ids && it.provider.isNotBlank() && it.model.isNotBlank() && it.span > 0 }.distinctBy { it.id }
        if (fresh.isNotEmpty()) store.put(fresh)
        val mergedPlan = plan?.let { UsagePlan.merge(state.plan, it) } ?: state.plan
        if (mergedPlan != state.plan) store.savePlan(mergedPlan)
        _state.value = UsageState(state.records + fresh, mergedPlan)
        fresh.size
    }

    /** The report at the current time; [names] are the current provider names by id. */
    suspend fun report(names: Map<String, String> = emptyMap()): UsageReport {
        val state = load()
        return UsageBoard.report(state.records, state.plan, clock(), zone(), names)
    }

    fun report(state: UsageState, names: Map<String, String> = emptyMap()): UsageReport =
        UsageBoard.report(state.records, state.plan, clock(), zone(), names)

    companion object {
        /** Width of a recent bucket. */
        const val FINE_SPAN = 5 * 60_000L

        /** Fine buckets are kept this long (longer than any window, a month being at most 31 days). */
        const val FINE_DAYS = 35

        /** Buckets older than this are dropped. */
        const val RETENTION_DAYS = 400

        /** The fine bucket [at] falls in. */
        fun bucketStart(at: Long): Long = at.floorDiv(FINE_SPAN) * FINE_SPAN

        /**
         * [records] with fine buckets older than [FINE_DAYS] folded into one bucket per local day,
         * provider and model, and everything older than [RETENTION_DAYS] dropped. Returns the
         * records to keep, the ids to remove from the store and the rows to add to it.
         */
        fun compact(records: List<UsageRecord>, now: Long, zone: ZoneRules): Triple<List<UsageRecord>, List<String>, List<UsageRecord>> {
            val dropBefore = now - RETENTION_DAYS * Civil.DAY_MS
            val foldBefore = Civil.startOfDay(Civil.date(now - FINE_DAYS * Civil.DAY_MS, zone), zone)
            val removed = ArrayList<String>()
            val keep = ArrayList<UsageRecord>()
            val fold = ArrayList<UsageRecord>()
            for (r in records) {
                when {
                    r.start < dropBefore -> removed += r.id
                    r.span < Civil.DAY_MS / 2 && r.start < foldBefore -> fold += r.also { removed += r.id }
                    else -> keep += r
                }
            }
            val days = fold.groupBy { Triple(Civil.date(it.start, zone), it.provider, it.model) }.map { (key, list) ->
                val start = Civil.startOfDay(key.first, zone)
                val end = Civil.startOfDay(CivilDate.ofEpochDay(key.first.epochDay + 1), zone)
                list.drop(1).fold(list.first().copy(start = start, span = end - start)) { acc, r -> acc + r }
            }
            // A day bucket may already exist (a partly folded day): add to it.
            val byId = LinkedHashMap<String, UsageRecord>()
            keep.forEach { byId[it.id] = it }
            val added = days.map { d -> (byId[d.id]?.let { it + d } ?: d).also { byId[d.id] = it } }
            return Triple(byId.values.toList(), removed.filterNot { id -> byId.containsKey(id) }, added)
        }
    }
}

/** A turn's tokens on one model of one provider. */
class TurnUsage(val provider: UsageProvider, val model: String, val usage: TokenUsage, val requests: Int)

/**
 * Counts every model call the agent makes: wraps each provider's [LlmAdapter] ([wrap]) and reads
 * the usage the provider reported. Calls are added to the [ledger] (not for incognito chats) and
 * to their turn's tally for the privacy receipt (a subagent's and a summary's calls count for the
 * turn that ran them).
 */
class UsageMeter(ledger: UsageLedger? = null, private val clock: () -> Long = ::systemNow) {
    @Volatile
    var ledger: UsageLedger? = ledger

    private val lock = Lock()
    private val turns = LinkedHashMap<Pair<String, Int>, LinkedHashMap<Pair<String, String>, TurnUsage>>()

    /** The price table now. */
    val plan: UsagePlan get() = ledger?.plan ?: UsagePlan()

    fun wrap(adapter: LlmAdapter, provider: UsageProvider): LlmAdapter = MeteredAdapter(adapter, provider, this)

    /** One call to [model] finished (or stopped); [usage] null when the provider sent none. */
    fun record(provider: UsageProvider, model: String, usage: TokenUsage?, sessionId: String?, trace: TurnTrace?) {
        val counted = usage ?: TokenUsage()
        trace?.let { t ->
            lock.withLock {
                val tally = turns.getOrPut(t.sessionId to t.turn) { LinkedHashMap() }
                val key = provider.id to model
                tally[key] = tally[key]?.let { TurnUsage(provider, model, it.usage + counted, it.requests + 1) } ?: TurnUsage(provider, model, counted, 1)
                while (turns.size > MAX_TURNS) turns.remove(turns.keys.first())
            }
        }
        val incognito = listOfNotNull(sessionId, trace?.sessionId).any { Memory.isIncognito(it) }
        if (incognito) return
        ledger?.add(
            UsageRecord(
                start = UsageLedger.bucketStart(clock()),
                span = UsageLedger.FINE_SPAN,
                provider = provider.id,
                model = model,
                providerName = provider.name,
                presetId = provider.presetId,
                onDevice = provider.onDevice,
                input = (counted.inputTokens ?: 0).toLong(),
                output = (counted.outputTokens ?: 0).toLong(),
                cacheRead = (counted.cacheReadTokens ?: 0).toLong(),
                cacheWrite = (counted.cacheWriteTokens ?: 0).toLong(),
                reasoning = (counted.reasoningTokens ?: 0).toLong(),
                requests = 1,
                unreported = if (usage?.inputTokens == null && usage?.outputTokens == null) 1 else 0,
            ),
        )
    }

    /** The tally of [turn] of [sessionId], forgotten once read. */
    fun takeTurn(sessionId: String, turn: Int): List<TurnUsage> =
        lock.withLock { turns.remove(sessionId to turn)?.values?.toList().orEmpty() }

    private operator fun TokenUsage.plus(o: TokenUsage) = TokenUsage(
        inputTokens = sum(inputTokens, o.inputTokens),
        outputTokens = sum(outputTokens, o.outputTokens),
        totalTokens = sum(totalTokens, o.totalTokens),
        cacheReadTokens = sum(cacheReadTokens, o.cacheReadTokens),
        reasoningTokens = sum(reasoningTokens, o.reasoningTokens),
        cacheWriteTokens = sum(cacheWriteTokens, o.cacheWriteTokens),
    )

    private fun sum(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

    private companion object {
        const val MAX_TURNS = 64
    }
}

/** An adapter whose calls the [meter] counts; everything else is [inner]'s. */
private class MeteredAdapter(private val inner: LlmAdapter, private val info: UsageProvider, private val meter: UsageMeter) : LlmAdapter by inner {
    override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
        var usage: TokenUsage? = null
        var served: String? = null
        var reasoning: Int? = null
        var started = false
        val trace = TurnTrace.current()
        try {
            inner.stream(call).collect { chunk ->
                started = true
                when (chunk) {
                    is StreamChunk.Usage -> usage = chunk.usage
                    is StreamChunk.ServedBy -> served = chunk.model
                    is StreamChunk.ReasoningStatus -> reasoning = chunk.tokens
                    else -> Unit
                }
                emit(chunk)
            }
        } finally {
            // A call that failed before any answer (refused, limit reached) used nothing.
            if (started || usage != null) {
                val counted = usage?.let { u -> if (u.reasoningTokens == null && reasoning != null) u.copy(reasoningTokens = reasoning) else u }
                meter.record(info, served ?: call.config.model, counted, call.sessionId, trace)
            }
        }
    }
}
