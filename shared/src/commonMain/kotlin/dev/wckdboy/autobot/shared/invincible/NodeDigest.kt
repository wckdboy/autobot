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

/**
 * What one bot did since the last look (bots mode, a node with more than one bot): the actions it
 * asked to do, how many did not run, and the approvals waiting for it now.
 */
data class NodeDigestBot(val name: String, val actions: Int = 0, val refused: Int = 0, val approvals: Int = 0)

/** One line of the digest: the words, and how they are shown (words first; tone only adds color). */
data class NodeDigestLine(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, OK, WARNING, ERROR }
}

/**
 * What a paired node did since the owner last looked ([since], Unix seconds), and what waits for
 * them now: the "While you were away" card. Computed by [NodeDigests.compute] from the node's own
 * logs (spec/audit.md) and the app's recipes. The requests are this device's own (from Invincible
 * 0.0.22 on, a node gives a device no one else's); what the agent did comes from its own logs.
 */
data class NodeDigest(
    val since: Long,
    /** This device's requests the node answered (or asked about, or refused). */
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
    /** Approvals the agent is waiting on now, oldest first; null when the node cannot list them (older nodes). */
    val approvals: List<PendingApproval>? = null,
    /** Board tasks that failed since [since]. */
    val failedTasks: List<BoardTask> = emptyList(),
    /** Why the agent's part is missing (it is not running, or not granted to this device); null when it answered. */
    val agentProblem: String? = null,
    /** Why this device's requests are missing (the node did not give it its request log); null when they came. */
    val auditProblem: String? = null,
    /**
     * Per bot, for a node with more than one (bots mode), the default bot first; empty on a node
     * with one bot or none, where the card reads as before.
     */
    val bots: List<NodeDigestBot> = emptyList(),
) {
    val failedRuns: List<NodeDigestRun> get() = runs.filter { !it.ok }

    /** Recipe runs and board tasks that failed. */
    val failures: Int get() = failedRuns.size + failedTasks.size

    val tokens: Long get() = tokensIn + tokensOut

    /**
     * Something happened since [since] (a question that started waiting counts; one the owner saw
     * already does not bring the card back on its own, though the card counts it when it shows).
     * This device's requests do not either: the owner made them. A receipt among them that failed
     * to verify does: it is the card's first line.
     */
    val hasNews: Boolean get() = unverified > 0 || actions > 0 || runs.isNotEmpty() || waiting.any { it.updated > since } ||
        approvals.orEmpty().any { it.askedAt > since } || failedTasks.isNotEmpty()

    /** Approvals waiting now (none when the node cannot say). */
    val approvalsWaiting: Int get() = approvals?.size ?: 0

    /**
     * Where "what needs you" opens: approvals first (they hold a turn), then the board's questions.
     * With approvals, the conversation they wait in when they are all in one ([approvalSession]).
     */
    val opensBoard: Boolean get() = approvalsWaiting == 0 && waiting.isNotEmpty()

    /** The one conversation every waiting approval is in (the oldest one's); null when they span several, or none waits. */
    val approvalSession: String? get() = approvals.orEmpty().map { it.session }.distinct().singleOrNull()?.takeIf { it.isNotEmpty() }

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
     * The digest of [activity] after [since]. [runs] are the recipe and schedule runs ([runs]).
     * Only requests from [device] (this device) count: a node before 0.0.22 gives every caller's,
     * a later one this device's alone. Null counts them all.
     */
    fun compute(activity: NodeActivity, runs: List<NodeDigestRun>, since: Long, device: String? = null): NodeDigest {
        val requests = activity.requests.filter { it.at > since && (device == null || it.device == device) }
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

    /**
     * [digest] split per bot when the node has more than one ([names]): each bot's actions after
     * the digest's start (from [actions]) and the approvals waiting for it. A node with one bot or
     * none gets [digest] as it is.
     */
    fun byBot(digest: NodeDigest, names: List<String>, actions: List<AgentActionEntry>): NodeDigest {
        if (names.distinct().size < 2) return digest.copy(bots = emptyList())
        val after = actions.filter { it.at > digest.since }
        val waiting = digest.approvals.orEmpty()
        val bots = NodeBots.ordered(names.distinct().map { NodeBot(it) }).map { bot ->
            val mine = after.filter { NodeBots.of(it.bot) == bot.name }
            NodeDigestBot(bot.name, mine.size, mine.count { !it.ran }, waiting.count { NodeBots.of(it.bot) == bot.name })
        }
        return digest.copy(bots = bots)
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
     * The digest of [agent]'s node after [since], for [providerId] and its [recipes]: [device]'s
     * requests (receipts checked against [receiptKey]), the agent's actions, its board and schedules.
     * The agent's part is empty, with [NodeDigest.agentProblem], when it is not running or not
     * granted to this device; the requests are, with [NodeDigest.auditProblem], when the node does
     * not give this device its request log.
     */
    suspend fun fetch(
        agent: NodeAgent,
        crypto: NodeCrypto,
        node: String,
        receiptKey: String?,
        providerId: String,
        since: Long,
        recipes: List<NodeDigestRecipe>,
        device: String? = null,
    ): NodeDigest {
        var auditProblem: String? = null
        val requests = try {
            agent.requests(crypto, node, receiptKey, limit = MAX_ENTRIES, since = since)
        } catch (e: NodeAgentException) {
            // A phone granted the agent without `audit` still gets what the agent did.
            if (e.status == 0) throw e
            auditProblem = e.message
            emptyList()
        }
        val activity: NodeActivity
        var schedules = emptyList<AgentSchedule>()
        var approvals: List<PendingApproval>? = null
        var bots = emptyList<String>()
        activity = try {
            val board = orEmptyOn404 { agent.board() }
            schedules = orEmptyOn404 { agent.schedules() }
            approvals = agent.approvals()
            // Bots mode: the card says which bot did what (an older node has none).
            bots = try {
                agent.bots().orEmpty().map { it.name }
            } catch (e: NodeAgentException) {
                // The bots list failing is no reason to lose the rest of the card.
                if (e.status == 0) throw e
                emptyList()
            }
            NodeActivity(requests, agent.actions(limit = MAX_ENTRIES, since = since), emptyList(), emptyList(), board = board)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeAgentException) {
            if (e.status == 0) throw e
            NodeActivity(requests, emptyList(), emptyList(), emptyList(), agentProblem = e.message)
        }
        val digest = compute(activity, runs(providerId, recipes, schedules), since, device)
            .copy(approvals = approvals?.sortedBy { it.askedAt }, auditProblem = auditProblem)
        return byBot(digest, bots, activity.actions)
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
    fun sample(now: Long, failedReceipt: Boolean = false): NodeDigest = sampleDigest(now, failedReceipt)

    /**
     * [sample] on a node with the three sample bots ([NodeBots.sample]): the card says which bot
     * did what, and the approvals waiting are the ops bot's.
     */
    fun sampleWithBots(now: Long): NodeDigest {
        val d = sampleDigest(now, failedReceipt = false)
        val approvals = d.approvals.orEmpty().map { it.copy(bot = "ops") }
        val actions = listOf(
            AgentActionEntry(now - 3 * 3_600, "app-studio-phone", "command", "run: df -h", ran = true, by = "policy"),
            AgentActionEntry(now - 2 * 3_600, "tg-ops-4411", "command", "run: systemctl status nginx", ran = true, by = "policy", bot = "ops"),
            AgentActionEntry(now - 50 * 60, "tg-ops-4411", "command", "run: systemctl restart nginx", ran = true, by = "telegram", bot = "ops"),
            AgentActionEntry(now - 30 * 60, "email-support-118", "write", "write drafts/reply-118.md", ran = true, by = "policy", bot = "support"),
        )
        return byBot(d.copy(approvals = approvals, actions = 4, autoAllowed = 3, approved = 1), NodeBots.sample(now).map { it.name }, actions)
    }

    private fun sampleDigest(now: Long, failedReceipt: Boolean): NodeDigest = NodeDigest(
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
        ),
        approvals = listOf(
            PendingApproval("a1", "cron-backups", "run: rsync -a ~/photos /mnt/backup", AgentAction.Command("rsync -a ~/photos /mnt/backup"), askedAt = now - 50 * 60),
            PendingApproval("a2", "cron-backups", "write 2 KB to /etc/fstab", AgentAction.Write("/etc/fstab", 2_048, inside = false), askedAt = now - 20 * 60),
        ),
    )
}

/** Words for the digest, the same on Android and iOS. */
object NodeDigestWords {
    const val TITLE = "While you were away"

    /** "12 requests from this phone, all on your node · 18.2k tokens". */
    fun requests(d: NodeDigest): String? {
        if (d.requests == 0) return null
        val where = when {
            d.onNode + d.inCloud == 0 -> "none ran"
            d.inCloud == 0 -> "all on your node"
            d.onNode == 0 -> "all in the cloud"
            else -> "${d.onNode} on your node, ${d.inCloud} in the cloud"
        }
        val runs = d.onNode + d.inCloud
        val asked = "${plural(d.requests, "request")} from this phone"
        val head = if (d.inCloud > 0 && d.onNode > 0) "$asked: $where" else "$asked, $where"
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

    /**
     * On a node with several bots, a line for each that did something or waits for the owner:
     * "ops: 2 actions, 2 waiting", "support: 1 action". At most [MAX_BOT_LINES], then "and 2 more
     * bots"; empty with one bot or none.
     */
    fun bots(d: NodeDigest): List<String> {
        if (d.bots.size < 2) return emptyList()
        val busy = d.bots.filter { it.actions > 0 || it.approvals > 0 }
        val lines = busy.take(MAX_BOT_LINES).map { b ->
            val parts = listOfNotNull(
                b.actions.takeIf { it > 0 }?.let { plural(it, "action") + (if (b.refused > 0) " (${b.refused} refused)" else "") },
                b.approvals.takeIf { it > 0 }?.let { "$it waiting" },
            )
            "${b.name}: ${parts.joinToString(", ")}"
        }
        val more = busy.size - MAX_BOT_LINES
        return if (more > 0) lines + "and ${plural(more, "more bot")}" else lines
    }

    private const val MAX_BOT_LINES = 3

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
            // Several bots: one short line each instead of the one actions line.
            val perBot = bots(d)
            listOfNotNull(requests(d), actions(d).takeIf { perBot.isEmpty() }).forEach { add(NodeDigestLine(it, NodeDigestLine.Tone.PLAIN)) }
            perBot.forEach { add(NodeDigestLine(it, NodeDigestLine.Tone.PLAIN)) }
            ran(d)?.let { add(NodeDigestLine(it, NodeDigestLine.Tone.PLAIN)) }
            failed(d)?.let { add(NodeDigestLine("$it ✗", NodeDigestLine.Tone.ERROR)) }
            if (d.agentProblem != null) add(NodeDigestLine("The agent was not running there !", NodeDigestLine.Tone.WARNING))
            if (d.auditProblem != null) add(NodeDigestLine("This phone's requests could not be read there !", NodeDigestLine.Tone.WARNING))
            if (receipts?.tone == NodeDigestLine.Tone.OK) add(receipts)
        }
    }

    /**
     * What needs the owner first, as the card's primary action: "2 approvals waiting",
     * "1 question waiting", or both, "2 approvals, 1 question waiting"; null when nothing does.
     */
    fun needsYou(d: NodeDigest): String? {
        val approvals = d.approvalsWaiting.takeIf { it > 0 }?.let { plural(it, "approval") }
        val questions = d.waiting.size.takeIf { it > 0 }?.let { plural(it, "question") }
        return listOfNotNull(approvals, questions).takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = " waiting")
    }

    /** The whole card for a screen reader: "While you were away on studio-mac. 12 requests from this phone, …". */
    fun spoken(d: NodeDigest, node: String): String = buildString {
        append("$TITLE on $node.")
        lines(d).forEach { append(' ').append(unmarked(it.text)).append('.') }
        needsYou(d)?.let { append(' ').append(it).append('.') }
    }

    /** A line without its state glyph, for a screen reader: the words say it. */
    fun unmarked(text: String): String = text.removeSuffix(" ✓").removeSuffix(" ✗").removeSuffix(" !")

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
