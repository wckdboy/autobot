package dev.wckdboy.autobot.shared.invincible

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Where a bot on the node listens (Invincible's bots mode): [kind] is `telegram`, `discord`,
 * `slack`, `email`, `signal`, `matrix` or `api` (or a kind this app does not know yet); [status]
 * is `connected`, `connecting` or `down`, with [error] saying why when it is down.
 */
data class BotChannel(
    val kind: String,
    val account: String = "",
    val status: String = "connected",
    val error: String? = null,
) {
    val down: Boolean get() = status == "down"
}

/**
 * One named bot on the node: its persona (the first line of its SOUL.md), model and approvals mode
 * (`ask`, `edits`, `allowlist`, `trust`); [status] is `running`, `stopped` or `error`. Times in
 * Unix seconds; [lastActive] is null when it has not done anything yet.
 */
data class NodeBot(
    val name: String,
    val persona: String = "",
    val model: String = "",
    val approvals: String = "ask",
    val enabled: Boolean = true,
    val status: String = "running",
    val error: String? = null,
    val channels: List<BotChannel> = emptyList(),
    val sessions: Int = 0,
    val lastActive: Long? = null,
    val queue: Int = 0,
) {
    val isDefault: Boolean get() = name == NodeBots.DEFAULT

    /** Channels that are down, and the bot itself when it failed: what the row shows in red. */
    val downChannels: List<BotChannel> get() = channels.filter { it.down }

    val hasProblem: Boolean get() = status == "error" || downChannels.isNotEmpty()
}

/**
 * The node's bots as the app knows them. [supported] is null until the node answered, false for a
 * node too old to have bots (it answers 404: one agent, today's screens), true otherwise.
 */
data class NodeBotsState(
    val bots: List<NodeBot> = emptyList(),
    val supported: Boolean? = null,
    val loading: Boolean = false,
    /** Why the last refresh or switch did not work; null when it did. */
    val problem: String? = null,
) {
    /** Show the Bots list: the node has bots mode (one bot shows as a single row). */
    val showList: Boolean get() = supported == true && bots.isNotEmpty()

    /** More than one bot: sessions, approvals and the digest say which bot. */
    val several: Boolean get() = supported == true && bots.size > 1

    fun bot(name: String): NodeBot? = bots.firstOrNull { it.name == name }
}

/**
 * The bots on a paired node ([agent]): refresh, and switch one on or off. [state] is what the
 * screens show; every call also answers with the new state, for hosts without flows (iOS).
 */
class NodeBotsRepository(private val agent: NodeAgent) {
    private val _state = MutableStateFlow(NodeBotsState())
    val state: StateFlow<NodeBotsState> = _state.asStateFlow()

    /** Asks the node for its bots; an older node (404) is [NodeBotsState.supported] false, not an error. */
    suspend fun refresh(): NodeBotsState {
        _state.update { it.copy(loading = true) }
        try {
            val bots = agent.bots()
            _state.update {
                if (bots == null) NodeBotsState(supported = false) else NodeBotsState(bots = NodeBots.ordered(bots), supported = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, problem = e.message ?: "The node did not answer.") }
        }
        return _state.value
    }

    /**
     * Switches bot [name] on or off: shown at once, put back when the node says no. Null, or why it
     * did not work.
     */
    suspend fun setEnabled(name: String, enabled: Boolean): String? {
        val before = _state.value.bot(name) ?: return "There is no bot called $name on the node."
        _state.update { s -> s.copy(bots = s.bots.map { if (it.name == name) NodeBots.switched(it, enabled) else it }, problem = null) }
        return try {
            agent.setBotEnabled(name, enabled)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val why = e.message ?: "The node did not answer."
            _state.update { s -> s.copy(bots = s.bots.map { if (it.name == name) before else it }, problem = why) }
            why
        }
    }
}

/** Parsing, ordering and words for the node's bots, the same on Android and iOS. */
object NodeBots {
    /** The bot a node without bots mode, or a session without a `bot`, belongs to. */
    const val DEFAULT = "default"

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** `GET /v1/agent/bots`: tolerant of fields it does not know and of ones that are missing. */
    fun parse(text: String): List<NodeBot> =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.get("bots").let { it as? JsonArray }.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NodeBot(
                name = name,
                persona = o.text("persona").orEmpty().lineSequence().firstOrNull().orEmpty().trim(),
                model = o.text("model").orEmpty(),
                approvals = o.text("approvals") ?: "ask",
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                status = o.text("status") ?: "running",
                error = o.text("error")?.takeIf { it.isNotBlank() },
                channels = (o["channels"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    BotChannel(
                        kind = co.text("kind") ?: return@mapNotNull null,
                        account = co.text("account").orEmpty(),
                        status = co.text("status") ?: "connected",
                        error = co.text("error")?.takeIf { it.isNotBlank() },
                    )
                },
                sessions = o.long("sessions")?.toInt() ?: 0,
                lastActive = o.long("last_active"),
                queue = o.long("queue")?.toInt() ?: 0,
            )
        }

    /** The default bot first, then by name. */
    fun ordered(bots: List<NodeBot>): List<NodeBot> = bots.sortedWith(compareBy<NodeBot> { !it.isDefault }.thenBy { it.name })

    /** [bot] as it looks right after switching it on or off, before the node says more. */
    fun switched(bot: NodeBot, enabled: Boolean): NodeBot =
        bot.copy(enabled = enabled, status = if (enabled) (if (bot.status == "stopped") "running" else bot.status) else "stopped")

    /** The bot [session] belongs to: its `bot`, or the default one. */
    fun of(bot: String?): String = bot?.takeIf { it.isNotBlank() } ?: DEFAULT

    /** "running", "stopped", "off", "error": the bot's state, in words. */
    fun status(bot: NodeBot): String = when {
        !bot.enabled -> "off"
        else -> bot.status
    }

    /** "telegram · slack": the kinds it listens on, each once, as the row's quiet tags. */
    fun kinds(bot: NodeBot): List<String> = bot.channels.map { it.kind }.distinct()

    /**
     * What is wrong, words first, for the row's red line: "slack down: token revoked ✗",
     * "error: model not found ✗". Null when nothing is.
     */
    fun problem(bot: NodeBot): String? {
        val parts = buildList {
            if (bot.status == "error") add("error" + (bot.error?.let { ": $it" } ?: ""))
            bot.downChannels.forEach { c -> add("${c.kind} down" + (c.error?.let { ": $it" } ?: "")) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ", postfix = " ✗")
    }

    /** "2 queued · active 5m ago" or "never active": the row's quiet meta. */
    fun meta(bot: NodeBot, now: Long): String = listOfNotNull(
        bot.queue.takeIf { it > 0 }?.let { "$it queued" },
        bot.lastActive?.let { "active ${ago(it, now)}" } ?: "not active yet",
    ).joinToString(" · ")

    /** "just now", "5m ago", "3h ago", "2d ago". */
    fun ago(at: Long, now: Long): String {
        val s = (now - at).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3_600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3_600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    /** "connected", "connecting", "down: token revoked": a channel's state on the detail screen. */
    fun channelState(c: BotChannel): String = if (c.down) "down" + (c.error?.let { ": $it" } ?: "") + " ✗" else c.status

    /** "Asks before commands and edits." and the like: the approvals mode, in words. */
    fun approvals(mode: String): String = when (mode) {
        "ask" -> "Asks before commands and edits."
        "edits" -> "Edits in its folder; asks before commands."
        "allowlist" -> "Runs allowlisted commands; asks for the rest."
        "trust" -> "Runs without asking."
        else -> mode
    }

    /** The row for a screen reader: "ops, running, telegram and slack, slack down: token revoked." */
    fun spoken(bot: NodeBot, now: Long): String = buildString {
        append(bot.name).append(", ").append(status(bot))
        if (bot.persona.isNotEmpty()) append(", ").append(bot.persona)
        kinds(bot).takeIf { it.isNotEmpty() }?.let { append(", on ").append(it.joinToString(" and ")) }
        problem(bot)?.let { append(", ").append(NodeDigestWords.unmarked(it)) }
        append(", ").append(meta(bot, now)).append('.')
    }

    /**
     * A new conversation with [bot] from [device]: this device's own id, the bot's name and a time
     * stamp (spec/agent.md: letters, digits, `-`, `_`).
     */
    fun newSession(device: String, bot: String, now: Long): String =
        NodeAgent.sessionFor(device) + "-" + bot.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }.take(24) + "-" + now.toString(36)

    /**
     * Three bots for screenshots and UI work (debug builds' demo data): the default one, an ops bot
     * on Telegram and Slack with Slack down, and a support bot on email.
     */
    fun sample(now: Long): List<NodeBot> = listOf(
        NodeBot(
            name = DEFAULT, persona = "A careful colleague on studio-mac.", model = "qwen3.5-4b", approvals = "ask",
            channels = listOf(BotChannel("api")), sessions = 6, lastActive = now - 4 * 60,
        ),
        NodeBot(
            name = "ops", persona = "Keeps the servers up and says so plainly.", model = "qwen3:8b", approvals = "allowlist",
            channels = listOf(
                BotChannel("telegram", "@studio_ops_bot"),
                BotChannel("slack", "wckd · #ops", status = "down", error = "token revoked"),
            ),
            sessions = 14, lastActive = now - 22 * 60, queue = 2,
        ),
        NodeBot(
            name = "support", persona = "Answers customer email in a friendly, short way.", model = "claude-sonnet", approvals = "edits",
            channels = listOf(BotChannel("email", "help@studio.example")), sessions = 31, lastActive = now - 3 * 3_600,
        ),
    )

    /** Recent sessions for the sample bots (the demo's Bot detail). */
    fun sampleSessions(now: Long): List<AgentSession> = listOf(
        AgentSession("tg-ops-4411", now - 22 * 60, bot = "ops"),
        AgentSession("slack-ops-c07", now - 5 * 3_600, bot = "ops"),
        AgentSession("tg-ops-4409", now - 26 * 3_600, bot = "ops"),
        AgentSession("email-support-118", now - 3 * 3_600, bot = "support"),
        AgentSession("email-support-117", now - 9 * 3_600, bot = "support"),
        AgentSession("app-studio-phone", now - 4 * 60, bot = DEFAULT),
    )

    /** Approvals waiting for the sample bots. */
    fun sampleApprovals(now: Long): List<PendingApproval> = listOf(
        PendingApproval("a-ops-1", "tg-ops-4411", "run: systemctl restart nginx", AgentAction.Command("systemctl restart nginx"), askedAt = now - 20 * 60, bot = "ops"),
    )
}
