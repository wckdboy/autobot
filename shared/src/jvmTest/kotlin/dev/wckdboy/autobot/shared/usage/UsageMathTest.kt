package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calendar math, window boundaries across time zones and DST, and cost. */
class UsageMathTest {

    private fun zone(id: String) = ZoneRules { ZoneId.of(id).rules.getOffset(Instant.ofEpochMilli(it)).totalSeconds * 1000L }

    private fun at(iso: String, zoneId: String): Long = LocalDateTime.parse(iso).atZone(ZoneId.of(zoneId)).toInstant().toEpochMilli()

    private fun utc(iso: String): Long = LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ---- calendar ------------------------------------------------------------------------------

    @Test
    fun civilDatesRoundTripAgainstJavaTime() {
        for (day in -800_000L..800_000L step 997) {
            val ours = CivilDate.ofEpochDay(day)
            val java = java.time.LocalDate.ofEpochDay(day)
            assertEquals(java.year, ours.year)
            assertEquals(java.monthValue, ours.month)
            assertEquals(java.dayOfMonth, ours.day)
            assertEquals(java.dayOfWeek.value, ours.dayOfWeek)
            assertEquals(day, ours.epochDay)
        }
        assertEquals(6, CivilDate(2026, 10, 17).dayOfWeek) // a Saturday
    }

    @Test
    fun localToUtcAroundDaylightSaving() {
        val berlin = zone("Europe/Berlin")
        // 25 Oct 2026: 03:00 CEST → 02:00 CET; 02:30 happens twice, the first is taken.
        assertEquals(at("2026-10-25T02:30", "Europe/Berlin"), Civil.utc(CivilDate(2026, 10, 25), 150 * 60_000L, berlin))
        assertEquals(utc("2026-10-25T00:30"), Civil.utc(CivilDate(2026, 10, 25), 150 * 60_000L, berlin))
        // 29 Mar 2026: 02:30 does not exist; it maps after the change.
        val skipped = Civil.utc(CivilDate(2026, 3, 29), 150 * 60_000L, berlin)
        assertTrue(skipped >= utc("2026-03-29T01:00"))
        assertEquals(CivilDate(2026, 3, 29), Civil.date(skipped, berlin))
    }

    @Test
    fun monthsClampToShortMonths() {
        assertEquals(CivilDate(2026, 2, 28), Civil.plusMonths(CivilDate(2026, 1, 31), 1))
        assertEquals(CivilDate(2028, 2, 29), Civil.plusMonths(CivilDate(2028, 1, 31), 1))
        assertEquals(CivilDate(2026, 3, 31), Civil.plusMonths(CivilDate(2026, 1, 31), 2))
        assertEquals(CivilDate(2025, 12, 31), Civil.plusMonths(CivilDate(2026, 1, 31), -1))
    }

    // ---- windows -------------------------------------------------------------------------------

    @Test
    fun fiveHourWindowsRepeatFromTheAnchor() {
        val h = Civil.HOUR_MS
        assertEquals(10 * h to 15 * h, UsageBoard.fixedSpan(WindowPeriod.FIVE_HOURS, 0, 12 * h, ZoneRules.UTC))
        // Before the anchor the windows run backwards from it.
        assertEquals(0L to 5 * h, UsageBoard.fixedSpan(WindowPeriod.FIVE_HOURS, 10 * h, 3 * h, ZoneRules.UTC))
        assertEquals(15 * h to 20 * h, UsageBoard.fixedSpan(WindowPeriod.FIVE_HOURS, 0, 15 * h, ZoneRules.UTC))
    }

    @Test
    fun weeklyWindowKeepsItsLocalTimeAcrossTheDstChange() {
        val berlin = zone("Europe/Berlin")
        val anchor = at("2026-10-19T09:00", "Europe/Berlin") // a Monday, CEST
        val now = at("2026-10-26T08:00", "Europe/Berlin") // next Monday, CET
        val (start, end) = UsageBoard.fixedSpan(WindowPeriod.WEEK, anchor, now, berlin)
        assertEquals(anchor, start)
        assertEquals(at("2026-10-26T09:00", "Europe/Berlin"), end)
        assertEquals(7 * Civil.DAY_MS + Civil.HOUR_MS, end - start) // the week had 169 hours
        val (next, _) = UsageBoard.fixedSpan(WindowPeriod.WEEK, anchor, end, berlin)
        assertEquals(end, next)
    }

    @Test
    fun dailyWindowAtTheAnchorsTimeOfDayInEachZone() {
        val anchor = utc("2026-10-01T22:00")
        val now = utc("2026-10-17T21:00")
        // In UTC the day starts at 22:00, so 21:00 is still the previous window.
        assertEquals(utc("2026-10-16T22:00") to utc("2026-10-17T22:00"), UsageBoard.fixedSpan(WindowPeriod.DAY, anchor, now, ZoneRules.UTC))
        // In Tokyo the anchor is 07:00 local; 21:00Z is 06:00 the next day there.
        val tokyo = zone("Asia/Tokyo")
        assertEquals(utc("2026-10-16T22:00") to utc("2026-10-17T22:00"), UsageBoard.fixedSpan(WindowPeriod.DAY, anchor, now, tokyo))
        // New York (EDT, -4): anchor 18:00 local; 21:00Z = 17:00 local → window from yesterday 18:00.
        val ny = zone("America/New_York")
        assertEquals(utc("2026-10-16T22:00") to utc("2026-10-17T22:00"), UsageBoard.fixedSpan(WindowPeriod.DAY, anchor, now, ny))
        // Across the US DST end (1 Nov 2026) the local 18:00 moves to 23:00Z.
        val after = utc("2026-11-02T12:00")
        assertEquals(utc("2026-11-01T23:00") to utc("2026-11-02T23:00"), UsageBoard.fixedSpan(WindowPeriod.DAY, anchor, after, ny))
    }

    @Test
    fun monthlyWindowOnThe31stClampsAndComesBack() {
        val anchor = utc("2026-01-31T10:00")
        assertEquals(anchor to utc("2026-02-28T10:00"), UsageBoard.fixedSpan(WindowPeriod.MONTH, anchor, utc("2026-02-15T00:00"), ZoneRules.UTC))
        assertEquals(utc("2026-02-28T10:00") to utc("2026-03-31T10:00"), UsageBoard.fixedSpan(WindowPeriod.MONTH, anchor, utc("2026-03-01T00:00"), ZoneRules.UTC))
        // Before 10:00 on the 31st the March window is still open.
        assertEquals(utc("2026-02-28T10:00") to utc("2026-03-31T10:00"), UsageBoard.fixedSpan(WindowPeriod.MONTH, anchor, utc("2026-03-31T09:59"), ZoneRules.UTC))
        assertEquals(utc("2028-02-29T10:00"), UsageBoard.fixedSpan(WindowPeriod.MONTH, anchor, utc("2028-02-01T00:00"), ZoneRules.UTC).second)
        // Year boundary.
        assertEquals(utc("2026-12-31T10:00") to utc("2027-01-31T10:00"), UsageBoard.fixedSpan(WindowPeriod.MONTH, anchor, utc("2027-01-01T00:00"), ZoneRules.UTC))
    }

    private fun bucket(start: Long, input: Long = 100, output: Long = 50, provider: String = "go", model: String = "glm-5", requests: Int = 1) =
        UsageRecord(start, UsageLedger.FINE_SPAN, provider, model, providerName = "OpenCode Go", presetId = ProviderPresets.OPENCODE_GO, input = input, output = output, requests = requests)

    @Test
    fun rollingWindowCountsTheLastPeriodAndSaysWhenTheOldestRollsOff() {
        val h = Civil.HOUR_MS
        val now = 100 * h
        val records = listOf(bucket(now - 6 * h), bucket(now - 4 * h), bucket(now - h, provider = "other"), bucket(now - h))
        val window = SubscriptionWindow("w", "go", WindowPeriod.FIVE_HOURS, rolling = true, unit = LimitUnit.REQUESTS, limit = 2.0)
        val status = UsageBoard.status(window, records, UsagePlan(), now, ZoneRules.UTC, "OpenCode Go")
        assertEquals(2.0, status.used, 0.0)
        assertTrue(status.over)
        assertEquals(1.0, status.fraction!!, 1e-9)
        assertEquals(now - 4 * h + UsageLedger.FINE_SPAN + 5 * h, status.resetsAt)
        assertEquals("2 requests of 2 requests · frees up in ~1 h", status.line)
    }

    @Test
    fun fixedWindowSaysWhenItResets() {
        val h = Civil.HOUR_MS
        val window = SubscriptionWindow("w", "go", WindowPeriod.FIVE_HOURS, rolling = false, unit = LimitUnit.TOKENS, limit = 1_000.0, anchor = 0)
        val status = UsageBoard.status(window, listOf(bucket(11 * h), bucket(9 * h)), UsagePlan(), 12 * h + 30 * 60_000, ZoneRules.UTC, "Go")
        assertEquals(150.0, status.used, 0.0) // only the bucket since 10:00
        assertEquals(15 * h, status.resetsAt)
        assertFalse(status.over)
        assertEquals("150 tokens of 1.0k tokens · resets in ~3 h", status.line)
    }

    @Test
    fun windowWithoutLimitShowsUsageOnly() {
        val window = SubscriptionWindow("w", "go", WindowPeriod.WEEK, rolling = true)
        val status = UsageBoard.status(window, emptyList(), UsagePlan(), 0, ZoneRules.UTC, "Go")
        assertNull(status.fraction)
        assertEquals("0 tokens · rolling week", status.line)
    }

    // ---- cost ----------------------------------------------------------------------------------

    @Test
    fun anthropicDefaultsBillCacheReadsAndWritesApart() {
        val price = UsageCost.price(UsagePlan(), "p1", ProviderPresets.ANTHROPIC, "claude-opus-5-5")
        assertEquals(PriceSource.DEFAULT, price.source)
        // 10k input of which 8k cache reads and 1k cache writes, 1k output.
        val cost = UsageCost.cost(price, input = 10_000, output = 1_000, cacheRead = 8_000, cacheWrite = 1_000)!!
        assertEquals((1_000 * 4.0 + 8_000 * 0.20 + 1_000 * 5.0 + 1_000 * 20.0) / 1e6, cost, 1e-12)
    }

    @Test
    fun defaultsMatchSnapshotsAndTheLongestId() {
        assertEquals("claude-haiku-4-5", DefaultPrices.find(ProviderPresets.ANTHROPIC, "claude-haiku-4-5-20251001")!!.model)
        assertEquals("claude-opus-5-5", DefaultPrices.find(ProviderPresets.ANTHROPIC, "claude-opus-5-5")!!.model)
        assertEquals("claude-opus-5", DefaultPrices.find(ProviderPresets.ANTHROPIC, "claude-opus-5")!!.model)
        assertNull(DefaultPrices.find(ProviderPresets.ANTHROPIC, "claude-opus-5-turbo"))
        // A subscription host serving the same model has no per-token price.
        assertNull(DefaultPrices.find(ProviderPresets.OPENCODE_GO, "claude-haiku-4-5"))
        assertNull(DefaultPrices.find(null, "claude-haiku-4-5"))
        assertEquals(PriceSource.NONE, UsageCost.price(UsagePlan(), "x", ProviderPresets.OPENAI, "gpt-6-sol").source)
    }

    @Test
    fun userPricesOverrideAndBlankCachePricesCountAtInput() {
        val plan = UsagePlan().withPrice(PriceEntry("go", "glm-5", input = 1.0, output = 3.0))
        val price = UsageCost.price(plan, "go", ProviderPresets.OPENCODE_GO, "GLM-5")
        assertEquals(PriceSource.YOURS, price.source)
        assertEquals((2_000 * 1.0 + 1_000 * 3.0) / 1e6, UsageCost.cost(price, 2_000, 1_000, cacheRead = 500, cacheWrite = 0)!!, 1e-12)
        // Removing every field removes the entry.
        assertTrue(plan.withPrice(PriceEntry("go", "glm-5")).prices.isEmpty())
        // Only input known: still unknown.
        assertNull(UsageCost.cost(UsageCost.price(UsagePlan(listOf(PriceEntry("go", "m", input = 1.0))), "go", null, "m"), 10, 10, 0, 0))
    }

    @Test
    fun onDeviceCostsNothing() {
        val local = UsageProvider("local", "This phone", null, onDevice = true)
        assertEquals(0.0, UsageCost.cost(UsagePlan(), local, "qwen3", TokenUsage(5_000, 400))!!, 0.0)
        assertEquals(0.0, UsageCost.cost(UsagePlan(), bucket(0).copy(onDevice = true, presetId = null))!!, 0.0)
        assertNull(UsageCost.cost(UsagePlan(), bucket(0)))
    }

    @Test
    fun planMergeKeepsWhatThisPhoneHas() {
        val local = UsagePlan(listOf(PriceEntry("a", "m", 1.0, 2.0)), listOf(SubscriptionWindow("w1", "a", WindowPeriod.DAY, limit = 5.0)))
        val incoming = UsagePlan(
            listOf(PriceEntry("a", "M", 9.0, 9.0), PriceEntry("b", "n", 3.0, 4.0)),
            listOf(SubscriptionWindow("w1", "a", WindowPeriod.DAY, limit = 99.0), SubscriptionWindow("w2", "b", WindowPeriod.WEEK)),
        )
        val merged = UsagePlan.merge(local, incoming)
        assertEquals(listOf(1.0, 3.0), merged.prices.map { it.input })
        assertEquals(listOf(5.0, null), merged.windows.map { it.limit })
    }

    // ---- wording -------------------------------------------------------------------------------

    @Test
    fun numbersAndMoney() {
        assertEquals("940", UsageFormat.tokens(940))
        assertEquals("1.2k", UsageFormat.tokens(1_234))
        assertEquals("12k", UsageFormat.tokens(12_345))
        assertEquals("999k", UsageFormat.tokens(999_499))
        assertEquals("1.0M", UsageFormat.tokens(999_500))
        assertEquals("3.4M", UsageFormat.tokens(3_400_000))
        assertEquals("$0", UsageFormat.usd(0.0))
        assertEquals("$0.0042", UsageFormat.usd(0.0042))
        assertEquals("$12.50", UsageFormat.usd(12.5))
        assertEquals("$1234", UsageFormat.usd(1234.4))
        assertEquals("cost unknown", UsageFormat.cost(0.0, partlyUnknown = true))
        assertEquals("$0.50 + unknown", UsageFormat.cost(0.5, partlyUnknown = true))
        assertEquals("0.3", UsageFormat.price(0.30))
        assertEquals("1.25", UsageFormat.price(1.25))
        assertEquals("", UsageFormat.price(null))
        assertEquals(0.3, UsageFormat.parsePrice(" $0,30 ")!!, 0.0)
        assertNull(UsageFormat.parsePrice("free"))
        assertNull(UsageFormat.parsePrice("-1"))
    }
}
