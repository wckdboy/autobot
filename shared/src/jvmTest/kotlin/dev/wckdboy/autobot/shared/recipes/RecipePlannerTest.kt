package dev.wckdboy.autobot.shared.recipes

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Due runs, missed runs (once, never a backlog), manual runs, unlock waits and zone changes. */
class RecipePlannerTest {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val newYork = TimeZone.of("America/New_York")

    private fun at(text: String, zone: TimeZone = berlin): Long = LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds()
    private fun local(millis: Long?, zone: TimeZone = berlin): String = Instant.fromEpochMilliseconds(millis!!).toLocalDateTime(zone).toString()

    private val brief = Recipe(id = "r", name = "Brief", prompt = "Summarise", schedule = RecipeSchedule.Daily(7 * 60))

    private fun record(start: Long, trigger: RecipeTrigger = RecipeTrigger.SCHEDULE) =
        RecipeRunRecord(startedAt = start, finishedAt = start + 60_000, status = RecipeRunStatus.DONE, trigger = trigger, sessionId = "c", summary = "ok")

    @Test
    fun disabledRecipesHaveNoNextRun() {
        val saved = RecipePlanner.saved(brief.copy(state = RecipeState(nextRunAt = 5, waitingForUnlock = true)), null, at("2026-10-18T06:00"), berlin)
        assertNull(saved.state.nextRunAt)
        assertFalse(saved.state.waitingForUnlock)
        assertFalse(RecipePlanner.isDue(saved, Long.MAX_VALUE))
    }

    @Test
    fun enablingSchedulesTheFirstRun() {
        val saved = RecipePlanner.saved(brief.copy(enabled = true), brief, at("2026-10-18T06:00"), berlin)
        assertEquals("2026-10-18T07:00", local(saved.state.nextRunAt))
        val late = RecipePlanner.saved(brief.copy(enabled = true), brief, at("2026-10-18T08:00"), berlin)
        assertEquals("2026-10-19T07:00", local(late.state.nextRunAt))
    }

    @Test
    fun editingWithoutChangingTheScheduleKeepsThePendingRun() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-18T06:00"), berlin)
        val edited = RecipePlanner.saved(on.copy(prompt = "  Summarise better "), on, at("2026-10-18T06:30"), berlin)
        assertEquals(on.state.nextRunAt, edited.state.nextRunAt)
        assertEquals("Summarise better", edited.prompt)
        val moved = RecipePlanner.saved(on.copy(schedule = RecipeSchedule.Daily(9 * 60)), on, at("2026-10-18T06:30"), berlin)
        assertEquals("2026-10-18T09:00", local(moved.state.nextRunAt))
    }

    @Test
    fun savingDropsToolsARecipeMayNotUse() {
        val saved = RecipePlanner.saved(brief.copy(tools = listOf("delete", "web_fetch", "ask_user_question", "read")), null, 0, berlin)
        assertEquals(listOf("read", "web_fetch"), saved.tools)
        assertTrue(saved.usesNetwork)
    }

    @Test
    fun dueAtItsTimeWithASmallSlack() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-18T06:00"), berlin)
        assertFalse(RecipePlanner.isDue(on, at("2026-10-18T06:58")))
        assertTrue(RecipePlanner.isDue(on, at("2026-10-18T06:59:30")))
        assertTrue(RecipePlanner.isDue(on, at("2026-10-18T07:00")))
    }

    @Test
    fun aMissedRunRunsOnceAndThenFollowsTheSchedule() {
        // Scheduled for Monday 07:00; the phone was off until Thursday 10:00.
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-12T06:00"), berlin)
        assertEquals("2026-10-12T07:00", local(on.state.nextRunAt))
        val thursday = at("2026-10-15T10:00")
        assertEquals(listOf(on), RecipePlanner.due(listOf(on), thursday))
        val after = RecipePlanner.finished(on, record(thursday), berlin)
        // Not Tuesday, not Wednesday: the next run is Friday 07:00, and nothing else is due.
        assertEquals("2026-10-16T07:00", local(after.state.nextRunAt))
        assertTrue(RecipePlanner.due(listOf(after), thursday + 60_000).isEmpty())
    }

    @Test
    fun aCatchUpRunRightBeforeTheNextOneSkipsIt() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-17T06:00"), berlin)
        // Yesterday's 07:00 run only happens today at 06:50.
        val after = RecipePlanner.finished(on, record(at("2026-10-18T06:50")), berlin)
        assertEquals("2026-10-19T07:00", local(after.state.nextRunAt))
        // A run on time is followed by the next day's run as usual.
        val onTime = RecipePlanner.finished(on, record(at("2026-10-17T07:00")), berlin)
        assertEquals("2026-10-18T07:00", local(onTime.state.nextRunAt))
    }

    @Test
    fun aManualRunBetweenRunsLeavesTheScheduleAlone() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-18T06:00"), berlin)
        val manual = RecipePlanner.finished(on, record(at("2026-10-18T06:10"), RecipeTrigger.MANUAL), berlin)
        assertEquals("2026-10-18T07:00", local(manual.state.nextRunAt))
        assertEquals(RecipeTrigger.MANUAL, manual.state.lastRun?.trigger)
        // A manual run while one is due counts as that run.
        val dueManual = RecipePlanner.finished(on, record(at("2026-10-18T07:30"), RecipeTrigger.MANUAL), berlin)
        assertEquals("2026-10-19T07:00", local(dueManual.state.nextRunAt))
    }

    @Test
    fun aManualRunOfADisabledRecipeSchedulesNothing() {
        val after = RecipePlanner.finished(brief, record(at("2026-10-18T06:10"), RecipeTrigger.MANUAL), berlin)
        assertNull(after.state.nextRunAt)
        assertEquals("ok", after.state.lastRun?.summary)
    }

    @Test
    fun aLockedRunWaitsForTheUnlockThenRunsOnce() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-18T06:00"), berlin)
        val waiting = RecipePlanner.waitForUnlock(on)
        assertTrue(waiting.state.waitingForUnlock)
        assertTrue(RecipePlanner.isDue(waiting, at("2026-10-18T05:00")))
        // A waiting recipe needs no timed wake: the unlock (or app open) runs it.
        assertNull(RecipePlanner.nextWake(listOf(waiting)))
        val ran = RecipePlanner.finished(waiting, record(at("2026-10-18T09:00")), berlin)
        assertFalse(ran.state.waitingForUnlock)
        assertEquals("2026-10-19T07:00", local(ran.state.nextRunAt))
    }

    @Test
    fun nextWakeIsTheEarliestEnabledRun() {
        val a = RecipePlanner.saved(brief.copy(id = "a", enabled = true), null, at("2026-10-18T06:00"), berlin)
        val b = RecipePlanner.saved(brief.copy(id = "b", enabled = true, schedule = RecipeSchedule.Daily(6 * 60 + 30)), null, at("2026-10-18T06:00"), berlin)
        val off = brief.copy(id = "c", state = RecipeState(nextRunAt = 1))
        assertEquals(b.state.nextRunAt, RecipePlanner.nextWake(listOf(a, b, off)))
        assertNull(RecipePlanner.nextWake(listOf(off)))
    }

    @Test
    fun travellingMovesThePendingRunToTheNewZone() {
        val on = RecipePlanner.saved(brief.copy(enabled = true), null, at("2026-10-18T06:00"), berlin)
        val now = at("2026-10-18T06:30")
        val there = RecipePlanner.reconcile(on, now, newYork)
        assertEquals("2026-10-18T07:00", local(there.state.nextRunAt, newYork))
        // Unchanged zone: nothing moves.
        assertEquals(on, RecipePlanner.reconcile(on, now, berlin))
        // An overdue run stays due rather than being skipped by the move.
        val overdue = RecipePlanner.reconcile(on, at("2026-10-18T08:00"), newYork)
        assertEquals(on.state.nextRunAt, overdue.state.nextRunAt)
    }

    @Test
    fun reconcileFillsAMissingRunAndClearsADisabledOne() {
        val filled = RecipePlanner.reconcile(brief.copy(enabled = true), at("2026-10-18T06:00"), berlin)
        assertEquals("2026-10-18T07:00", local(filled.state.nextRunAt))
        val cleared = RecipePlanner.reconcile(brief.copy(state = RecipeState(nextRunAt = 1)), 0, berlin)
        assertNull(cleared.state.nextRunAt)
    }

    @Test
    fun everyHoursRunsFromTheLastRun() {
        val hourly = brief.copy(schedule = RecipeSchedule.EveryHours(3), enabled = true)
        val on = RecipePlanner.saved(hourly, null, at("2026-10-18T10:00"), berlin)
        assertEquals("2026-10-18T13:00", local(on.state.nextRunAt))
        // Ran late at 15:20 (phone off): next at 18:20, not 16:00.
        val after = RecipePlanner.finished(on, record(at("2026-10-18T15:20")), berlin)
        assertEquals("2026-10-18T18:20", local(after.state.nextRunAt))
        // Reconcile never shifts an interval schedule.
        assertEquals(after, RecipePlanner.reconcile(after, at("2026-10-18T16:00"), newYork))
    }
}
