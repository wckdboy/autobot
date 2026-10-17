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
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.shared.usage.UsageCost
import dev.wckdboy.autobot.shared.usage.UsageFormat
import dev.wckdboy.autobot.shared.usage.UsageMeter
import dev.wckdboy.autobot.shared.usage.UsageProvider
import dev.wckdboy.autobot.shared.usage.TurnUsage
import dev.wckdboy.autobot.shared.usage.UsagePlan
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable

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
    /** The turn's tokens and cost in one line (`12k in · 410 out · 8.1k cached · $0.0042`); null when not counted. */
    val usage: String? = null,
    /** The same per model, in [models] order. */
    val usageRows: List<ReceiptUsageRow> = emptyList(),
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
        val line = when {
            onDevice && hosts.any { it.blocked > 0 } -> "on device · ${hosts.sumOf { it.blocked }} blocked"
            onDevice -> "on device"
            else -> listOfNotNull(
                if (tracked) "${off.size} ${if (off.size == 1) "host" else "hosts"}" else null,
                if (tracked) "${formatBytes(sent)} out" else null,
                routes.filter { it != "direct" }.joinToString(" + ").ifEmpty { null },
                policyWord(remote),
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
            usage = usageLine(event.models),
            usageRows = event.models.filter { it.usage != null }.map { m ->
                ReceiptUsageRow(m.model, usageLine(listOf(m)).orEmpty())
            },
        )
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
    provider: suspend (id: String) -> ReceiptProvider?,
): Plugin = plugin("privacy-receipts") { ctx ->
    ctx.intercept(Hooks.TurnReceipt) { input, next ->
        val base = next(input)
        val counted = meter?.let { Receipts.withUsage(base.models, it.takeTurn(input.agent.id, input.turn), it.plan) } ?: base.models
        val receipt = base.copy(
            models = counted.map { Receipts.describe(it, runCatching { provider(it.provider) }.getOrNull()) },
            network = audit?.let { Receipts.network(it.forTurn(input.agent.id, input.turn)) },
        )
        // The turn's entries were kept apart from the ring until now.
        audit?.release(input.agent.id, input.turn)
        receipt
    }
}
