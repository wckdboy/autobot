package dev.wckdboy.autobot.core.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [ModelDownloader] works and shows its progress.
 * It does no networking itself. When Android ends the dataSync allowance ([onTimeout]),
 * downloads pause and resume later from where they stopped.
 */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject
    lateinit var downloader: ModelDownloader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of model downloads"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        collector?.cancel()
        collector = scope.launch {
            combine(downloader.progress, downloader.busy) { p, busy -> p to busy }.collect { (p, busy) ->
                if (!busy) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (NotificationManagerCompat.from(this@ModelDownloadService).areNotificationsEnabled()) {
                    runCatching { NotificationManagerCompat.from(this@ModelDownloadService).notify(NOTIFICATION_ID, notification(p)) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloader.pauseAll()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(p: DownloadProgress?): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(p?.title ?: "Preparing download")
            .setContentText(
                p?.let { "${formatBytes(it.downloaded)} / ${formatBytes(it.total)} · ${formatBytes(it.bytesPerSecond)}/s" } ?: "Starting…",
            )
            .setProgress(1000, ((p?.fraction ?: 0f) * 1000).toInt(), p == null || p.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    private companion object {
        const val CHANNEL = "model_downloads"
        const val NOTIFICATION_ID = 7101
    }
}
