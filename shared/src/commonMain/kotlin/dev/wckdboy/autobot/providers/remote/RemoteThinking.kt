package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A local model's size in billions of parameters, for the reasoning budget: read from its name
 * ("Qwen3.5-0.8B", "gpt-oss-20b", "Qwen3-30B-A3B" → 30, "gemma-3-270m" → 0.27), else estimated
 * from the GGUF file size (~0.75 bytes per parameter, between Q4 and Q8).
 */
object LocalModelSize {
    private val BILLIONS = Regex("(?i)(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)\\s*b(?![a-z0-9])")
    private val MILLIONS = Regex("(?i)(?<![a-z0-9.])(\\d+(?:\\.\\d+)?)\\s*m(?![a-z0-9])")

    fun paramsB(vararg names: String?, fileBytes: Long? = null): Double? {
        for (name in names) {
            if (name.isNullOrBlank()) continue
            BILLIONS.find(name)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
            MILLIONS.find(name)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it / 1000.0 }
        }
        return fileBytes?.takeIf { it > 0 }?.let { it / 0.75e9 }
    }
}

/**
 * The Think switch as fields of an OpenAI-style request. Each field is only set for a provider
 * that documents it, and only for its models that have the control: unknown fields break strict
 * providers, so everything else gets nothing (the provider's own default).
 */
data class OpenAiReasoning(
    /** `reasoning_effort` (OpenAI, Gemini, Groq). */
    val effort: String? = null,
    /** `reasoning` object (OpenRouter: `enabled`, `effort`, `max_tokens`). */
    val reasoning: JsonObject? = null,
    /** `chat_template_kwargs` (vLLM and llama.cpp server: the template's `enable_thinking`). */
    val chatTemplateKwargs: JsonObject? = null,
    /** `reasoning_budget_tokens` (llama.cpp server). */
    val reasoningBudgetTokens: Int? = null,
    /** `enable_thinking` / `thinking_budget` (Alibaba Model Studio). */
    val enableThinking: Boolean? = null,
    val thinkingBudget: Int? = null,
    /** `thinking: {type}` (Z.ai GLM, DeepSeek). */
    val thinking: JsonObject? = null,
) {
    val isEmpty: Boolean
        get() = effort == null && reasoning == null && chatTemplateKwargs == null && reasoningBudgetTokens == null &&
            enableThinking == null && thinkingBudget == null && thinking == null
}

/**
 * Maps [ChatRequest.thinking] to each provider's real reasoning control. AUTO (or no setting)
 * sends nothing beyond what a provider requires anyway: the provider's default is the auto mode
 * for remote models. OFF and ON send the documented switch; ON's budget becomes a token budget
 * where one exists, else an effort level ([effortFor]).
 */
object RemoteThinking {
    /** ON without a budget, where the provider wants a number. */
    const val DEFAULT_ON_BUDGET = 8192

    /** A token budget as an effort level: ≤ 1024 low, ≤ 8192 medium, more (or none) high. */
    fun effortFor(budget: Int?): String = when {
        budget == null -> "high"
        budget <= 1024 -> "low"
        budget <= 8192 -> "medium"
        else -> "high"
    }

    fun openAi(presetId: String?, request: ChatRequest): OpenAiReasoning {
        val m = request.model.trim().lowercase()
        val mode = request.thinking?.mode ?: ThinkingMode.AUTO
        val budget = request.thinking?.budget
        // OpenAI's GPT-6 Sol / Luna call tools over chat completions only without reasoning: that wins.
        if (presetId == ProviderPresets.OPENAI) ProviderPresets.openAiReasoningEffort(request)?.let { return OpenAiReasoning(effort = it) }
        if (mode == ThinkingMode.AUTO) return OpenAiReasoning()
        val on = mode == ThinkingMode.ON
        return when (presetId) {
            ProviderPresets.OPENAI -> when {
                // https://developers.openai.com/api/docs/guides/reasoning: `none` from GPT-5.1 on.
                m.startsWith("gpt-6") || Regex("^gpt-5\\.[1-9]").containsMatchIn(m) ->
                    OpenAiReasoning(effort = if (on) effortFor(budget) else "none")
                m.startsWith("gpt-5") -> OpenAiReasoning(effort = if (on) effortFor(budget) else "minimal")
                Regex("^o[1-9]").containsMatchIn(m) -> OpenAiReasoning(effort = if (on) effortFor(budget) else "low")
                else -> OpenAiReasoning()
            }
            // Gemini's OpenAI layer: reasoning_effort; Gemini 3 cannot turn thinking off (low is the floor).
            ProviderPresets.GEMINI -> when {
                "gemini-3" in m -> OpenAiReasoning(effort = if (on) "high" else "low")
                "gemini-2.5-flash" in m -> OpenAiReasoning(effort = if (on) effortFor(budget) else "none")
                "gemini-2.5" in m -> OpenAiReasoning(effort = if (on) effortFor(budget) else "low")
                else -> OpenAiReasoning()
            }
            // https://openrouter.ai/docs/use-cases/reasoning-tokens: one `reasoning` object for every model.
            ProviderPresets.OPENROUTER -> OpenAiReasoning(
                reasoning = buildJsonObject {
                    if (!on) put("enabled", false)
                    else if (budget != null) put("max_tokens", budget)
                    else put("effort", "high")
                },
            )
            // Alibaba Model Studio (OpenAI-compatible): top-level enable_thinking + thinking_budget.
            "qwen" -> if (m.startsWith("qwen3") || m.startsWith("qwen-plus") || m.startsWith("qwen-flash") || m.startsWith("qwen-turbo")) {
                OpenAiReasoning(enableThinking = on, thinkingBudget = if (on) budget else null)
            } else {
                OpenAiReasoning()
            }
            // Z.ai: thinking {type: enabled | disabled} on GLM-4.5 and later.
            "zai" -> if (m.startsWith("glm-4.5") || m.startsWith("glm-4.6") || m.startsWith("glm-4.7") || m.startsWith("glm-5")) {
                OpenAiReasoning(thinking = typed(on))
            } else {
                OpenAiReasoning()
            }
            // DeepSeek: thinking {type} on the chat models (deepseek-reasoner always thinks).
            ProviderPresets.DEEPSEEK -> if (m != ProviderPresets.DEEPSEEK_REASONER) OpenAiReasoning(thinking = typed(on)) else OpenAiReasoning()
            // Groq: Qwen3 takes none / default, gpt-oss low / medium / high.
            "groq" -> when {
                "qwen3" in m -> OpenAiReasoning(effort = if (on) "default" else "none")
                "gpt-oss" in m -> OpenAiReasoning(effort = if (on) effortFor(budget) else "low")
                else -> OpenAiReasoning()
            }
            // Self-hosted servers render the model's template: its enable_thinking switch is the real one.
            "vllm" -> OpenAiReasoning(chatTemplateKwargs = buildJsonObject { put("enable_thinking", on) })
            "llamacpp" -> OpenAiReasoning(
                chatTemplateKwargs = buildJsonObject { put("enable_thinking", on) },
                reasoningBudgetTokens = if (on) budget else null,
            )
            else -> OpenAiReasoning()
        }
    }

    private fun typed(on: Boolean) = buildJsonObject { put("type", if (on) "enabled" else "disabled") }

    /**
     * The Messages API fields for [request] on [model]: `thinking` and `output_config.effort`
     * (null each when nothing is sent). Per the API (2026-10): Claude Opus 5.5 and Fable 5 / 5.1 cannot
     * turn thinking off (only a low effort); Sonnet 5.5 turns it off with `between_tools`; Opus 5,
     * Sonnet 5, Opus 4.6–4.8 and Sonnet 4.6 accept `disabled`; on all of these ON is adaptive
     * thinking with an effort level (a token budget is refused from 4.7 on). Haiku 4.5 and older
     * models take `enabled` with `budget_tokens` (at least 1024, below `max_tokens`).
     */
    fun anthropic(request: ChatRequest, maxTokens: Int): AnthropicThinking {
        val mode = request.thinking?.mode ?: ThinkingMode.AUTO
        if (mode == ThinkingMode.AUTO) return AnthropicThinking()
        val m = request.model.trim().lowercase()
        val on = mode == ThinkingMode.ON
        val budget = request.thinking?.budget
        return when (val family = AnthropicFamily.of(m)) {
            AnthropicFamily.ALWAYS_ON -> AnthropicThinking(
                thinking = if (on) adaptive() else null,
                effort = if (on) effortFor(budget) else "low",
            )
            AnthropicFamily.BETWEEN_TOOLS -> if (on) {
                AnthropicThinking(thinking = adaptive(), effort = effortFor(budget))
            } else {
                AnthropicThinking(thinking = buildJsonObject { put("type", "between_tools") })
            }
            AnthropicFamily.ADAPTIVE -> if (on) {
                AnthropicThinking(thinking = adaptive(), effort = effortFor(budget))
            } else {
                AnthropicThinking(thinking = buildJsonObject { put("type", "disabled") })
            }
            AnthropicFamily.BUDGET -> if (on) {
                val tokens = (budget ?: DEFAULT_ON_BUDGET).coerceAtLeast(1024)
                AnthropicThinking(
                    thinking = buildJsonObject {
                        put("type", "enabled")
                        put("budget_tokens", tokens)
                    },
                    // budget_tokens must stay below max_tokens: the answer keeps its own share.
                    maxTokens = maxOf(maxTokens, tokens + 4096),
                    family = family,
                )
            } else {
                AnthropicThinking()
            }
            AnthropicFamily.UNKNOWN -> AnthropicThinking()
        }
    }

    private fun adaptive() = buildJsonObject {
        put("type", "adaptive")
        put("display", "summarized")
    }
}

/** What [RemoteThinking.anthropic] adds to a Messages API request. */
data class AnthropicThinking(
    val thinking: JsonObject? = null,
    val effort: String? = null,
    /** A raised `max_tokens` (budget_tokens models), or null to keep the request's. */
    val maxTokens: Int? = null,
    val family: AnthropicFamily? = null,
)

/** Claude models by how their thinking is controlled. */
enum class AnthropicFamily {
    /** Thinking always on, effort only: Opus 5.5, Fable 5 / 5.1, Mythos. */
    ALWAYS_ON,

    /** Off is `between_tools`: Sonnet 5.5. */
    BETWEEN_TOOLS,

    /** Adaptive thinking, `disabled` accepted: Opus 5, Sonnet 5, Opus 4.6–4.8, Sonnet 4.6. */
    ADAPTIVE,

    /** `enabled` + `budget_tokens`: Haiku 4.5, Sonnet / Opus 4.5 and earlier 4.x, Sonnet 3.7. */
    BUDGET,
    UNKNOWN,
    ;

    companion object {
        /** Whether [model] refuses a non-default `temperature` (removed from Opus 4.7 on and in the 5 family). */
        fun refusesTemperature(model: String): Boolean {
            val m = model.lowercase().substringAfterLast('/')
            return when (of(m)) {
                ALWAYS_ON, BETWEEN_TOOLS -> true
                ADAPTIVE -> !(m.startsWith("claude-opus-4-6") || m.startsWith("claude-sonnet-4-6"))
                else -> false
            }
        }

        fun of(model: String): AnthropicFamily {
            val m = model.lowercase().substringAfterLast('/')
            return when {
                m.startsWith("claude-opus-5-5") || m.startsWith("claude-fable-5") || m.startsWith("claude-mythos") -> ALWAYS_ON
                m.startsWith("claude-sonnet-5-5") -> BETWEEN_TOOLS
                m.startsWith("claude-opus-5") || m.startsWith("claude-sonnet-5") ||
                    m.startsWith("claude-opus-4-8") || m.startsWith("claude-opus-4-7") ||
                    m.startsWith("claude-opus-4-6") || m.startsWith("claude-sonnet-4-6") -> ADAPTIVE
                m.startsWith("claude-haiku-4-5") || m.startsWith("claude-sonnet-4") || m.startsWith("claude-opus-4") ||
                    m.startsWith("claude-3-7-sonnet") -> BUDGET
                else -> UNKNOWN
            }
        }
    }
}
