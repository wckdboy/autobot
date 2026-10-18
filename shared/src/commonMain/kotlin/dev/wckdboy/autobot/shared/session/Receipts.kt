package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.Hooks
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.core.network.AuditEntry
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ModelDataPolicy
import dev.wckdboy.autobot.providers.remote.SystemModels
import dev.wckdboy.autobot.shared.redaction.PiiKind
import dev.wckdboy.autobot.shared.cascade.CascadeLabels
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.shared.usage.UsageCost
import dev.wckdboy.autobot.shared.usage.UsageFormat
import dev.wckdboy.autobot.shared.usage.UsageMeter
import dev.wckdboy.autobot.shared.usage.UsageProvider
import dev.wckdboy.autobot.shared.usage.TurnUsage
import dev.wckdboy.autobot.shared.usage.UsagePlan
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

/** What the redaction gateway hid in a turn: [count] distinct values of one [kind] (`PERSON`), [label] "2 names". */
@Serializable
data class ReceiptRedaction(val kind: String, val count: Int, val label: String)

/**
 * The local-first cascade in a receipt, in words ([dev.wckdboy.autobot.shared.cascade.CascadeLabels]):
 * [role] `local` (this phone answered first) or `cloud` (the follow-up), [summary] what happened
 * and why, [details] short facts (policy, judge, risk). Codes as in
 * [dev.wckdboy.autobot.agent.core.session.ReceiptCascade]; never content.
 */
@Serializable
data class ReceiptCascadeInfo(
    val role: String,
    val policy: String,
    val verdict: String,
    val action: String,
    val summary: String,
    val details: List<String> = emptyList(),
    /** Where a follow-up went: `cloud`, or `my node` (the user's own, kept private there). */
    val followUpTarget: String = "cloud",
)

/** A tool that ran in a turn; [network] tools (`web_fetch`, `web_search`, MCP) reach other hosts. */
@Serializable
data class ReceiptTool(val name: String, val network: Boolean)

/**
 * A turn's privacy receipt as the chat screens show it: [line] under the answer ("on device",
 * "2 hosts · 14 KB out · no retention"), the rest in the receipt sheet.
 *
 * @property onDevice nothing left the phone: traffic was tracked and no request reached a host
 *   off the device (loopback and refused requests stay on it).
 * @property tracked whether the host tracked the turn's traffic at all.
 * @property hosts every host contacted (or refused), with bytes and route.
 * @property routes the routes used off the device (`direct`, `tor`, `socks5`).
 * @property redacted whether the redaction gateway ran for this turn's cloud requests;
 *   [redactions] is what it hid (empty: nothing found).
 */
@Serializable
data class Receipt(
    val turn: Int,
    val line: String,
    val onDevice: Boolean,
    val tracked: Boolean,
    val hosts: List<ReceiptHost>,
    val bytesSent: Long,
    val bytesReceived: Long,
    val routes: List<String>,
    val models: List<ReceiptModel>,
    val tools: List<ReceiptTool>,
    val fallbacks: List<String>,
    val redacted: Boolean = false,
    val redactions: List<ReceiptRedaction> = emptyList(),
    /** The turn's tokens and cost in one line (`12k in · 410 out · 8.1k cached · $0.0042`); null when not counted. */
    val usage: String? = null,
    /** The same per model, in [models] order. */
    val usageRows: List<ReceiptUsageRow> = emptyList(),
    /** What the local-first cascade did in this turn; null when it was not in play. */
    val cascade: ReceiptCascadeInfo? = null,
    /** What paired nodes signed about the requests they answered (Invincible), one per node. */
    val nodes: List<ReceiptNodeInfo> = emptyList(),
)

/**
 * A paired node's part of a turn, from its signed receipts: [verified] when every one of them
 * checked out with the pinned key; [egress] what left the node (empty: nothing); [models] that ran
 * there (`qwen3.5-4b`, or a cloud model with tier `cloud`); [missing] receipts it could not give.
 */
@Serializable
data class ReceiptNodeInfo(
    val node: String,
    val requests: Int,
    val verified: Boolean,
    val models: List<String>,
    val cloud: Boolean,
    val egress: List<ReceiptHost>,
    val redactions: List<ReceiptRedaction>,
    val missing: Int,
)

/** One model's line of a receipt's usage. */
@Serializable
data class ReceiptUsageRow(val model: String, val line: String)

/** What a host knows about a provider for a receipt. */
class ReceiptProvider(val name: String, val onDevice: Boolean, val presetId: String?, val privacyNote: String?)

/**
 * Privacy receipts: built on the device from the session log and the audit log, stored in the
 * session log (`turn/receipt`), never sent anywhere.
 */
object Receipts {

    /** Tools that reach other hosts on their own. */
    fun isNetworkTool(name: String): Boolean = name == "web_fetch" || name == "web_search" || name.startsWith("mcp__")

    /** Short receipt wording of a data [policy]. */
    fun policyLabel(policy: ModelDataPolicy): String = when (policy) {
        ModelDataPolicy.NoRetention -> "no retention"
        is ModelDataPolicy.Retention -> "kept ${policy.days} days"
        ModelDataPolicy.Trains -> "trains on prompts"
    }

    /** A request that stayed on the phone: loopback, or refused before any I/O. */
    private fun ReceiptHost.offPhone(): Boolean = route != "loopback" && requests > blocked

    /**
     * The turn's audit [entries] grouped by host and route (first contact first). A failed request
     * still counts: it may have sent its body before failing.
     */
    fun network(entries: List<AuditEntry>): List<ReceiptHost> {
        val groups = LinkedHashMap<Pair<String, String>, MutableList<AuditEntry>>()
        entries.forEach { groups.getOrPut(it.host to it.route) { mutableListOf() } += it }
        return groups.map { (key, list) ->
            ReceiptHost(
                host = key.first,
                route = key.second,
                requests = list.sumOf { it.count },
                bytesSent = list.filterNot { it.blocked }.sumOf { it.bytesSent ?: 0 },
                bytesReceived = list.sumOf { it.bytesReceived ?: 0 },
                blocked = list.filter { it.blocked }.sumOf { it.count },
                tags = list.mapNotNull { it.tag }.distinct(),
            )
        }
    }

    /** [base] (provider id and model) with the provider's name, on-device flag and data policy. */
    fun describe(base: ReceiptModel, provider: ReceiptProvider?): ReceiptModel {
        if (provider == null) return base
        val onDevice = provider.onDevice || SystemModels.isSystem(base.model)
        if (onDevice) return base.copy(name = provider.name, onDevice = true, policy = "on device", policyNote = "Runs on this phone.")
        val policy = ModelDataPolicies.policy(provider.presetId, base.model)
        return base.copy(
            name = provider.name,
            policy = policy?.let(::policyLabel),
            policyNote = ModelDataPolicies.caption(provider.presetId, base.model) ?: provider.privacyNote,
        )
    }

    /** The receipt [event] as the chat screens show it. */
    fun summarize(event: SessionEvent.TurnReceipt): Receipt {
        val hosts = event.network.orEmpty()
        val off = hosts.filter { it.offPhone() }
        val tracked = event.network != null
        val remote = event.models.filterNot { it.onDevice }
        val onDevice = tracked && off.isEmpty()
        val sent = off.sumOf { it.bytesSent }
        val routes = off.map { it.route }.distinct()
        val redactions = redactions(event.redacted)
        val hidden = redactions.sumOf { it.count }
        val cascadeWord = event.cascade?.let(CascadeLabels::line)
        val nodes = nodeInfo(event.nodes.orEmpty())
        val nodeWord = nodes.joinToString(" · ") { n ->
            when {
                !n.verified -> "${n.node} unverified"
                n.egress.isNotEmpty() -> "${n.node} → ${n.egress.size} ${if (n.egress.size == 1) "host" else "hosts"}"
                else -> "kept on ${n.node} ✓"
            }
        }.ifEmpty { null }
        val line = when {
            onDevice && hosts.any { it.blocked > 0 } -> listOfNotNull("on device · ${hosts.sumOf { it.blocked }} blocked", nodeWord, cascadeWord).joinToString(" · ")
            // A node reached over loopback (a tunnel) still ran the turn elsewhere: say where.
            onDevice -> listOfNotNull("on device".takeIf { nodeWord == null }, nodeWord, cascadeWord).joinToString(" · ")
            else -> listOfNotNull(
                if (tracked) "${off.size} ${if (off.size == 1) "host" else "hosts"}" else null,
                if (tracked) "${formatBytes(sent)} out" else null,
                routes.filter { it != "direct" }.joinToString(" + ").ifEmpty { null },
                policyWord(remote),
                if (hidden > 0) "$hidden redacted" else null,
                nodeWord,
                cascadeWord,
            ).joinToString(" · ").ifEmpty { "receipt" }
        }
        return Receipt(
            turn = event.turn,
            line = line,
            onDevice = onDevice,
            tracked = tracked,
            hosts = hosts,
            bytesSent = sent,
            bytesReceived = off.sumOf { it.bytesReceived },
            routes = routes,
            models = event.models,
            tools = event.tools.map { ReceiptTool(it, isNetworkTool(it)) },
            fallbacks = event.fallbacks,
            redacted = event.redacted != null,
            redactions = redactions,
            usage = usageLine(event.models),
            usageRows = event.models.filter { it.usage != null }.map { m ->
                ReceiptUsageRow(m.model, usageLine(listOf(m)).orEmpty())
            },
            cascade = event.cascade?.let { c ->
                ReceiptCascadeInfo(
                    c.role, c.policy, c.verdict, c.action, CascadeLabels.summary(c), CascadeLabels.details(c),
                    followUpTarget = if (c.target == "node") "my node" else "cloud",
                )
            },
            nodes = nodes,
        )
    }

    /** The node receipts of a turn, one entry per node; egress merged by host and route. */
    fun nodeInfo(receipts: List<dev.wckdboy.autobot.agent.core.session.ReceiptNode>): List<ReceiptNodeInfo> =
        receipts.filter { it.node.isNotEmpty() || it.missing != null }.groupBy { it.node.ifEmpty { "node" } }.map { (node, list) ->
            val present = list.filter { it.missing == null }
            val egress = present.flatMap { it.egress }.groupBy { it.host to it.route }.map { (key, hosts) ->
                ReceiptHost(
                    host = key.first,
                    route = key.second,
                    requests = hosts.sumOf { it.requests },
                    bytesSent = hosts.sumOf { it.bytesSent },
                    bytesReceived = hosts.sumOf { it.bytesReceived },
                    tags = hosts.flatMap { it.tags }.distinct(),
                )
            }
            val redacted = present.flatMap { it.redacted.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, v) -> v.max() }
            ReceiptNodeInfo(
                node = node,
                requests = list.size,
                verified = present.isNotEmpty() && present.all { it.verified } && present.size == list.size,
                models = present.mapNotNull { it.model }.distinct(),
                cloud = present.any { it.tier == "cloud" },
                egress = egress,
                redactions = redactions(redacted),
                missing = list.size - present.size,
            )
        }

    /** The receipt's redaction counts in a fixed order ([PiiKind] order; unknown kinds last). */
    fun redactions(counts: Map<String, Int>?): List<ReceiptRedaction> {
        if (counts.isNullOrEmpty()) return emptyList()
        val order = PiiKind.entries.map { it.tag }
        return counts.filterValues { it > 0 }.entries
            .sortedBy { (kind, _) -> order.indexOf(kind).takeIf { it >= 0 } ?: order.size }
            .map { (kind, n) -> ReceiptRedaction(kind, n, PiiKind.of(kind)?.count(n) ?: "$n ${kind.lowercase()}") }
    }

    /**
     * `12k in · 410 out · 8.1k cached · $0.0042`: the tokens of [models] that were counted and
     * their cost (`cost unknown` for a model without a price; on device `$0`). Null when none was
     * counted.
     */
    fun usageLine(models: List<ReceiptModel>): String? {
        val counted = models.filter { it.usage != null }
        if (counted.isEmpty()) return null
        fun total(f: (TokenUsage) -> Int?) = counted.sumOf { (f(it.usage!!) ?: 0).toLong() }
        val input = total { it.inputTokens }
        val output = total { it.outputTokens }
        val cached = total { it.cacheReadTokens }
        val cost = counted.sumOf { it.costUsd ?: 0.0 }
        val unknown = counted.any { m -> m.costUsd == null && m.usage!!.let { (it.inputTokens ?: 0) + (it.outputTokens ?: 0) } > 0 }
        return listOfNotNull(
            "${UsageFormat.tokens(input)} in",
            "${UsageFormat.tokens(output)} out",
            if (cached > 0) "${UsageFormat.tokens(cached)} cached" else null,
            UsageFormat.cost(cost, unknown),
        ).joinToString(" · ")
    }

    /**
     * [models] with the turn's counted [usage] (from the [UsageMeter]) and its cost by [plan]
     * prices; a model that answered only inside a subagent or summary is added.
     */
    fun withUsage(models: List<ReceiptModel>, usage: List<TurnUsage>, plan: UsagePlan): List<ReceiptModel> {
        if (usage.isEmpty()) return models
        val byKey = usage.associateBy { it.provider.id to it.model }
        val out = models.map { m ->
            val u = byKey[m.provider to m.model] ?: return@map m
            m.copy(usage = u.usage, requests = u.requests, costUsd = cost(u, plan))
        }
        val extra = usage.filter { u -> models.none { it.provider == u.provider.id && it.model == u.model } }.map { u ->
            ReceiptModel(u.provider.id, u.model, usage = u.usage, requests = u.requests, costUsd = cost(u, plan))
        }
        return out + extra
    }

    private fun cost(u: TurnUsage, plan: UsagePlan): Double? =
        UsageCost.cost(plan, UsageProvider(u.provider.id, u.provider.name, u.provider.presetId, u.provider.onDevice || SystemModels.isSystem(u.model)), u.model, u.usage)

    /**
     * The policy word of the line: the least private of the remote [models] (training, then the
     * longest retention, then none); a provider without a per-model policy shows its name.
     */
    private fun policyWord(models: List<ReceiptModel>): String? {
        if (models.isEmpty()) return null
        models.firstOrNull { it.policy == policyLabel(ModelDataPolicy.Trains) }?.let { return it.policy }
        models.firstOrNull { it.policy == null }?.let { return it.name ?: it.model }
        return models.mapNotNull { it.policy }.maxByOrNull { p -> RETENTION_DAYS.find(p)?.groupValues?.get(1)?.toInt() ?: 0 }
    }

    private val RETENTION_DAYS = Regex("kept (\\d+) days")

    /** `0 B`, `512 B`, `14 KB`, `1.2 MB` (1 KB = 1024 B). */
    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToLong()} KB"
        else -> {
            val tenths = (bytes * 10.0 / (1024 * 1024)).roundToLong()
            "${tenths / 10}.${tenths % 10} MB"
        }
    }
}

/**
 * Fills each turn's receipt with the traffic the [audit] log tied to it, the data policy of
 * each model ([provider] by id; null when unknown) and, with a [meter], the tokens each model used
 * and their cost. Without an audit log the network part stays unknown.
 */
fun receiptsPlugin(
    audit: AuditLog?,
    meter: UsageMeter? = null,
    /** Paired nodes' own receipts for the turn's requests (Invincible). */
    nodes: dev.wckdboy.autobot.shared.invincible.NodeReceiptLog? = null,
    provider: suspend (id: String) -> ReceiptProvider?,
): Plugin = plugin("privacy-receipts") { ctx ->
    ctx.intercept(Hooks.TurnReceipt) { input, next ->
        val base = next(input)
        val counted = meter?.let { Receipts.withUsage(base.models, it.takeTurn(input.agent.id, input.turn), it.plan) } ?: base.models
        val receipt = base.copy(
            models = counted.map { Receipts.describe(it, runCatching { provider(it.provider) }.getOrNull()) },
            network = audit?.let { Receipts.network(it.forTurn(input.agent.id, input.turn)) },
            nodes = nodes?.take(input.agent.id, input.turn)?.ifEmpty { null },
        )
        // The turn's entries were kept apart from the ring until now.
        audit?.release(input.agent.id, input.turn)
        receipt
    }
}
