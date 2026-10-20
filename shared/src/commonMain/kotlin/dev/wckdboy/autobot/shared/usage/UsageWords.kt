package dev.wckdboy.autobot.shared.usage

/** The Usage screen's labels, written as shown, the same on Android and iOS. */
object UsageWords {
    const val COST = "Cost · USD"
    const val PRICES = "Prices · USD per 1M tokens"
    const val PRICE_UNIT = "USD per 1M tokens"

    /** How the numbers are counted: behind the screen's ⓘ, not a paragraph under the list. */
    const val ABOUT = "Counted on this phone from each provider's own usage report; nothing is sent. Costs use your price table: " +
        "only Anthropic's published prices ship, every other model stays unknown until you enter its price. Incognito chats are not kept."
}
