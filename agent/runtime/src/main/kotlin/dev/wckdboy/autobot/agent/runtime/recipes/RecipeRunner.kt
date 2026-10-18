package dev.wckdboy.autobot.agent.runtime.recipes

import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeContext
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipePlanner
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeTrigger
import dev.wckdboy.autobot.shared.recipes.RecipeTurnResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone

/** How a run request ended (the worker maps it to a WorkManager result). */
enum class RecipeRunOutcome {
    /** The recipe ran (its record says how it went). */
    RAN,

    /** Not due any more (edited, disabled, already run): nothing happened. */
    NOT_DUE,

    /** Deleted since the work was scheduled. */
    GONE,

    /** The encrypted data could not be read: "Unlock to run", retried later. */
    LOCKED,
}

/** Everything a run needs from the app, so the orchestration is tested on the JVM. */
interface RecipeEnvironment {
    fun now(): Long
    fun zone(): TimeZone = TimeZone.currentSystemDefault()

    /** The recipe; throws [RecipeDataLocked] when the encrypted store cannot be read. */
    suspend fun load(id: String): Recipe?
    suspend fun save(recipe: Recipe)

    /** The on-device route for [recipe]: provider id and model, or why there is none. */
    suspend fun route(recipe: Recipe): RecipeRoute

    /** A new chat for one run (in the Recipes folder); returns its id. */
    suspend fun newChat(recipe: Recipe, route: RecipeRoute.OnDevice, atMillis: Long): String

    /** Chats changed after [sinceMillis] for the digest (not incognito, not recipe results). */
    suspend fun recentChats(sinceMillis: Long): List<RecipeContext.ChatExcerpt>

    suspend fun runTurn(chatId: String, recipe: Recipe, route: RecipeRoute.OnDevice, context: String?): RecipeTurnResult

    val showSummary: Boolean
    fun notifyFinished(recipe: Recipe, record: RecipeRunRecord, showSummary: Boolean)
    fun notifyUnlock(id: String, name: String)

    /** Puts the recipe's next run into the scheduler. */
    suspend fun schedule(recipe: Recipe)
}

/** The encrypted data cannot be read right now (keys unavailable): the run waits for an unlock. */
class RecipeDataLocked(cause: Throwable? = null) : Exception("The encrypted data is locked", cause)

/** Where a recipe runs. */
sealed interface RecipeRoute {
    data class OnDevice(val provider: String, val model: String) : RecipeRoute
    data class Unavailable(val reason: String) : RecipeRoute
}

/**
 * Runs recipes one at a time: a scheduled run only when it is still due, a manual one at once.
 * Each run gets a new chat in the Recipes folder on the on-device model, runs headless through
 * the agent runtime ([dev.wckdboy.autobot.agent.runtime.AgentHost.runRecipe]), stores its record
 * and next run, posts the notification and reschedules.
 */
@Singleton
class RecipeRunner @Inject constructor(private val env: RecipeEnvironment) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow<Set<String>>(emptySet())

    /** Recipes running now (the list shows them busy). */
    val running: StateFlow<Set<String>> = _running.asStateFlow()

    /** Runs recipe [id]; [nameHint] names it in "Unlock to run" when the data cannot be read. */
    suspend fun run(id: String, trigger: RecipeTrigger, nameHint: String? = null): RecipeRunOutcome = mutex.withLock {
        val recipe = try {
            env.load(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RecipeDataLocked) {
            env.notifyUnlock(id, nameHint?.takeIf { it.isNotBlank() } ?: "your recipe")
            return RecipeRunOutcome.LOCKED
        } ?: return RecipeRunOutcome.GONE
        val start = env.now()
        if (trigger == RecipeTrigger.SCHEDULE && !RecipePlanner.isDue(recipe, start)) {
            env.schedule(recipe)
            return RecipeRunOutcome.NOT_DUE
        }
        _running.update { it + id }
        try {
            val record = execute(recipe, trigger, start)
            // The user may have edited the recipe while it ran: the record goes onto what is stored now.
            val latest = runCatching { env.load(id) }.getOrNull() ?: recipe
            val updated = RecipePlanner.finished(latest, record, env.zone())
            env.save(updated)
            env.notifyFinished(updated, record, env.showSummary)
            env.schedule(updated)
        } finally {
            _running.update { it - id }
        }
        RecipeRunOutcome.RAN
    }

    private suspend fun execute(recipe: Recipe, trigger: RecipeTrigger, start: Long): RecipeRunRecord {
        val route = when (val r = env.route(recipe)) {
            is RecipeRoute.Unavailable -> return RecipeTurnResult.failed(r.reason).record(start, env.now(), trigger, null)
            is RecipeRoute.OnDevice -> r
        }
        var chat: String? = null
        val result = try {
            val context = if (recipe.includeRecentChats) {
                RecipeContext.recentChats(env.recentChats(RecipeContext.since(recipe, start)), RecipeContext.since(recipe, start))
            } else {
                null
            }
            chat = env.newChat(recipe, route, start)
            env.runTurn(chat, recipe, route, context)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RecipeTurnResult.failed(t.message ?: (t::class.simpleName ?: RecipeCopy.EMPTY_ANSWER))
        }
        return result.record(start, env.now(), trigger, chat)
    }
}
