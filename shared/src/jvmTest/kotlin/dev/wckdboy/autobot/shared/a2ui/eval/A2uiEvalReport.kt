package dev.wckdboy.autobot.shared.a2ui.eval

import java.util.Locale

/** A model's scores over the eval set. Rates are over the UI tasks; [restraint] over the no-UI ones. */
data class A2uiModelSummary(
    val model: String,
    val uiTasks: Int,
    val called: Int,
    /** UI tasks where at least one call came as text and was recovered. */
    val salvaged: Int,
    val firstTryValid: Int,
    val validWithin: Int,
    val meanTries: Double,
    val expectScore: Double,
    val allExpectations: Int,
    val noUiTasks: Int,
    val restraint: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val meanLatencyMs: Double,
    val topErrors: List<Pair<String, Int>>,
) {
    companion object {
        fun of(model: String, results: List<A2uiTaskResult>): A2uiModelSummary {
            val ui = results.filter { !it.noUi }
            val none = results.filter { it.noUi }
            val called = ui.filter { it.calledTool }
            return A2uiModelSummary(
                model = model,
                uiTasks = ui.size,
                called = called.size,
                salvaged = ui.count { it.salvaged > 0 },
                firstTryValid = ui.count { it.firstTryValid },
                validWithin = ui.count { it.valid },
                meanTries = if (called.isEmpty()) 0.0 else called.sumOf { it.tries }.toDouble() / called.size,
                expectScore = if (ui.isEmpty()) 0.0 else ui.sumOf { it.expectScore } / ui.size,
                allExpectations = ui.count { it.allExpectationsMet },
                noUiTasks = none.size,
                restraint = none.count { !it.calledTool },
                promptTokens = results.sumOf { it.promptTokens },
                completionTokens = results.sumOf { it.completionTokens },
                meanLatencyMs = if (results.isEmpty()) 0.0 else results.sumOf { it.latencyMs }.toDouble() / results.size,
                topErrors = results.flatMap { r -> r.errors.map { A2uiEvalRunner.signature(it) } }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key to it.value },
            )
        }
    }
}

/** The Markdown report of a run. */
object A2uiEvalReport {
    private fun pct(n: Int, of: Int) = if (of == 0) "–" else "$n/$of (${(100.0 * n / of).toInt()}%)"
    private fun f(x: Double, digits: Int = 2) = String.format(Locale.ROOT, "%.${digits}f", x)

    fun summaryTable(summaries: List<A2uiModelSummary>): String = buildString {
        appendLine("| Model | Called show_ui | Call as text | Valid 1st try | Valid within 3 | Mean tries | Expectations (mean) | All expectations met | No-UI restraint | Tokens in/out | Mean latency/task |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (s in summaries) {
            append("| ").append(s.model)
            append(" | ").append(pct(s.called, s.uiTasks))
            append(" | ").append(pct(s.salvaged, s.uiTasks))
            append(" | ").append(pct(s.firstTryValid, s.uiTasks))
            append(" | ").append(pct(s.validWithin, s.uiTasks))
            append(" | ").append(f(s.meanTries))
            append(" | ").append(f(s.expectScore * 100, 0)).append('%')
            append(" | ").append(pct(s.allExpectations, s.uiTasks))
            append(" | ").append(pct(s.restraint, s.noUiTasks))
            append(" | ").append(s.promptTokens).append(" / ").append(s.completionTokens)
            append(" | ").append(f(s.meanLatencyMs / 1000, 1)).append(" s |")
            appendLine()
        }
    }

    fun modelSection(s: A2uiModelSummary, results: List<A2uiTaskResult>): String = buildString {
        appendLine("### ${s.model}")
        appendLine()
        appendLine("| Task | Category | Outcome | Tries | Expectations | Failed checks |")
        appendLine("|---|---|---|---|---|---|")
        for (r in results) {
            val passed = r.checks.count { it.pass }
            val exp = if (r.noUi) (if (r.calledTool) "called show_ui" else "ok") else if (!r.valid) "–" else "$passed/${r.checks.size}"
            val failed = r.checks.filter { !it.pass }.joinToString("; ") { c -> c.name + (if (c.detail.isNotEmpty()) " (${c.detail})" else "") }
            appendLine("| ${r.task} | ${r.category} | ${r.outcome} | ${r.tries} | $exp | ${failed.replace("|", "\\|")} |")
        }
        if (s.topErrors.isNotEmpty()) {
            appendLine()
            appendLine("Most common errors:")
            appendLine()
            for ((sig, n) in s.topErrors) appendLine("- ${n}× `${sig.replace("`", "'")}`")
        }
        appendLine()
    }

    fun markdown(endpoint: String, byModel: Map<String, List<A2uiTaskResult>>): String = buildString {
        val summaries = byModel.map { (m, r) -> A2uiModelSummary.of(m, r) }
        appendLine("# A2UI show_ui eval")
        appendLine()
        appendLine("Endpoint: `$endpoint`. Prompt: the phone chat's rules + `A2uiPrompt.text`; tool: `show_ui`; up to ${ShowUiToolTries} tries a task.")
        appendLine()
        append(summaryTable(summaries))
        appendLine()
        for (s in summaries) append(modelSection(s, byModel.getValue(s.model)))
    }

    private val ShowUiToolTries = dev.wckdboy.autobot.shared.a2ui.ShowUiTool.MAX_INVALID_PER_TURN
}
