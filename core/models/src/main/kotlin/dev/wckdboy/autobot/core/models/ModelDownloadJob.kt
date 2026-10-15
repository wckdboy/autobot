package dev.wckdboy.autobot.core.models

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The user-initiated data transfer job (Android 14+, so every supported device) that runs the
 * download queue. Unlike a dataSync foreground service it has no daily time limit (Android 15
 * caps dataSync at 6 h a day) and outlives the app's UI: leaving the app or swiping it from
 * recents does not stop it. If the process dies anyway, JobScheduler starts the job again and the
 * queue resumes from the part files. It does no networking itself: [ModelDownloader] does, through
 * the kill switch.
 */
@AndroidEntryPoint
class ModelDownloadJob : JobService() {

    @Inject
    lateinit var downloader: ModelDownloader

    override fun onStartJob(params: JobParameters): Boolean {
        downloader.onJobStarted(this, params)
        return true
    }

    /** Network lost, Wi-Fi only on mobile data, or stopped from Task Manager. */
    override fun onStopJob(params: JobParameters): Boolean = downloader.onJobStopped(params.stopReason)

    companion object {
        const val JOB_ID = 7101

        /**
         * The job for the queue: any network, or unmetered only with [wifiOnly]; [bytes] is what is
         * left to download. Scheduling a user-initiated job needs the app to be visible (or a
         * notification action); returns false when the system refused.
         */
        fun schedule(context: Context, wifiOnly: Boolean, bytes: Long): Boolean {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, ModelDownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(bytes.coerceAtLeast(0), 0)
                .build()
            return runCatching { context.getSystemService(JobScheduler::class.java).schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        }

        /** Whether the job is scheduled (waiting for its network) or running. */
        fun isScheduled(context: Context): Boolean = context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
    }
}
