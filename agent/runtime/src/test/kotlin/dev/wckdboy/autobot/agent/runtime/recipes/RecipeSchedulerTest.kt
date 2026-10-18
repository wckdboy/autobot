package dev.wckdboy.autobot.agent.runtime.recipes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeSchedule
import dev.wckdboy.autobot.shared.recipes.RecipeState
import dev.wckdboy.autobot.shared.recipes.RecipeTools
import dev.wckdboy.autobot.shared.recipes.RecipeTurnResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WorkManager holds one pending run per enabled recipe, with its constraints; the worker maps outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecipeSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scheduler: RecipeScheduler
    private val work get() = WorkManager.getInstance(context)

    private val now = 1_000_000L
    private val brief = Recipe(
        "r", "Brief", "Summarise", RecipeSchedule.Daily(420), enabled = true,
        state = RecipeState(nextRunAt = now + 3_600_000L),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        scheduler = RecipeScheduler(context)
    }

    @After
    fun tearDown() {
        RecipeWorker.runnerForTests = null
    }

    private fun pending(): List<WorkInfo> =
        work.getWorkInfosByTag(RecipeScheduler.ALL).get().filter { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun anEnabledRecipeHasOneDelayedRunWithItsConstraints() = runBlocking {
        scheduler.sync(brief.copy(tools = RecipeTools.WEB.tools, requiresCharging = true), now)
        val info = pending().single()
        assertTrue(RecipeScheduler.SCHEDULED in info.tags)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresCharging())
        assertTrue(info.constraints.requiresBatteryNotLow())
        assertEquals(3_600_000L, info.initialDelayMillis)
    }

    @Test
    fun aRecipeWithoutNetworkToolsNeedsNoNetwork() = runBlocking {
        scheduler.sync(brief, now)
        assertEquals(NetworkType.NOT_REQUIRED, pending().single().constraints.requiredNetworkType)
        assertFalse(pending().single().constraints.requiresCharging())
    }

    @Test
    fun syncingTwiceKeepsOneRunAndAChangeReplacesIt() = runBlocking {
        scheduler.sync(brief, now)
        scheduler.sync(brief, now)
        val first = pending().single().id
        scheduler.sync(brief.copy(state = RecipeState(nextRunAt = now + 7_200_000L)), now)
        val second = pending().single()
        assertTrue(second.id != first)
        assertEquals(7_200_000L, second.initialDelayMillis)
    }

    @Test
    fun disablingOrWaitingForAnUnlockCancelsThePendingRun() = runBlocking {
        scheduler.sync(brief, now)
        scheduler.sync(brief.copy(enabled = false), now)
        assertTrue(pending().isEmpty())
        scheduler.sync(brief, now)
        scheduler.sync(brief.copy(state = brief.state.copy(waitingForUnlock = true)), now)
        assertTrue(pending().isEmpty())
    }

    @Test
    fun anOverdueRunIsQueuedWithoutDelayAndOnlyOnce() = runBlocking {
        val missed = brief.copy(state = RecipeState(nextRunAt = now - 3 * RecipeSchedule.DAY_MS))
        scheduler.sync(missed, now)
        scheduler.sync(missed, now)
        assertEquals(0L, pending().single().initialDelayMillis)
    }

    @Test
    fun syncAllDropsRunsOfDeletedRecipes() = runBlocking {
        scheduler.sync(brief, now)
        scheduler.sync(brief.copy(id = "gone"), now)
        scheduler.syncAll(listOf(brief), now)
        assertEquals(listOf(RecipeScheduler.tag("r")), pending().map { info -> info.tags.single { it.startsWith("recipe-id:") } })
    }

    @Test
    fun cancelAllStopsEverything() = runBlocking {
        scheduler.sync(brief, now)
        scheduler.cancelAll()
        assertTrue(pending().isEmpty())
    }

    /** A runner whose store is locked, or that runs the recipe. */
    private fun runner(locked: Boolean) = RecipeRunner(
        object : RecipeEnvironment {
            override fun now() = now
            override suspend fun load(id: String): Recipe? = if (locked) throw RecipeDataLocked() else brief.copy(enabled = false)
            override suspend fun save(recipe: Recipe) = Unit
            override suspend fun route(recipe: Recipe): RecipeRoute = RecipeRoute.OnDevice("p", "m")
            override suspend fun newChat(recipe: Recipe, route: RecipeRoute.OnDevice, atMillis: Long) = "c"
            override suspend fun recentChats(sinceMillis: Long) = emptyList<dev.wckdboy.autobot.shared.recipes.RecipeContext.ChatExcerpt>()
            override suspend fun runTurn(chatId: String, recipe: Recipe, route: RecipeRoute.OnDevice, context: String?) = RecipeTurnResult.failed("x")
            override val showSummary = false
            override fun notifyFinished(recipe: Recipe, record: RecipeRunRecord, showSummary: Boolean) = Unit
            override fun notifyUnlock(id: String, name: String) = Unit
            override suspend fun schedule(recipe: Recipe) = Unit
        },
    )

    @Test
    fun theWorkerRetriesWhileTheDataIsLocked() = runBlocking {
        RecipeWorker.runnerForTests = runner(locked = true)
        val worker = TestListenableWorkerBuilder<RecipeWorker>(context)
            .setInputData(workDataOf(RecipeWorker.KEY_ID to "r", RecipeWorker.KEY_NAME to "Brief"))
            .build()
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        RecipeWorker.runnerForTests = runner(locked = false)
        val manual = TestListenableWorkerBuilder<RecipeWorker>(context)
            .setInputData(workDataOf(RecipeWorker.KEY_ID to "r", RecipeWorker.KEY_MANUAL to true))
            .build()
        assertEquals(ListenableWorker.Result.success(), manual.doWork())
        val missing = TestListenableWorkerBuilder<RecipeWorker>(context).build()
        assertEquals(ListenableWorker.Result.failure(), missing.doWork())
    }
}
