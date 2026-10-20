package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.recipes.NodeRecipes
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCodec
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import kotlinx.coroutines.CancellationException

/**
 * A recipe or scheduled task that ran on the node (Unix seconds). [chat] is the result chat in the
 * Recipes folder once the app collected the run; null before that, and for the node's own schedules.
 */
data class NodeDigestRun(val name: String, val at: Long, val ok: Boolean, val chat: String? = null)

/**
 * A recipe as the digest needs it: which node it runs on, its schedule's id there, and the last run
 * the app collected (Unix seconds; null when none).
 */
data class NodeDigestRecipe(
    val name: String,
    val runsOn: String?,
    val scheduleId: String,
    val lastAt: Long? = null,
    val lastOk: Boolean = true,
    val lastChat: String? = null,
)

/** One line of the digest: the words, and how they are shown (words first; tone only adds color). */
data class NodeDigestLine(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, OK, WARNING, ERROR }
}

/**
 * What a paired node did since the owner last looked ([since], Unix seconds), and what waits for
 * them now: the "While you were away" card. Computed by [NodeDigests.compute] from the node's own
 * logs (spec/audit.md) and the app's recipes.
 */
data class NodeDigest(
    val since: Long,
    /** Requests the node answered (or asked about, or refused). */
    val requests: Int = 0,
    /** Requests that ran on the node's own models. */
    val onNode: Int = 0,
    /** Requests that ran on a cloud model through the node. */
    val inCloud: Int = 0,
    /** Requests the node asked about first or refused. */
    val notRun: Int = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /** Requests that sent anything off the node. */
    val leftTheNode: Int = 0,
    /** Requests whose receipt did not verify against the key pinned at pairing. */
    val unverified: Int = 0,
    /** Actions the agent asked to do. */
    val actions: Int = 0,
    /** Ran without asking: the approval rules, an earlier "always", or an unattended allowlist. */
    val autoAllowed: Int = 0,
    /** Ran because the owner said yes (on the app, the CLI or Telegram). */
    val approved: Int = 0,
    /** Did not run: refused by the rules or denied by the owner. */
    val refused: Int = 0,
    /** Recipes and scheduled tasks that ran, newest first. */
    val runs: List<NodeDigestRun> = emptyList(),
    /** Board tasks waiting for the owner's answer now (whenever they started waiting). */
    val waiting: List<BoardTask> = emptyList(),
    /** Board tasks that failed since [since]. */
    val failedTasks: List<BoardTask> = emptyList(),
    /** Why the agent's part is missing (it is not running); null when it answered. */
    val agentProblem: String? = null,
) {
    val failedRuns: List<NodeDigestRun> get() = runs.filter { !it.ok }

    /** Recipe runs and board tasks that failed. */
    val failures: Int get() = failedRuns.size + failedTasks.size

    val tokens: Long get() = tokensIn + tokensOut

    /** Something happened since [since], or something waits for the owner: the card shows. */
    val hasNews: Boolean get() = requests > 0 || actions > 0 || runs.isNotEmpty() || waiting.isNotEmpty() || failedTasks.isNotEmpty()

    /** The newest result chat of a recipe that ran, to open from the card. */
    val newestChat: String? get() = runs.firstOrNull { it.chat != null }?.chat
}

/** Computing, wording and fetching the digest, the same on Android and iOS. */
object NodeDigests {
    /** With no "last seen" yet (a new pairing, a new install), the card covers the last day. */
    const val FIRST_LOOK_SECONDS = 24L * 60 * 60

    /** Where each platform keeps the "last seen" time of [providerId]'s node (Unix seconds). */
    fun seenKey(providerId: String): String = "node_digest_seen.$providerId"

    /** Where the digest starts: when the owner last looked, or a day back on the first look. */
    fun since(lastSeen: Long?, now: Long): Long = lastSeen?.coerceAtMost(now) ?: (now - FIRST_LOOK_SECONDS)

    /**
     * The digest of [activity] after [since]. [runs] are the recipe and schedule runs ([runs]);
     * requests from [exceptDevice] (this device: the owner was there) are left out.
     */
    fun compute(activity: NodeActivity, runs: List<NodeDigestRun>, since: Long, exceptDevice: String? = null): NodeDigest {
        val requests = activity.requests.filter { it.at > since && (exceptDevice == null || it.device != exceptDevice) }
        val ran = requests.filter { it.action == "run" }
        val actions = activity.actions.filter { it.at > since }
        val (allowed, refused) = actions.partition { it.ran }
        return NodeDigest(
            since = since,
            requests = requests.size,
            onNode = ran.count { it.receipt.tier != "cloud" },
            inCloud = ran.count { it.receipt.tier == "cloud" },
            notRun = requests.size - ran.size,
            tokensIn = requests.sumOf { it.tokensIn },
            tokensOut = requests.sumOf { it.tokensOut },
            leftTheNode = requests.count { it.receipt.egress.isNotEmpty() },
            unverified = requests.count { !it.receipt.verified },
            actions = actions.size,
            autoAllowed = allowed.count { it.by in AUTOMATIC },
            approved = allowed.count { it.by !in AUTOMATIC },
            refused = refused.size,
            runs = runs.filter { it.at > since }.distinct().sortedByDescending { it.at },
            waiting = activity.waiting,
            failedTasks = activity.board.filter { it.status == "failed" && it.updated > since },
            agentProblem = activity.agentProblem,
        )
    }

    /** Who decides without asking the owner (spec/audit.md, the action log's `by`). */
    private val AUTOMATIC = setOf("policy", "grant", "unattended")

    /**
     * What ran on [providerId]'s node: each of its recipes' last collected run (with its chat), a
     * newer run the app has not collected yet, and the node's own schedules (not recipes).
     */
    fun runs(providerId: String, recipes: List<NodeDigestRecipe>, schedules: List<AgentSchedule>): List<NodeDigestRun> {
        val mine = recipes.filter { it.runsOn == providerId }
        val byId = mine.associateBy { it.scheduleId }
        val collected = mine.mapNotNull { r -> r.lastAt?.let { NodeDigestRun(r.name, it, r.lastOk, r.lastChat) } }
        val onNode = schedules.mapNotNull { s ->
            val at = s.lastAt ?: return@mapNotNull null
            val recipe = byId[s.id]
            when {
                // A recipe's run the app has not collected yet: no chat until it is.
                recipe != null -> if (recipe.lastAt == null || at > recipe.lastAt) NodeDigestRun(recipe.name, at, s.lastOk ?: true) else null
                // Another device's recipe on this node, or the owner's own scheduled task.
                else -> NodeDigestRun(name(s), at, s.lastOk ?: true)
            }
        }
        return collected + onNode
    }

    /** A schedule's name for the card: its task's first line, short. */
    private fun name(s: AgentSchedule): String {
        s.name?.takeIf { it.isNotBlank() }?.let { return it }
        val line = s.task.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { s.id }
        return if (line.length <= 40) line else line.take(39).trimEnd() + "…"
    }

    /** [recipe] as the digest needs it. */
    fun recipe(recipe: Recipe): NodeDigestRecipe {
        val last = recipe.state.lastRun
        return NodeDigestRecipe(
            name = recipe.name,
            runsOn = recipe.runsOn,
            scheduleId = NodeRecipes.scheduleId(recipe),
            lastAt = last?.startedAt?.div(1000),
            lastOk = last == null || last.status == RecipeRunStatus.DONE,
            lastChat = last?.sessionId,
        )
    }

    /** A recipe stored as JSON ([RecipeCodec], as iOS keeps them); null when it cannot be read. */
    fun recipeJson(json: String): NodeDigestRecipe? = RecipeCodec.decode(json)?.let(::recipe)

    /**
     * The digest of [agent]'s node after [since], for [providerId] and its [recipes]: the node's
     * requests (receipts checked against [receiptKey]), the agent's actions, its board and schedules.
     * The agent's part is empty, with [NodeDigest.agentProblem], when it is not running.
     */
    suspend fun fetch(
        agent: NodeAgent,
        crypto: NodeCrypto,
        node: String,
        receiptKey: String?,
        providerId: String,
        since: Long,
        recipes: List<NodeDigestRecipe>,
        exceptDevice: String? = null,
    ): NodeDigest {
        val requests = agent.requests(crypto, node, receiptKey, limit = MAX_ENTRIES, since = since)
        val activity: NodeActivity
        var schedules = emptyList<AgentSchedule>()
        activity = try {
            val board = orEmptyOn404 { agent.board() }
            schedules = orEmptyOn404 { agent.schedules() }
            NodeActivity(requests, agent.actions(limit = MAX_ENTRIES, since = since), emptyList(), emptyList(), board = board)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeAgentException) {
            if (e.status == 0) throw e
            NodeActivity(requests, emptyList(), emptyList(), emptyList(), agentProblem = e.message)
        }
        return compute(activity, runs(providerId, recipes, schedules), since, exceptDevice)
    }

    private suspend fun <T> orEmptyOn404(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: NodeAgentException) {
        if (e.status == 404) emptyList() else throw e
    }

    /** The most entries the node gives at once (spec/audit.md). */
    private const val MAX_ENTRIES = 500

    /**
     * A digest for screenshots and UI work (debug builds' demo data): a quiet night on the node,
     * with a question waiting; [failedReceipt] adds one receipt that did not verify.
     */
    fun sample(now: Long, failedReceipt: Boolean = false): NodeDigest = NodeDigest(
        since = now - 9 * 60 * 60,
        requests = 12,
        onNode = 12,
        tokensIn = 14_820,
        tokensOut = 3_410,
        unverified = if (failedReceipt) 1 else 0,
        actions = 3,
        autoAllowed = 2,
        approved = 1,
        runs = listOf(NodeDigestRun("Morning brief", now - 95 * 60, ok = true, chat = null)),
        waiting = listOf(
            BoardTask("b2", "p1", "writer", "Draft the release notes", "blocked", listOf("b1"), null, "Markdown or plain text?", now - 40 * 60),
            BoardTask("b4", "p1", "researcher", "Check the mirrors", "blocked", emptyList(), null, "Include the EU mirror?", now - 30 * 60),
        ),
    )
}

/** Words for the digest, the same on Android and iOS. */
object NodeDigestWords {
    const val TITLE = "While you were away"

    /** "12 requests, all on your node · 18.2k tokens". */
    fun requests(d: NodeDigest): String? {
        if (d.requests == 0) return null
        val where = when {
            d.onNode + d.inCloud == 0 -> "none ran"
            d.inCloud == 0 -> "all on your node"
            d.onNode == 0 -> "all in the cloud"
            else -> "${d.onNode} on your node, ${d.inCloud} in the cloud"
        }
        val runs = d.onNode + d.inCloud
        val head = if (d.inCloud > 0 && d.onNode > 0) "${plural(d.requests, "request")}: $where" else "${plural(d.requests, "request")}, $where"
        return buildString {
            append(head)
            if (d.notRun > 0 && runs > 0) append(", ${d.notRun} not run")
            if (d.tokens > 0) append(" · ${tokens(d.tokens)} tokens")
        }
    }

    /** "3 actions: 2 by the rules, 1 approved by you". */
    fun actions(d: NodeDigest): String? {
        if (d.actions == 0) return null
        val parts = listOfNotNull(
            d.autoAllowed.takeIf { it > 0 }?.let { "$it by the rules" },
            d.approved.takeIf { it > 0 }?.let { "$it approved by you" },
            d.refused.takeIf { it > 0 }?.let { "$it refused" },
        )
        return if (parts.size == 1 && d.autoAllowed == d.actions) {
            "${plural(d.actions, "action")}, all allowed by the rules"
        } else {
            "${plural(d.actions, "action")}: ${parts.joinToString(", ")}"
        }
    }

    /** "Morning brief ran", "Morning brief and Backups ran", "Morning brief, Backups and 2 more ran". */
    fun ran(d: NodeDigest): String? = names(d.runs.filter { it.ok }.map { it.name })?.let { "$it ran" }

    /** "Inbox triage failed · 1 board task failed". */
    fun failed(d: NodeDigest): String? {
        val parts = listOfNotNull(
            names(d.failedRuns.map { it.name })?.let { "$it failed" },
            d.failedTasks.size.takeIf { it > 0 }?.let { "${plural(it, "board task")} failed" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** "2 receipts failed to verify ✗", or "All 12 receipts verified ✓". */
    fun receipts(d: NodeDigest): NodeDigestLine? = when {
        d.requests == 0 -> null
        d.unverified > 0 -> NodeDigestLine(
            if (d.unverified == 1) "1 receipt failed to verify ✗" else "${d.unverified} receipts failed to verify ✗",
            NodeDigestLine.Tone.ERROR,
        )
        d.requests == 1 -> NodeDigestLine("Its receipt verified ✓", NodeDigestLine.Tone.OK)
        else -> NodeDigestLine("All ${d.requests} receipts verified ✓", NodeDigestLine.Tone.OK)
    }

    /**
     * The card's lines, in order: a receipt that failed to verify first (it matters most), then
     * what happened, then what failed, then the quiet "verified" line.
     */
    fun lines(d: NodeDigest): List<NodeDigestLine> {
        val receipts = receipts(d)
        return buildList {
            if (receipts?.tone == NodeDigestLine.Tone.ERROR) add(receipts)
            listOfNotNull(requests(d), actions(d), ran(d)).forEach { add(NodeDigestLine(it, NodeDigestLine.Tone.PLAIN)) }
            failed(d)?.let { add(NodeDigestLine("$it ✗", NodeDigestLine.Tone.ERROR)) }
            if (d.agentProblem != null) add(NodeDigestLine("The agent was not running there !", NodeDigestLine.Tone.WARNING))
            if (receipts?.tone == NodeDigestLine.Tone.OK) add(receipts)
        }
    }

    /** What needs the owner first, as the card's primary action: "2 questions waiting"; null when nothing does. */
    fun needsYou(d: NodeDigest): String? = when (val n = d.waiting.size) {
        0 -> null
        1 -> "1 question waiting"
        else -> "$n questions waiting"
    }

    /** The whole card for a screen reader: "While you were away on studio-mac. 12 requests, …". */
    fun spoken(d: NodeDigest, node: String): String = buildString {
        append("$TITLE on $node.")
        lines(d).forEach { append(' ').append(it.text.removeSuffix(" ✓").removeSuffix(" ✗").removeSuffix(" !")).append('.') }
        needsYou(d)?.let { append(' ').append(it).append('.') }
    }

    /** 812, 18.2k, 1.4M. */
    fun tokens(n: Long): String = when {
        n < 1_000 -> "$n"
        n < 1_000_000 -> tenths(n, 1_000) + "k"
        else -> tenths(n, 1_000_000) + "M"
    }

    private fun tenths(n: Long, unit: Long): String {
        val t = (n * 10 + unit / 2) / unit
        return if (t % 10 == 0L || t >= 1000) "${t / 10}" else "${t / 10}.${t % 10}"
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun names(list: List<String>): String? {
        val unique = list.distinct()
        return when (unique.size) {
            0 -> null
            1 -> unique[0]
            2 -> "${unique[0]} and ${unique[1]}"
            3 -> "${unique[0]}, ${unique[1]} and ${unique[2]}"
            else -> "${unique[0]}, ${unique[1]} and ${unique.size - 2} more"
        }
    }
}
