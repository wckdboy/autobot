package dev.wckdboy.autobot.shared.recipes

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * When a recipe runs. Wall-clock schedules ([Daily], [Weekdays], [Weekly]) follow the phone's time
 * zone: "07:00" stays 07:00 across daylight saving changes and after travelling. A time that does
 * not exist on a spring-forward day (02:30 when clocks jump from 02:00 to 03:00) runs shifted by
 * the gap (03:30); a time that happens twice on a fall-back day runs once, at the first one.
 * [EveryHours] counts elapsed hours from the last run, whatever the clocks do.
 */
@Serializable
sealed interface RecipeSchedule {
    /** Every day at [minuteOfDay] (0 ..< 1440, local time). */
    @Serializable
    @SerialName("daily")
    data class Daily(val minuteOfDay: Int) : RecipeSchedule

    /** Monday to Friday at [minuteOfDay]. */
    @Serializable
    @SerialName("weekdays")
    data class Weekdays(val minuteOfDay: Int) : RecipeSchedule

    /** Once a week on [dayOfWeek] (ISO: 1 = Monday … 7 = Sunday) at [minuteOfDay]. */
    @Serializable
    @SerialName("weekly")
    data class Weekly(val dayOfWeek: Int, val minuteOfDay: Int) : RecipeSchedule

    /** Every [hours] hours (1 … 24) after the last run (the first one [hours] after enabling). */
    @Serializable
    @SerialName("hours")
    data class EveryHours(val hours: Int) : RecipeSchedule

    /** Why this schedule is not valid (null: it is). */
    fun problem(): String? = when (this) {
        is Daily -> timeProblem(minuteOfDay)
        is Weekdays -> timeProblem(minuteOfDay)
        is Weekly -> if (dayOfWeek !in 1..7) "Pick a day of the week" else timeProblem(minuteOfDay)
        is EveryHours -> if (hours !in 1..MAX_HOURS) "Pick every 1 to $MAX_HOURS hours" else null
    }

    /**
     * The first run strictly after [afterMillis] (epoch ms) in [zone]. For [EveryHours] that is
     * [afterMillis] plus the interval: call it with the time of the last run (or of enabling).
     */
    fun next(afterMillis: Long, zone: TimeZone): Long {
        if (this is EveryHours) return afterMillis + hours.coerceIn(1, MAX_HOURS) * HOUR_MS
        val after = Instant.fromEpochMilliseconds(afterMillis)
        val minute = when (this) {
            is Daily -> minuteOfDay
            is Weekdays -> minuteOfDay
            is Weekly -> minuteOfDay
            is EveryHours -> 0
        }.coerceIn(0, MINUTES_PER_DAY - 1)
        val time = LocalTime(minute / 60, minute % 60)
        val today = after.toLocalDateTime(zone).date
        // Eight days always reach the next matching day (a weekly one at most seven away, plus
        // today when its time has passed).
        for (offset in 0..8) {
            val date = today.plus(DatePeriod(days = offset))
            if (!matches(date.dayOfWeek)) continue
            val at = LocalDateTime(date, time).toInstant(zone)
            if (at > after) return at.toEpochMilliseconds()
        }
        // Unreachable for valid schedules; a day later keeps the scheduler going.
        return afterMillis + DAY_MS
    }

    private fun matches(day: DayOfWeek): Boolean = when (this) {
        is Daily -> true
        is Weekdays -> day.isoDayNumber in 1..5
        is Weekly -> day.isoDayNumber == dayOfWeek
        is EveryHours -> true
    }

    /** "Every day at 07:00", "Weekdays at 07:00", "Fridays at 18:30", "Every 6 hours". */
    fun label(): String = when (this) {
        is Daily -> "Every day at ${clock(minuteOfDay)}"
        is Weekdays -> "Weekdays at ${clock(minuteOfDay)}"
        is Weekly -> "${dayName(dayOfWeek)}s at ${clock(minuteOfDay)}"
        is EveryHours -> if (hours == 1) "Every hour" else "Every $hours hours"
    }

    /** The kind, as the editor's picker names it. */
    val kind: Kind
        get() = when (this) {
            is Daily -> Kind.DAILY
            is Weekdays -> Kind.WEEKDAYS
            is Weekly -> Kind.WEEKLY
            is EveryHours -> Kind.EVERY_HOURS
        }

    /** The time of day of a wall-clock schedule (null for [EveryHours]). */
    val minute: Int?
        get() = when (this) {
            is Daily -> minuteOfDay
            is Weekdays -> minuteOfDay
            is Weekly -> minuteOfDay
            is EveryHours -> null
        }

    /** The schedule kinds in picker order. */
    enum class Kind(val label: String) {
        DAILY("Daily"),
        WEEKDAYS("Weekdays"),
        WEEKLY("Weekly"),
        EVERY_HOURS("Every N hours"),
    }

    companion object {
        const val MAX_HOURS = 24
        const val MINUTES_PER_DAY = 24 * 60
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS

        /** Day names, Monday first (ISO numbering minus one). */
        val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        fun dayName(isoDay: Int): String = DAY_NAMES.getOrElse(isoDay - 1) { "Day" }

        /** 420 → "07:00". */
        fun clock(minuteOfDay: Int): String {
            val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
            return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
        }

        /**
         * A schedule of [kind] that keeps what it can of [from] (the time of day, the interval):
         * switching kinds in the editor does not lose the chosen time.
         */
        fun of(kind: Kind, from: RecipeSchedule, defaultDay: Int = 1): RecipeSchedule {
            val minute = from.minute ?: DEFAULT_MINUTE
            return when (kind) {
                Kind.DAILY -> Daily(minute)
                Kind.WEEKDAYS -> Weekdays(minute)
                Kind.WEEKLY -> Weekly((from as? Weekly)?.dayOfWeek ?: defaultDay, minute)
                Kind.EVERY_HOURS -> (from as? EveryHours) ?: EveryHours(DEFAULT_HOURS)
            }
        }

        /** 07:00. */
        const val DEFAULT_MINUTE = 7 * 60
        const val DEFAULT_HOURS = 6

        private fun timeProblem(minute: Int): String? = if (minute !in 0 until MINUTES_PER_DAY) "Pick a time of day" else null
    }
}
