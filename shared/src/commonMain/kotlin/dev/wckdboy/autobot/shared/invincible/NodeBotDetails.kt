package dev.wckdboy.autobot.shared.invincible

import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A personality a bot can take (`GET /v1/agent/personalities`); [builtin]: it came with the agent. */
data class NodePersonality(val name: String, val description: String = "", val builtin: Boolean = true)

/** A bot's personalities: [active] (null for none), its SOUL.md ([soul], null without one), and the list. */
data class NodePersonalities(val active: String?, val soul: String?, val list: List<NodePersonality>)

/** What a bot keeps about how the owner works (its USER.md): one entry per `- ` line, and the whole text. */
data class NodeProfile(val entries: List<String>, val text: String)

/**
 * Sums from a bot's turn log (`GET /v1/agent/usage`): [cost] in dollars for the models with a
 * price; [unpriced] the cloud models without one.
 */
data class UsageTotals(
    val turns: Int = 0,
    val calls: Int = 0,
    val cloudCalls: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val cloudTokens: Long = 0,
    val seconds: Double = 0.0,
    val cost: Double = 0.0,
    val unpriced: List<String> = emptyList(),
) {
    val tokens: Long get() = input + output
}

/** Today's usage (and one conversation's, when asked for). */
data class NodeUsage(val today: UsageTotals, val session: UsageTotals? = null, val bot: String? = null)

/** A tool and how often it ran. */
data class ToolCount(val name: String, val count: Int)

/** A model a bot called, how often, with how many tokens; [cloud] when it ran off the machine. */
data class ModelUse(val model: String, val calls: Int, val tokens: Long, val cloud: Boolean)

/** Turns that went wrong. */
data class InsightFailures(val errors: Int = 0, val stopped: Int = 0, val toolErrors: Int = 0, val refused: Int = 0) {
    val total: Int get() = errors + stopped + toolErrors + refused
}

/**
 * What a bot did over the last [days] (`GET /v1/agent/insights`): [localCalls] and [localTokens]
 * are the shares (0 to 1) that stayed on the machine.
 */
data class NodeInsights(
    val days: Int = 7,
    val turns: Int = 0,
    val seconds: Double = 0.0,
    val calls: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val cost: Double = 0.0,
    val unpriced: List<String> = emptyList(),
    val localCalls: Double = 1.0,
    val localTokens: Double = 1.0,
    val tools: List<ToolCount> = emptyList(),
    val projects: List<ToolCount> = emptyList(),
    val models: List<ModelUse> = emptyList(),
    val failures: InsightFailures = InsightFailures(),
) {
    val tokens: Long get() = input + output
    val anyCloud: Boolean get() = models.any { it.cloud } || localCalls < 1.0
}

/**
 * What the node did when a bot was switched: [status] `running`, `stopped` or `error` (null from
 * an agent that cannot start or stop bots while it runs: then [restart] is true).
 */
data class BotSwitch(val enabled: Boolean, val restart: Boolean = false, val status: String? = null, val error: String? = null)

/** `POST /v1/agent/bots/reload`: the names in each group. */
data class BotsReload(
    val started: List<String> = emptyList(),
    val stopped: List<String> = emptyList(),
    val restarted: List<String> = emptyList(),
    val persona: List<String> = emptyList(),
    val failed: List<Pair<String, String>> = emptyList(),
    val running: List<String> = emptyList(),
)

/**
 * Bot detail's node parts: each null when the node did not give it (an older node answers 404),
 * with [problem] when it did not answer at all.
 */
data class NodeBotDetail(
    val personalities: NodePersonalities? = null,
    val profile: NodeProfile? = null,
    val usage: NodeUsage? = null,
    val insights: NodeInsights? = null,
    val problem: String? = null,
)

/** One bar of a compact chart: a label, its value in words, and how full it is (0 to 1). */
data class InsightBar(val label: String, val value: String, val fraction: Double)

/** Parsing and words for a bot's personality, profile, usage and insights, the same on Android and iOS. */
object NodeBotDetails {
    /** The personality name that means "none". */
    const val NONE = "none"

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0
    private fun JsonObject.int(key: String): Int = long(key).toInt()
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.names(key: String): List<String> = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonElement?.counts(): List<ToolCount> = (this as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        ToolCount(o.text("name") ?: return@mapNotNull null, o.int("count"))
    }

    fun parsePersonalities(text: String): NodePersonalities {
        val o = obj(text) ?: return NodePersonalities(null, null, emptyList())
        return NodePersonalities(
            active = o.text("active")?.takeIf { it.isNotBlank() && it != NONE },
            soul = o.text("soul")?.takeIf { it.isNotBlank() },
            list = (o["personalities"] as? JsonArray).orEmpty().mapNotNull { e ->
                val p = e as? JsonObject ?: return@mapNotNull null
                NodePersonality(p.text("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null, p.text("description").orEmpty().trim(), (p["builtin"] as? JsonPrimitive)?.booleanOrNull ?: true)
            },
        )
    }

    fun parseProfile(text: String): NodeProfile {
        val o = obj(text) ?: return NodeProfile(emptyList(), "")
        val body = o.text("text").orEmpty()
        val entries = (o["entries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        // A node that sends only the text: its `- ` lines are the entries.
        return NodeProfile(entries.ifEmpty { body.lines().mapNotNull { it.trim().removePrefix("- ").takeIf { l -> it.trim().startsWith("- ") && l.isNotBlank() }?.trim() } }, body)
    }

    private fun totals(o: JsonObject?): UsageTotals? = o?.let {
        UsageTotals(
            turns = it.int("turns"), calls = it.int("calls"), cloudCalls = it.int("cloud_calls"),
            input = it.long("input"), output = it.long("output"), cached = it.long("cached"), cloudTokens = it.long("cloud_tokens"),
            seconds = it.double("seconds") ?: 0.0, cost = it.double("cost") ?: 0.0, unpriced = it.names("unpriced"),
        )
    }

    fun parseUsage(text: String): NodeUsage {
        val o = obj(text) ?: return NodeUsage(UsageTotals())
        return NodeUsage(totals(o["today"] as? JsonObject) ?: UsageTotals(), totals(o["session"] as? JsonObject), o.text("bot"))
    }

    fun parseInsights(text: String): NodeInsights {
        val o = obj(text) ?: return NodeInsights()
        // `tokens` is `{"input", "output", "cached"}`; a plain number counts as all of it.
        val tokens = o["tokens"]
        val (input, output, cached) = when (tokens) {
            is JsonObject -> Triple(tokens.long("input"), tokens.long("output"), tokens.long("cached"))
            is JsonPrimitive -> Triple(tokens.longOrNull ?: 0, 0L, 0L)
            else -> Triple(0L, 0L, 0L)
        }
        val share = o["local_share"] as? JsonObject
        val f = o["failures"] as? JsonObject
        return NodeInsights(
            days = o.int("days").takeIf { it > 0 } ?: 7,
            turns = o.int("turns"),
            seconds = o.double("seconds") ?: 0.0,
            calls = o.int("calls"),
            input = input, output = output, cached = cached,
            cost = o.double("cost") ?: 0.0,
            unpriced = o.names("unpriced"),
            localCalls = share?.double("calls")?.coerceIn(0.0, 1.0) ?: 1.0,
            localTokens = share?.double("tokens")?.coerceIn(0.0, 1.0) ?: 1.0,
            tools = o["tools"].counts(),
            projects = o["projects"].counts(),
            models = (o["models"] as? JsonArray).orEmpty().mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                ModelUse(m.text("model") ?: return@mapNotNull null, m.int("calls"), m.long("tokens"), (m["cloud"] as? JsonPrimitive)?.booleanOrNull ?: false)
            },
            failures = InsightFailures(f?.int("errors") ?: 0, f?.int("stopped") ?: 0, f?.int("tool_errors") ?: 0, f?.int("refused") ?: 0),
        )
    }

    fun parseSwitch(text: String, enabled: Boolean): BotSwitch {
        val o = obj(text) ?: return BotSwitch(enabled, restart = true)
        return BotSwitch(
            enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: enabled,
            restart = (o["restart"] as? JsonPrimitive)?.booleanOrNull ?: false,
            status = o.text("status"),
            error = o.text("error")?.takeIf { it.isNotBlank() },
        )
    }

    fun parseReload(text: String): BotsReload {
        val o = obj(text) ?: return BotsReload()
        return BotsReload(
            started = o.names("started"), stopped = o.names("stopped"), restarted = o.names("restarted"), persona = o.names("persona"),
            failed = (o["failed"] as? JsonArray).orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                (f.text("name") ?: return@mapNotNull null) to f.text("error").orEmpty()
            },
            running = o.names("running"),
        )
    }

    /**
     * Bot [bot]'s personality, profile, usage today and the last [days]' insights. A part the node
     * does not have (404, an older node) is null; a node that does not answer is [NodeBotDetail.problem].
     */
    suspend fun fetch(agent: NodeAgent, bot: String?, days: Int = 7): NodeBotDetail {
        var problem: String? = null
        suspend fun <T> part(block: suspend () -> T): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeAgentException) {
            if (e.status != 404) problem = problem ?: e.message
            null
        } catch (e: Exception) {
            problem = problem ?: (e.message ?: "The node did not answer.")
            null
        }
        val personalities = part { agent.personalities(bot) }
        val profile = part { agent.profile(bot) }
        val usage = part { agent.usage(bot) }
        val insights = part { agent.insights(bot, days) }
        return NodeBotDetail(personalities, profile, usage, insights, problem)
    }

    // Words.

    /** 950, 1.2k, 3.40M. */
    fun tokens(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> "${oneDecimal(n / 1_000.0)}k"
        else -> "${twoDecimals(n / 1_000_000.0)}M"
    }

    /** "$0.04", "$12.30", "<$0.01" for a cost under a cent; null when nothing was priced. */
    fun cost(dollars: Double): String? = when {
        dollars <= 0.0 -> null
        dollars < 0.01 -> "<$0.01"
        else -> "$" + twoDecimals(dollars)
    }

    /** "82 %": a share from 0 to 1. */
    fun percent(share: Double): String = "${(share.coerceIn(0.0, 1.0) * 100).roundToInt()} %"

    /** "12 turns · 48 calls · 210.4k tokens · $0.31" for today; "No turns today." when idle. */
    fun today(t: UsageTotals): String = if (t.turns == 0) "No turns today." else listOfNotNull(
        plural(t.turns, "turn"),
        plural(t.calls, "model call"),
        "${tokens(t.tokens)} tokens",
        cost(t.cost),
    ).joinToString(" · ")

    /** "34 turns · 120 model calls · 1.20M tokens · 2h 4m" over the insights' days. */
    fun summary(i: NodeInsights): String = if (i.turns == 0) "No turns in the last ${plural(i.days, "day")}." else listOfNotNull(
        plural(i.turns, "turn"),
        plural(i.calls, "model call"),
        "${tokens(i.tokens)} tokens",
        duration(i.seconds),
    ).joinToString(" · ")

    /** "local 82 % · cloud 18 %" of the model calls; "all local" when nothing left the machine. */
    fun share(i: NodeInsights): String = if (i.localCalls >= 1.0) "all local" else "local ${percent(i.localCalls)} · cloud ${percent(1 - i.localCalls)}"

    /**
     * The cost line: "$1.24 for cloud calls", plus the cloud models without a price; "$0"
     * when every call stayed on the machine; null when cloud calls had no price at all.
     */
    fun costLine(i: NodeInsights): String? {
        val priced = cost(i.cost)
        val unpriced = i.unpriced.takeIf { it.isNotEmpty() }?.let { "no price for ${it.joinToString(", ")}" }
        return when {
            !i.anyCloud -> "$0"
            priced != null -> listOfNotNull("$priced for cloud calls", unpriced).joinToString(" · ")
            else -> unpriced
        }
    }

    /** The tools it ran most, as bars against the busiest one (at most [max]). */
    fun toolBars(i: NodeInsights, max: Int = 5): List<InsightBar> {
        val top = i.tools.filter { it.count > 0 }.sortedByDescending { it.count }.take(max)
        val most = top.maxOfOrNull { it.count }?.toDouble() ?: return emptyList()
        return top.map { InsightBar(it.name, it.count.toString(), it.count / most) }
    }

    /** The models it called, as bars by calls; the value says "local" or "cloud". */
    fun modelBars(i: NodeInsights, max: Int = 4): List<InsightBar> {
        val top = i.models.filter { it.calls > 0 }.sortedByDescending { it.calls }.take(max)
        val most = top.maxOfOrNull { it.calls }?.toDouble() ?: return emptyList()
        return top.map { InsightBar(it.model, "${it.calls} · ${if (it.cloud) "cloud" else "local"}", it.calls / most) }
    }

    /** "2 failed · 1 stopped · 3 tool errors · 1 refused", leaving out what is zero; null when nothing went wrong. */
    fun failures(f: InsightFailures): String? = listOfNotNull(
        f.errors.takeIf { it > 0 }?.let { "$it failed" },
        f.stopped.takeIf { it > 0 }?.let { "$it stopped" },
        f.toolErrors.takeIf { it > 0 }?.let { plural(it, "tool error") },
        f.refused.takeIf { it > 0 }?.let { "$it refused" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** The active personality's name, or "none". */
    fun personality(p: NodePersonalities): String = p.active ?: NONE

    /** What the active personality is, in its own words; null for none. */
    fun personalityLine(p: NodePersonalities): String? = p.active?.let { a -> p.list.firstOrNull { it.name == a }?.description?.takeIf { it.isNotEmpty() } }

    /** Where [bot]'s profile lives on the node: `~/.autobot/USER.md` for the default bot, `~/.autobot/bots/<name>/USER.md` otherwise. */
    fun profilePath(bot: String?): String =
        if (bot == null || bot == NodeBots.DEFAULT) "~/.autobot/USER.md" else "~/.autobot/bots/$bot/USER.md"

    /** The command to edit it in the node's terminal. */
    fun profileEditCommand(bot: String?): String = "\$EDITOR ${profilePath(bot)}"

    /** A `kept` event, as the chat's quiet note: "kept · profile: prefers short answers". */
    fun kept(text: String): String = "kept · " + text.trim()

    /** What the node did when [name] was switched, in a sentence. */
    fun switched(name: String, s: BotSwitch): String = when {
        s.error != null -> "$name could not start: ${s.error}"
        s.restart || s.status == null -> "Saved. The agent on the node applies it when it restarts."
        s.status == "running" -> "$name is running."
        s.status == "error" -> "$name could not start."
        else -> "$name is stopped."
    }

    /** [bot] as the node says it is after a switch. */
    fun afterSwitch(bot: NodeBot, s: BotSwitch): NodeBot = bot.copy(
        enabled = s.enabled,
        status = s.status ?: NodeBots.switched(bot, s.enabled).status,
        error = if (s.status == "error" || s.error != null) s.error ?: bot.error else null,
    )

    /** "Restarted ops · support took its new persona", or "Nothing to change." */
    fun reloaded(r: BotsReload): String {
        val parts = buildList {
            if (r.started.isNotEmpty()) add("started ${r.started.joinToString(", ")}")
            if (r.stopped.isNotEmpty()) add("stopped ${r.stopped.joinToString(", ")}")
            if (r.restarted.isNotEmpty()) add("restarted ${r.restarted.joinToString(", ")}")
            if (r.persona.isNotEmpty()) add("new persona for ${r.persona.joinToString(", ")}")
            r.failed.forEach { (name, error) -> add("$name failed" + (if (error.isNotEmpty()) ": $error" else "") + " ✗") }
        }
        return if (parts.isEmpty()) "Nothing to change." else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    fun plural(n: Int, word: String): String = "$n $word" + if (n == 1) "" else "s"

    /** "9.4s", "3m 12s", "2h 4m". */
    fun duration(seconds: Double): String {
        val s = seconds.roundToInt()
        return when {
            s < 60 -> "${oneDecimal(seconds)}s"
            s < 3_600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3_600}h ${s % 3_600 / 60}m"
        }
    }

    private fun oneDecimal(v: Double): String {
        val tenths = (v * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun twoDecimals(v: Double): String {
        val cents = (v * 100).roundToInt()
        return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }

    // Demo data (debug builds' sample, screenshots).

    /** The sample bots' details: personalities, a profile, today and the last week. */
    fun sample(bot: String): NodeBotDetail {
        val personalities = NodePersonalities(
            active = when (bot) {
                "ops" -> "concise"
                "support" -> "friendly"
                else -> null
            },
            soul = null,
            list = listOf(
                NodePersonality("concise", "Short answers, no preamble."),
                NodePersonality("friendly", "Warm and plain; says what happens next."),
                NodePersonality("teacher", "Explains the why, one step at a time."),
                NodePersonality("reviewer", "Reads critically and names the risks.", builtin = false),
            ),
        )
        val profile = when (bot) {
            "ops" -> NodeProfile(
                listOf("Wants restarts confirmed in one line.", "On call on weekdays, 08:00 to 18:00.", "Uses systemd and nginx on studio-mac."),
                "",
            )
            "support" -> NodeProfile(listOf("Signs replies as \"The studio team\".", "Prefers British spelling."), "")
            else -> NodeProfile(listOf("Prefers short answers.", "Works in Kotlin and Swift.", "Keeps notes in ~/notes."), "")
        }
        val insights = when (bot) {
            "ops" -> NodeInsights(
                days = 7, turns = 46, seconds = 1_912.0, calls = 131, input = 402_000, output = 38_400, cached = 120_000,
                localCalls = 1.0, localTokens = 1.0,
                tools = listOf(ToolCount("terminal", 58), ToolCount("read_file", 31), ToolCount("web_fetch", 9), ToolCount("remember", 4)),
                models = listOf(ModelUse("qwen3:8b", 131, 440_400, false)),
                failures = InsightFailures(errors = 1, refused = 2),
            )
            "support" -> NodeInsights(
                days = 7, turns = 88, seconds = 2_740.0, calls = 176, input = 610_000, output = 92_000, cached = 300_000,
                cost = 1.24, localCalls = 0.27, localTokens = 0.18,
                tools = listOf(ToolCount("read_file", 40), ToolCount("search_sessions", 22), ToolCount("remember", 12)),
                models = listOf(ModelUse("claude-sonnet", 128, 576_000, true), ModelUse("qwen3.5-4b", 48, 126_000, false)),
            )
            else -> NodeInsights(
                days = 7, turns = 21, seconds = 1_260.0, calls = 64, input = 188_000, output = 21_300, cached = 60_000,
                localCalls = 0.94, localTokens = 0.97, unpriced = listOf("deepseek-chat"),
                tools = listOf(ToolCount("read_file", 26), ToolCount("edit_file", 14), ToolCount("terminal", 11), ToolCount("web_fetch", 3)),
                models = listOf(ModelUse("qwen3.5-4b", 60, 203_000, false), ModelUse("deepseek-chat", 4, 6_300, true)),
            )
        }
        val today = when (bot) {
            "ops" -> UsageTotals(turns = 6, calls = 17, input = 52_000, output = 4_100)
            "support" -> UsageTotals(turns = 12, calls = 25, cloudCalls = 18, input = 88_000, output = 12_500, cloudTokens = 80_000, cost = 0.21)
            else -> UsageTotals(turns = 3, calls = 9, input = 24_000, output = 2_800)
        }
        return NodeBotDetail(personalities, profile, NodeUsage(today, bot = bot), insights)
    }
}

/** The node chat the debug builds' demo shows (screenshots): a photo, the answer, what the agent kept. */
object NodeChatSample {
    const val QUESTION = "What is on this shelf? Keep it short."
    const val ANSWER = "Three Raspberry Pi 4 boards, a USB hub and a label printer. The top Pi has no SD card in it."
    const val ROUTE = "qwen3-vl-8b · node"
    const val KEPT = "profile: wants short answers about hardware"
    const val FOLLOW_UP = "Which one runs Home Assistant?"
    const val FOLLOW_ANSWER = "The middle one: `ha.local` answers from it, and its LED blinks with the dashboard's polling."

    val info: AgentInfo = AgentInfo("Autobot", "qwen3-vl-8b", "/Users/me/work/lab", "ask", private = true, bots = listOf("default", "ops", "support"))
}
