package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One request the node answered, from its audit log (Invincible's spec/audit.md): when, who asked,
 * and its receipt, checked against the receipt key pinned at pairing.
 */
data class NodeRequest(
    /** Unix seconds. */
    val at: Long,
    /** `local`, or `device:<name>`. */
    val caller: String,
    val client: String?,
    /** `run`, `ask` or `block`. */
    val action: String,
    val reason: String?,
    val receipt: ReceiptNode,
) {
    /** The device's name for a device's request; null for the node's own (the agent, a local app). */
    val device: String? get() = caller.removePrefix("device:").takeIf { caller.startsWith("device:") }
}

/** One action the node's agent asked to do, from its action log (spec/audit.md). */
data class AgentActionEntry(
    val at: Long,
    val session: String,
    /** `command`, `write`, `read` or `cloud`. */
    val kind: String,
    val action: String,
    val ran: Boolean,
    /** `policy`, `grant`, `unattended`, or the channel the owner answered on (`cli`, `app`, `telegram`). */
    val by: String,
)

data class AgentFact(val id: Long, val at: Long, val text: String)

/**
 * A task on the node's team board (Invincible's spec/agent.md): part of [plan], for member
 * [agent]; [status] is `todo`, `running`, `blocked` (waiting for the owner's answer to
 * [question]), `done` or `failed`.
 */
data class BoardTask(
    val id: String,
    val plan: String,
    val agent: String,
    val goal: String,
    val status: String,
    val dependsOn: List<String>,
    val result: String?,
    val question: String?,
    val updated: Long,
) {
    val waiting: Boolean get() = status == "blocked"
}

data class AgentSkill(val name: String, val description: String)

/**
 * What a paired node and its agent did and know: the node's requests, the agent's actions, its
 * memory and the owner's skills. [agentProblem] says why the agent's part is missing (not running).
 */
data class NodeActivity(
    val requests: List<NodeRequest>,
    val actions: List<AgentActionEntry>,
    val facts: List<AgentFact>,
    val skills: List<AgentSkill>,
    val agentProblem: String? = null,
    /// The team board, newest plans first.
    val board: List<BoardTask> = emptyList(),
) {
    /** Board tasks waiting for the owner. */
    val waiting: List<BoardTask> get() = board.filter { it.waiting }

    /** Requests in [requests] that sent anything off the node. */
    val leftTheNode: Int get() = requests.count { it.receipt.egress.isNotEmpty() }

    /** Requests whose receipt did not verify against the pinned key. */
    val unverified: Int get() = requests.count { !it.receipt.verified }
}

/** Words for what a node and its agent did, the same on Android and iOS. */
object NodeActivityWords {
    /** "My-Phone", "the agent", "this machine". */
    fun who(r: NodeRequest): String = r.device ?: if (r.client == "autobot-agent") "the agent" else "this machine"

    /** "qwen3.5-4b on the node", "asked first: …", "refused: …". */
    fun where(r: NodeRequest): String = when (r.action) {
        "run" -> "${r.receipt.model ?: "?"} on the ${if (r.receipt.tier == "cloud") "cloud" else "node"}"
        "ask" -> "asked first (${r.reason ?: "?"})"
        else -> "refused (${r.reason ?: "?"})"
    }

    /** "nothing left the node" or "sent to api.anthropic.com via tor". */
    fun left(r: NodeRequest): String =
        if (r.receipt.egress.isEmpty()) "nothing left the node" else "sent to " + r.receipt.egress.joinToString { "${it.host} via ${it.route}" }

    fun decided(a: AgentActionEntry): String = when {
        a.ran && a.by == "policy" -> "allowed by the rules"
        a.ran && a.by == "grant" -> "allowed (always, this conversation)"
        a.ran -> "approved on ${a.by}"
        a.by == "policy" -> "refused by the rules"
        a.by == "unattended" -> "refused: no one to ask"
        else -> "denied on ${a.by}"
    }

    /** "✓ b1 researcher: Find the status" (and the result's first line when done). */
    fun task(t: BoardTask): String {
        val mark = when (t.status) {
            "done" -> "✓"
            "failed" -> "✗"
            "running" -> "▸"
            "blocked" -> "?"
            else -> "·"
        }
        val after = if (t.dependsOn.isEmpty()) "" else " (after ${t.dependsOn.joinToString()})"
        return "$mark ${t.id} ${t.agent}: ${t.goal}$after"
    }

    fun summary(a: NodeActivity): String = buildString {
        append("${a.requests.size} requests · ")
        append(if (a.leftTheNode == 0) "nothing left the node" else "${a.leftTheNode} sent off the node")
        if (a.unverified > 0) append(" · ${a.unverified} not verified")
    }
}

internal object NodeActivityParser {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /**
     * `GET /v1/audit`'s entries, newest first. Each receipt counts as verified only when it is
     * signed by [receiptKey] (pinned at pairing; null verifies nothing).
     */
    fun requests(text: String, node: String, receiptKey: String?, crypto: NodeCrypto): List<NodeRequest> =
        (json.parseToJsonElement(text) as? JsonObject)?.get("entries").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val envelope = o["receipt"] as? JsonObject ?: return@mapNotNull null
            val receipt = envelope["receipt"] as? JsonObject ?: return@mapNotNull null
            val id = receipt.text("request_id") ?: return@mapNotNull null
            val signature = envelope.text("signature").orEmpty()
            val verified = receiptKey != null && receipt.text("node") == receiptKey &&
                NodeReceipts.verify(crypto, receiptKey, receipt, signature)
            val decision = receipt["decision"] as? JsonObject
            NodeRequest(
                at = o.long("at") ?: 0,
                caller = o.text("caller") ?: "local",
                client = o.text("client"),
                action = decision?.text("action") ?: "?",
                reason = decision?.text("reason"),
                receipt = NodeReceiptLog.parse(node, id, verified, receipt),
            )
        }.reversed()

    /** `GET /v1/agent/actions`, newest first. */
    fun actions(text: String): List<AgentActionEntry> =
        (json.parseToJsonElement(text) as? JsonObject)?.get("actions").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            AgentActionEntry(
                at = o.long("at") ?: 0,
                session = o.text("session").orEmpty(),
                kind = o.text("kind") ?: "?",
                action = o.text("action").orEmpty(),
                ran = (o["ran"] as? JsonPrimitive)?.booleanOrNull ?: false,
                by = o.text("by") ?: "?",
            )
        }.reversed()

    /** `GET /v1/agent/memory`, newest first. */
    fun facts(text: String): List<AgentFact> =
        (json.parseToJsonElement(text) as? JsonObject)?.get("facts").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            AgentFact(o.long("id") ?: return@mapNotNull null, o.long("at") ?: 0, o.text("text").orEmpty())
        }.reversed()

    /** `GET /v1/agent/board`, newest plans first. */
    fun board(text: String): List<BoardTask> =
        (json.parseToJsonElement(text) as? JsonObject)?.get("tasks").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            BoardTask(
                id = o.text("id") ?: return@mapNotNull null,
                plan = o.text("plan").orEmpty(),
                agent = o.text("agent").orEmpty(),
                goal = o.text("goal").orEmpty(),
                status = o.text("status") ?: "todo",
                dependsOn = (o["depends_on"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                result = o.text("result"),
                question = o.text("question"),
                updated = o.long("updated") ?: 0,
            )
        }.sortedWith(compareByDescending<BoardTask> { it.plan.removePrefix("p").toIntOrNull() ?: 0 }.thenBy { it.id.removePrefix("b").toIntOrNull() ?: 0 })

    fun skills(text: String): List<AgentSkill> =
        (json.parseToJsonElement(text) as? JsonObject)?.get("skills").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            AgentSkill(o.text("name") ?: return@mapNotNull null, o.text("description").orEmpty())
        }
}
