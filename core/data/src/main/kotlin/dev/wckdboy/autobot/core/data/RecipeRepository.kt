package dev.wckdboy.autobot.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.db.RecipeDao
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.RecipeRow
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCodec
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Non-secret recipe preferences (whether the templates were added, the notification summary switch). */
interface RecipePrefs {
    var seeded: Boolean
    var summaryInNotifications: Boolean
}

/**
 * Scheduled recipes, one row each in the encrypted database (the shared `RecipeCodec` JSON, as
 * the iOS app stores them). The three example templates are added once, disabled. Unreadable rows
 * are skipped rather than failing the list.
 */
@Singleton
class RecipeRepository internal constructor(
    private val dao: () -> RecipeDao,
    private val prefs: RecipePrefs,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        database: DatabaseHolder,
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this({ database.get().recipeDao() }, SharedRecipePrefs(context), io, System::currentTimeMillis)

    private val seedLock = Mutex()

    fun observe(): Flow<List<Recipe>> = flow {
        seedOnce()
        emitAll(dao().observeAll())
    }.map { rows -> rows.decode() }.flowOn(io)

    suspend fun all(): List<Recipe> = withContext(io) {
        seedOnce()
        dao().all().decode()
    }

    suspend fun get(id: String): Recipe? = withContext(io) { dao().get(id)?.json?.let(RecipeCodec::decode) }

    suspend fun save(recipe: Recipe) = withContext(io) { dao().upsert(RecipeRow(recipe.id, RecipeCodec.encode(recipe), clock())) }

    suspend fun delete(id: String) = withContext(io) { dao().delete(id) }

    var summaryInNotifications: Boolean
        get() = prefs.summaryInNotifications
        set(value) {
            prefs.summaryInNotifications = value
        }

    /** Adds the example recipes the first time recipes are read (never again, even after deleting them). */
    private suspend fun seedOnce() {
        if (prefs.seeded) return
        seedLock.withLock {
            if (prefs.seeded) return
            val existing = dao().all().map { it.id }.toSet()
            RecipeTemplates.all(clock()).filter { it.id !in existing }.forEach { dao().upsert(RecipeRow(it.id, RecipeCodec.encode(it), clock())) }
            prefs.seeded = true
        }
    }

    private fun List<RecipeRow>.decode(): List<Recipe> = mapNotNull { RecipeCodec.decode(it.json) }.sortedWith(RecipeTemplates.listOrder)
}

/** [RecipePrefs] in a small SharedPreferences file (removed by a panic wipe with the rest). */
private class SharedRecipePrefs(context: Context) : RecipePrefs {
    private val prefs = context.getSharedPreferences("recipes", Context.MODE_PRIVATE)

    override var seeded: Boolean
        get() = prefs.getBoolean(SEEDED, false)
        set(value) = prefs.edit().putBoolean(SEEDED, value).apply()

    override var summaryInNotifications: Boolean
        get() = prefs.getBoolean(SUMMARY, false)
        set(value) = prefs.edit().putBoolean(SUMMARY, value).apply()

    private companion object {
        const val SEEDED = "templates_seeded"
        const val SUMMARY = "summary_in_notifications"
    }
}
