package dev.wckdboy.autobot.shared.cascade

/**
 * The local-first cascade policy of a chat (ROADMAP Phase 2.5, item 3): whether "This phone"
 * answers before the chat's model (a cloud one, or the user's own node), and what happens when the
 * judge finds the phone's answer wanting.
 *
 * On a chat whose model is the user's own node, the node takes the cloud model's place and the
 * rules differ ([CascadeRoutes]), so the chip and the line are [nodeChip] and [nodeDetail] there.
 */
enum class CascadePolicy(
    /** The chip on the chat bar (micro label, upper-cased by the platforms). */
    val chip: String,
    /** The option's name in Settings and in the chip's menu. */
    val title: String,
    /** One line under the option. */
    val detail: String,
    /** [chip] on a chat with the user's own node. */
    val nodeChip: String = chip,
    /** [detail] on a chat with the user's own node. */
    val nodeDetail: String = detail,
) {
    /** No cascade: the chat's own model answers, as before. The default. */
    OFF("phone first off", "Off", "The chat's model answers, as before."),

    /** The phone answers; when the judge is not convinced, an "Ask" button offers the chat's model (cloud or node). */
    ASK(
        "phone first · ask", "Ask", "This phone answers first. When its answer looks weak, you can ask the cloud model.",
        nodeDetail = "This phone answers first. When its answer looks weak, you can ask your node.",
    ),

    /**
     * The phone answers; a weak answer to a low-risk prompt goes to the cloud model on its own.
     * With a node, a weak answer goes there on its own, a sensitive prompt included; an answer the
     * judge is unsure about is offered; a turn the phone cannot take (no on-device model, a chat
     * too long for it) goes to the node directly. All of these go marked private.
     */
    AUTO(
        "phone first · auto", "Auto", "This phone answers first. A weak answer goes to the cloud on its own, unless the prompt is personal: then it asks.",
        nodeDetail = "This phone answers first. A weak answer goes to your node on its own, personal prompts included. " +
            "If the phone isn't sure, you can ask your node; if it can't answer, your node does. All of it goes marked private.",
    ),

    /**
     * The phone answers and nothing goes to the cloud, even when its answer is weak. With a node it
     * works exactly as [AUTO] does there (a turn the phone cannot take goes to the node instead of
     * being refused), only the judge skips the self-check: rules alone decide.
     */
    NEVER(
        "phone only", "Never", "This phone answers. Nothing goes to the cloud.",
        nodeChip = "phone · my node",
        nodeDetail = "Same as Auto, but the phone's answer is judged by rules alone, without a self-check.",
    ),
    ;

    /** Whether the phone answers first under this policy. */
    val localFirst: Boolean get() = this != OFF

    /**
     * Whether "Ask" under an offered answer may send its prompt to the chat's model under this
     * policy: to the user's own node ([node]) always, to any other model unless "Never" (nothing
     * goes to the cloud). An offer made on a node chat outlives a switch to a cloud model.
     */
    fun allowsAsk(node: Boolean): Boolean = node || this != NEVER

    companion object {
        /** Settings and chats start with the cascade off: nothing changes until the user opts in. */
        val DEFAULT = OFF

        /** A stored value; unknown or damaged values are null (callers fall back to the default). */
        fun parse(value: String?): CascadePolicy? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }

        /** The policy a chat uses: its own choice, else the app's default. */
        fun resolve(chat: String?, default: CascadePolicy): CascadePolicy = parse(chat) ?: default

        /**
         * What a chat stores when the user picks [chosen]: null (follow Settings) when that is
         * what Settings says anyway, else the policy's name.
         */
        fun override(chosen: CascadePolicy, default: CascadePolicy): String? = if (chosen == default) null else chosen.name
    }
}

/** How a turn is routed under the cascade, decided before its first step ([CascadeRoutes.route]). */
sealed interface CascadeRoute {
    /** The cascade is not in play: agent runs, on-device or loopback chats, the policy is off. */
    data object NotApplicable : CascadeRoute

    /** The phone answers first. */
    data object Local : CascadeRoute

    /** The chat's model (a cloud one, or the user's node) answers directly, because the phone cannot ([reason]: `no-local-model`, `too-long`). */
    data class Direct(val reason: String) : CascadeRoute

    /** The turn does not run: "Never" on a cloud chat with no on-device model ([message] says so). */
    data class Blocked(val message: String) : CascadeRoute
}

/** The routing and escalation rules of the cascade, pure so they are unit tested. */
object CascadeRoutes {

    /** Above this share of the on-device model's context window, the phone does not try. */
    const val LOCAL_WINDOW_SHARE = 0.75

    const val NO_LOCAL_MODEL = "no-local-model"
    const val TOO_LONG = "too-long"

    const val BLOCKED_MESSAGE =
        "Phone only: no on-device chat model is installed. Download one under Models, or change \"phone first\" for this chat."

    /**
     * @param policy the chat's policy ([CascadePolicy.resolve]).
     * @param cloudRoute whether the chat's model is a cloud one (not "This phone", not loopback, not the user's node).
     * @param toolsEnabled agent runs (tools on) never cascade in this version: plain chat only.
     * @param hasLocalModel whether an on-device chat model is installed.
     * @param promptTokens the request's estimated size; [localWindow] the on-device model's window (null: unknown).
     * @param owned whether the chat's model is the user's own paired node: the cascade applies there
     *   too, and "Never" does not keep a turn from it.
     */
    fun route(
        policy: CascadePolicy,
        cloudRoute: Boolean,
        toolsEnabled: Boolean,
        hasLocalModel: Boolean,
        promptTokens: Int = 0,
        localWindow: Int? = null,
        owned: Boolean = false,
    ): CascadeRoute {
        // The user's own node is not the cloud: "Never" (nothing to the cloud) does not keep a turn
        // from it, so with a node the phone steps aside when it cannot answer. Such a turn goes to
        // the node marked private (LocalFirstCascade), so the node keeps it off cloud models.
        val cloudRuledOut = policy == CascadePolicy.NEVER && !owned
        return when {
            !policy.localFirst || !(cloudRoute || owned) || toolsEnabled -> CascadeRoute.NotApplicable
            !hasLocalModel -> if (cloudRuledOut) CascadeRoute.Blocked(BLOCKED_MESSAGE) else CascadeRoute.Direct(NO_LOCAL_MODEL)
            // "Never" still lets the phone try a long chat: the user ruled the cloud out.
            !cloudRuledOut && localWindow != null && promptTokens > localWindow * LOCAL_WINDOW_SHARE -> CascadeRoute.Direct(TOO_LONG)
            else -> CascadeRoute.Local
        }
    }

    /**
     * What follows a judged answer: keep it, offer the chat's model, or escalate to it. To a cloud
     * model, "Auto" escalates only a [CascadeVerdict.WEAK] answer to a prompt the risk classifier
     * calls low-risk; a sensitive prompt, or an answer the judge is merely unsure about, is offered
     * instead. With the user's own node as the chat's model ([owned]), see below.
     */
    fun action(policy: CascadePolicy, verdict: CascadeVerdict, risk: CascadeRisk, owned: Boolean = false): CascadeAction = when {
        verdict == CascadeVerdict.GOOD -> CascadeAction.KEPT
        policy == CascadePolicy.OFF -> CascadeAction.KEPT
        // To the user's own node (marked private, so it keeps the prompt off cloud models): "Never"
        // and "Auto" send a weak answer on, a sensitive prompt included; "Ask" offers.
        owned -> if (policy != CascadePolicy.ASK && verdict == CascadeVerdict.WEAK) CascadeAction.ESCALATED else CascadeAction.OFFERED
        policy == CascadePolicy.NEVER -> CascadeAction.KEPT
        policy == CascadePolicy.AUTO && verdict == CascadeVerdict.WEAK && !risk.sensitive -> CascadeAction.ESCALATED
        else -> CascadeAction.OFFERED
    }
}

/** What the cascade did with the phone's answer (stored in receipts by name). */
enum class CascadeAction { KEPT, OFFERED, ESCALATED, DIRECT }
