package dev.wckdboy.autobot.core.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The download notifications. The running one belongs to the user-initiated job (Android shows
 * it, or the job in Task Manager when notifications are off); it names the model, its progress,
 * speed and time left, and offers pause and cancel. A pause from it leaves a quiet "paused"
 * notification with resume. Finished and failed downloads say so with the model's name only.
 */
@Singleton
class DownloadNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of model downloads"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "A model finished downloading or failed"
                    setShowBadge(false)
                },
            ),
        )
    }

    val enabled: Boolean get() = manager.areNotificationsEnabled()

    /** The job's notification: [p] is null until the first bytes arrive. */
    fun running(p: DownloadProgress?, title: String?, waiting: Int, block: DownloadBlock?): Notification {
        val text = when {
            block != null -> block.label
            p == null -> "Starting…"
            else -> listOfNotNull(
                "${formatBytes(p.downloaded)} / ${formatBytes(p.total)}",
                "${formatBytes(p.bytesPerSecond)}/s".takeIf { p.bytesPerSecond > 0 },
                p.etaSeconds?.let { "${DownloadQueue.formatEta(it)} left" },
            ).joinToString(" · ")
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(p?.title ?: title ?: "Preparing download")
            .setContentText(text)
            .setSubText(if (waiting > 0) "$waiting more queued" else null)
            .setProgress(1000, ((p?.fraction ?: 0f) * 1000).toInt(), p == null || p.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                p?.let {
                    addAction(0, "Pause", action(ACTION_PAUSE, it.id))
                    addAction(0, "Cancel", action(ACTION_CANCEL, it.id))
                }
            }
            .build()
    }

    fun update(notification: Notification) = post(NOTIFICATION_ID, notification)

    /** Left after a pause from the notification, so the download can be resumed from there. */
    fun paused(id: String, title: String) = post(
        PAUSED_ID,
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText("Paused")
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Resume", action(ACTION_RESUME, id))
            .addAction(0, "Cancel", action(ACTION_CANCEL, id))
            .build(),
    )

    fun clearPaused() = manager.cancel(PAUSED_ID)

    /** A model is ready or failed: its name only. */
    fun finished(id: String, title: String, ok: Boolean) = post(
        DONE_ID_BASE + (id.hashCode() and 0xffff),
        NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (ok) "Model ready" else "Download failed")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build(),
    )

    private fun post(id: Int, notification: Notification) {
        if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, notification) }
    }

    private fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        (action + id).hashCode(),
        Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "model_downloads"
        const val CHANNEL_DONE = "model_downloads_done"
        const val NOTIFICATION_ID = 7101
        const val PAUSED_ID = 7102
        private const val DONE_ID_BASE = 7200
        const val ACTION_PAUSE = "dev.wckdboy.autobot.models.PAUSE"
        const val ACTION_RESUME = "dev.wckdboy.autobot.models.RESUME"
        const val ACTION_CANCEL = "dev.wckdboy.autobot.models.CANCEL"
        const val EXTRA_ID = "id"
    }
}

/** Pause / resume / cancel from a download notification. */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloader: ModelDownloader

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadNotifications.EXTRA_ID) ?: return
        when (intent.action) {
            DownloadNotifications.ACTION_PAUSE -> downloader.pause(id, fromNotification = true)
            DownloadNotifications.ACTION_RESUME -> downloader.resume(id)
            DownloadNotifications.ACTION_CANCEL -> downloader.remove(id)
        }
    }
}
