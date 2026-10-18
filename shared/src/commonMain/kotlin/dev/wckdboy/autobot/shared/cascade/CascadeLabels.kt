package dev.wckdboy.autobot.shared.cascade

import dev.wckdboy.autobot.agent.core.session.ReceiptCascade

/** The cascade's words on screen, shared so both apps say the same thing. */
object CascadeLabels {

    /** A reason code in words: "it declined", "it said it doesn't know". */
    fun reason(code: String): String = when (code) {
        CascadeJudge.EMPTY -> "it gave no answer"
        CascadeJudge.FAILED -> "it couldn't finish"
        CascadeJudge.TRUNCATED -> "its answer was cut off"
        CascadeJudge.REFUSAL -> "it declined"
        CascadeJudge.DONT_KNOW_CODE -> "it said it doesn't know"
        CascadeJudge.LOOPING -> "it repeated itself"
        CascadeJudge.HEDGE -> "it wasn't sure"
        CascadeJudge.SHORT -> "the answer is very short"
        CascadeJudge.FRESH -> "this needs fresh information"
        CascadeJudge.SELF_CHECK -> "its self-check scored it low"
        CascadeRoutes.NO_LOCAL_MODEL -> "no on-device model is installed"
        CascadeRoutes.TOO_LONG -> "the chat is too long for the on-device model"
        else -> code
    }

    /** Up to two reasons, joined: "it declined, this needs fresh information". */
    fun reasons(codes: List<String>): String = codes.take(2).joinToString(", ") { reason(it) }

    fun risk(code: String): String = when (code) {
        CascadeRisk.PERSONAL -> "personal details"
        CascadeRisk.HEALTH -> "health"
        CascadeRisk.FINANCE -> "money"
        CascadeRisk.ATTACHMENT -> "an attachment"
        CascadeRisk.MEMORY -> "memory notes"
        else -> code
    }

    fun verdict(verdict: String): String = when (verdict) {
        CascadeVerdict.GOOD.name -> "ok"
        CascadeVerdict.UNSURE.name -> "unsure"
        CascadeVerdict.WEAK.name -> "weak"
        else -> "not judged"
    }

    /** The offer under the phone's answer ("Ask <model>" is the button beside it). */
    fun offer(verdict: String, reasons: List<String>): String {
        val why = reasons(reasons).ifEmpty { null }
        val lead = if (verdict == CascadeVerdict.UNSURE.name) "The phone isn't sure about this answer" else "The phone's answer looks weak"
        return if (why != null) "$lead: $why." else "$lead."
    }

    /** The button of the offer: "Ask DeepSeek V3". */
    fun ask(model: String): String = "Ask ${model.ifBlank { "the cloud" }}"

    /** The small print of the chat's "phone first" choice; [node]: the chat's model is the user's own node. */
    fun sheetNote(model: String, node: Boolean = false): String =
        if (node) {
            "Plain chats only, not agent runs. The phone's answer stays; ${model.ifBlank { "your node" }} answers below it, " +
                "on hardware you own: it never sends these on to a cloud model. The receipt says why."
        } else {
            "Plain chats only, not agent runs. The phone's answer stays; ${model.ifBlank { "the cloud model" }} answers below it, " +
                "through redaction when it is on. The receipt says why."
        }

    /** The notice above the follow-up; [target] `node`: it went to the user's own node. */
    fun followUp(trigger: String?, reasons: List<String>, target: String? = null): String {
        val to = if (target == "node") "phone → my node" else "phone → cloud"
        return when (trigger) {
            "auto" -> listOfNotNull("$to · auto", reasons(reasons).ifEmpty { null }).joinToString(" · ")
            else -> "$to · you asked"
        }
    }

    /** The word the receipt line ends in: "judge ok", "judge weak", "cascaded". */
    fun line(cascade: ReceiptCascade): String = when {
        cascade.role == "cloud" && cascade.action == CascadeAction.DIRECT.name -> "phone skipped"
        cascade.role == "cloud" -> "cascaded"
        else -> "judge ${verdict(cascade.verdict)}"
    }

    /** One or two sentences for the receipt sheet: what the cascade did and why. Never content. */
    fun summary(c: ReceiptCascade): String {
        val why = reasons(c.reasons).ifEmpty { null }
        val weak = if (c.verdict == CascadeVerdict.UNSURE.name) "wasn't sure about it" else "found it weak"
        val sensitive = c.risk.takeIf { it.isNotEmpty() }?.joinToString(", ") { risk(it) }
        if (c.target == "node") return nodeSummary(c, why, weak)
        return when {
            c.role == "cloud" && c.action == CascadeAction.DIRECT.name ->
                "The chat's model answered directly: ${why ?: "the phone couldn't answer"}."
            c.role == "cloud" && c.trigger == "auto" ->
                "Sent to the cloud on its own: the phone's answer looked weak${why?.let { " ($it)" }.orEmpty()} and the prompt is low-risk."
            c.role == "cloud" ->
                "Sent to the cloud because you asked. The judge $weak${why?.let { " ($it)" }.orEmpty()}."
            c.action == CascadeAction.KEPT.name && c.verdict == CascadeVerdict.GOOD.name ->
                "This phone answered. The judge kept its answer; nothing went to the cloud."
            c.action == CascadeAction.KEPT.name ->
                "This phone answered. The judge $weak${why?.let { " ($it)" }.orEmpty()}, but this chat never asks the cloud."
            c.action == CascadeAction.ESCALATED.name ->
                "This phone answered first. The judge found it weak${why?.let { " ($it)" }.orEmpty()}, so the prompt went to the cloud model."
            else ->
                "This phone answered. The judge $weak${why?.let { " ($it)" }.orEmpty()}, so you can ask the cloud model." +
                    if (c.policy == CascadePolicy.AUTO.name && sensitive != null) " Not sent on its own: the prompt involves $sensitive." else ""
        }
    }

    /** [summary] when the follow-up target is the user's own node, where it stays private. */
    private fun nodeSummary(c: ReceiptCascade, why: String?, weak: String): String {
        val because = why?.let { " ($it)" }.orEmpty()
        return when {
            c.role == "cloud" && c.action == CascadeAction.DIRECT.name ->
                "Your node answered directly, and kept it there: ${why ?: "the phone couldn't answer"}."
            c.role == "cloud" && c.trigger == "auto" ->
                "Sent to your node on its own, and kept there: the phone's answer looked weak$because."
            c.role == "cloud" ->
                "Sent to your node because you asked, and kept there. The judge $weak$because."
            c.action == CascadeAction.KEPT.name ->
                "This phone answered. The judge kept its answer; nothing went to your node."
            c.action == CascadeAction.ESCALATED.name ->
                "This phone answered first. The judge found it weak$because, so the prompt went to your node, which keeps it on its own models."
            else ->
                "This phone answered. The judge $weak$because, so you can ask your node."
        }
    }

    /** Short facts for the receipt sheet's tags: policy, judge, risk. */
    fun details(c: ReceiptCascade): List<String> = listOfNotNull(
        "policy · ${CascadePolicy.parse(c.policy)?.title?.lowercase() ?: c.policy.lowercase()}",
        c.judge?.let { j -> listOfNotNull("judge · ${if (j == "self-check") "self-check" else "rules"}", c.judgeMs?.let { "$it ms" }).joinToString(" · ") },
        if (c.risk.isEmpty()) "risk · low" else "risk · ${c.risk.joinToString(", ") { risk(it) }}",
    )
}
