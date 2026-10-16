package dev.wckdboy.autobot.agent.core.llm

import kotlin.math.max
import kotlin.math.min
import kotlinx.serialization.Serializable

/** The Think switch of a chat: off, auto (the default) or on. */
@Serializable
enum class ThinkingMode { OFF, AUTO, ON }

/**
 * What a chat asked for. [budget] (reasoning tokens) only applies to [ThinkingMode.ON]; null there
 * means the model's default (see [ThinkingPolicy.plan]).
 */
@Serializable
data class ThinkingSetting(val mode: ThinkingMode = ThinkingMode.AUTO, val budget: Int? = null) {
    companion object {
        val AUTO = ThinkingSetting()
        val OFF = ThinkingSetting(ThinkingMode.OFF)

        /** Budgets offered for ON (the sheet); null = the model's default. */
        val BUDGETS: List<Int?> = listOf(null, 256, 512, 1024, 2048, 4096)
    }
}

/**
 * What one request does about reasoning. [budget] caps the reasoning tokens (null: no cap of our
 * own, the provider's default applies); [answerReserve] is kept for the answer whatever the
 * reasoning does.
 */
data class ThinkingPlan(val enabled: Boolean, val budget: Int?, val answerReserve: Int = 0) {
    companion object {
        val OFF = ThinkingPlan(enabled = false, budget = 0)
    }
}

/**
 * Turns a [ThinkingSetting] into a [ThinkingPlan] for one request, scaled to the model and its
 * output limit. The defaults, by model size (billions of parameters; unknown counts as large):
 *
 * | size     | AUTO, agent/code (tools on)  | AUTO, quick chat (tools off) | ON, no budget given          |
 * |----------|------------------------------|------------------------------|------------------------------|
 * | < 4B     | min(1024, 25 % of output)    | off                          | min(2048, 50 % of output)    |
 * | 4B–14B   | min(2048, 35 % of output)    | min(1024, 20 % of output)    | min(4096, 60 % of output)    |
 * | ≥ 14B    | min(4096, 50 % of output)    | min(2048, 25 % of output)    | min(8192, 70 % of output)    |
 *
 * "Output" is everything the request may generate (reasoning and answer). Every budget leaves at
 * least [answerReserve] tokens for the answer: a quarter of the output, 64..1024 tokens. Small
 * models think in circles easily and are slow on a phone, so they get the least room; background
 * work (subagents, summaries, titles) asks for [ThinkingSetting.OFF].
 */
object ThinkingPolicy {
    const val SMALL_B = 4.0
    const val LARGE_B = 14.0

    /** Tokens the answer always keeps out of [outputLimit]. */
    fun answerReserve(outputLimit: Int): Int = (outputLimit / 4).coerceIn(64, 1024).coerceAtMost(outputLimit / 2)

    /**
     * @param quickChat a plain chat (tools off): the cheapest setting is enough there.
     * @param paramsB the model's size in billions of parameters, when known.
     * @param outputLimit tokens the request may generate in all.
     */
    fun plan(setting: ThinkingSetting?, quickChat: Boolean, paramsB: Double?, outputLimit: Int): ThinkingPlan {
        val mode = setting?.mode ?: ThinkingMode.AUTO
        if (mode == ThinkingMode.OFF || outputLimit <= 0) return ThinkingPlan.OFF
        val reserve = answerReserve(outputLimit)
        val room = max(0, outputLimit - reserve)
        val size = sizeClass(paramsB)
        val budget = when (mode) {
            ThinkingMode.ON -> setting?.budget ?: when (size) {
                Size.SMALL -> min(2048, outputLimit * 50 / 100)
                Size.MEDIUM -> min(4096, outputLimit * 60 / 100)
                Size.LARGE -> min(8192, outputLimit * 70 / 100)
            }
            else -> when {
                quickChat && size == Size.SMALL -> return ThinkingPlan.OFF
                quickChat && size == Size.MEDIUM -> min(1024, outputLimit * 20 / 100)
                quickChat -> min(2048, outputLimit * 25 / 100)
                size == Size.SMALL -> min(1024, outputLimit * 25 / 100)
                size == Size.MEDIUM -> min(2048, outputLimit * 35 / 100)
                else -> min(4096, outputLimit * 50 / 100)
            }
        }
        val capped = budget.coerceIn(0, room)
        return if (capped <= 0) ThinkingPlan.OFF else ThinkingPlan(enabled = true, budget = capped, answerReserve = reserve)
    }

    private enum class Size { SMALL, MEDIUM, LARGE }

    private fun sizeClass(paramsB: Double?): Size = when {
        paramsB == null -> Size.LARGE
        paramsB < SMALL_B -> Size.SMALL
        paramsB < LARGE_B -> Size.MEDIUM
        else -> Size.LARGE
    }
}
