package dev.wckdboy.autobot.shared.recipes

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Next-run times: wall-clock schedules in the phone's zone, across DST gaps and overlaps. */
class RecipeScheduleTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")
    private val utc = TimeZone.UTC

    private fun at(text: String, zone: TimeZone): Long = LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds()
    private fun local(millis: Long, zone: TimeZone): String = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).toString()
    private fun utc(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

    @Test
    fun dailyRunsLaterTodayOrTomorrow() {
        val daily = RecipeSchedule.Daily(7 * 60)
        assertEquals("2026-10-18T07:00", local(daily.next(at("2026-10-18T06:59", berlin), berlin), berlin))
        assertEquals("2026-10-19T07:00", local(daily.next(at("2026-10-18T07:00", berlin), berlin), berlin))
        assertEquals("2026-10-19T07:00", local(daily.next(at("2026-10-18T23:59", berlin), berlin), berlin))
    }

    @Test
    fun nextIsStrictlyAfterTheReference() {
        val daily = RecipeSchedule.Daily(7 * 60)
        val seven = at("2026-10-18T07:00", berlin)
        assertTrue(daily.next(seven, berlin) > seven)
        assertEquals(seven, daily.next(seven - 1, berlin))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val weekdays = RecipeSchedule.Weekdays(7 * 60)
        // Friday 16 Oct 2026 after 07:00 → Monday 19 Oct.
        assertEquals("2026-10-19T07:00", local(weekdays.next(at("2026-10-16T08:00", berlin), berlin), berlin))
        assertEquals("2026-10-19T07:00", local(weekdays.next(at("2026-10-17T06:00", berlin), berlin), berlin))
        assertEquals("2026-10-16T07:00", local(weekdays.next(at("2026-10-16T06:00", berlin), berlin), berlin))
    }

    @Test
    fun weeklyFindsItsDay() {
        val friday = RecipeSchedule.Weekly(dayOfWeek = 5, minuteOfDay = 18 * 60)
        assertEquals("2026-10-23T18:00", local(friday.next(at("2026-10-16T18:00", berlin), berlin), berlin))
        assertEquals("2026-10-16T18:00", local(friday.next(at("2026-10-16T17:59", berlin), berlin), berlin))
        val sunday = RecipeSchedule.Weekly(dayOfWeek = 7, minuteOfDay = 0)
        assertEquals("2026-10-18T00:00", local(sunday.next(at("2026-10-17T12:00", berlin), berlin), berlin))
    }

    @Test
    fun everyHoursCountsElapsedTimeAcrossDst() {
        val six = RecipeSchedule.EveryHours(6)
        // 29 Mar 2026: Berlin skips 02:00–03:00. Six real hours after 00:00 is 07:00 local.
        val start = at("2026-03-29T00:00", berlin)
        assertEquals(start + 6 * RecipeSchedule.HOUR_MS, six.next(start, berlin))
        assertEquals("2026-03-29T07:00", local(six.next(start, berlin), berlin))
    }

    @Test
    fun sevenStaysSevenAcrossSpringForward() {
        val daily = RecipeSchedule.Daily(7 * 60)
        val sat = daily.next(at("2026-03-28T06:00", berlin), berlin)
        val sun = daily.next(sat, berlin)
        val mon = daily.next(sun, berlin)
        assertEquals("2026-03-28T06:00:00Z", utc(sat)) // CET, UTC+1
        assertEquals("2026-03-29T05:00:00Z", utc(sun)) // CEST, UTC+2
        assertEquals("2026-03-30T05:00:00Z", utc(mon))
        // That day is only 23 hours long.
        assertEquals(23 * RecipeSchedule.HOUR_MS, sun - sat)
    }

    @Test
    fun sevenStaysSevenAcrossFallBack() {
        val daily = RecipeSchedule.Daily(7 * 60)
        val sat = daily.next(at("2026-10-24T06:00", berlin), berlin)
        val sun = daily.next(sat, berlin)
        assertEquals("2026-10-24T05:00:00Z", utc(sat))
        assertEquals("2026-10-25T06:00:00Z", utc(sun))
        assertEquals(25 * RecipeSchedule.HOUR_MS, sun - sat)
    }

    @Test
    fun aTimeInTheSpringGapRunsShiftedByTheGap() {
        val daily = RecipeSchedule.Daily(2 * 60 + 30)
        val next = daily.next(at("2026-03-29T00:00", berlin), berlin)
        assertEquals("2026-03-29T03:30", local(next, berlin))
        assertEquals("2026-03-29T01:30:00Z", utc(next))
        // And the day after it is back at 02:30.
        assertEquals("2026-03-30T02:30", local(daily.next(next, berlin), berlin))
    }

    @Test
    fun aTimeThatHappensTwiceRunsOnce() {
        val daily = RecipeSchedule.Daily(2 * 60 + 30)
        // 25 Oct 2026: Berlin goes 03:00 CEST → 02:00 CET, so 02:30 happens twice.
        val first = daily.next(at("2026-10-25T00:00", berlin), berlin)
        assertEquals("2026-10-25T00:30:00Z", utc(first)) // the first 02:30 (CEST)
        val next = daily.next(first, berlin)
        assertEquals("2026-10-26T02:30", local(next, berlin))
        // Asking from between the two 02:30s does not find the second one either.
        val between = first + 30 * 60_000L
        assertEquals(next, daily.next(between, berlin))
    }

    @Test
    fun newYorkSpringForwardAndAHalfHourZone() {
        val daily = RecipeSchedule.Daily(7 * 60)
        val sun = daily.next(at("2026-03-08T00:00", newYork), newYork)
        assertEquals("2026-03-08T11:00:00Z", utc(sun)) // EDT, UTC-4
        // Lord Howe Island moves its clocks by thirty minutes.
        val lordHowe = TimeZone.of("Australia/Lord_Howe")
        val a = daily.next(at("2026-04-03T08:00", lordHowe), lordHowe)
        val b = daily.next(a, lordHowe)
        assertEquals("2026-04-04T07:00", local(a, lordHowe))
        assertEquals("2026-04-05T07:00", local(b, lordHowe))
        assertEquals(24 * RecipeSchedule.HOUR_MS + 30 * 60_000L, b - a)
    }

    @Test
    fun theSameScheduleFollowsTheZoneItIsAskedIn() {
        val daily = RecipeSchedule.Daily(7 * 60)
        val now = at("2026-10-18T03:00", utc)
        assertEquals("2026-10-18T05:00:00Z", utc(daily.next(now, berlin)))
        assertEquals("2026-10-18T11:00:00Z", utc(daily.next(now, newYork)))
    }

    @Test
    fun validationAndLabels() {
        assertNull(RecipeSchedule.Daily(0).problem())
        assertEquals("Pick a time of day", RecipeSchedule.Daily(1440).problem())
        assertEquals("Pick a day of the week", RecipeSchedule.Weekly(8, 60).problem())
        assertEquals("Pick every 1 to 24 hours", RecipeSchedule.EveryHours(0).problem())
        assertEquals("Every day at 07:00", RecipeSchedule.Daily(420).label())
        assertEquals("Weekdays at 07:05", RecipeSchedule.Weekdays(425).label())
        assertEquals("Fridays at 18:30", RecipeSchedule.Weekly(5, 18 * 60 + 30).label())
        assertEquals("Every hour", RecipeSchedule.EveryHours(1).label())
        assertEquals("Every 6 hours", RecipeSchedule.EveryHours(6).label())
    }

    @Test
    fun switchingKindsKeepsTheTime() {
        val daily = RecipeSchedule.Daily(9 * 60 + 15)
        assertEquals(RecipeSchedule.Weekdays(555), RecipeSchedule.of(RecipeSchedule.Kind.WEEKDAYS, daily))
        assertEquals(RecipeSchedule.Weekly(1, 555), RecipeSchedule.of(RecipeSchedule.Kind.WEEKLY, daily))
        assertEquals(RecipeSchedule.EveryHours(6), RecipeSchedule.of(RecipeSchedule.Kind.EVERY_HOURS, daily))
        assertEquals(RecipeSchedule.Daily(RecipeSchedule.DEFAULT_MINUTE), RecipeSchedule.of(RecipeSchedule.Kind.DAILY, RecipeSchedule.EveryHours(3)))
    }

    @Test
    fun everyDayOfTheYearIsExactlyOneRunApart() {
        // A year of daily runs in a DST zone: each next run is the next calendar day at 07:00.
        val daily = RecipeSchedule.Daily(7 * 60)
        var t = at("2026-01-01T07:00", berlin)
        repeat(365) {
            val next = daily.next(t, berlin)
            val a = Instant.fromEpochMilliseconds(t).toLocalDateTime(berlin)
            val b = Instant.fromEpochMilliseconds(next).toLocalDateTime(berlin)
            assertEquals("07:00", b.time.toString())
            assertEquals(a.date.toEpochDays() + 1, b.date.toEpochDays())
            t = next
        }
    }
}
