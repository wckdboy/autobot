package dev.wckdboy.autobot.agent.runtime.recipes

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeTrigger
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps WorkManager holding exactly one pending run per enabled recipe: a one-time request at its
 * next run, with its constraints (charging, battery not low, an unmetered network when it may use
 * the network). Each request's unique name carries the run time and constraints, so a changed
 * recipe replaces its pending request without touching one that is running. A missed run (phone
 * off, constraints unmet) is simply late: WorkManager runs it once when it can, and the next run
 * is computed from it (`RecipePlanner`), never a backlog.
 */
@Singleton
class RecipeScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val work: WorkManager get() = WorkManager.getInstance(context)

    /** Makes the pending work match [recipe] (none when it is disabled or waits for an unlock). */
    suspend fun sync(recipe: Recipe, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val wanted = request(recipe, now)
        val wantedName = wanted?.let { name(recipe) }
        val pending = work.getWorkInfosByTag(tag(recipe.id)).get()
        pending.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .filter { SCHEDULED in it.tags && (wantedName == null || nameTag(wantedName) !in it.tags) }
            .forEach { work.cancelWorkById(it.id) }
        if (wanted != null) work.enqueueUniqueWork(wantedName!!, ExistingWorkPolicy.KEEP, wanted)
    }

    /** Syncs every recipe and drops pending work of recipes that no longer exist. */
    suspend fun syncAll(recipes: List<Recipe>, now: Long = System.currentTimeMillis()) {
        recipes.forEach { sync(it, now) }
        val ids = recipes.map { it.id }.toSet()
        withContext(Dispatchers.IO) {
            work.getWorkInfosByTag(ALL).get()
                .filter { !it.state.isFinished && it.state != WorkInfo.State.RUNNING && it.tags.none { t -> t.startsWith(ID_PREFIX) && t.removePrefix(ID_PREFIX) in ids } }
                .forEach { work.cancelWorkById(it.id) }
        }
    }

    /** "Run now": an expedited run outside the schedule (no constraints: the user is asking). */
    fun runNow(recipe: Recipe) {
        val request = OneTimeWorkRequestBuilder<RecipeWorker>()
            .setInputData(input(recipe, manual = true))
            .addTag(ALL)
            .addTag(tag(recipe.id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        work.enqueueUniqueWork("recipe-now-${recipe.id}", ExistingWorkPolicy.KEEP, request)
    }

    /** Panic wipe: no recipe runs again. */
    fun cancelAll() {
        work.cancelAllWorkByTag(ALL)
    }

    companion object {
        const val ALL = "recipes"
        const val SCHEDULED = "recipe-scheduled"
        private const val ID_PREFIX = "recipe-id:"
        private const val NAME_PREFIX = "recipe-name:"

        fun tag(id: String) = "$ID_PREFIX$id"
        private fun nameTag(name: String) = "$NAME_PREFIX$name"

        /** The unique work name of [recipe]'s next run: its time and constraints. */
        fun name(recipe: Recipe): String =
            "recipe-${recipe.id}@${recipe.state.nextRunAt}#${if (recipe.requiresCharging) "c" else ""}${if (recipe.requiresBatteryNotLow) "b" else ""}${if (recipe.usesNetwork) "n" else ""}"

        /** The constraints [recipe] asks for. */
        fun constraints(recipe: Recipe): Constraints = Constraints.Builder()
            .setRequiresCharging(recipe.requiresCharging)
            .setRequiresBatteryNotLow(recipe.requiresBatteryNotLow)
            .setRequiredNetworkType(if (recipe.usesNetwork) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .build()

        /** The request for [recipe]'s next scheduled run, or null when it has none. */
        fun request(recipe: Recipe, now: Long): OneTimeWorkRequest? {
            val next = recipe.state.nextRunAt?.takeIf { recipe.enabled && !recipe.state.waitingForUnlock } ?: return null
            return OneTimeWorkRequestBuilder<RecipeWorker>()
                .setInitialDelay((next - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(constraints(recipe))
                .setInputData(input(recipe, manual = false))
                .addTag(ALL)
                .addTag(SCHEDULED)
                .addTag(tag(recipe.id))
                .addTag(nameTag(name(recipe)))
                .build()
        }

        // The name rides along so "Unlock to run <name>" can be said without reading the database.
        private fun input(recipe: Recipe, manual: Boolean): Data = Data.Builder()
            .putString(RecipeWorker.KEY_ID, recipe.id)
            .putString(RecipeWorker.KEY_NAME, recipe.name)
            .putBoolean(RecipeWorker.KEY_MANUAL, manual)
            .build()
    }
}

/**
 * Runs one recipe from WorkManager, headless. The runner comes from Hilt (or [runnerForTests]);
 * a run that finds the data locked is retried with backoff (and has said "Unlock to run").
 */
class RecipeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val trigger = if (inputData.getBoolean(KEY_MANUAL, false)) RecipeTrigger.MANUAL else RecipeTrigger.SCHEDULE
        val runner = runnerForTests ?: EntryPointAccessors.fromApplication(applicationContext, RecipeWorkerEntryPoint::class.java).recipeRunner()
        return when (runner.run(id, trigger, inputData.getString(KEY_NAME))) {
            RecipeRunOutcome.LOCKED -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_MANUAL = "manual"

        /** Tests: the runner the worker uses instead of the app's. */
        @Volatile
        internal var runnerForTests: RecipeRunner? = null
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecipeWorkerEntryPoint {
    fun recipeRunner(): RecipeRunner
}
