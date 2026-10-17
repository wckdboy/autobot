package dev.wckdboy.autobot.shared.usage

import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.providers.remote.ProviderPresets

/** A price the app ships, for a preset's model (prefix: dated snapshots of the id match too). */
class DefaultPrice(val presetId: String, val model: String, val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

/**
 * Prices shipped with the app, only where they are published and certain: Anthropic's
 * first-party API (anthropic.com/pricing, checked 2026-09; cache writes are the 5-minute TTL at
 * 1.25× input). Every other model starts blank ("cost unknown") until the user enters its price:
 * prices change and differ by plan, and a guessed price would be worse than none. Subscription
 * hosts (OpenCode Go) bill by plan, not by token, so they get no default either.
 */
object DefaultPrices {
    val all: List<DefaultPrice> = listOf(
        anthropic("claude-fable-5-1", 10.0, 50.0, cacheRead = 0.25),
        anthropic("claude-fable-5", 10.0, 50.0, cacheRead = 1.0),
        anthropic("claude-opus-5-5", 4.0, 20.0, cacheRead = 0.20),
        anthropic("claude-opus-5", 5.0, 25.0, cacheRead = 0.50),
        anthropic("claude-opus-4-8", 5.0, 25.0, cacheRead = 0.50),
        anthropic("claude-opus-4-7", 5.0, 25.0, cacheRead = 0.50),
        anthropic("claude-opus-4-6", 5.0, 25.0, cacheRead = 0.50),
        anthropic("claude-sonnet-5-5", 2.0, 10.0, cacheRead = 0.20),
        anthropic("claude-sonnet-5", 2.0, 10.0, cacheRead = 0.20),
        anthropic("claude-sonnet-4-6", 3.0, 15.0, cacheRead = 0.30),
        anthropic("claude-haiku-4-5", 1.0, 5.0, cacheRead = 0.10),
    )

    private fun anthropic(model: String, input: Double, output: Double, cacheRead: Double) =
        DefaultPrice(ProviderPresets.ANTHROPIC, model, input, output, cacheRead, cacheWrite = input * 1.25)

    /**
     * The shipped price of [model] on a provider made from [presetId]: the id itself or a dated
     * snapshot of it (`claude-haiku-4-5-20251001`); the longest id wins (`claude-opus-5-5` over
     * `claude-opus-5`).
     */
    fun find(presetId: String?, model: String): DefaultPrice? {
        if (presetId == null) return null
        val m = model.lowercase()
        return all.filter { it.presetId == presetId && (m == it.model || (m.startsWith(it.model + "-") && SNAPSHOT.matches(m.removePrefix(it.model + "-")))) }
            .maxByOrNull { it.model.length }
    }

    private val SNAPSHOT = Regex("\\d{8}|latest")
}

/** Where a model's price comes from. */
enum class PriceSource { YOURS, DEFAULT, NONE }

/** A resolved price (per 1M tokens) and where it came from. */
class Price(val input: Double?, val output: Double?, val cacheRead: Double?, val cacheWrite: Double?, val source: PriceSource) {
    val known: Boolean get() = input != null && output != null
}

/** Cost math: the user's table first, then [DefaultPrices]; on-device runs cost nothing. */
object UsageCost {

    fun price(plan: UsagePlan, provider: String, presetId: String?, model: String): Price {
        plan.price(provider, model)?.let { return Price(it.input, it.output, it.cacheRead, it.cacheWrite, PriceSource.YOURS) }
        DefaultPrices.find(presetId, model)?.let { return Price(it.input, it.output, it.cacheRead, it.cacheWrite, PriceSource.DEFAULT) }
        return Price(null, null, null, null, PriceSource.NONE)
    }

    /**
     * US dollars for the given counts at [price], or null when the price is not known. [input] is
     * the whole input: the cached parts are taken out and billed at their own prices (blank cache
     * prices at the input price). Reasoning is part of [output] and billed with it.
     */
    fun cost(price: Price, input: Long, output: Long, cacheRead: Long, cacheWrite: Long): Double? {
        val pin = price.input ?: return null
        val pout = price.output ?: return null
        val uncached = (input - cacheRead - cacheWrite).coerceAtLeast(0)
        val dollars = uncached * pin + cacheRead * (price.cacheRead ?: pin) + cacheWrite * (price.cacheWrite ?: pin) + output * pout
        return dollars / 1_000_000.0
    }

    fun cost(plan: UsagePlan, record: UsageRecord): Double? =
        if (record.onDevice) 0.0
        else if (record.tokens == 0L) 0.0
        else cost(price(plan, record.provider, record.presetId, record.model), record.input, record.output, record.cacheRead, record.cacheWrite)

    fun cost(plan: UsagePlan, provider: UsageProvider, model: String, usage: TokenUsage): Double? =
        if (provider.onDevice) 0.0
        else cost(
            price(plan, provider.id, provider.presetId, model),
            (usage.inputTokens ?: 0).toLong(), (usage.outputTokens ?: 0).toLong(),
            (usage.cacheReadTokens ?: 0).toLong(), (usage.cacheWriteTokens ?: 0).toLong(),
        )
}
