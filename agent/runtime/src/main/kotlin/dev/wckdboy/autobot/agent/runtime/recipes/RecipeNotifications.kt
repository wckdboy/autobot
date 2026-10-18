package dev.wckdboy.autobot.agent.runtime.recipes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.agent.runtime.R
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recipe notifications. The title only ever names the recipe ("Recipe Morning brief finished",
 * "Unlock to run Morning brief"); the lock screen gets exactly that (a public version), and the
 * body carries a summary only when the user switched "Summary in notifications" on. Tapping one
 * opens the result chat after the app lock.
 */
@Singleton
class RecipeNotifications @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, RecipeCopy.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = RecipeCopy.CHANNEL_DETAIL
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    val enabled: Boolean get() = manager.areNotificationsEnabled()

    fun finished(recipe: Recipe, record: RecipeRunRecord, showSummary: Boolean) {
        cancelUnlock(recipe.id)
        val title = RecipeCopy.title(recipe.name, record)
        post(tag(recipe.id), FINISHED, build(title, RecipeCopy.body(record, showSummary), open(recipe.id, record.sessionId, FINISHED)))
    }

    /** A due run could not read the encrypted data: says so, with the name only. */
    fun unlockToRun(recipeId: String, name: String) {
        post(tag(recipeId), UNLOCK, build(RecipeCopy.unlockToRun(name), RecipeCopy.UNLOCK_BODY, open(recipeId, null, UNLOCK)))
    }

    fun cancelUnlock(recipeId: String) = manager.cancel(tag(recipeId), UNLOCK)

    /** Panic wipe: every recipe notification goes. */
    fun cancelAll() {
        manager.activeNotificationsCompat().filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun NotificationManagerCompat.activeNotificationsCompat() = runCatching { activeNotifications }.getOrDefault(emptyList())

    private fun build(title: String, body: String, intent: PendingIntent?): Notification {
        // What the lock screen shows instead (the title is all it carries).
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(title)
            .build()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_recipe)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun post(tag: String, id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) runCatching { manager.notify(tag, id, notification) }
    }

    /**
     * Opens the app on the recipe's result chat (or the Recipes screen). Each notification of a
     * recipe ([id]) has its own request code: with one shared code, FLAG_UPDATE_CURRENT would
     * let a later "Unlock to run" strip the result chat from the finished notification's tap.
     */
    private fun open(recipeId: String, chatId: String?, id: Int): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        val intent = it.setAction(ACTION_OPEN).putExtra(EXTRA_RECIPE, recipeId).apply { chatId?.let { c -> putExtra(EXTRA_CHAT, c) } }
        PendingIntent.getActivity(context, 31 * recipeId.hashCode() + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Notifications are tagged per recipe, so their ids never meet another feature's. */
    private fun tag(recipeId: String) = "recipe:$recipeId"

    companion object {
        const val CHANNEL = "recipe_results"
        private const val FINISHED = 1
        private const val UNLOCK = 2

        /** The intent a recipe notification opens (handled by the app's entry routing). */
        const val ACTION_OPEN = "ai.wckd.autobot.action.OPEN_RECIPE"
        const val EXTRA_RECIPE = "ai.wckd.autobot.extra.RECIPE"
        const val EXTRA_CHAT = "ai.wckd.autobot.extra.CHAT"
    }
}
