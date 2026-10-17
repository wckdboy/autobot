package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.shared.backup.BackupManifest
import dev.wckdboy.autobot.shared.session.Receipts
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aggregation, compaction, the ledger over a store, the meter and receipt lines. */
class UsageLedgerTest {

    private fun utc(iso: String): Long = LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()

    private class MemoryStore : UsageStore {
        val rows = LinkedHashMap<String, UsageRecord>()
        var plan: UsagePlan? = null
        var reads = 0

        override suspend fun records(): List<UsageRecord> = rows.values.toList().also { reads++ }
        override suspend fun put(records: List<UsageRecord>) = records.forEach { rows[it.id] = it }
        override suspend fun remove(ids: List<String>) = ids.forEach { rows.remove(it) }
        override suspend fun removeAll() = rows.clear()
        override suspend fun plan(): UsagePlan? = plan
        override suspend fun savePlan(plan: UsagePlan) {
            this.plan = plan
        }
    }

    private fun rec(start: Long, provider: String, model: String, input: Long, output: Long, onDevice: Boolean = false, preset: String? = null, span: Long = UsageLedger.FINE_SPAN) =
        UsageRecord(start, span, provider, model, providerName = provider.uppercase(), presetId = preset, onDevice = onDevice, input = input, output = output, requests = 1)

    // ---- board ---------------------------------------------------------------------------------

    @Test
    fun periodsGroupByProviderAndModelAndKeepUnpricedApart() {
        val now = utc("2026-10-17T12:00") // a Saturday
        val records = listOf(
            rec(utc("2026-10-17T09:00"), "anth", "claude-opus-5-5", 1_000_000, 100_000, preset = ProviderPresets.ANTHROPIC),
            rec(utc("2026-10-17T10:00"), "go", "glm-5", 50_000, 5_000, preset = ProviderPresets.OPENCODE_GO),
            rec(utc("2026-10-17T11:00"), "local", "qwen3", 20_000, 2_000, onDevice = true),
            rec(utc("2026-10-12T08:00"), "go", "glm-5", 10_000, 1_000), // Monday: this week, not today
            rec(utc("2026-10-01T08:00"), "go", "kimi-k3", 10_000, 1_000), // this month only
            rec(utc("2026-09-30T08:00"), "go", "kimi-k3", 99_000, 1_000), // last month
        )
        val report = UsageBoard.report(records, UsagePlan(), now, ZoneRules.UTC, names = mapOf("go" to "OpenCode Go"))
        val (day, week, month) = report.periods
        assertEquals(1_000_000L + 100_000 + 55_000 + 22_000, day.sums.tokens)
        assertEquals(22_000L, day.sums.onDeviceTokens)
        assertEquals(55_000L, day.sums.unpricedTokens)
        assertEquals(4.0 + 2.0, day.sums.costUsd, 1e-9)
        assertEquals("$6.00 + unknown", day.sums.costLine)
        assertEquals(listOf("anth", "go", "local"), day.providers.map { it.provider })
        assertEquals("OpenCode Go", day.providers[1].name)
        assertEquals("LOCAL", day.providers[2].name) // the name the row was saved with
        assertEquals(day.sums.tokens + 11_000, week.sums.tokens)
        assertEquals(week.sums.tokens + 11_000, month.sums.tokens)
        assertEquals(listOf("claude-opus-5-5", "glm-5", "qwen3", "kimi-k3"), month.models.map { it.model })
        // Bars: 14 days, today last and the largest; 8 weeks; 6 months.
        val days = report.series[0].bars
        assertEquals(14, days.size)
        assertEquals("17", days.last().label)
        assertEquals(1.0, days.last().fraction, 0.0)
        assertEquals(8, report.series[1].bars.size)
        assertEquals(listOf("may", "jun", "jul", "aug", "sep", "oct"), report.series[2].bars.map { it.label })
        assertEquals(100_000L, report.series[2].bars[4].tokens)
        // Prices: every cloud model seen, defaults marked.
        assertEquals(listOf("claude-opus-5-5", "glm-5", "kimi-k3"), report.prices.map { it.model })
        assertEquals(listOf(PriceSource.DEFAULT, PriceSource.NONE, PriceSource.NONE), report.prices.map { it.source })
    }

    @Test
    fun daysFollowTheLocalZone() {
        val now = utc("2026-10-17T23:50")
        val late = rec(utc("2026-10-17T23:30"), "go", "glm-5", 100, 10)
        // In UTC it is today; in Tokyo (+9) it is already the 18th, so today there holds it too.
        assertEquals(110L, UsageBoard.report(listOf(late), UsagePlan(), now, ZoneRules.UTC).periods[0].sums.tokens)
        val tokyo = ZoneRules { ZoneId.of("Asia/Tokyo").rules.getOffset(Instant.ofEpochMilli(it)).totalSeconds * 1000L }
        val there = UsageBoard.report(listOf(late), UsagePlan(), now, tokyo)
        assertEquals(110L, there.periods[0].sums.tokens)
        assertEquals("18", there.series[0].bars.last().label)
        // In Los Angeles (-7) 23:30Z is the afternoon of the 17th; "now" is still the 17th there.
        val la = ZoneRules { ZoneId.of("America/Los_Angeles").rules.getOffset(Instant.ofEpochMilli(it)).totalSeconds * 1000L }
        assertEquals("17", UsageBoard.report(listOf(late), UsagePlan(), now, la).series[0].bars.last().label)
        // A bucket from the day before in LA is not "today".
        val yesterday = rec(utc("2026-10-17T06:00"), "go", "glm-5", 100, 10) // 23:00 on the 16th in LA
        assertEquals(0L, UsageBoard.report(listOf(yesterday), UsagePlan(), now, la).periods[0].sums.tokens)
    }

    // ---- compaction ----------------------------------------------------------------------------

    @Test
    fun oldBucketsFoldIntoDaysAndVeryOldOnesAreDropped() {
        val now = utc("2026-10-17T12:00")
        val old1 = rec(utc("2026-08-01T09:00"), "go", "glm-5", 100, 10)
        val old2 = rec(utc("2026-08-01T17:05"), "go", "glm-5", 200, 20)
        val oldMidnight = rec(utc("2026-08-01T00:00"), "go", "glm-5", 1, 1)
        val other = rec(utc("2026-08-01T10:00"), "go", "kimi-k3", 5, 5)
        val ancient = rec(utc("2025-01-01T00:00"), "go", "glm-5", 9, 9)
        val recent = rec(utc("2026-10-16T10:00"), "go", "glm-5", 7, 7)
        val (kept, removed, added) = UsageLedger.compact(listOf(old1, old2, oldMidnight, other, ancient, recent), now, ZoneRules.UTC)
        val day = kept.single { it.model == "glm-5" && it.span == Civil.DAY_MS }
        assertEquals(utc("2026-08-01T00:00"), day.start)
        assertEquals(301L, day.input)
        assertEquals(3, day.requests)
        assertTrue(kept.any { it.model == "kimi-k3" && it.span == Civil.DAY_MS })
        assertTrue(recent in kept)
        assertTrue(ancient.id in removed)
        assertTrue(old1.id in removed && old2.id in removed)
        // The midnight bucket shares the day's id: it is replaced, not removed.
        assertTrue(oldMidnight.id !in removed)
        assertEquals(2, added.size)
        // Folding again changes nothing.
        val again = UsageLedger.compact(kept, now, ZoneRules.UTC)
        assertTrue(again.second.isEmpty() && again.third.isEmpty())
    }

    // ---- ledger --------------------------------------------------------------------------------

    @Test
    fun ledgerAddsIntoBucketsPersistsAndImports() = runTest {
        val store = MemoryStore()
        var clock = utc("2026-10-17T12:01")
        val ledger = UsageLedger(store, this, clock = { clock }, zone = { ZoneRules.UTC })
        val start = UsageLedger.bucketStart(clock)
        ledger.add(rec(start, "go", "glm-5", 10, 1))
        ledger.add(rec(start, "go", "glm-5", 5, 2))
        clock += UsageLedger.FINE_SPAN
        ledger.add(rec(UsageLedger.bucketStart(clock), "go", "glm-5", 1, 1))
        advanceUntilIdle()
        assertEquals(1, store.reads)
        assertEquals(2, store.rows.size)
        assertEquals(15L, store.rows.values.first().input)
        assertEquals(2, store.rows.values.first().requests)

        ledger.savePlan(UsagePlan().withPrice(PriceEntry("go", "glm-5", 1.0, 2.0)))
        assertEquals(1, store.plan!!.prices.size)

        val added = ledger.import(listOf(store.rows.values.first().copy(input = 999), rec(utc("2026-10-01T00:00"), "go", "glm-5", 3, 3)), null)
        assertEquals(1, added)
        assertEquals(15L, ledger.state.value!!.records.first().input) // existing bucket kept

        ledger.clearHistory()
        assertTrue(store.rows.isEmpty())
        assertEquals(1, ledger.plan.prices.size) // the plan stays
    }

    @Test
    fun importingABackupAgainAfterItsDaysFoldedDoesNotCountThemTwice() = runTest {
        // A backup with two 5-minute buckets of 1 October, imported, then again 40 days later.
        val backup = listOf(rec(utc("2026-10-01T09:00"), "go", "glm-5", 100, 10), rec(utc("2026-10-01T09:05"), "go", "glm-5", 50, 5))
        val store = MemoryStore()
        var clock = utc("2026-10-02T12:00")
        assertEquals(2, UsageLedger(store, this, clock = { clock }, zone = { ZoneRules.UTC }).import(backup, null))

        clock = utc("2026-11-10T12:00")
        val later = UsageLedger(store, this, clock = { clock }, zone = { ZoneRules.UTC })
        later.load() // the buckets are folded into 1 October now
        assertEquals(0, later.import(backup, null))
        assertEquals(150L, later.state.value!!.records.sumOf { it.input })
        // A new phone imports the same backup as one folded day.
        val fresh = UsageLedger(MemoryStore(), this, clock = { clock }, zone = { ZoneRules.UTC })
        assertEquals(1, fresh.import(backup, null))
        assertEquals(150L, fresh.state.value!!.records.single().input)
    }

    @Test
    fun exportManifestCarriesUsageAndOlderManifestsDecode() {
        val manifest = BackupManifest(usage = listOf(rec(0, "go", "glm-5", 1, 2)), usagePlan = UsagePlan(windows = UsagePlan.openCodeGoWindows("go", 5)))
        val back = BackupManifest.decode(BackupManifest.encode(manifest))
        assertEquals(manifest.usage, back.usage)
        assertEquals(3, back.usagePlan!!.windows.size)
        val old = BackupManifest.decode("""{"settings":{"amoled":"true"}}""".encodeToByteArray())
        assertTrue(old.usage.isEmpty())
        assertNull(old.usagePlan)
    }

    @Test
    fun jsonRoundTripsForTheBridge() {
        val r = rec(1, "go", "glm-5", 1, 2).copy(cacheRead = 3, unreported = 1)
        assertEquals(r, UsageJson.decodeRecord(UsageJson.encode(r)))
        assertNull(UsageJson.decodeRecord("not json"))
        val plan = UsagePlan(listOf(PriceEntry("go", "glm-5", 1.0, null)), UsagePlan.openCodeGoWindows("go", 9))
        assertEquals(plan, UsageJson.decodePlan(UsageJson.encode(plan)))
    }

    // ---- meter ---------------------------------------------------------------------------------

    private class FakeAdapter(private val chunks: List<StreamChunk>) : LlmAdapter {
        override val provider = "anth"
        override val helperModel = "claude-haiku-4-5"
        override fun stream(call: LlmCall): Flow<StreamChunk> = flowOf(*chunks.toTypedArray())
    }

    private fun call(model: String, session: String = "s1") = LlmCall(LlmCallConfig("anth", model), emptyList(), emptyList(), sessionId = session)

    @Test
    fun meterCountsCallsPerTurnAndSkipsIncognitoInTheLedger() = runTest {
        val store = MemoryStore()
        val ledger = UsageLedger(store, this, clock = { utc("2026-10-17T12:00") }, zone = { ZoneRules.UTC })
        val meter = UsageMeter(ledger, clock = { utc("2026-10-17T12:00") })
        val info = UsageProvider("anth", "Anthropic", ProviderPresets.ANTHROPIC, onDevice = false)
        val usage = TokenUsage(inputTokens = 1_000, outputTokens = 100, cacheReadTokens = 800)
        val adapter = meter.wrap(FakeAdapter(listOf(StreamChunk.TextDelta("hi"), StreamChunk.Usage(usage), StreamChunk.Finish("stop"))), info)
        assertEquals("claude-haiku-4-5", adapter.helperModel)

        withContext(TurnTrace("s1", 3)) {
            adapter.stream(call("claude-opus-5-5")).toList()
            adapter.stream(call("claude-opus-5-5")).toList()
            adapter.stream(call("claude-haiku-4-5", session = "s1-sub")).toList() // a subagent of the turn
        }
        withContext(TurnTrace("incognito-1", 1)) { adapter.stream(call("claude-opus-5-5", "incognito-1")).toList() }
        advanceUntilIdle()

        val turn = meter.takeTurn("s1", 3)
        assertEquals(listOf("claude-opus-5-5" to 2, "claude-haiku-4-5" to 1), turn.map { it.model to it.requests })
        assertEquals(2_000, turn[0].usage.inputTokens)
        assertEquals(1_600, turn[0].usage.cacheReadTokens)
        assertTrue(meter.takeTurn("s1", 3).isEmpty()) // read once
        assertEquals(1, meter.takeTurn("incognito-1", 1).size) // the receipt still counts it…
        val rows = store.rows.values.toList() // …but the ledger does not
        assertEquals(3, rows.sumOf { it.requests })
        assertEquals(listOf("claude-opus-5-5", "claude-haiku-4-5"), rows.map { it.model })
    }

    @Test
    fun aCallThatFailedBeforeAnswerIsNotCounted() = runTest {
        val meter = UsageMeter()
        val failing = object : LlmAdapter {
            override val provider = "go"
            override fun stream(call: LlmCall): Flow<StreamChunk> = kotlinx.coroutines.flow.flow { throw IllegalStateException("429") }
        }
        val adapter = meter.wrap(failing, UsageProvider("go", "Go", null, false))
        withContext(TurnTrace("s", 1)) { runCatching { adapter.stream(call("glm-5")).toList() } }
        assertTrue(meter.takeTurn("s", 1).isEmpty())
    }

    // ---- receipts ------------------------------------------------------------------------------

    @Test
    fun receiptsCarryTokensAndCostPerModel() {
        val anth = UsageProvider("anth", "Anthropic", ProviderPresets.ANTHROPIC, false)
        val go = UsageProvider("go", "OpenCode Go", ProviderPresets.OPENCODE_GO, false)
        val local = UsageProvider("local", "This phone", null, true)
        val usage = listOf(
            TurnUsage(anth, "claude-opus-5-5", TokenUsage(inputTokens = 10_000, outputTokens = 1_000, cacheReadTokens = 8_000), 2),
            TurnUsage(go, "glm-5", TokenUsage(inputTokens = 500, outputTokens = 50), 1),
            TurnUsage(local, "qwen3", TokenUsage(inputTokens = 300, outputTokens = 30), 1),
        )
        val models = Receipts.withUsage(listOf(ReceiptModel("anth", "claude-opus-5-5"), ReceiptModel("local", "qwen3")), usage, UsagePlan())
        assertEquals(listOf("claude-opus-5-5", "qwen3", "glm-5"), models.map { it.model }) // a subagent's model is added
        assertEquals((2_000 * 4.0 + 8_000 * 0.2 + 1_000 * 20.0) / 1e6, models[0].costUsd!!, 1e-12)
        assertEquals(0.0, models[1].costUsd!!, 0.0)
        assertNull(models[2].costUsd)

        val receipt = Receipts.summarize(SessionEvent.TurnReceipt(turn = 1, models = models))
        assertEquals("11k in · 1.1k out · 8.0k cached · $0.0296 + unknown", receipt.usage)
        assertEquals(
            listOf("10k in · 1.0k out · 8.0k cached · $0.0296", "300 in · 30 out · $0", "500 in · 50 out · cost unknown"),
            receipt.usageRows.map { it.line },
        )
        // A receipt without counted usage (older logs) has no usage line.
        assertNull(Receipts.summarize(SessionEvent.TurnReceipt(turn = 1, models = listOf(ReceiptModel("a", "m")))).usage)
    }
}
