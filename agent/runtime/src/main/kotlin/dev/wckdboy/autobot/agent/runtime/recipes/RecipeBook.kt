package dev.wckdboy.autobot.agent.runtime.recipes

import dev.wckdboy.autobot.agent.runtime.AgentHost
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.RecipeRepository
import dev.wckdboy.autobot.core.data.db.DatabaseUnavailableException
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.providers.remote.LocalProviderSync
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeContext
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipePlanner
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import dev.wckdboy.autobot.shared.recipes.RecipeTurnResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone

/** An on-device chat model a recipe can pick. */
data class RecipeModelChoice(val id: String, val title: String)

/**
 * The recipes as the app manages them: the list, saving (with the next run worked out by the
 * shared planner), enabling, deleting, "Run now", and bringing the schedule up to date at start.
 */
@Singleton
class RecipeBook @Inject constructor(
    private val repository: RecipeRepository,
    private val scheduler: RecipeScheduler,
    private val runner: RecipeRunner,
    private val library: ModelLibrary,
    private val notifications: RecipeNotifications,
) {
    val recipes: Flow<List<Recipe>> get() = repository.observe()
    val running get() = runner.running

    /** Installed on-device chat models (the editor's model picker). */
    val models: Flow<List<RecipeModelChoice>> = library.models.map { list -> list.filter { it.isLocalChat }.map { RecipeModelChoice(it.id, it.title) } }

    var summaryInNotifications: Boolean
        get() = repository.summaryInNotifications
        set(value) {
            repository.summaryInNotifications = value
        }

    val notificationsAllowed: Boolean get() = notifications.enabled

    private fun zone() = TimeZone.currentSystemDefault()

    /** Saves [recipe] from the editor (or a toggle) and schedules its next run. */
    suspend fun save(recipe: Recipe) {
        val previous = repository.get(recipe.id)
        val saved = RecipePlanner.saved(recipe, previous, System.currentTimeMillis(), zone())
        repository.save(saved)
        scheduler.sync(saved)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val recipe = repository.get(id) ?: return
        save(recipe.copy(enabled = enabled))
    }

    suspend fun delete(id: String) {
        repository.get(id)?.let { scheduler.sync(it.copy(enabled = false)) }
        repository.delete(id)
        notifications.cancelUnlock(id)
    }

    /** Runs [id] now, through WorkManager (it goes on if the screen closes). */
    suspend fun runNow(id: String) {
        repository.get(id)?.let(scheduler::runNow)
    }

    /**
     * App start: every recipe's next run is brought up to date (time zone changes, missed runs
     * stay due) and WorkManager holds exactly those runs.
     */
    suspend fun reconcile() {
        val now = System.currentTimeMillis()
        val all = repository.all().map { r -> RecipePlanner.reconcile(r, now, zone()).also { if (it != r) repository.save(it) } }
        scheduler.syncAll(all, now)
    }

    /** Panic wipe: no recipe runs or notifies again. */
    fun stopAll() {
        scheduler.cancelAll()
        notifications.cancelAll()
    }
}

/** [RecipeEnvironment] on Android: the encrypted repositories, the agent host, WorkManager. */
@Singleton
class AndroidRecipeEnvironment @Inject constructor(
    private val repository: RecipeRepository,
    private val host: AgentHost,
    private val conversations: ConversationRepository,
    private val providers: ProviderRepository,
    private val library: ModelLibrary,
    private val notifications: RecipeNotifications,
    private val scheduler: RecipeScheduler,
) : RecipeEnvironment {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun load(id: String): Recipe? = try {
        repository.get(id)
    } catch (e: DatabaseUnavailableException) {
        throw RecipeDataLocked(e)
    }

    override suspend fun save(recipe: Recipe) = repository.save(recipe)

    override suspend fun route(recipe: Recipe): RecipeRoute {
        val local = providers.getProvider(LocalProviderSync.LOCAL_PROVIDER_ID)?.takeIf { it.kind == ProviderKind.LOCAL }
            ?: return RecipeRoute.Unavailable(RecipeCopy.NO_LOCAL_MODEL)
        val model = recipe.model.ifBlank { local.defaultModel }
        if (model.isBlank()) return RecipeRoute.Unavailable(RecipeCopy.NO_LOCAL_MODEL)
        val installed = library.all().filter { it.isLocalChat }.map { it.id }
        if (model != local.defaultModel && model !in installed) return RecipeRoute.Unavailable(RecipeCopy.modelMissing(model))
        return RecipeRoute.OnDevice(local.id, model)
    }

    override suspend fun newChat(recipe: Recipe, route: RecipeRoute.OnDevice, atMillis: Long): String {
        // Never incognito: the result must still be there when the user opens it.
        val chat = conversations.createConversation(
            incognito = false,
            providerId = route.provider,
            model = route.model,
            title = RecipeCopy.chatTitle(recipe.name, atMillis, TimeZone.currentSystemDefault()),
        )
        folderId()?.let { conversations.moveToFolder(listOf(chat.id), it) }
        return chat.id
    }

    /** The Recipes chat folder, created on first use. */
    private suspend fun folderId(): String? =
        conversations.folders().firstOrNull { it.name == RecipeCopy.FOLDER }?.id ?: conversations.createFolder(RecipeCopy.FOLDER)?.id

    override suspend fun recentChats(sinceMillis: Long): List<RecipeContext.ChatExcerpt> {
        val folder = conversations.folders().firstOrNull { it.name == RecipeCopy.FOLDER }?.id
        return conversations.observeConversations().first()
            .filter { it.updatedAt > sinceMillis && it.folderId != folder && !conversations.isIncognito(it.id) }
            .map { RecipeContext.ChatExcerpt(it.title, it.updatedAt, conversations.searchText(it.id).orEmpty()) }
    }

    override suspend fun runTurn(chatId: String, recipe: Recipe, route: RecipeRoute.OnDevice, context: String?): RecipeTurnResult =
        host.runRecipe(chatId, recipe, route.provider, route.model, context)

    override val showSummary: Boolean get() = repository.summaryInNotifications

    override fun notifyFinished(recipe: Recipe, record: RecipeRunRecord, showSummary: Boolean) = notifications.finished(recipe, record, showSummary)

    override fun notifyUnlock(id: String, name: String) = notifications.unlockToRun(id, name)

    override suspend fun schedule(recipe: Recipe) = scheduler.sync(recipe)
}
