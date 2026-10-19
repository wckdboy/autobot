package dev.wckdboy.autobot.shared.recipes

import kotlinx.datetime.TimeZone

/**
 * The scheduling rules both apps follow, as pure functions over [Recipe] (the platforms only
 * persist the result and ask WorkManager / BGTaskScheduler to wake them at [nextWake]).
 *
 * - A recipe is due once its [RecipeState.nextRunAt] has passed, or while it waits for an unlock.
 * - Missed runs (phone off, iOS never woke the app, the data was locked) run **once** at the next
 *   opportunity; the following run is computed from that run, never a backlog of the missed ones.
 * - A wall-clock run that would follow a catch-up run within [MIN_GAP_MS] is skipped to the next
 *   occurrence (yesterday's missed 07:00 brief run at 06:50 does not run again at 07:00).
 */
object RecipePlanner {
    /** Least time between a catch-up run and the next wall-clock run. */
    const val MIN_GAP_MS = RecipeSchedule.HOUR_MS

    /** How early a scheduled wake may fire and still count as the due run (OS batching). */
    const val EARLY_SLACK_MS = 60_000L

    fun isDue(recipe: Recipe, nowMillis: Long): Boolean {
        // A node runs it; the phone only collects the results.
        if (!recipe.enabled || recipe.runsOn != null) return false
        if (recipe.state.waitingForUnlock) return true
        val next = recipe.state.nextRunAt ?: return false
        return nowMillis + EARLY_SLACK_MS >= next
    }

    /** The due recipes, longest overdue first. */
    fun due(recipes: List<Recipe>, nowMillis: Long): List<Recipe> =
        recipes.filter { isDue(it, nowMillis) }.sortedBy { it.state.nextRunAt ?: Long.MIN_VALUE }

    /** When the app should next be woken for [recipes] (null: nothing scheduled). */
    fun nextWake(recipes: List<Recipe>): Long? =
        recipes.filter { it.enabled && it.runsOn == null && !it.state.waitingForUnlock }.mapNotNull { it.state.nextRunAt }.minOrNull()

    /**
     * [recipe] as saved from the editor (or toggled), with its next run worked out: disabled
     * recipes have none; an enabled one keeps its pending run when neither the schedule nor the
     * switch changed, else gets the first run after [nowMillis].
     */
    fun saved(recipe: Recipe, previous: Recipe?, nowMillis: Long, zone: TimeZone): Recipe {
        val clean = recipe.copy(tools = RecipeTools.sanitize(recipe.tools), name = recipe.name.trim(), prompt = recipe.prompt.trim())
        if (!clean.enabled) return clean.copy(state = clean.state.copy(nextRunAt = null, waitingForUnlock = false))
        val keep = previous != null && previous.enabled && previous.schedule == clean.schedule && previous.state.nextRunAt != null
        if (keep) return reconcile(clean.copy(state = previous!!.state.copy(lastRun = clean.state.lastRun ?: previous.state.lastRun)), nowMillis, zone)
        return clean.copy(state = clean.state.copy(nextRunAt = clean.schedule.next(nowMillis, zone), waitingForUnlock = false))
    }

    /**
     * Brings [recipe]'s next run up to date at app start or after a time zone change: a pending
     * wall-clock run that is no longer one of the schedule's times in the current zone moves to
     * the next one there; one that still is stays (so a run skipped after a catch-up stays
     * skipped); an overdue one stays due (it runs once); a disabled one has none.
     */
    fun reconcile(recipe: Recipe, nowMillis: Long, zone: TimeZone): Recipe {
        val state = recipe.state
        if (!recipe.enabled) {
            return if (state.nextRunAt == null && !state.waitingForUnlock) recipe else recipe.copy(state = state.copy(nextRunAt = null, waitingForUnlock = false))
        }
        val next = state.nextRunAt ?: return recipe.copy(state = state.copy(nextRunAt = recipe.schedule.next(nowMillis, zone)))
        if (recipe.schedule is RecipeSchedule.EveryHours || next <= nowMillis) return recipe
        // Still a scheduled time in this zone (the zone did not change): keep it, even when it is
        // not the first one after now (a catch-up run skipped the one in between).
        if (recipe.schedule.next(next - 1, zone) == next) return recipe
        return recipe.copy(state = state.copy(nextRunAt = recipe.schedule.next(nowMillis, zone)))
    }

    /**
     * [recipe] after a run that ended with [record]. A scheduled run (or a manual one that came
     * when a run was due anyway) sets the next run from this one; a manual run between scheduled
     * ones leaves the schedule alone.
     */
    fun finished(recipe: Recipe, record: RecipeRunRecord, zone: TimeZone): Recipe {
        val state = recipe.state
        val wasDue = state.waitingForUnlock || state.nextRunAt?.let { record.startedAt + EARLY_SLACK_MS >= it } == true
        val next = when {
            !recipe.enabled -> null
            record.trigger == RecipeTrigger.SCHEDULE || wasDue || state.nextRunAt == null -> nextAfterRun(recipe, state.nextRunAt, record.startedAt, zone)
            else -> state.nextRunAt
        }
        return recipe.copy(state = RecipeState(nextRunAt = next, waitingForUnlock = false, lastRun = record, pushed = state.pushed))
    }

    /**
     * The run after one that started at [startedAt] (catch-up runs keep [MIN_GAP_MS] to the next).
     * A run the OS woke a little early (within [EARLY_SLACK_MS]) counts from its scheduled time,
     * so the same occurrence is not run twice.
     */
    private fun nextAfterRun(recipe: Recipe, scheduledAt: Long?, startedAt: Long, zone: TimeZone): Long {
        val schedule = recipe.schedule
        val from = if (scheduledAt != null && startedAt < scheduledAt) scheduledAt else startedAt
        val next = schedule.next(from, zone)
        if (schedule is RecipeSchedule.EveryHours) return next
        val catchUp = scheduledAt != null && startedAt - scheduledAt > EARLY_SLACK_MS
        return if (catchUp && next - startedAt < MIN_GAP_MS) schedule.next(next, zone) else next
    }

    /** A due run could not decrypt the app's data: it waits for the next unlock (or app open). */
    fun waitForUnlock(recipe: Recipe): Recipe = recipe.copy(state = recipe.state.copy(waitingForUnlock = true))
}
