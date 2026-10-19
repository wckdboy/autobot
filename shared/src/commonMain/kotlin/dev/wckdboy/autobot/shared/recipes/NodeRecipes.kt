package dev.wckdboy.autobot.shared.recipes

import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeAgentException
import dev.wckdboy.autobot.shared.invincible.NodeRun
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recipes handed to a paired Invincible node (Invincible's spec/agent.md, "Recipes from Autobot"):
 * the node's agent runs them on its schedule, always on and with no time limit, offering only the
 * recipe's tool groups; the phone collects each run's answer and checks the node's signed
 * receipts for it.
 */
object NodeRecipes {
    /** The node's id for [recipe]'s schedule. */
    fun scheduleId(recipe: Recipe): String =
        "r-" + recipe.id.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }.take(60).ifEmpty { "recipe" }

    /** Tool groups the node has nothing for (shown in the editor). */
    private val NOT_ON_NODES = setOf(RecipeTools.WEB.id, RecipeTools.VAULT.id)

    /** [recipe]'s tool groups, as the node names them. */
    fun groups(recipe: Recipe): List<String> = RecipeTools.groups.filter { RecipeTools.isOn(recipe.tools, it) }.map { it.id }

    /** What [recipe] asks for that a node cannot give it, in words; empty when it all carries over. */
    fun limits(recipe: Recipe): List<String> = buildList {
        RecipeTools.groups.filter { it.id in NOT_ON_NODES && RecipeTools.isOn(recipe.tools, it) }.forEach {
            add("${it.label} is not available on the node")
        }
        if (recipe.includeRecentChats) add("Recent chats stay on this phone: the node does not get them")
        if (recipe.requiresCharging) add("“Only while charging” is about this phone; the node runs on its schedule")
    }

    /**
     * [schedule] as the node's `when`, in UTC with the phone's current offset in [zone], so it runs
     * at the phone's wall-clock time wherever the node is. The phone sends it again when the
     * offset changes ([needsPush]).
     */
    fun cron(schedule: RecipeSchedule, zone: TimeZone, nowMillis: Long): String {
        val offset = zone.offsetAt(kotlin.time.Instant.fromEpochMilliseconds(nowMillis)).totalSeconds / 60
        fun utc(minuteOfDay: Int): Pair<Int, Int> {
            val m = minuteOfDay - offset
            return m.mod(1440) to m.floorDiv(1440)
        }
        fun days(days: List<Int>, shift: Int) = days.map { (it + shift).mod(7) }.sorted().joinToString(",")
        return when (schedule) {
            is RecipeSchedule.Daily -> utc(schedule.minuteOfDay).let { (m, _) -> "CRON_TZ=UTC ${m % 60} ${m / 60} * * *" }
            is RecipeSchedule.Weekdays -> utc(schedule.minuteOfDay).let { (m, shift) -> "CRON_TZ=UTC ${m % 60} ${m / 60} * * ${days(listOf(1, 2, 3, 4, 5), shift)}" }
            // Cron counts Sunday as 0; ISO as 7.
            is RecipeSchedule.Weekly -> utc(schedule.minuteOfDay).let { (m, shift) -> "CRON_TZ=UTC ${m % 60} ${m / 60} * * ${days(listOf(schedule.dayOfWeek % 7), shift)}" }
            is RecipeSchedule.EveryHours -> "every ${schedule.hours.coerceIn(1, RecipeSchedule.MAX_HOURS)}h"
        }
    }

    /** The schedule body the node is sent for [recipe]. */
    fun body(recipe: Recipe, zone: TimeZone, nowMillis: Long): String = buildJsonObject {
        put("id", scheduleId(recipe))
        put("name", recipe.name)
        put("when", cron(recipe.schedule, zone, nowMillis))
        put("task", recipe.prompt)
        put("tools", JsonArray(groups(recipe).map(::JsonPrimitive)))
        put("enabled", recipe.enabled)
    }.toString()

    private val runJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encodeRun(run: NodeRun): String = buildJsonObject {
        put("at", run.at)
        put("finished", run.finished)
        put("ok", run.ok)
        put("result", run.result)
        put("receipts", JsonArray(run.receipts.map(::JsonPrimitive)))
        put("trigger", run.trigger)
    }.toString()

    fun decodeRun(text: String): NodeRun? = runCatching {
        val o = runJson.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        fun p(key: String) = o[key] as? JsonPrimitive
        NodeRun(
            at = p("at")!!.content.toLong(),
            finished = p("finished")?.content?.toLongOrNull() ?: 0,
            ok = p("ok")?.content == "true",
            result = p("result")?.content.orEmpty(),
            receipts = (o["receipts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            trigger = p("trigger")?.content ?: "schedule",
        )
    }.getOrNull()

    /** Hands [recipe] to the node (creates or replaces its schedule there). */
    suspend fun push(agent: NodeAgent, recipe: Recipe, zone: TimeZone = TimeZone.currentSystemDefault(), nowMillis: Long = Clock.System.now().toEpochMilliseconds()) {
        agent.putSchedule(body(recipe, zone, nowMillis))
    }

    /** Takes [recipe] off the node (it moved to the phone, or was deleted); fine when it was not there. */
    suspend fun remove(agent: NodeAgent, recipe: Recipe) {
        try {
            agent.removeSchedule(scheduleId(recipe))
        } catch (e: NodeAgentException) {
            if (e.status != 404) throw e
        }
    }

    /** Starts [recipe] on the node now; false when it is already running there. */
    suspend fun runNow(agent: NodeAgent, recipe: Recipe): Boolean = agent.runSchedule(scheduleId(recipe))

    /** Runs the node finished that the phone has not collected yet (after [recipe]'s last run), oldest first. */
    suspend fun newRuns(agent: NodeAgent, recipe: Recipe): List<NodeRun> =
        agent.runs(scheduleId(recipe), after = (recipe.state.lastRun?.startedAt ?: 0L) / 1000)

    /**
     * [run] as a chat: the recipe's prompt, the node's answer, and a privacy receipt holding the
     * node's own signed receipts for the run ([receipts], already checked against the pinned key).
     */
    fun transcript(recipe: Recipe, run: NodeRun, model: String?, receipts: List<ReceiptNode>): List<SessionEvent> = listOf(
        SessionEvent.TurnStart(1),
        SessionEvent.UserMessage(recipe.prompt),
        SessionEvent.AssistantMessage(turn = 1, step = 1, text = if (run.ok) run.result else "✗ ${run.result}", model = model),
        SessionEvent.TurnReceipt(turn = 1, nodes = receipts),
        SessionEvent.TurnEnd(1, if (run.ok) TurnEndReason.COMPLETED else TurnEndReason.ERROR, if (run.ok) null else run.result.take(200)),
    )

    /** The phone's record of [run]: [receipts] say what left the node, and whether they verified. */
    fun record(run: NodeRun, sessionId: String, receipts: List<ReceiptNode>): RecipeRunRecord {
        val egress = receipts.flatMap { it.egress }
        val notes = buildList {
            if (receipts.any { !it.verified }) add("A receipt from the node did not verify")
            if (run.receipts.isEmpty() && run.ok) add("The node kept no receipt for this run")
        }
        return RecipeRunRecord(
            startedAt = run.at * 1000,
            finishedAt = run.finished * 1000,
            status = if (run.ok) RecipeRunStatus.DONE else RecipeRunStatus.FAILED,
            trigger = if (run.trigger == "manual") RecipeTrigger.MANUAL else RecipeTrigger.SCHEDULE,
            sessionId = sessionId,
            summary = RecipeCopy.summarize(run.result),
            notes = notes,
            hosts = egress.map { it.host }.distinct().size,
            bytesSent = egress.sumOf { it.bytesSent },
        )
    }

    /**
     * Whether [recipe] must be sent to its node again: it changed in a way the node sees, or the
     * phone's UTC offset moved (daylight saving, travel), shifting its UTC schedule.
     */
    fun needsPush(recipe: Recipe, previous: Recipe?, zone: TimeZone, nowMillis: Long): Boolean =
        recipe.runsOn != null && (
            previous == null || previous.runsOn != recipe.runsOn || previous.prompt != recipe.prompt || previous.name != recipe.name ||
                previous.schedule != recipe.schedule || previous.enabled != recipe.enabled || groups(previous) != groups(recipe) ||
                recipe.state.pushed != pushed(recipe, zone, nowMillis)
            )

    /** What the node holds for [recipe] once it is pushed, kept in [RecipeState.pushed]. */
    fun pushed(recipe: Recipe, zone: TimeZone, nowMillis: Long): String = "${recipe.runsOn}|${cron(recipe.schedule, zone, nowMillis)}"
}
