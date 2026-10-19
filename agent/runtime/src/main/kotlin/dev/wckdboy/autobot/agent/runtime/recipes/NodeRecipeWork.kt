package dev.wckdboy.autobot.agent.runtime.recipes

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionLog
import dev.wckdboy.autobot.agent.runtime.EncryptedSessionStore
import dev.wckdboy.autobot.core.data.ConversationRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.RecipeRepository
import dev.wckdboy.autobot.providers.remote.NodeAgents
import dev.wckdboy.autobot.shared.recipes.NodeRecipeHost
import dev.wckdboy.autobot.shared.recipes.NodeRecipeSync
import dev.wckdboy.autobot.shared.recipes.NodeRecipeTarget
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone

/** [NodeRecipeHost] on Android: the encrypted recipe and chat stores, the paired nodes, the notifications. */
@Singleton
class AndroidNodeRecipeHost @Inject constructor(
    private val repository: RecipeRepository,
    private val conversations: ConversationRepository,
    private val sessions: EncryptedSessionStore,
    private val providers: ProviderRepository,
    private val agents: NodeAgents,
    private val notifications: RecipeNotifications,
) : NodeRecipeHost {
    override fun now(): Long = System.currentTimeMillis()

    override suspend fun recipes(): List<Recipe> = repository.all()

    override suspend fun load(id: String): Recipe? = repository.get(id)

    override suspend fun save(recipe: Recipe) = repository.save(recipe)

    override suspend fun target(providerId: String): NodeRecipeTarget? =
        providers.observeProviders().first().firstOrNull { it.id == providerId }?.let { agents.recipeTarget(it) }

    override suspend fun newChat(recipe: Recipe, providerId: String, atMillis: Long): String {
        val chat = conversations.createConversation(
            incognito = false,
            providerId = providerId,
            model = "auto",
            title = RecipeCopy.chatTitle(recipe.name, atMillis, TimeZone.currentSystemDefault()),
        )
        val folder = conversations.folders().firstOrNull { it.name == RecipeCopy.FOLDER }?.id ?: conversations.createFolder(RecipeCopy.FOLDER)?.id
        folder?.let { conversations.moveToFolder(listOf(chat.id), it) }
        return chat.id
    }

    override suspend fun append(chatId: String, events: List<SessionEvent>) {
        SessionLog.open(chatId, sessions).appendAll(events)
    }

    override fun notifyFinished(recipe: Recipe, record: RecipeRunRecord) = notifications.finished(recipe, record, repository.summaryInNotifications)
}

/**
 * When recipes run on a node, the phone checks in with it: every 15 minutes (WorkManager's
 * shortest period) while any recipe is on a node, and right after "Run now".
 */
@Singleton
class NodeRecipeWork @Inject constructor(@ApplicationContext private val context: Context) {
    private val work: WorkManager get() = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Collects while [recipes] has any on a node; stops collecting otherwise. */
    fun keep(recipes: List<Recipe>) {
        if (recipes.any { it.runsOn != null }) {
            val request = PeriodicWorkRequestBuilder<NodeRecipeWorker>(15, TimeUnit.MINUTES).setConstraints(online).addTag(TAG).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(PERIODIC)
        }
    }

    /** One check soon (the app opened). */
    fun syncSoon() {
        val request = OneTimeWorkRequestBuilder<NodeRecipeWorker>().setConstraints(online).addTag(TAG).build()
        work.enqueueUniqueWork("node-recipes-now", ExistingWorkPolicy.KEEP, request)
    }

    /** "Run now" for a recipe on a node: starts it there, then waits for the result for a while. */
    fun runNow(recipe: Recipe) {
        val request = OneTimeWorkRequestBuilder<NodeRecipeWorker>()
            .setInputData(workDataOf(NodeRecipeWorker.KEY_RUN_NOW to recipe.id))
            .setConstraints(online)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork("node-recipe-now-${recipe.id}", ExistingWorkPolicy.REPLACE, request)
    }

    /** Panic wipe: no more checks. */
    fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    companion object {
        const val PERIODIC = "node-recipes"
        // Not RecipeScheduler.ALL: its syncAll cancels work that names no recipe.
        const val TAG = "node-recipes"
    }
}

class NodeRecipeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, NodeRecipeEntryPoint::class.java).nodeRecipeSync()
        val id = inputData.getString(KEY_RUN_NOW)
        if (id != null) {
            val recipe = EntryPointAccessors.fromApplication(applicationContext, NodeRecipeEntryPoint::class.java).recipes().get(id) ?: return Result.success()
            sync.runNow(recipe)?.let { return Result.success() }
            // A worker may run about ten minutes; a longer run is collected by the periodic check.
            sync.awaitRun(id, timeoutMillis = 9 * 60_000L)
            return Result.success()
        }
        sync.sync()
        return Result.success()
    }

    companion object {
        const val KEY_RUN_NOW = "run-now"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface NodeRecipeEntryPoint {
    fun nodeRecipeSync(): NodeRecipeSync
    fun recipes(): RecipeRepository
}
