package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole

/** The judge's verdict on the phone's answer. */
enum class CascadeVerdict { GOOD, UNSURE, WEAK }

/**
 * The cascade's judge: decides whether the phone's answer is good enough. Cheap by design: rules
 * over the prompt and the answer ([signals]) plus, when the on-device model is there, one short
 * self-check pass ([selfCheckMessages], at most [SELF_CHECK_MAX_TOKENS] tokens) whose reply
 * [parseSelfCheck] reads as a grade from 1 to 5. [judge] combines both into a score from 0 to 1
 * and compares it with [threshold]; a score within [unsureBand] below it is [CascadeVerdict.UNSURE].
 *
 * Hard signals (no answer, a failed or cut-off answer, a refusal, "I don't know", a loop) make
 * the answer [CascadeVerdict.WEAK] whatever the self-check says: small models grade their own
 * refusals generously.
 */
class CascadeJudge(
    val threshold: Double = DEFAULT_THRESHOLD,
    val unsureBand: Double = DEFAULT_UNSURE_BAND,
) {
    init {
        require(threshold in 0.0..1.0) { "threshold must be within 0..1" }
        require(unsureBand >= 0.0) { "unsureBand must not be negative" }
    }

    /** What the rules found: [hard] reasons force a weak verdict, [soft] ones lower the score. */
    data class Signals(val hard: List<String>, val soft: List<String>)

    /**
     * @property score 0 (worthless) to 1 (complete and correct).
     * @property reasons the codes behind the verdict, hard ones first; `self-check` when the
     *   model's own grade was low.
     * @property selfCheck the parsed grade (1 to 5), null when there was none (heuristics only).
     */
    data class Result(val verdict: CascadeVerdict, val score: Double, val reasons: List<String>, val selfCheck: Int?)

    /**
     * The rules. [truncated]: the engine stopped at its output limit; [failed]: the on-device
     * request failed (nothing usable came back).
     */
    fun signals(prompt: String, answer: String, truncated: Boolean = false, failed: Boolean = false): Signals {
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()
        val text = stripThinking(answer).trim()
        val head = text.take(HEAD_CHARS).lowercase().replace('’', '\'')
        when {
            failed -> hard += FAILED
            text.isEmpty() -> hard += EMPTY
        }
        if (text.isNotEmpty()) {
            if (truncated || text.split("```").size % 2 == 0) hard += TRUNCATED
            if (REFUSALS.any { head.contains(it) }) hard += REFUSAL
            if (DONT_KNOW.any { head.contains(it) }) hard += DONT_KNOW_CODE
            if (looping(text)) hard += LOOPING
            if (HEDGES.any { head.contains(it) }) soft += HEDGE
            if (prompt.trim().length >= SHORT_PROMPT_MIN && text.length < SHORT_ANSWER_MAX) soft += SHORT
        }
        if (needsFreshInfo(prompt)) soft += FRESH
        return Signals(hard.distinct(), soft.distinct())
    }

    /** Combines [signals] with the self-check grade (1 to 5, null: none) into a verdict. */
    fun judge(signals: Signals, selfCheck: Int?): Result {
        val grade = selfCheck?.takeIf { it in 1..5 }
        val reasons = ArrayList<String>(signals.hard)
        if (signals.hard.isNotEmpty()) return Result(CascadeVerdict.WEAK, 0.0, reasons + signals.soft, grade)
        var score = grade?.let { (it - 1) / 4.0 } ?: PRIOR
        signals.soft.forEach { score -= SOFT_PENALTY[it] ?: 0.25 }
        score = score.coerceIn(0.0, 1.0)
        reasons += signals.soft
        if (grade != null && grade <= LOW_GRADE) reasons += SELF_CHECK
        val verdict = when {
            score >= threshold - EPSILON -> CascadeVerdict.GOOD
            score >= threshold - unsureBand - EPSILON -> CascadeVerdict.UNSURE
            else -> CascadeVerdict.WEAK
        }
        return Result(verdict, score, if (verdict == CascadeVerdict.GOOD) emptyList() else reasons, grade)
    }

    /**
     * The self-check request: a short grading prompt over the (clipped) question and answer. Sent
     * to the on-device model with Think off and [SELF_CHECK_MAX_TOKENS] output tokens.
     */
    fun selfCheckMessages(prompt: String, answer: String): List<ModelMessage> = listOf(
        ModelMessage(ModelRole.SYSTEM, SELF_CHECK_SYSTEM),
        ModelMessage(
            ModelRole.USER,
            "Question:\n${clip(prompt.trim(), PROMPT_CLIP)}\n\nAnswer:\n${clip(stripThinking(answer).trim(), ANSWER_CLIP)}\n\n$SELF_CHECK_ASK",
        ),
    )

    companion object {
        /** Scores at or above this are good enough to keep. Tests and hosts may pick another. */
        const val DEFAULT_THRESHOLD = 0.55
        const val DEFAULT_UNSURE_BAND = 0.2

        /** The score of an answer no rule objects to, when there is no self-check grade. */
        const val PRIOR = 0.7

        /** Grades at or below this name the self-check among the reasons. */
        const val LOW_GRADE = 2

        /** Rounding slack for score comparisons (0.7 - 0.35 is 0.3499...). */
        private const val EPSILON = 1e-9

        const val SELF_CHECK_MAX_TOKENS = 8
        const val SELF_CHECK_TIMEOUT_MS = 20_000L
        private const val PROMPT_CLIP = 1_200
        private const val ANSWER_CLIP = 2_000
        private const val HEAD_CHARS = 400
        private const val SHORT_PROMPT_MIN = 120
        private const val SHORT_ANSWER_MAX = 16

        // Reason codes (stored in receipts; labels in CascadeLabels).
        const val EMPTY = "empty"
        const val FAILED = "failed"
        const val TRUNCATED = "truncated"
        const val REFUSAL = "refusal"
        const val DONT_KNOW_CODE = "dont-know"
        const val LOOPING = "looping"
        const val HEDGE = "hedge"
        const val SHORT = "short"
        const val FRESH = "fresh"
        const val SELF_CHECK = "self-check"

        private val SOFT_PENALTY = mapOf(HEDGE to 0.25, SHORT to 0.3, FRESH to 0.35)

        const val SELF_CHECK_SYSTEM = "You grade answers strictly. Reply with a single digit and nothing else."
        const val SELF_CHECK_ASK =
            "Does the answer fully and correctly answer the question? Reply with one digit: " +
                "1 = wrong, empty or refuses, 2 = mostly wrong, 3 = partly right, 4 = right, 5 = complete and correct."

        private val REFUSALS = listOf(
            "i can't help with", "i cannot help with", "i can't assist", "i cannot assist", "i'm unable to", "i am unable to",
            "i'm not able to", "i am not able to", "i won't be able to", "as an ai", "i cannot provide", "i can't provide",
            "i'm sorry, but i can't", "i'm sorry, but i cannot", "i must decline", "i can't do that", "i cannot do that",
        )
        private val DONT_KNOW = listOf(
            "i don't know", "i do not know", "i don't have access to real-time", "i don't have real-time", "i do not have real-time",
            "i don't have access to current", "i don't have current", "i don't have up-to-date", "my knowledge cutoff",
            "my knowledge cut-off", "as of my last update", "as of my knowledge", "i cannot browse", "i can't browse",
            "i don't have browsing", "i'm not aware of any", "i have no information",
        )
        private val HEDGES = listOf("i'm not sure", "i am not sure", "i'm not certain", "i think it might", "it's possible that", "i believe it may")

        private val FRESH_WORDS = setOf(
            "today", "tonight", "yesterday", "tomorrow", "latest", "current", "currently", "news", "weather", "forecast",
            "price", "prices", "stock", "stocks", "score", "scores", "recent", "recently", "breaking", "election", "standings",
            "search", "google", "browse", "website", "url", "heute", "gestern", "morgen", "aktuell", "aktuelle", "wetter", "preis",
        )
        private val FRESH_PHRASES = listOf(
            "this week", "this month", "this year", "right now", "look up", "on the web", "on the internet", "search the web",
            "what time is it", "exchange rate", "who won",
        )

        /** Whether [prompt] asks for information newer than any on-device model or for a web search. */
        fun needsFreshInfo(prompt: String): Boolean {
            val lower = prompt.lowercase()
            val words = lower.split(Regex("[^\\p{L}\\p{N}]+")).toSet()
            return words.any { it in FRESH_WORDS } || FRESH_PHRASES.any { lower.contains(it) }
        }

        /**
         * The grade in a self-check reply: the first digit 1 to 5 (after any reasoning span), or
         * "yes"/"no" from a model that ignored the format; null when it says neither.
         */
        fun parseSelfCheck(reply: String): Int? {
            val text = stripThinking(reply).trim()
            Regex("(?<![0-9])([1-5])(?![0-9])").find(text)?.let { return it.groupValues[1].toInt() }
            val lower = text.lowercase()
            return when {
                Regex("^\\W*yes\\b").containsMatchIn(lower) -> 4
                Regex("^\\W*no\\b").containsMatchIn(lower) -> 1
                else -> null
            }
        }

        /** [text] without `<think>…</think>` spans (and an unterminated one at the start). */
        fun stripThinking(text: String): String {
            var out = text.replace(Regex("(?s)<think>.*?</think>"), "")
            val open = out.indexOf("<think>")
            if (open >= 0) out = out.substring(0, open)
            return out
        }

        /** The same sentence of 15+ characters three times or more: the engine is looping. */
        internal fun looping(text: String): Boolean {
            val sentences = text.split(Regex("(?<=[.!?\\n])")).map { it.trim().lowercase() }.filter { it.length >= 15 }
            return sentences.groupingBy { it }.eachCount().values.any { it >= 3 }
        }

        private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max) + " …"
    }
}
