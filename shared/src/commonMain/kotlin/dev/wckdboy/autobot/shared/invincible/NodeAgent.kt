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
    /** A kind this app does not know yet; [AgentEvent.Approval.text] still describes it. */
    data class Other(val kind: String) : AgentAction
}

/** One event of a turn (spec/agent.md). [Answer] or [Failed] is the last. */
sealed interface AgentEvent {
    data class Tool(val text: String) : AgentEvent
    data class Refused(val text: String) : AgentEvent
    data class Receipt(val id: String, val model: String?, val tier: String?) : AgentEvent
    data class Approval(val id: String, val text: String, val action: AgentAction) : AgentEvent
    data class Answer(val text: String, val receipts: List<String>) : AgentEvent
    data class Failed(val text: String) : AgentEvent
}

enum class ApprovalAnswer(val wire: String) { ONCE("once"), ALWAYS("always"), DENY("deny") }

data class AgentInfo(val name: String, val model: String, val workdir: String, val approvals: String, val private: Boolean)

data class AgentSession(val id: String, val modified: Long)

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
)

/** A line of a conversation's history: who and what (tool calls summarized). */
data class AgentLine(val role: String, val text: String)

/** The node answered with an error (spec/agent.md's error shape). */
class NodeAgentException(val status: Int, val type: String?, message: String) : Exception(message)

/**
 * The Autobot Agent on a paired node, through the gateway's `/v1/agent` (spec/agent.md), over a
 * [transport] to the node (a [NodeHttpTransport]: every request is its own Noise session).
 */
class NodeAgent(private val transport: HttpTransport, private val tag: String = "node agent") {

    /** Runs a turn in [session]; events as they come. Cancelling stops listening, not the turn. */
    fun turn(session: String, text: String): Flow<AgentEvent> = callbackFlow {
        val body = buildJsonObject { put("text", text) }.toString()
        var finished = false
        val request = spec("POST", "/sessions/$session/turns", body, accept = "text/event-stream")
        val running = transport.send(request, object : HttpSink {
            override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                if (status !in 200..299) {
                    finished = true
                    trySend(AgentEvent.Failed(errorMessage(status, errorBody).message ?: "error $status"))
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
                trySend(AgentEvent.Failed(if (blocked) "Blocked by network policy: $message" else message))
                close()
            }
        })
        awaitClose { running.cancel() }
    }.buffer(64)

    /** Answers a question; false when it was no longer waiting (answered or expired). */
    suspend fun answer(id: String, answer: ApprovalAnswer): Boolean = try {
        request("POST", "/approvals/$id", buildJsonObject { put("answer", answer.wire) }.toString())
        true
    } catch (e: NodeAgentException) {
        if (e.status == 404) false else throw e
    }

    suspend fun info(): AgentInfo {
        val o = json.parseToJsonElement(request("GET", "")).jsonObject
        return AgentInfo(o.text("name") ?: "Autobot", o.text("model") ?: "auto", o.text("workdir") ?: "", o.text("approvals") ?: "ask", o["private"]?.jsonPrimitive?.booleanOrNull ?: true)
    }

    suspend fun sessions(): List<AgentSession> =
        json.parseToJsonElement(request("GET", "/sessions")).jsonObject["sessions"]?.jsonArray.orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            AgentSession(o.text("id") ?: return@mapNotNull null, o["modified"]?.jsonPrimitive?.longOrNull ?: 0)
        }

    suspend fun history(session: String): List<AgentLine> =
        json.parseToJsonElement(request("GET", "/sessions/$session")).jsonObject["messages"]?.jsonArray.orEmpty().mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = o.text("role") ?: return@mapNotNull null
            val content = o.text("content").orEmpty()
            val calls = (o["tool_calls"] as? JsonArray).orEmpty().mapNotNull { c ->
                (c as? JsonObject)?.get("function")?.jsonObject?.text("name")
            }
            when {
                role == "user" || (role == "assistant" && content.isNotBlank()) -> AgentLine(role, content.trim())
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

    private fun spec(method: String, path: String, body: String?, accept: String = "application/json") = HttpRequestSpec(
        method = method,
        url = "$BASE$path",
        headers = buildMap {
            put("Accept", accept)
            if (body != null) put("Content-Type", "application/json")
        },
        body = body,
        tag = tag,
        maxBytes = MAX_BYTES,
    )

    /** One request; the body, or [NodeAgentException]. */
    private suspend fun request(method: String, path: String, body: String? = null): String = suspendCancellableCoroutine { cont ->
        val text = StringBuilder()
        val running = transport.send(spec(method, path, body), object : HttpSink {
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
        private const val MAX_BYTES = 8L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun errorMessage(status: Int, body: String?): NodeAgentException {
            val error = runCatching { json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonObject }.getOrNull()
            val type = error?.text("type")
            val message = when (type) {
                "agent_unavailable" -> "The Autobot Agent is not running on the node. Start it there with `autobot serve` (or `autobot service install`)."
                "session_busy" -> "The agent is still working on this conversation."
                else -> error?.text("message") ?: "The node answered $status."
            }
            return NodeAgentException(status, type, message)
        }

        /** One `data:` payload of a turn's stream; null for what this app does not understand. */
        fun parseEvent(data: String): AgentEvent? {
            val o = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
            return when (o.text("type")) {
                "tool" -> AgentEvent.Tool(o.text("text").orEmpty())
                "refused" -> AgentEvent.Refused(o.text("text").orEmpty())
                "receipt" -> AgentEvent.Receipt(o.text("id") ?: return null, o.text("model"), o.text("tier"))
                "approval" -> AgentEvent.Approval(o.text("id") ?: return null, o.text("text").orEmpty(), action(o["action"]))
                "answer" -> AgentEvent.Answer(o.text("text").orEmpty(), (o["receipts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                "error" -> AgentEvent.Failed(o.text("text").orEmpty())
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
                else -> AgentAction.Other(kind ?: "?")
            }
        }

        /** This device's conversation with the agent (spec/agent.md: letters, digits, `-`, `_`). */
        fun sessionFor(device: String): String =
            "app-${device.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.take(40).ifEmpty { "device" }}"
    }
}
