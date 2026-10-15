package dev.wckdboy.autobot.agent.core.policy

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.RequestErrorDecision
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmErrorCode
import dev.wckdboy.autobot.agent.core.plugin

/**
 * "On limit, fall back": when a request fails with [LlmErrorCode.QUOTA] (the provider's usage
 * limit or credit is used up) and the user turned this on, the turn is retried once on the
 * provider's helper model, else on [onDevice]. The answer carries a notice naming the model
 * that stood in, so a fallback is never silent. Off, or with nowhere to go, the failure stands.
 *
 * @param enabled read on every failure (the setting can change while a chat is open).
 * @param onDevice the on-device route, if a chat model is installed.
 * @param eligible whether a route may be chosen automatically (hosts exclude models whose
 *   provider trains on prompts).
 */
fun quotaFallbackPlugin(
    enabled: () -> Boolean,
    onDevice: suspend () -> LlmCallConfig? = { null },
    eligible: suspend (LlmCallConfig) -> Boolean = { true },
): Plugin = plugin("quota-fallback") { ctx ->
    ctx.intercept(Hooks.RequestError) { input, next ->
        val agent = input.agent
        if (input.failure.code != LlmErrorCode.QUOTA || agent.onFallback || !enabled()) return@intercept next(input)
        val failing = agent.activeLlm
        fun LlmCallConfig.isFailing() = provider == failing.provider && model == failing.model
        val helper = agent.helperConfig()?.takeIf { !it.isFailing() && eligible(it) }
        val target = helper ?: onDevice()?.takeIf { !it.isFailing() && eligible(it) } ?: return@intercept next(input)
        val role = if (helper != null) "helper model" else "on-device model"
        RequestErrorDecision.Fallback(target, "${failing.model} hit its usage limit — answered by the $role")
    }
}
