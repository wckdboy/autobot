package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.core.data.model.ProviderKind

/** Whether a model reasons, and whether that can be switched. Decides whether the Think chip shows. */
enum class ThinkingSupport {
    /** No reasoning: the chip is hidden. */
    NONE,

    /** Reasoning with a real switch (Qwen3 / Qwen3.5 `enable_thinking`, a provider's effort or thinking field). */
    SWITCH,

    /**
     * Always reasons (DeepSeek-R1 distills, gpt-oss): Off still works on this device, where the
     * engine closes the think span at once; elsewhere only the provider's lowest setting.
     */
    ALWAYS,
    ;

    val shown: Boolean get() = this != NONE

    companion object {
        /**
         * From a GGUF's chat template (`tokenizer.chat_template`): a template that reads
         * `enable_thinking` has the switch; one with think tags or a reasoning channel reasons.
         */
        fun fromTemplate(template: String?): ThinkingSupport? {
            if (template.isNullOrBlank()) return null
            return when {
                "enable_thinking" in template -> SWITCH
                "<think>" in template || "reasoning_content" in template || "reasoning_effort" in template ||
                    "<|channel|>" in template -> ALWAYS
                else -> NONE
            }
        }

        /** From the model's name, when nothing better is known. */
        fun byName(kind: ProviderKind, model: String): ThinkingSupport {
            val m = model.lowercase()
            return when {
                kind == ProviderKind.LOCAL && SystemModels.isSystem(model) -> NONE
                Regex("qwen3|qwq|glm-4\\.[5-9]|glm-5|smollm3|granite-?4|hunyuan|magistral|phi-?4-(mini-)?reasoning|kimi-k|minimax-m").containsMatchIn(m) -> SWITCH
                Regex("deepseek-?r1|r1-distill|gpt-oss|deepseek-reasoner|exaone-?deep|openthinker|skywork-or1").containsMatchIn(m) -> ALWAYS
                kind == ProviderKind.LOCAL -> NONE
                Regex("^(gpt-5|gpt-6|o[1-9])|/(gpt-5|gpt-6|o[1-9])|claude-(opus|sonnet|fable|mythos)-[45]|claude-haiku-4-5|claude-3-7|gemini-(2\\.5|3)|deepseek|grok-[34]").containsMatchIn(m) -> SWITCH
                else -> NONE
            }
        }
    }
}

/**
 * The user's Think default per model (Settings), stored as one string on both platforms:
 * `provider/model<TAB>MODE[<TAB>budget]` lines. A chat that never chose uses its model's default.
 */
object ThinkingDefaults {
    fun key(provider: String, model: String): String = "$provider/$model"

    fun decode(text: String?): Map<String, ThinkingSetting> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('\t')
        val mode = parts.getOrNull(1)?.let { m -> ThinkingMode.entries.firstOrNull { it.name == m } } ?: return@mapNotNull null
        parts[0].takeIf { it.isNotBlank() }?.let { it to ThinkingSetting(mode, parts.getOrNull(2)?.toIntOrNull()?.takeIf { b -> b > 0 }) }
    }.toMap()

    fun encode(map: Map<String, ThinkingSetting>): String = map.entries.sortedBy { it.key }.joinToString("\n") { (k, s) ->
        listOfNotNull(k.replace('\t', ' ').replace('\n', ' '), s.mode.name, s.budget?.toString()).joinToString("\t")
    }

    /** [text] with [provider]/[model]'s default set (null: removed; auto without budget is the default, so it is removed too). */
    fun with(text: String?, provider: String, model: String, setting: ThinkingSetting?): String {
        val map = decode(text).toMutableMap()
        val k = key(provider, model)
        if (setting == null || setting == ThinkingSetting.AUTO) map.remove(k) else map[k] = setting
        return encode(map)
    }

    fun get(text: String?, provider: String, model: String): ThinkingSetting? = decode(text)[key(provider, model)]
}

/** Short labels of the Think chip and the reasoning row. */
object ThinkingLabels {
    /** The chip: "Think · auto", "Think · off", "Think · on", "Think · 1k". */
    fun chip(setting: ThinkingSetting): String = when (setting.mode) {
        ThinkingMode.OFF -> "think · off"
        ThinkingMode.AUTO -> "think · auto"
        ThinkingMode.ON -> setting.budget?.let { "think · ${tokens(it)}" } ?: "think · on"
    }

    /** The collapsed reasoning row: "Thinking · 412 tokens", "Thought · 1,024 tokens · stopped at budget". */
    fun row(live: Boolean, tokens: Int?, stop: String?): String = buildString {
        append(if (live) "thinking" else "thought")
        if (tokens != null && tokens > 0) append(" · ").append(tokens).append(if (tokens == 1) " token" else " tokens")
        when (stop) {
            "budget" -> append(" · stopped at budget")
            "loop" -> append(" · stopped: repeating")
        }
    }

    fun tokens(n: Int): String = if (n >= 1000 && n % 1024 == 0) "${n / 1024}k" else if (n >= 1000) "${n / 1000}k" else n.toString()

    /** Next mode when the chip is tapped: auto → on → off → auto. */
    fun next(setting: ThinkingSetting): ThinkingSetting = when (setting.mode) {
        ThinkingMode.AUTO -> ThinkingSetting(ThinkingMode.ON)
        ThinkingMode.ON -> ThinkingSetting.OFF
        ThinkingMode.OFF -> ThinkingSetting.AUTO
    }
}
