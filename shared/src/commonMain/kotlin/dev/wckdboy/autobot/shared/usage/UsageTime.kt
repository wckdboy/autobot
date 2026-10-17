package dev.wckdboy.autobot.shared.usage

/**
 * A time zone as the usage board needs it: the offset from UTC at an instant. The platforms
 * supply the phone's zone ([systemZone]); tests use [fixed] offsets or their own DST rules.
 */
fun interface ZoneRules {
    /** Milliseconds to add to UTC at [utcMs] to get local wall-clock time. */
    fun offsetMs(utcMs: Long): Long

    companion object {
        fun fixed(hours: Int, minutes: Int = 0): ZoneRules {
            val ms = (hours * 60L + minutes) * 60_000L
            return ZoneRules { ms }
        }

        val UTC: ZoneRules = fixed(0)
    }
}

/** The phone's current time zone (DST included). */
expect fun systemZone(): ZoneRules

/** A calendar date. [dayOfWeek] is 1 = Monday … 7 = Sunday. */
data class CivilDate(val year: Int, val month: Int, val day: Int) {
    val epochDay: Long get() = Civil.daysFromCivil(year, month, day)
    val dayOfWeek: Int get() = ((epochDay + 3).mod(7L) + 1).toInt()

    /** `2026-10-17` */
    fun iso(): String = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    companion object {
        fun ofEpochDay(day: Long): CivilDate = Civil.civilFromDays(day)
    }
}

/** Gregorian calendar arithmetic (Howard Hinnant's algorithms) and local ↔ UTC conversion. */
object Civil {
    const val DAY_MS = 86_400_000L
    const val HOUR_MS = 3_600_000L

    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (y).floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun civilFromDays(days: Long): CivilDate {
        val z = days + 719_468
        val era = (z).floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return CivilDate(y, m, d)
    }

    fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Local wall-clock milliseconds of [utcMs]. */
    fun local(utcMs: Long, zone: ZoneRules): Long = utcMs + zone.offsetMs(utcMs)

    /** The local date of [utcMs]. */
    fun date(utcMs: Long, zone: ZoneRules): CivilDate = CivilDate.ofEpochDay(local(utcMs, zone).floorDiv(DAY_MS))

    /** Milliseconds since local midnight of [utcMs]. */
    fun timeOfDay(utcMs: Long, zone: ZoneRules): Long = local(utcMs, zone).mod(DAY_MS)

    /**
     * The UTC instant of local wall-clock time [localMs]. A time skipped by a DST change maps to an
     * instant after the change; a repeated one to its first occurrence.
     */
    fun utc(localMs: Long, zone: ZoneRules): Long {
        // The offsets in force around that wall time (a DST change is never more than a day away from both).
        val candidates = listOf(localMs - DAY_MS, localMs, localMs + DAY_MS).map { localMs - zone.offsetMs(it) }.distinct()
        val valid = candidates.filter { local(it, zone) == localMs }
        return valid.minOrNull() ?: candidates.max()
    }

    /** The UTC instant of [date] at [timeOfDay] local time. */
    fun utc(date: CivilDate, timeOfDay: Long, zone: ZoneRules): Long = utc(date.epochDay * DAY_MS + timeOfDay, zone)

    /** Local midnight starting [date], in UTC. */
    fun startOfDay(date: CivilDate, zone: ZoneRules): Long = utc(date, 0, zone)

    /** [date] plus [months] calendar months, the day clamped to the month's length (31 Jan + 1 = 28/29 Feb). */
    fun plusMonths(date: CivilDate, months: Int, day: Int = date.day): CivilDate {
        val index = date.year * 12 + (date.month - 1) + months
        val y = (index).floorDiv(12)
        val m = (index).mod(12) + 1
        return CivilDate(y, m, minOf(day, lengthOfMonth(y, m)))
    }
}
