package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.providers.remote.QuotaErrors
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/**
 * Totals of a set of usage buckets. [tokens] counts everything (input + output), [onDeviceTokens]
 * the part that ran on this phone. [costUsd] adds up what is priced; [unpricedTokens] are cloud
 * tokens of models without a price, so a total never pretends to be complete.
 */
@Serializable
data class UsageSums(
    val tokens: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val requests: Int = 0,
    val onDeviceTokens: Long = 0,
    val costUsd: Double = 0.0,
    val unpricedTokens: Long = 0,
    val unreported: Int = 0,
) {
    val cloudTokens: Long get() = tokens - onDeviceTokens

    /** `$1.24`, `$1.24 + unknown`, `cost unknown`, `$0`. */
    val costLine: String get() = UsageFormat.cost(costUsd, unpricedTokens > 0)

    fun add(record: UsageRecord, cost: Double?): UsageSums = UsageSums(
        tokens = tokens + record.tokens,
        input = input + record.input,
        output = output + record.output,
        cacheRead = cacheRead + record.cacheRead,
        cacheWrite = cacheWrite + record.cacheWrite,
        reasoning = reasoning + record.reasoning,
        requests = requests + record.requests,
        onDeviceTokens = onDeviceTokens + if (record.onDevice) record.tokens else 0,
        costUsd = costUsd + (cost ?: 0.0),
        unpricedTokens = unpricedTokens + if (cost == null) record.tokens else 0,
        unreported = unreported + record.unreported,
    )

    companion object {
        fun of(records: List<UsageRecord>, plan: UsagePlan): UsageSums =
            records.fold(UsageSums()) { sums, r -> sums.add(r, UsageCost.cost(plan, r)) }
    }
}

/** One provider (or one model of a provider) in a period. */
@Serializable
data class UsageGroup(val provider: String, val name: String, val model: String?, val onDevice: Boolean, val sums: UsageSums)

/** Today, this week (from Monday) or this month, with its breakdowns. */
@Serializable
data class UsagePeriod(
    val key: String,
    val label: String,
    val start: Long,
    val sums: UsageSums,
    val providers: List<UsageGroup>,
    val models: List<UsageGroup>,
)

/** One bar of a [UsageSeries]: [fraction] of the series' largest token count. */
@Serializable
data class UsageBar(val label: String, val start: Long, val tokens: Long, val onDeviceTokens: Long, val costUsd: Double, val fraction: Double)

/** Bars per day (14), week (8) or month (6), oldest first. */
@Serializable
data class UsageSeries(val key: String, val label: String, val bars: List<UsageBar>)

/**
 * A subscription window now: [used] in its [SubscriptionWindow.unit], [fraction] of the limit
 * (null without one), [resetsAt] when a fixed window starts over or, for a rolling one, when the
 * oldest usage in it rolls off. [line] says it in words.
 */
@Serializable
data class WindowStatus(
    val id: String,
    val provider: String,
    val providerName: String,
    val period: WindowPeriod,
    val rolling: Boolean,
    val unit: LimitUnit,
    val limit: Double?,
    val used: Double,
    val fraction: Double?,
    val start: Long,
    val resetsAt: Long?,
    val resetsInMs: Long?,
    val line: String,
    val over: Boolean,
    val sums: UsageSums,
)

/** A model's price as the editor shows it. */
@Serializable
data class PriceRow(
    val provider: String,
    val providerName: String,
    val presetId: String?,
    val model: String,
    val input: Double?,
    val output: Double?,
    val cacheRead: Double?,
    val cacheWrite: Double?,
    val source: PriceSource,
)

/** Everything the Usage screen shows, computed on the device. */
@Serializable
data class UsageReport(
    val now: Long,
    val periods: List<UsagePeriod>,
    val series: List<UsageSeries>,
    val windows: List<WindowStatus>,
    val prices: List<PriceRow>,
    val plan: UsagePlan,
    val empty: Boolean,
)

/** Aggregation and window math over usage buckets. Pure: time and zone come in as arguments. */
object UsageBoard {
    private const val DAYS = 14
    private const val WEEKS = 8
    private const val MONTHS = 6

    /**
     * The report at [now] in [zone]. [names] are the current provider names by id (records keep
     * the name they had, used when a provider is gone).
     */
    fun report(records: List<UsageRecord>, plan: UsagePlan, now: Long, zone: ZoneRules, names: Map<String, String> = emptyMap()): UsageReport {
        val recordNames = records.mapNotNull { r -> r.providerName?.let { r.provider to it } }.toMap()
        fun name(provider: String) = names[provider] ?: recordNames[provider] ?: provider
        val today = Civil.date(now, zone)
        val monday = CivilDate.ofEpochDay(today.epochDay - (today.dayOfWeek - 1))
        val first = CivilDate(today.year, today.month, 1)
        val periods = listOf(
            period("day", "today", Civil.startOfDay(today, zone), now, records, plan, ::name),
            period("week", "this week", Civil.startOfDay(monday, zone), now, records, plan, ::name),
            period("month", "this month", Civil.startOfDay(first, zone), now, records, plan, ::name),
        )
        return UsageReport(
            now = now,
            periods = periods,
            series = listOf(days(records, plan, today, zone), weeks(records, plan, monday, zone), months(records, plan, first, zone)),
            windows = plan.windows.map { status(it, records, plan, now, zone, name(it.provider)) },
            prices = prices(records, plan, ::name),
            plan = plan,
            empty = records.isEmpty(),
        )
    }

    private fun period(
        key: String,
        label: String,
        start: Long,
        now: Long,
        records: List<UsageRecord>,
        plan: UsagePlan,
        name: (String) -> String,
    ): UsagePeriod {
        val inside = records.filter { it.start in start..now }
        return UsagePeriod(key, label, start, UsageSums.of(inside, plan), groups(inside, plan, name, byModel = false), groups(inside, plan, name, byModel = true))
    }

    /** Groups by provider (or provider + model), most expensive first, then most tokens. */
    fun groups(records: List<UsageRecord>, plan: UsagePlan, name: (String) -> String, byModel: Boolean): List<UsageGroup> =
        records.groupBy { if (byModel) it.provider to it.model else it.provider to null }
            .map { (key, list) -> UsageGroup(key.first, name(key.first), key.second, list.any { it.onDevice }, UsageSums.of(list, plan)) }
            .sortedWith(compareByDescending<UsageGroup> { it.sums.costUsd }.thenByDescending { it.sums.tokens }.thenBy { it.name }.thenBy { it.model })

    private fun series(key: String, label: String, ranges: List<Triple<String, Long, Long>>, records: List<UsageRecord>, plan: UsagePlan): UsageSeries {
        val sums = ranges.map { (_, from, to) -> UsageSums.of(records.filter { it.start >= from && it.start < to }, plan) }
        val max = sums.maxOfOrNull { it.tokens }?.takeIf { it > 0 } ?: 1
        return UsageSeries(
            key, label,
            ranges.zip(sums) { (l, from, _), s -> UsageBar(l, from, s.tokens, s.onDeviceTokens, s.costUsd, s.tokens.toDouble() / max) },
        )
    }

    private fun days(records: List<UsageRecord>, plan: UsagePlan, today: CivilDate, zone: ZoneRules): UsageSeries {
        val ranges = (DAYS - 1 downTo 0).map { back ->
            val d = CivilDate.ofEpochDay(today.epochDay - back)
            Triple(d.day.toString(), Civil.startOfDay(d, zone), Civil.startOfDay(CivilDate.ofEpochDay(d.epochDay + 1), zone))
        }
        return series("day", "days", ranges, records, plan)
    }

    private fun weeks(records: List<UsageRecord>, plan: UsagePlan, monday: CivilDate, zone: ZoneRules): UsageSeries {
        val ranges = (WEEKS - 1 downTo 0).map { back ->
            val d = CivilDate.ofEpochDay(monday.epochDay - 7L * back)
            Triple("${d.day}/${d.month}", Civil.startOfDay(d, zone), Civil.startOfDay(CivilDate.ofEpochDay(d.epochDay + 7), zone))
        }
        return series("week", "weeks", ranges, records, plan)
    }

    private fun months(records: List<UsageRecord>, plan: UsagePlan, first: CivilDate, zone: ZoneRules): UsageSeries {
        val ranges = (MONTHS - 1 downTo 0).map { back ->
            val d = Civil.plusMonths(first, -back, 1)
            Triple(MONTH_NAMES[d.month - 1], Civil.startOfDay(d, zone), Civil.startOfDay(Civil.plusMonths(d, 1, 1), zone))
        }
        return series("month", "months", ranges, records, plan)
    }

    private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** A rolling window's length (a month is 30 days). */
    fun duration(period: WindowPeriod): Long = when (period) {
        WindowPeriod.FIVE_HOURS -> 5 * Civil.HOUR_MS
        WindowPeriod.DAY -> Civil.DAY_MS
        WindowPeriod.WEEK -> 7 * Civil.DAY_MS
        WindowPeriod.MONTH -> 30 * Civil.DAY_MS
    }

    /**
     * The fixed window around [now]: [start, end). It repeats from [anchor] every [period] in
     * [zone]: 5 hours exactly; days and weeks at the anchor's local time of day (and weekday);
     * months on the anchor's day of the month, clamped to short months (31st → 30th, 28th/29th).
     */
    fun fixedSpan(period: WindowPeriod, anchor: Long, now: Long, zone: ZoneRules): Pair<Long, Long> {
        val tod = Civil.timeOfDay(anchor, zone)
        return when (period) {
            WindowPeriod.FIVE_HOURS -> {
                val len = duration(period)
                val start = anchor + (now - anchor).floorDiv(len) * len
                start to start + len
            }
            WindowPeriod.DAY, WindowPeriod.WEEK -> {
                val step = if (period == WindowPeriod.DAY) 1L else 7L
                val anchorDay = Civil.date(anchor, zone).epochDay
                val nowDay = Civil.date(now, zone).epochDay
                var startDay = anchorDay + (nowDay - anchorDay).floorDiv(step) * step
                if (Civil.utc(CivilDate.ofEpochDay(startDay), tod, zone) > now) startDay -= step
                Civil.utc(CivilDate.ofEpochDay(startDay), tod, zone) to Civil.utc(CivilDate.ofEpochDay(startDay + step), tod, zone)
            }
            WindowPeriod.MONTH -> {
                val a = Civil.date(anchor, zone)
                val n = Civil.date(now, zone)
                var months = (n.year * 12 + n.month) - (a.year * 12 + a.month)
                if (Civil.utc(Civil.plusMonths(a, months, a.day), tod, zone) > now) months -= 1
                Civil.utc(Civil.plusMonths(a, months, a.day), tod, zone) to Civil.utc(Civil.plusMonths(a, months + 1, a.day), tod, zone)
            }
        }
    }

    /** [window] at [now]: what it used, of what limit, and when it resets. */
    fun status(window: SubscriptionWindow, records: List<UsageRecord>, plan: UsagePlan, now: Long, zone: ZoneRules, providerName: String): WindowStatus {
        val mine = records.filter { it.provider == window.provider }
        val anchor = window.anchor
        val fixed = !window.rolling && anchor != null
        val start: Long
        val inside: List<UsageRecord>
        val resetsAt: Long?
        if (fixed) {
            val (from, to) = fixedSpan(window.period, anchor!!, now, zone)
            start = from
            inside = mine.filter { it.start >= from && it.start <= now }
            resetsAt = to
        } else {
            start = now - duration(window.period)
            // A bucket counts while any of it is inside the window; it has rolled off once its end has.
            inside = mine.filter { it.start + it.span > start && it.start <= now }
            resetsAt = inside.minOfOrNull { it.start + it.span }?.let { it + duration(window.period) }
        }
        val sums = UsageSums.of(inside, plan)
        val used = when (window.unit) {
            LimitUnit.TOKENS -> sums.tokens.toDouble()
            LimitUnit.REQUESTS -> sums.requests.toDouble()
            LimitUnit.USD -> sums.costUsd
        }
        val limit = window.limit?.takeIf { it > 0 }
        val over = limit != null && used >= limit
        val resetsIn = resetsAt?.let { (it - now).coerceAtLeast(0) }
        val line = buildString {
            append(UsageFormat.amount(used, window.unit))
            limit?.let { append(" of ").append(UsageFormat.amount(it, window.unit)) }
            when {
                fixed -> append(" · resets ").append(QuotaErrors.inWords(resetsIn ?: 0))
                resetsIn != null -> append(if (over) " · frees up " else " · oldest use rolls off ").append(QuotaErrors.inWords(resetsIn))
                else -> append(" · rolling ").append(window.period.short)
            }
        }
        return WindowStatus(
            window.id, window.provider, providerName, window.period, !fixed, window.unit, limit, used,
            limit?.let { used / it }, start, resetsAt, resetsIn, line, over, sums,
        )
    }

    /**
     * Price rows for every cloud model seen in [records] or priced by the user, by provider then
     * model; on-device models are left out (they cost nothing).
     */
    fun prices(records: List<UsageRecord>, plan: UsagePlan, name: (String) -> String): List<PriceRow> {
        val seen = LinkedHashMap<Pair<String, String>, String?>()
        records.filterNot { it.onDevice }.forEach { seen.getOrPut(it.provider to it.model) { it.presetId } }
        plan.prices.forEach { p -> if (seen.keys.none { it.first == p.provider && it.second.equals(p.model, ignoreCase = true) }) seen[p.provider to p.model] = null }
        val presets = records.mapNotNull { r -> r.presetId?.let { r.provider to it } }.toMap()
        return seen.map { (key, preset) ->
            val presetId = preset ?: presets[key.first]
            val price = UsageCost.price(plan, key.first, presetId, key.second)
            PriceRow(key.first, name(key.first), presetId, key.second, price.input, price.output, price.cacheRead, price.cacheWrite, price.source)
        }.sortedWith(compareBy<PriceRow> { it.providerName.lowercase() }.thenBy { it.model.lowercase() })
    }
}

/** Number and money wording shared by both apps. */
object UsageFormat {

    /** `940`, `1.2k`, `12k`, `340k`, `1.2M`, `12M`, `1.2B`. */
    fun tokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 999_500 -> scaled(n, 1_000, "k")
        n < 999_500_000 -> scaled(n, 1_000_000, "M")
        else -> scaled(n, 1_000_000_000, "B")
    }

    private fun scaled(n: Long, unit: Long, suffix: String): String {
        val tenths = (n * 10.0 / unit).roundToLong()
        return if (tenths < 100) "${tenths / 10}.${tenths % 10}$suffix" else "${(n.toDouble() / unit).roundToLong()}$suffix"
    }

    /** `$0`, `$0.0042`, `$0.0296`, `$1.24`, `$1234`. */
    fun usd(d: Double): String = when {
        d <= 0.0 -> "$0"
        d < 0.1 -> "$" + fixed(d, 4)
        d < 1_000 -> "$" + fixed(d, 2)
        else -> "$" + d.roundToLong()
    }

    /** [usd] with `+ unknown` when some of it has no price; `cost unknown` when none has. */
    fun cost(usd: Double, partlyUnknown: Boolean): String = when {
        partlyUnknown && usd <= 0.0 -> "cost unknown"
        partlyUnknown -> usd(usd) + " + unknown"
        else -> usd(usd)
    }

    /** A window amount: `1.2M tokens`, `420 requests`, `$3.10`. */
    fun amount(value: Double, unit: LimitUnit): String = when (unit) {
        LimitUnit.TOKENS -> tokens(value.roundToLong()) + " tokens"
        LimitUnit.REQUESTS -> value.roundToLong().toString() + " requests"
        LimitUnit.USD -> usd(value)
    }

    /** A price per 1M tokens for the editor: `3`, `0.3`, `1.25`; blank when unknown. */
    fun price(d: Double?): String {
        d ?: return ""
        val text = fixed(d, 4).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" }
    }

    /** A price typed by the user: `3`, `0,30`, `$1.25`; blank or invalid → null. */
    fun parsePrice(text: String): Double? =
        text.trim().removePrefix("$").replace(',', '.').trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

    /** [d] with exactly [decimals] decimals, rounded half up. */
    fun fixed(d: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val n = (d * scale).roundToLong()
        if (decimals == 0) return n.toString()
        val whole = n / scale
        val frac = (n % scale).toString().padStart(decimals, '0')
        return "$whole.$frac"
    }
}
