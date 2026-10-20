package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the agent on a node asks the owner to approve (spec/agent.md, `action`). */
sealed interface AgentAction {
    data class Command(val command: String) : AgentAction
    data class Write(val path: String, val bytes: Long, val inside: Boolean) : AgentAction
    data class Read(val path: String) : AgentAction
    data class Cloud(val reason: String) : AgentAction
    data class Fetch(val url: String) : AgentAction
    /** An MCP server's tool on the node: `server/tool`, and its arguments (JSON). */
    data class Mcp(val tool: String, val arguments: String) : AgentAction
    /** A kind this app does not know yet; [AgentEvent.Approval.text] still describes it. */
    data class Other(val kind: String) : AgentAction
}

/** One event of a turn (spec/agent.md). [Answer] or [Failed] is the last. */
sealed interface AgentEvent {
    data class Tool(val text: String) : AgentEvent
    data class Refused(val text: String) : AgentEvent
    data class Receipt(val id: String, val model: String?, val tier: String?) : AgentEvent
    /** [agent]: the team member asking, when it is not the main agent. */
    data class Approval(val id: String, val text: String, val action: AgentAction, val agent: String? = null) : AgentEvent
    /** A team member on the node finished the task it was handed: its answer to the lead. */
    data class Member(val agent: String, val text: String) : AgentEvent
    data class Answer(val text: String, val receipts: List<String>) : AgentEvent
    /** [code]: the node's error code when it refused the turn (`model_cannot_see_images`, …). */
    data class Failed(val text: String, val code: String? = null) : AgentEvent
    /**
     * After [Answer], when the turn was at a nudge: what the agent kept on its own
     * (`profile: …`, `memory: …`). The conversation is already free for the next turn.
     */
    data class Kept(val text: String) : AgentEvent
}

enum class ApprovalAnswer(val wire: String) { ONCE("once"), ALWAYS("always"), DENY("deny") }

/**
 * A question the agent is waiting on now (spec/agent.md, `GET /v1/agent/approvals`), in [session];
 * [agent] is the team member asking, when it is not the main agent. Times in Unix seconds.
 */
data class PendingApproval(
    val id: String,
    val session: String,
    val text: String,
    val action: AgentAction,
    val agent: String? = null,
    val askedAt: Long,
    val expiresAt: Long? = null,
    /** The bot whose conversation it is (bots mode); null on a node without bots: the default one. */
    val bot: String? = null,
)

/** [bots]: the names of the node's bots (bots mode); empty on an older node. */
data class AgentInfo(val name: String, val model: String, val workdir: String, val approvals: String, val private: Boolean, val bots: List<String> = emptyList())

/** A conversation on the node; [bot] is the bot it belongs to (null on a node without bots: the default one). */
data class AgentSession(val id: String, val modified: Long, val bot: String? = null)

/**
 * A task the agent runs on a schedule (spec/agent.md): [schedule] is a cron expression, `@daily`,
 * or `every 30m`; times are Unix seconds.
 */
data class AgentSchedule(
    val id: String,
    val schedule: String,
    val task: String,
    val telegram: Boolean,
    val next: Long?,
    val lastAt: Long?,
    val lastOk: Boolean?,
    val lastResult: String?,
    /** Its name, when it has one (a recipe's). */
    val name: String? = null,
)

/** The agent as a device opens it: its conversation id there, the agent's settings, the conversation so far. */
data class NodeAgentOpened(val session: String, val info: AgentInfo, val history: List<AgentLine>)

/** A line of a conversation's history: who and what (tool calls summarized); [images]: the names of a user message's images. */
data class AgentLine(val role: String, val text: String, val images: List<String> = emptyList())

/** One finished run of a schedule on the node (Unix seconds); [receipts] are the gateway's request ids. */
data class NodeRun(val at: Long, val finished: Long, val ok: Boolean, val result: String, val receipts: List<String>, val trigger: String)

/** The node answered with an error (spec/agent.md's error shape); [code] when it gave one. */
class NodeAgentException(val status: Int, val type: String?, message: String, val code: String? = null) : Exception(message)

/**
 * The Autobot Agent on a paired node, through the gateway's `/v1/agent` (spec/agent.md), over a
 * [transport] to the node (a [NodeHttpTransport]: every request is its own Noise session).
 */
class NodeAgent(private val transport: HttpTransport, private val tag: String = "node agent") {

    /**
     * Runs a turn in [session]; events as they come. Cancelling stops listening, not the turn.
     * [bot] picks the bot a new session belongs to (bots mode); null for the default one.
     * [images]: JPEG photos, base64 (no `data:` prefix), sent as OpenAI-style `image_url` parts
     * ([NodeImages.turnBody]). After the answer the stream may carry [AgentEvent.Kept] events.
     */
    fun turn(session: String, text: String, bot: String? = null, images: List<String> = emptyList()): Flow<AgentEvent> =
        stream("/sessions/$session/turns", NodeImages.turnBody(text, bot, images))

    /**
     * Runs the last turn of [session] again (`POST …/retry`): it leaves the conversation and its
     * request, with its images, runs again; streamed like a turn. No turn to retry is a
     * [AgentEvent.Failed] with code `not_found`.
     */
    fun retry(session: String): Flow<AgentEvent> = stream("/sessions/$session/retry", "{}")

    private fun stream(path: String, body: String): Flow<AgentEvent> = callbackFlow {
        var finished = false
        val request = spec("POST", path, body, accept = "text/event-stream")
        val running = transport.send(request, object : HttpSink {
            override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                if (status !in 200..299) {
                    finished = true
                    val e = errorMessage(status, errorBody)
                    trySend(AgentEvent.Failed(e.message ?: "error $status", e.code ?: e.type))
                    close()
                }
            }

            override fun onLine(line: String) {
                val event = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") }?.let(::parseEvent) ?: return
                if (event is AgentEvent.Answer || event is AgentEvent.Failed) finished = true
                trySend(event)
            }

            override fun onComplete() {
                if (!finished) trySend(AgentEvent.Failed("The connection to the node ended before the answer; the turn goes on there."))
                close()
            }

            override fun onFailure(blocked: Boolean, message: String) {
                // After the answer only `kept` notes were still to come: nothing is lost.
                if (!finished) trySend(AgentEvent.Failed(if (blocked) "Blocked by network policy: $message" else message))
                close()
            }
        })
        awaitClose { running.cancel() }
    }.buffer(64)

    /**
     * The questions waiting for the owner now, oldest first; null from a node too old to list them
     * (it answers 404), so the caller can tell "none" from "unknown".
     */
    suspend fun approvals(): List<PendingApproval>? = try {
        json.parseToJsonElement(request("GET", "/approvals")).jsonObject["approvals"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            PendingApproval(
                id = o.text("id") ?: return@mapNotNull null,
                session = o.text("session").orEmpty(),
                text = o.text("text").orEmpty(),
                action = action(o["action"]),
                agent = o.text("agent"),
                askedAt = (o["asked_at"] as? JsonPrimitive)?.longOrNull ?: 0,
                expiresAt = (o["expires_at"] as? JsonPrimitive)?.longOrNull,
                bot = o.text("bot"),
            )
        }
    } catch (e: NodeAgentException) {
        if (e.status == 404) null else throw e
    }

    /** Answers a question with "no, and do this instead: [text]"; false when it was no longer waiting. */
    suspend fun answerInstead(id: String, text: String): Boolean {
        require(text.isNotBlank()) { "Say what to do instead." }
        return try {
            request("POST", "/approvals/$id", buildJsonObject {
                put("answer", "instead")
                put("text", text.trim())
            }.toString())
            true
        } catch (e: NodeAgentException) {
            if (e.status == 404) false else throw e
        }
    }

    /** Answers a question; false when it was no longer waiting (answered or expired). */
    suspend fun answer(id: String, answer: ApprovalAnswer): Boolean = try {
        request("POST", "/approvals/$id", buildJsonObject { put("answer", answer.wire) }.toString())
        true
    } catch (e: NodeAgentException) {
        if (e.status == 404) false else throw e
    }

    suspend fun info(): AgentInfo {
        val o = json.parseToJsonElement(request("GET", "")).jsonObject
        return AgentInfo(
            o.text("name") ?: "Autobot",
            o.text("model") ?: "auto",
            o.text("workdir") ?: "",
            o.text("approvals") ?: "ask",
            o["private"]?.jsonPrimitive?.booleanOrNull ?: true,
            bots = (o["bots"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    /** The conversations on the node, or only [bot]'s (bots mode). */
    suspend fun sessions(bot: String? = null): List<AgentSession> =
        json.parseToJsonElement(request("GET", "/sessions" + (bot?.let { "?bot=${encoded(it)}" } ?: ""))).jsonObject["sessions"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            AgentSession(o.text("id") ?: return@mapNotNull null, (o["modified"] as? JsonPrimitive)?.longOrNull ?: 0, o.text("bot"))
        }

    /**
     * The node's bots (bots mode), as it lists them; null from a node too old to have bots (it
     * answers 404): one agent, the screens as before.
     */
    suspend fun bots(): List<NodeBot>? = try {
        NodeBots.parse(request("GET", "/bots"))
    } catch (e: NodeAgentException) {
        if (e.status == 404) null else throw e
    }

    /** Switches bot [name] on or off on the node; what the node did about it. */
    suspend fun setBotEnabled(name: String, enabled: Boolean): BotSwitch =
        NodeBotDetails.parseSwitch(request("POST", "/bots/${encoded(name)}/${if (enabled) "enable" else "disable"}", "{}"), enabled)

    /** Brings the running bots in line with their configuration now (`POST /v1/agent/bots/reload`). */
    suspend fun reloadBots(): BotsReload = NodeBotDetails.parseReload(request("POST", "/bots/reload", "{}"))

    /** The personalities [bot] can take, the one in use and its SOUL.md. */
    suspend fun personalities(bot: String? = null): NodePersonalities =
        NodeBotDetails.parsePersonalities(request("GET", "/personalities" + botQuery(bot, first = true)))

    /** Sets [bot]'s personality for every turn from now on ([name] null or `none`: none); the one in use. */
    suspend fun setPersonality(name: String?, bot: String? = null): String? {
        val body = buildJsonObject { put("name", name ?: NodeBotDetails.NONE) }.toString()
        val o = json.parseToJsonElement(request("POST", "/personality" + botQuery(bot, first = true), body)).jsonObject
        return o.text("active")?.takeUnless { it == NodeBotDetails.NONE }
    }

    /** What [bot] keeps about how the owner works (its USER.md). */
    suspend fun profile(bot: String? = null): NodeProfile = NodeBotDetails.parseProfile(request("GET", "/profile" + botQuery(bot, first = true)))

    /** [bot]'s usage today (and in [session]), from its turn log. */
    suspend fun usage(bot: String? = null, session: String? = null): NodeUsage {
        val query = listOfNotNull(session?.let { "session=${encoded(it)}" }, bot?.let { "bot=${encoded(it)}" }).joinToString("&")
        return NodeBotDetails.parseUsage(request("GET", "/usage" + (if (query.isEmpty()) "" else "?$query")))
    }

    /** What [bot] did over the last [days]. */
    suspend fun insights(bot: String? = null, days: Int = 7): NodeInsights =
        NodeBotDetails.parseInsights(request("GET", "/insights?days=$days" + botQuery(bot, first = false)))

    private fun botQuery(bot: String?, first: Boolean): String = bot?.let { (if (first) "?" else "&") + "bot=${encoded(it)}" } ?: ""

    /** [value] as a path segment or query value: letters, digits and `-_.~` as they are, the rest %-encoded. */
    private fun encoded(value: String): String = buildString {
        value.encodeToByteArray().forEach { b ->
            val c = b.toInt().toChar()
            if (b >= 0 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~")) {
                append(c)
            } else {
                append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
        }
    }

    suspend fun history(session: String): List<AgentLine> =
        json.parseToJsonElement(request("GET", "/sessions/$session")).jsonObject["messages"]?.jsonArray.orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = o.text("role") ?: return@mapNotNull null
            val content = o.text("content").orEmpty()
            val calls = (o["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
                (c as? JsonObject)?.get("function")?.jsonObject?.text("name")
            }
            // Images stay on the node by reference (spec/agent.md, `autobot_images`): their names.
            val images = (o["autobot_images"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { i -> i.text("name") ?: "image" } }
            when {
                role == "user" -> AgentLine(role, content.trim(), images)
                role == "assistant" && content.isNotBlank() -> AgentLine(role, content.trim())
                role == "assistant" && calls.isNotEmpty() -> AgentLine("tool", calls.joinToString(", "))
                else -> null
            }
        }

    suspend fun reset(session: String) {
        request("POST", "/sessions/$session/reset", "")
    }

    suspend fun schedules(): List<AgentSchedule> =
        json.parseToJsonElement(request("GET", "/schedules")).jsonObject["schedules"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val last = o["last"] as? JsonObject
            AgentSchedule(
                id = o.text("id") ?: return@mapNotNull null,
                schedule = o.text("when").orEmpty(),
                task = o.text("task").orEmpty(),
                telegram = o["telegram"]?.jsonPrimitive?.booleanOrNull ?: false,
                next = (o["next"] as? JsonPrimitive)?.longOrNull,
                lastAt = last?.get("at")?.jsonPrimitive?.longOrNull,
                lastOk = last?.get("ok")?.jsonPrimitive?.booleanOrNull,
                lastResult = last?.text("result"),
                name = o.text("name"),
            )
        }

    /** Adds a scheduled task; its id. A schedule that does not parse comes back as [NodeAgentException] (400). */
    suspend fun addSchedule(schedule: String, task: String, telegram: Boolean = false): String {
        val body = buildJsonObject {
            put("when", schedule)
            put("task", task)
            put("telegram", telegram)
        }.toString()
        return json.parseToJsonElement(request("POST", "/schedules", body)).jsonObject.text("id") ?: error("no id")
    }

    suspend fun removeSchedule(id: String) {
        request("DELETE", "/schedules/$id")
    }

    /** Creates or replaces a schedule from its JSON body (spec/agent.md); its id. */
    suspend fun putSchedule(body: String): String =
        json.parseToJsonElement(request("POST", "/schedules", body)).jsonObject.text("id") ?: error("no id")

    /** Starts schedule [id] now; false when it is already running. */
    suspend fun runSchedule(id: String): Boolean = try {
        request("POST", "/schedules/$id/run", "")
        true
    } catch (e: NodeAgentException) {
        if (e.status == 409) false else throw e
    }

    /** Schedule [id]'s finished runs that started after [after] (Unix seconds), oldest first. */
    suspend fun runs(id: String, after: Long): List<NodeRun> =
        json.parseToJsonElement(request("GET", "/schedules/$id/runs?after=$after")).jsonObject["runs"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            NodeRun(
                at = o["at"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                finished = o["finished"]?.jsonPrimitive?.longOrNull ?: 0,
                ok = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                result = o.text("result").orEmpty(),
                receipts = (o["receipts"] as? JsonArray).orEmpty().mapNotNull { r -> (r as? JsonPrimitive)?.contentOrNull },
                trigger = o.text("trigger") ?: "schedule",
            )
        }

    /**
     * The node's audit log (Invincible's spec/audit.md), newest first: the requests it answered,
     * each receipt checked against [receiptKey], the key pinned at pairing.
     */
    suspend fun requests(crypto: NodeCrypto, node: String, receiptKey: String?, limit: Int = 100, since: Long? = null): List<NodeRequest> =
        NodeActivityParser.requests(request("GET", "?limit=$limit" + sinceQuery(since), root = AUDIT), node, receiptKey, crypto)

    /** The agent's action log, newest first: what it asked to do, whether it ran, who decided. */
    suspend fun actions(limit: Int = 100, since: Long? = null): List<AgentActionEntry> =
        NodeActivityParser.actions(request("GET", "/actions?limit=$limit" + sinceQuery(since)))

    private fun sinceQuery(since: Long?): String = if (since == null) "" else "&since=$since"

    /** What the agent remembered between conversations, newest first. */
    suspend fun memory(): List<AgentFact> = NodeActivityParser.facts(request("GET", "/memory"))

    /** Removes a remembered fact; false when there was none. */
    suspend fun forget(id: Long): Boolean = try {
        request("DELETE", "/memory/$id")
        true
    } catch (e: NodeAgentException) {
        if (e.status == 404) false else throw e
    }

    /** The node's team board (empty on a node without a team). */
    suspend fun board(): List<BoardTask> = NodeActivityParser.board(request("GET", "/board"))

    /** Answers a board task's question; false when it was not waiting any more. */
    suspend fun answerBoard(id: String, text: String): Boolean = try {
        request("POST", "/board/$id/answer", buildJsonObject { put("text", text) }.toString())
        true
    } catch (e: NodeAgentException) {
        if (e.status == 404) false else throw e
    }

    /** The owner's skills on the node. */
    suspend fun skills(): List<AgentSkill> = NodeActivityParser.skills(request("GET", "/skills"))

    /** All of it at once; the agent's part is empty, with [NodeActivity.agentProblem] set, when it is not running. */
    suspend fun activity(crypto: NodeCrypto, node: String, receiptKey: String?): NodeActivity {
        val requests = requests(crypto, node, receiptKey)
        return try {
            // An older node has no board: that part is empty, not an error.
            val board = try {
                board()
            } catch (e: NodeAgentException) {
                if (e.status == 404) emptyList() else throw e
            }
            NodeActivity(requests, actions(), memory(), skills(), board = board)
        } catch (e: NodeAgentException) {
            if (e.status == 0) throw e
            NodeActivity(requests, emptyList(), emptyList(), emptyList(), agentProblem = e.message)
        }
    }

    private fun spec(method: String, path: String, body: String?, accept: String = "application/json", root: String = BASE) = HttpRequestSpec(
        method = method,
        url = "$root$path",
        headers = buildMap {
            put("Accept", accept)
            if (body != null) put("Content-Type", "application/json")
        },
        body = body,
        tag = tag,
        maxBytes = MAX_BYTES,
    )

    /** One request; the body, or [NodeAgentException]. */
    private suspend fun request(method: String, path: String, body: String? = null, root: String = BASE): String = suspendCancellableCoroutine { cont ->
        val text = StringBuilder()
        val running = transport.send(spec(method, path, body, root = root), object : HttpSink {
            private var failed = false

            override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                if (status !in 200..299) {
                    failed = true
                    cont.resumeWithException(errorMessage(status, errorBody))
                }
            }

            override fun onLine(line: String) {
                text.append(line).append('\n')
            }

            override fun onComplete() {
                if (!failed) cont.resume(text.toString())
            }

            override fun onFailure(blocked: Boolean, message: String) {
                cont.resumeWithException(NodeAgentException(0, if (blocked) "blocked" else null, message))
            }
        })
        cont.invokeOnCancellation { running.cancel() }
    }

    companion object {
        private const val BASE = "http://invincible/v1/agent"
        private const val AUDIT = "http://invincible/v1/audit"
        private const val MAX_BYTES = 8L * 1024 * 1024
        private const val HEX = "0123456789ABCDEF"
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun errorMessage(status: Int, body: String?): NodeAgentException {
            val error = runCatching { json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
            val type = error?.text("type")
            val code = error?.text("code")
            val message = if (code == NodeImages.CANNOT_SEE || type == NodeImages.CANNOT_SEE) NodeImages.CANNOT_SEE_MESSAGE else when (type) {
                "agent_unavailable" -> "The Autobot Agent is not running on the node. Start it there with `autobot serve` (or `autobot service install`)."
                "session_busy" -> "The agent is still working on this conversation."
                else -> error?.text("message") ?: "The node answered $status."
            }
            return NodeAgentException(status, type, message, code)
        }

        /** One `data:` payload of a turn's stream; null for what this app does not understand. */
        fun parseEvent(data: String): AgentEvent? {
            val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            return when (o.text("type")) {
                "tool" -> AgentEvent.Tool(o.text("text").orEmpty())
                "refused" -> AgentEvent.Refused(o.text("text").orEmpty())
                "receipt" -> AgentEvent.Receipt(o.text("id") ?: return null, o.text("model"), o.text("tier"))
                "approval" -> AgentEvent.Approval(o.text("id") ?: return null, o.text("text").orEmpty(), action(o["action"]), o.text("agent"))
                "member" -> AgentEvent.Member(o.text("agent") ?: return null, o.text("text").orEmpty())
                "answer" -> AgentEvent.Answer(o.text("text").orEmpty(), (o["receipts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                "error" -> AgentEvent.Failed(o.text("text").orEmpty(), o.text("code"))
                "kept" -> o.text("text")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Kept(it.trim()) }
                else -> null
            }
        }

        private fun action(element: JsonElement?): AgentAction {
            val o = element as? JsonObject ?: return AgentAction.Other("?")
            return when (val kind = o.text("kind")) {
                "command" -> AgentAction.Command(o.text("command").orEmpty())
                "write" -> AgentAction.Write(o.text("path").orEmpty(), o["bytes"]?.jsonPrimitive?.longOrNull ?: 0, o["inside"]?.jsonPrimitive?.booleanOrNull ?: false)
                "read" -> AgentAction.Read(o.text("path").orEmpty())
                "cloud" -> AgentAction.Cloud(o.text("reason").orEmpty())
                "fetch" -> AgentAction.Fetch(o.text("url").orEmpty())
                "mcp" -> AgentAction.Mcp(o.text("tool").orEmpty(), o.text("arguments").orEmpty())
                else -> AgentAction.Other(kind ?: "?")
            }
        }

        /** This device's conversation with the agent (spec/agent.md: letters, digits, `-`, `_`). */
        fun sessionFor(device: String): String =
            "app-${device.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(40).ifEmpty { "device" }}"
    }
}
