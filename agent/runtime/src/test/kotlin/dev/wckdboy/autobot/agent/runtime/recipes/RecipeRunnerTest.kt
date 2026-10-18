package dev.wckdboy.autobot.agent.runtime.recipes

import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeContext
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeSchedule
import dev.wckdboy.autobot.shared.recipes.RecipeState
import dev.wckdboy.autobot.shared.recipes.RecipeTrigger
import dev.wckdboy.autobot.shared.recipes.RecipeTurnResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android run orchestration against a fake app: due checks, the locked store, routes, delivery. */
class RecipeRunnerTest {

    private class FakeEnv(var stored: Recipe?) : RecipeEnvironment {
        var clock = 1_000_000L
        var locked = false
        var routeResult: RecipeRoute = RecipeRoute.OnDevice("local-this-phone", "qwen")
        var turn: (String?) -> RecipeTurnResult = { RecipeTurnResult(RecipeRunStatus.DONE, "Five headlines.", "Five headlines.", emptyList(), 1, 900) }
        var summary = false
        val chats = mutableListOf<String>()
        val contexts = mutableListOf<String?>()
        val notified = mutableListOf<Pair<String, RecipeRunRecord>>()
        val unlocks = mutableListOf<String>()
        val scheduled = mutableListOf<Recipe>()
        var digest = listOf(RecipeContext.ChatExcerpt("Trip", clock - 1000, "plan the trip"))

        override fun now() = clock
        override fun zone() = TimeZone.UTC
        override suspend fun load(id: String): Recipe? {
            if (locked) throw RecipeDataLocked()
            return stored?.takeIf { it.id == id }
        }
        override suspend fun save(recipe: Recipe) {
            stored = recipe
        }
        override suspend fun route(recipe: Recipe) = routeResult
        override suspend fun newChat(recipe: Recipe, route: RecipeRoute.OnDevice, atMillis: Long): String = "chat-${chats.size + 1}".also { chats += it }
        override suspend fun recentChats(sinceMillis: Long) = digest.filter { it.updatedAt > sinceMillis }
        override suspend fun runTurn(chatId: String, recipe: Recipe, route: RecipeRoute.OnDevice, context: String?): RecipeTurnResult {
            contexts += context
            clock += 30_000
            return turn(context)
        }
        override val showSummary get() = summary
        override fun notifyFinished(recipe: Recipe, record: RecipeRunRecord, showSummary: Boolean) {
            notified += recipe.name to record
        }
        override fun notifyUnlock(id: String, name: String) {
            unlocks += name
        }
        override suspend fun schedule(recipe: Recipe) {
            scheduled += recipe
        }
    }

    private val due = Recipe(
        "r", "Brief", "Summarise", RecipeSchedule.Daily(0), enabled = true,
        state = RecipeState(nextRunAt = 900_000L),
    )

    @Test
    fun aDueRecipeRunsIntoANewChatAndSchedulesTheNext() = runBlocking {
        val env = FakeEnv(due)
        val runner = RecipeRunner(env)
        assertEquals(RecipeRunOutcome.RAN, runner.run("r", RecipeTrigger.SCHEDULE))
        assertEquals(listOf("chat-1"), env.chats)
        val saved = env.stored!!
        val last = saved.state.lastRun!!
        assertEquals(RecipeRunStatus.DONE, last.status)
        assertEquals("chat-1", last.sessionId)
        assertEquals(1_000_000L, last.startedAt)
        assertEquals(1_030_000L, last.finishedAt)
        assertEquals(1, last.hosts)
        // Next midnight UTC after the run, not the missed one before it.
        assertEquals(RecipeSchedule.DAY_MS, saved.state.nextRunAt)
        assertEquals(listOf("Brief" to last), env.notified)
        assertEquals(saved, env.scheduled.single())
        assertNull(env.contexts.single())
        assertTrue(runner.running.value.isEmpty())
    }

    @Test
    fun aScheduledWakeForARecipeThatIsNotDueOnlyReschedules() = runBlocking {
        val env = FakeEnv(due.copy(state = RecipeState(nextRunAt = 5_000_000L)))
        assertEquals(RecipeRunOutcome.NOT_DUE, RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE))
        assertTrue(env.chats.isEmpty())
        assertEquals(1, env.scheduled.size)
        val disabled = FakeEnv(due.copy(enabled = false))
        assertEquals(RecipeRunOutcome.NOT_DUE, RecipeRunner(disabled).run("r", RecipeTrigger.SCHEDULE))
        assertTrue(disabled.chats.isEmpty())
    }

    @Test
    fun runNowRunsEvenWhenNotDue() = runBlocking {
        val env = FakeEnv(due.copy(enabled = false, state = RecipeState()))
        assertEquals(RecipeRunOutcome.RAN, RecipeRunner(env).run("r", RecipeTrigger.MANUAL))
        assertEquals(RecipeTrigger.MANUAL, env.stored!!.state.lastRun!!.trigger)
        assertNull(env.stored!!.state.nextRunAt)
    }

    @Test
    fun aLockedStoreSaysUnlockToRunWithTheNameOnly() = runBlocking {
        val env = FakeEnv(due).apply { locked = true }
        assertEquals(RecipeRunOutcome.LOCKED, RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE, nameHint = "Brief"))
        assertEquals(listOf("Brief"), env.unlocks)
        assertTrue(env.chats.isEmpty())
        assertTrue(env.notified.isEmpty())
        assertEquals(RecipeCopy.unlockToRun("Brief"), "Unlock to run Brief")
    }

    @Test
    fun aDeletedRecipeIsGone() = runBlocking {
        assertEquals(RecipeRunOutcome.GONE, RecipeRunner(FakeEnv(null)).run("r", RecipeTrigger.SCHEDULE))
    }

    @Test
    fun noOnDeviceModelFailsWithoutAChat() = runBlocking {
        val env = FakeEnv(due).apply { routeResult = RecipeRoute.Unavailable(RecipeCopy.NO_LOCAL_MODEL) }
        RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE)
        val last = env.stored!!.state.lastRun!!
        assertEquals(RecipeRunStatus.FAILED, last.status)
        assertEquals(RecipeCopy.NO_LOCAL_MODEL, last.summary)
        assertNull(last.sessionId)
        assertTrue(env.chats.isEmpty())
        assertEquals("Recipe Brief didn't finish", RecipeCopy.title("Brief", env.notified.single().second))
    }

    @Test
    fun aTurnThatThrowsIsRecordedAsFailedWithItsChat() = runBlocking {
        val env = FakeEnv(due).apply { turn = { error("engine crashed") } }
        assertEquals(RecipeRunOutcome.RAN, RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE))
        val last = env.stored!!.state.lastRun!!
        assertEquals(RecipeRunStatus.FAILED, last.status)
        assertEquals("engine crashed", last.summary)
        assertEquals("chat-1", last.sessionId)
        assertFalse(env.stored!!.state.waitingForUnlock)
    }

    @Test
    fun theChatDigestIsPassedAsContext() = runBlocking {
        val env = FakeEnv(due.copy(includeRecentChats = true))
        RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE)
        val context = env.contexts.single()!!
        assertTrue(context.contains("## Trip"))
        assertTrue(context.contains("plan the trip"))
    }

    @Test
    fun anEditDuringTheRunIsKept() = runBlocking {
        val env = FakeEnv(due)
        env.turn = {
            env.stored = env.stored!!.copy(prompt = "Edited while running")
            RecipeTurnResult(RecipeRunStatus.DONE, "ok", "ok", emptyList(), 0, 0)
        }
        RecipeRunner(env).run("r", RecipeTrigger.SCHEDULE)
        assertEquals("Edited while running", env.stored!!.prompt)
        assertEquals("ok", env.stored!!.state.lastRun!!.summary)
    }
}
