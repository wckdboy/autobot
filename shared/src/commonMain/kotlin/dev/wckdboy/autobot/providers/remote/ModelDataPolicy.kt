package dev.wckdboy.autobot.providers.remote

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What a provider does with the prompts sent to one model. */
sealed interface ModelDataPolicy {
    /** Short picker caption. */
    val label: String

    /** Not used for training; not kept after the response (zero data retention). */
    data object NoRetention : ModelDataPolicy {
        override val label = "no training · zero retention"
    }

    /** Not used for training; kept [days] days (abuse monitoring, the model maker's policy). */
    data class Retention(val days: Int) : ModelDataPolicy {
        override val label = "no training · kept $days days"
    }

    /** Prompts and completions may be used to train models. */
    data object Trains : ModelDataPolicy {
        override val label = "trains on your prompts"
    }
}

/**
 * Per-model data policies of providers whose policy differs by model (a provider-wide policy is
 * the preset's `privacyNote`). Each entry is copied from the provider's own table and dated; an
 * agreement with an end date ([Rule.until]) stops counting after it, and the model reads as
 * undocumented until the table is checked again.
 *
 * Pickers show [caption]; switching a chat to a [ModelDataPolicy.Trains] model asks first
 * ([TRAINS_WARNING]); automatic choices (defaults, helper, quota fallback) skip such models
 * ([autoPickable]).
 */
object ModelDataPolicies {

    /**
     * [policy] for the models of [presetId] matching [pattern] (`*` = any characters, case
     * ignored), until the end of the UTC day [until] (`yyyy-mm-dd`) when it is time-bounded.
     */
    class Rule(val presetId: String, val pattern: String, val policy: ModelDataPolicy, val until: String? = null) {
        private val regex = Regex(pattern.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

        fun matches(model: String): Boolean = regex.matches(model.trim())

        @OptIn(ExperimentalTime::class)
        fun validAt(nowMs: Long): Boolean =
            until == null || nowMs < Instant.parse("${until}T00:00:00Z").toEpochMilliseconds() + DAY_MS
    }

    /** Checked against https://opencode.ai/docs/go/#privacy on 2026-10-15. First match wins. */
    val rules: List<Rule> = listOf(
        // "Contributor" models: discounted in exchange for training on prompts and completions.
        Rule(ProviderPresets.OPENCODE_GO, "*-contributor", ModelDataPolicy.Trains),
        // DeepSeek's ZDR agreement is renewed monthly: "valid through October 31, 2026".
        Rule(ProviderPresets.OPENCODE_GO, "deepseek-*", ModelDataPolicy.NoRetention, until = "2026-10-31"),
        Rule(ProviderPresets.OPENCODE_GO, "claude-*", ModelDataPolicy.Retention(30)),
        Rule(ProviderPresets.OPENCODE_GO, "grok-*", ModelDataPolicy.Retention(30)),
        Rule(ProviderPresets.OPENCODE_GO, "gpt-*", ModelDataPolicy.Retention(30)),
        Rule(ProviderPresets.OPENCODE_GO, "cat-jam-*", ModelDataPolicy.Retention(30)),
        Rule(ProviderPresets.OPENCODE_GO, "glm-*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "kimi-*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "longcat-*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "mimo-*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "minimax-*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "qwen3*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "hy3*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "hy4*", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "space-bunny", ModelDataPolicy.NoRetention),
        Rule(ProviderPresets.OPENCODE_GO, "step-*", ModelDataPolicy.NoRetention),
    )

    const val TRAINS_WARNING = "This model's provider trains on your prompts."
    const val UNDOCUMENTED = "data policy not documented"

    /** Whether [presetId] has per-model policies (its other models are then "not documented"). */
    fun covers(presetId: String?): Boolean = rules.any { it.presetId == presetId }

    /** The policy of [model] on [presetId] now, or null when it is not documented (or expired). */
    @OptIn(ExperimentalTime::class)
    fun policy(presetId: String?, model: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): ModelDataPolicy? =
        rule(presetId, model)?.takeIf { it.validAt(nowMs) }?.policy

    /**
     * Picker caption for [model]: its policy (with the end date of a time-bounded one), "not
     * documented" on a provider with per-model policies, else null (the preset's note applies).
     */
    @OptIn(ExperimentalTime::class)
    fun caption(presetId: String?, model: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String? {
        val rule = rule(presetId, model) ?: return if (covers(presetId)) UNDOCUMENTED else null
        if (!rule.validAt(nowMs)) return "$UNDOCUMENTED (agreement ended ${rule.until})"
        return rule.policy.label + (rule.until?.let { " until $it" }.orEmpty())
    }

    /** [RemoteModel.caption] followed by the model's [caption], joined with ` · `. */
    @OptIn(ExperimentalTime::class)
    fun modelCaption(presetId: String?, model: RemoteModel, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String =
        listOfNotNull(model.caption().ifEmpty { null }, caption(presetId, model.id, nowMs)).joinToString(" · ")

    /** Whether choosing [model] needs the user's confirmation ([TRAINS_WARNING]). */
    @OptIn(ExperimentalTime::class)
    fun trains(presetId: String?, model: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        policy(presetId, model, nowMs) == ModelDataPolicy.Trains

    /** Whether the app may pick [model] on its own (defaults, first-in-list, fallbacks). */
    @OptIn(ExperimentalTime::class)
    fun autoPickable(presetId: String?, model: String, nowMs: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
        !trains(presetId, model, nowMs)

    private fun rule(presetId: String?, model: String): Rule? = rules.firstOrNull { it.presetId == presetId && it.matches(model) }

    private const val DAY_MS = 86_400_000L
}
