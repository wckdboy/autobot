package dev.wckdboy.autobot.core.a2ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * DateTimeInput values: ISO 8601 in the data model, the device's own words on screen. A date is
 * `2026-10-21`, a time `14:30`, both `2026-10-21T14:30:00` (local, no zone), as the spec's
 * examples write them; an incoming value with a zone or offset is shown in local time.
 */
internal object A2uiDateTime {
    class Value(val date: LocalDate?, val time: LocalTime?)

    fun parse(text: String?): Value? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { return LocalDate.parse(s).let { Value(it, null) } }
        runCatching { return LocalTime.parse(s).let { Value(null, it) } }
        runCatching { return LocalDateTime.parse(s).let { Value(it.toLocalDate(), it.toLocalTime()) } }
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).let { Value(it.toLocalDate(), it.toLocalTime()) } }
        runCatching { return Instant.parse(s).atZone(ZoneId.systemDefault()).let { Value(it.toLocalDate(), it.toLocalTime()) } }
        return null
    }

    fun format(date: LocalDate?, time: LocalTime?): String = when {
        date != null && time != null -> LocalDateTime.of(date, time.withSecond(0).withNano(0)).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        date != null -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        time != null -> time.withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("HH:mm"))
        else -> ""
    }

    fun display(value: Value?): String {
        value ?: return ""
        val date = value.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        val time = value.time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        return listOfNotNull(date, time).joinToString(", ")
    }

    /** The DatePicker's UTC-midnight millis for [date]. */
    fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromUtcMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
