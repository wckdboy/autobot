package dev.wckdboy.autobot.shared.usage

import kotlinx.serialization.Serializable

/**
 * Tokens one provider + model used in one time bucket, counted on the device from the providers'
 * own `usage` reports. Buckets are [UsageLedger.FINE_SPAN] (5 min) wide while they are recent and
 * folded into one bucket per local day after [UsageLedger.FINE_DAYS]; nothing else is kept (no
 * prompts, no chat ids). [input] is the whole input, [cacheRead] and [cacheWrite] included;
 * [output] includes [reasoning]. [requests] counts model calls. The same shape is exported in
 * `.autobot` bundles.
 *
 * @property provider the local provider id; [providerName] and [presetId] as they were then.
 * @property onDevice ran on this phone (costs nothing).
 * @property unreported calls whose provider sent no usage (tokens unknown, requests counted).
 */
@Serializable
data class UsageRecord(
    val start: Long,
    val span: Long,
    val provider: String,
    val model: String,
    val providerName: String? = null,
    val presetId: String? = null,
    val onDevice: Boolean = false,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val reasoning: Long = 0,
    val requests: Int = 0,
    val unreported: Int = 0,
) {
    /** Storage key: one row per bucket, provider and model. */
    val id: String get() = "$start|$provider|$model"

    val tokens: Long get() = input + output

    /** [other] (same bucket, provider and model) added to this one. */
    operator fun plus(other: UsageRecord): UsageRecord = copy(
        providerName = other.providerName ?: providerName,
        presetId = other.presetId ?: presetId,
        onDevice = onDevice || other.onDevice,
        input = input + other.input,
        output = output + other.output,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
        reasoning = reasoning + other.reasoning,
        requests = requests + other.requests,
        unreported = unreported + other.unreported,
    )
}

/**
 * The user's price for one model of one provider, in US dollars per 1M tokens. Blank (null)
 * prices are unknown: a model without [input] and [output] has no cost, never a guessed one.
 * Cache prices left blank count at the [input] price (the most it can be).
 *
 * @property provider the local provider id.
 */
@Serializable
data class PriceEntry(
    val provider: String,
    val model: String,
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
) {
    val known: Boolean get() = input != null && output != null
}

/** How long a subscription window lasts. */
@Serializable
enum class WindowPeriod(val label: String, val short: String) {
    FIVE_HOURS("5-hour", "5 h"),
    DAY("daily", "day"),
    WEEK("weekly", "week"),
    MONTH("monthly", "month"),
}

/** What a window's limit counts. */
@Serializable
enum class LimitUnit(val label: String) {
    TOKENS("tokens"),
    REQUESTS("requests"),
    USD("usd"),
}

/**
 * A subscription's usage window for one provider (OpenCode Go: 5 hours, a week, a month), with
 * the limit the user typed in from the plan's page; null: usage is shown without a limit.
 *
 * A [rolling] window counts the last [period] (a month is 30 days). A fixed one starts at
 * [anchor] and repeats every [period] in the phone's time zone (the same time of day, weekday or
 * day of the month), so "resets in" is exact once the user set the anchor to a known reset.
 */
@Serializable
data class SubscriptionWindow(
    val id: String,
    val provider: String,
    val period: WindowPeriod,
    val rolling: Boolean = true,
    val unit: LimitUnit = LimitUnit.TOKENS,
    val limit: Double? = null,
    val anchor: Long? = null,
)

/**
 * The user's price table and subscription windows; stored encrypted with the usage buckets and
 * exported with them. [prices] override the shipped [DefaultPrices] for the same provider and
 * model.
 */
@Serializable
data class UsagePlan(
    val prices: List<PriceEntry> = emptyList(),
    val windows: List<SubscriptionWindow> = emptyList(),
) {
    fun price(provider: String, model: String): PriceEntry? =
        prices.firstOrNull { it.provider == provider && it.model.equals(model, ignoreCase = true) }

    /** [entry] replacing the user's price for its provider and model (all blank: removed). */
    fun withPrice(entry: PriceEntry): UsagePlan {
        val rest = prices.filterNot { it.provider == entry.provider && it.model.equals(entry.model, ignoreCase = true) }
        val blank = entry.input == null && entry.output == null && entry.cacheRead == null && entry.cacheWrite == null
        return copy(prices = if (blank) rest else rest + entry)
    }

    /** [window] added, or replacing the one with its id. */
    fun withWindow(window: SubscriptionWindow): UsagePlan {
        val i = windows.indexOfFirst { it.id == window.id }
        return copy(windows = if (i < 0) windows + window else windows.toMutableList().also { it[i] = window })
    }

    fun withoutWindow(id: String): UsagePlan = copy(windows = windows.filterNot { it.id == id })

    companion object {
        /**
         * An imported [incoming] plan joined to this phone's [local] one: prices and windows this
         * phone does not have are added; what the user set here is kept.
         */
        fun merge(local: UsagePlan, incoming: UsagePlan): UsagePlan {
            val prices = local.prices + incoming.prices.filter { p -> local.price(p.provider, p.model) == null }
            val ids = local.windows.map { it.id }.toSet()
            return UsagePlan(prices, local.windows + incoming.windows.filter { it.id !in ids })
        }

        /** OpenCode Go's three windows (limits left for the user to fill in from the plan page). */
        fun openCodeGoWindows(provider: String, now: Long): List<SubscriptionWindow> = listOf(
            SubscriptionWindow("$provider-5h", provider, WindowPeriod.FIVE_HOURS, rolling = true, anchor = now),
            SubscriptionWindow("$provider-week", provider, WindowPeriod.WEEK, rolling = false, anchor = now),
            SubscriptionWindow("$provider-month", provider, WindowPeriod.MONTH, rolling = false, anchor = now),
        )
    }
}

/** What the agent's [UsageMeter] needs to know about a provider. */
class UsageProvider(val id: String, val name: String?, val presetId: String?, val onDevice: Boolean)
