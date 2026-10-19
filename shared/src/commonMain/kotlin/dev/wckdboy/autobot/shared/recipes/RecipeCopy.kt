package dev.wckdboy.autobot.shared.recipes

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/** Every user-facing recipe string, so both apps say the same thing. */
object RecipeCopy {
    const val SCREEN_TITLE = "Recipes"
    const val SETTINGS_ROW = "Recipes"
    const val SETTINGS_DETAIL = "Scheduled on-device agents"
    const val FOLDER = "Recipes"
    const val RUN_NOW = "Run now"
    const val NEW_RECIPE = "New recipe"
    const val EMPTY = "No recipes yet."
    const val NO_MODEL = "Download an on-device chat model first: recipes run only on this phone's model."
    const val SUMMARY_SWITCH = "Summary in notifications"
    const val SUMMARY_DETAIL = "Off: a notification only says that a recipe finished. On: it adds a short summary, still hidden on the lock screen."
    const val TOOLS_HEADER = "Allowed tools"
    const val TOOLS_DETAIL = "Allowed tools run without asking during this recipe. Anything else that needs approval is denied and noted in the result."
    const val RECENT_CHATS = "Include recent chats"
    const val RECENT_CHATS_DETAIL = "Adds the text of chats changed since the last run (not incognito chats, not recipe results)."
    const val CHARGING = "Only while charging"
    const val BATTERY = "Not when the battery is low"
    const val MODEL_HEADER = "On-device model"
    const val RUNS_ON_HEADER = "Runs on"
    const val THIS_PHONE = "This phone"
    const val NODE_DETAIL = "Your node runs it on its schedule: always on, no time limit, on its own models. Results come back here with the node's signed receipts."

    /** "Daily at 07:00 · on studio" for a recipe on a node. */
    fun where(scheduleLabel: String, node: String?): String = if (node == null) scheduleLabel else "$scheduleLabel · on $node"
    const val MODEL_DEFAULT = "This phone's default"
    const val NETWORK_NOTE = "Uses the network: it waits for Wi-Fi (an unmetered network), goes through the kill switch and is listed in the audit log and the run's receipt."
    const val OPEN_RESULT = "Open result"
    const val DELETE = "Delete recipe"

    /** How the platform schedules runs, shown under the list. */
    const val ANDROID_NOTE =
        "Recipes run on this phone's model at their time once their conditions are met, even while Autobot is closed. " +
            "Each run leaves a notification, a chat in the Recipes folder and a privacy receipt."
    const val IOS_NOTE =
        "iOS decides when background work runs: a recipe runs around its time, often later, and only while the phone is unlocked " +
            "(Autobot's data is encrypted while it is locked). In the background it runs on the CPU, so it is slower. " +
            "Due recipes also run when you open Autobot. Each run leaves a notification, a chat in the Recipes folder and a privacy receipt."

    // Notifications: the title never carries content beyond the recipe's name.
    fun finishedTitle(name: String) = "Recipe $name finished"
    fun failedTitle(name: String) = "Recipe $name didn't finish"
    fun unlockToRun(name: String) = "Unlock to run $name"
    const val OPEN_TO_READ = "Open Autobot to read the result."
    const val UNLOCK_BODY = "It runs when Autobot can read its encrypted data again."
    const val CHANNEL_NAME = "Recipe results"
    const val CHANNEL_DETAIL = "A scheduled recipe finished or is waiting for an unlock"

    /** The notification's title for [record]. */
    fun title(name: String, record: RecipeRunRecord): String =
        if (record.status == RecipeRunStatus.DONE) finishedTitle(name) else failedTitle(name)

    /** The notification's body: the summary only when the user switched [showSummary] on. */
    fun body(record: RecipeRunRecord, showSummary: Boolean): String {
        if (!showSummary) return OPEN_TO_READ
        val text = record.summary.ifBlank { null } ?: return OPEN_TO_READ
        return listOfNotNull(text, record.notes.firstOrNull()).joinToString(" · ")
    }

    /** The chat a run writes to: "Morning brief · Sat 18 Oct 07:00". */
    fun chatTitle(name: String, atMillis: Long, zone: TimeZone): String {
        val t = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
        val day = RecipeSchedule.dayName(t.dayOfWeek.isoDayNumber).take(3)
        val month = MONTHS[t.month.ordinal]
        return "${name.trim().ifEmpty { "Recipe" }} · $day ${t.day} $month ${RecipeSchedule.clock(t.hour * 60 + t.minute)}"
    }

    /**
     * When [atMillis] is, as the list says it: "today 07:00", "tomorrow 07:00", "Fri 18:00",
     * "18 Oct 07:00". [approximate] adds "around" (iOS decides the exact time).
     */
    fun moment(atMillis: Long, nowMillis: Long, zone: TimeZone, approximate: Boolean = false): String {
        val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
        val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
        val clock = RecipeSchedule.clock(at.hour * 60 + at.minute)
        val days = now.date.daysUntil(at.date)
        val day = when (days) {
            0 -> "today"
            1 -> "tomorrow"
            -1 -> "yesterday"
            in 2..6 -> RecipeSchedule.dayName(at.dayOfWeek.isoDayNumber).take(3)
            else -> "${at.day} ${MONTHS[at.month.ordinal]}"
        }
        return if (approximate) "$day around $clock" else "$day $clock"
    }

    /** The list row's status line for [recipe]. */
    fun status(recipe: Recipe, nowMillis: Long, zone: TimeZone, approximate: Boolean): String {
        val state = recipe.state
        val next = when {
            !recipe.enabled -> "Off"
            state.waitingForUnlock -> "Waiting for unlock"
            state.nextRunAt == null -> "Not scheduled"
            state.nextRunAt <= nowMillis -> "Due now"
            else -> "Next: ${moment(state.nextRunAt, nowMillis, zone, approximate)}"
        }
        val last = state.lastRun?.let { r -> "${lastLabel(r)} ${moment(r.finishedAt, nowMillis, zone)}" }
        return listOfNotNull(next, last).joinToString(" · ")
    }

    fun lastLabel(record: RecipeRunRecord): String = when (record.status) {
        RecipeRunStatus.DONE -> "Done"
        RecipeRunStatus.FAILED -> "Failed"
        RecipeRunStatus.TIMED_OUT -> "Timed out"
    }

    fun neededApproval(tool: String) = "Needed approval for $tool"
    fun notAllowed(tool: String) = "$tool is not allowed in this recipe"
    const val NO_LOCAL_MODEL = "No on-device chat model is installed"
    const val ON_DEVICE_ONLY = "Recipes run only on an on-device model"
    fun modelMissing(model: String) = "The model $model is not on this phone any more"
    const val TIMED_OUT = "Stopped: the run took too long"
    const val EMPTY_ANSWER = "The model returned no answer"

    /** [text] as a short plain line: markdown marks dropped, whitespace collapsed, cut at a word. */
    fun summarize(text: String, max: Int = SUMMARY_MAX): String {
        val plain = text
            .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|[-*+]|\\d+[.)]|>)\\s+"), "")
            .replace(Regex("[*_`~]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (plain.length <= max) return plain
        val cut = plain.take(max - 1)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.take(space) else cut).trimEnd(',', ';', ':', '.', ' ') + "…"
    }

    const val SUMMARY_MAX = 140

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
