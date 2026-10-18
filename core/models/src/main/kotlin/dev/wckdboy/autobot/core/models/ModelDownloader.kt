package dev.wckdboy.autobot.core.models

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.StatFs
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Downloads queued models one at a time.
 *
 * Per file: resolve the URL hop by hop with redirects handled here (each hop passes the kill
 * switch and is audited; credentials are only sent to their own hub), append to `*.part` with
 * `Range` resume, hash while writing, verify SHA-256 (from the catalog/API, or the Hub's
 * `X-Linked-ETag`), then atomically rename.
 *
 * The queue is the model registry (QUEUED rows, oldest first), so it survives process death. It
 * runs inside a user-initiated data transfer job ([ModelDownloadJob]): the job keeps going when
 * the app is left or swiped away, waits for a network (unmetered with "Wi-Fi only"), and when
 * Android stops it (network lost, process killed) the interrupted model queues again and the job
 * resumes it from its part file. A pause, Task Manager's stop or the kill switch leave the model
 * paused; the kill switch's pauses continue by themselves once a network mode is allowed again.
 *
 * A pause or a removal is written to the registry first, under the same lock the loop picks the
 * next model with, and only then is the transfer stopped: what the user sees is what survives a
 * crash or a kill, and a model picked a moment earlier never starts.
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: ModelLibrary,
    clients: HttpClientFactory,
    private val accounts: ModelAccounts,
    @ApplicationScope private val scope: CoroutineScope,
    private val environment: DownloadEnvironmentSource,
    private val notifications: DownloadNotifications,
    private val settings: SettingsRepository,
    private val policy: NetworkPolicy,
    private val visibility: AppVisibility,
) {
    private val fetcher = FileFetcher(clients)
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The downloads panel: queue, current transfer, pauses and what blocks the queue (computed once, shared). */
    val downloads: Flow<DownloadsState> = downloadsState(library.models, progress, environment.environment, scope, Dispatchers.Default)

    private val lock = Mutex()

    /** A failed registry write (e.g. a full disk) must not take the app down with it. */
    private val guard = CoroutineExceptionHandler { _, e -> Log.w(TAG, "download bookkeeping failed", e) }

    /** The queue loop of the running job, and the job it reports to. */
    private var worker: Job? = null
    private var running: DownloadJob? = null

    /** A job started and its loop is not running yet: a kick must not schedule (restart) it. */
    @Volatile private var starting = false

    /** The loop found nothing more to do and is finishing its job; a kick waits for it. */
    private var closing = false
    private var kickAfterClose = false

    /** The model being downloaded, cancelled alone by a pause or a remove. */
    @Volatile private var current: Job? = null

    @Volatile private var currentId: String? = null

    /** Android stopped the job (not the user): what was running queues again instead of pausing. */
    @Volatile private var requeueOnStop = false

    private val recovery = Mutex()
    private var recovered = false

    private val attempts = ConcurrentHashMap<String, Int>()

    init {
        // The app came on screen: the moment a user-initiated job may be scheduled, so a queue
        // whose job is gone (e.g. after a force stop) starts again.
        visibility.whenVisible { scope.launch(guard) { if (!ModelDownloadJob.isScheduled(context)) kick() } }
        // A kill switch pause continues as soon as a network mode is allowed again.
        scope.launch(guard) {
            policy.mode.collect { mode ->
                if (mode == NetworkMode.Offline) return@collect
                val blocked = DownloadQueue.blockedByKillSwitch(library.all())
                if (blocked.isEmpty()) return@collect
                blocked.forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.QUEUED) }
                kick()
            }
        }
    }

    /** Queues [plan] and starts the job. */
    fun install(plan: ModelPlan) {
        scope.launch(guard) {
            library.add(plan)
            kick()
        }
    }

    /** Queues [id] again (a pause, a failure, or a model whose files went missing); the job ends once it is queued. */
    fun resume(id: String): Job = scope.launch(guard) {
        notifications.clearPaused()
        attempts.remove(id)
        library.get(id)?.let { library.progress(id, library.bytesOnDisk(id), ModelStatus.QUEUED) }
        kick()
    }

    /** Pauses [id]; the rest of the queue carries on. The job ends once the pause is stored and the transfer stopped. */
    fun pause(id: String, fromNotification: Boolean = false): Job = scope.launch(guard) {
        val transfer = lock.withLock {
            val model = library.get(id) ?: return@withLock null
            if (model.status == ModelStatus.QUEUED || model.status == ModelStatus.DOWNLOADING) {
                library.progress(id, library.bytesOnDisk(id), ModelStatus.PAUSED)
            }
            if (fromNotification) notifications.paused(id, model.title)
            current.takeIf { currentId == id }
        }
        transfer?.cancelAndJoin()
    }

    /**
     * Cancels [id] and deletes what it downloaded. The registry entry goes first, so the cancel
     * sticks even if the process dies before the transfer has stopped or the files are deleted
     * (left-over files are swept on the next start). The job ends when all is done.
     */
    fun remove(id: String): Job = scope.launch(guard) {
        val transfer = lock.withLock {
            library.forget(id)
            current.takeIf { currentId == id }
        }
        attempts.remove(id)
        transfer?.cancelAndJoin()
        notifications.clearPaused()
        library.deleteFiles(id)
    }

    /**
     * A ready model whose files are gone (deleted outside the app) becomes a failed download:
     * the downloads panel offers to retry it, which fetches what is missing.
     */
    fun markMissing(id: String): Job = scope.launch(guard) {
        lock.withLock { markIfMissing(library.get(id) ?: return@withLock) }
    }

    private suspend fun markIfMissing(model: InstalledModel) {
        val missing = library.missingFiles(model)
        if (missing.isEmpty()) return
        library.transition(model.id, ModelStatus.READY, ModelStatus.FAILED, library.bytesOnDisk(model.id), DownloadQueue.missingFilesMessage(missing.size, model.manifest.files.size))
    }

    /** Pauses everything queued or running. */
    fun pauseAll(): Job = scope.launch(guard) {
        val transfer = lock.withLock {
            library.all().filter { it.status == ModelStatus.QUEUED || it.status == ModelStatus.DOWNLOADING }
                .forEach { library.progress(it.id, library.bytesOnDisk(it.id), ModelStatus.PAUSED) }
            current
        }
        transfer?.cancelAndJoin()
    }

    /** "Wi-Fi only" changes the job's network constraint: the job is scheduled again with it. */
    fun setWifiOnly(enabled: Boolean) {
        scope.launch(guard) {
            environment.setWifiOnly(enabled)
            val loop = lock.withLock { worker }
            if (loop != null) {
                requeueOnStop = true
                loop.cancelAndJoin()
            }
            kick()
        }
    }

    /**
     * Once per process, before the queue runs: a download that was in flight when the process
     * died queues again (it resumes from its part file).
     */
    suspend fun recoverAfterRestart() {
        recovery.withLock {
            if (recovered) return
            recovered = true
            runCatching {
                DownloadQueue.recover(library.all()).forEach { library.progress(it.id, library.bytesOnDisk(it.id), ModelStatus.QUEUED) }
                // Models a panic wipe kept are registered again before their folders look orphaned.
                library.adoptKept()
                // Files of models removed just before the process died.
                library.sweepOrphans().forEach { Log.i(TAG, "deleted the files of a removed model: $it") }
                // Ready models whose files were deleted outside the app are offered for download again.
                lock.withLock { library.all().filter { it.isReady }.forEach { markIfMissing(it) } }
            }.onFailure { Log.w(TAG, "queue recovery failed", it) }
        }
        // Rows still QUEUED (and the ones just restored) get a job if none is waiting for them.
        if (!ModelDownloadJob.isScheduled(context)) runCatching { kick() }
    }

    // ---- The job

    /** [ModelDownloadJob] started: show its notification right away, then run the queue. */
    fun onJobStarted(service: JobService, params: JobParameters) = onJobStarted(
        object : DownloadJob {
            override fun notify(notification: android.app.Notification) {
                service.setNotification(params, DownloadNotifications.NOTIFICATION_ID, notification, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
            }

            override fun finished(reschedule: Boolean) = service.jobFinished(params, reschedule)
        },
    )

    internal fun onJobStarted(job: DownloadJob) {
        starting = true
        showJobNotification(job, notifications.running(null, null, 0, null))
        scope.launch(guard) {
            try {
                lock.withLock { worker }?.cancelAndJoin()
                lock.withLock {
                    running = job
                    closing = false
                    worker = scope.launch(Dispatchers.IO + guard) { drain(job) }
                    starting = false
                }
            } finally {
                starting = false
            }
        }
    }

    /**
     * Android stops the job: lost network, Wi-Fi only on mobile data, the process is needed, or
     * the user stopped it in Task Manager. Returns whether the job should run again.
     */
    fun onJobStopped(reason: Int): Boolean {
        val byUser = reason == JobParameters.STOP_REASON_USER
        requeueOnStop = !byUser
        scope.launch(guard) {
            val loop = lock.withLock {
                running = null
                worker
            }
            loop?.cancelAndJoin()
            if (byUser) {
                lock.withLock {
                    library.all().filter { it.status == ModelStatus.QUEUED }.forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED) }
                }
            }
        }
        return !byUser
    }

    private enum class Outcome { NEXT, WAIT_FOR_NETWORK, KILL_SWITCH }

    private fun showJobNotification(job: DownloadJob, notification: android.app.Notification) {
        runCatching { job.notify(notification) }
    }

    private suspend fun drain(job: DownloadJob) {
        val self = currentCoroutineContext()[Job]
        _busy.value = true
        requeueOnStop = false
        var reschedule = false
        try {
            recoverAfterRestart()
            awaitNetworkPolicy()
            while (true) {
                var outcome = Outcome.NEXT
                // Picked and published as current under the lock a pause or a removal takes:
                // either it sees this transfer (and stops it), or this pick no longer sees the model.
                val more = coroutineScope {
                    val child = lock.withLock {
                        val next = DownloadQueue.next(library.all())
                        if (next == null) {
                            closing = true
                            return@withLock null
                        }
                        launch(start = CoroutineStart.LAZY) { outcome = runOne(next, job) }.also {
                            current = it
                            currentId = next.id
                        }
                    } ?: return@coroutineScope false
                    child.start()
                    child.join()
                    true
                }
                current = null
                currentId = null
                if (!more) break
                if (outcome == Outcome.WAIT_FOR_NETWORK) {
                    reschedule = true
                    break
                }
                if (outcome == Outcome.KILL_SWITCH) {
                    lock.withLock {
                        library.all().filter { it.status == ModelStatus.QUEUED }
                            .forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED, DownloadQueue.KILL_SWITCH_ERROR) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The registry could not be read (e.g. the disk is full): stop; the next start retries.
            reschedule = false
        } finally {
            withContext(NonCancellable) {
                current = null
                currentId = null
                _progress.value = null
                _busy.value = false
                val again = lock.withLock {
                    if (running === job) {
                        running = null
                        runCatching { job.finished(reschedule) }
                    }
                    if (worker === self) worker = null
                    closing = false
                    kickAfterClose.also { kickAfterClose = false }
                }
                if (again) kick()
            }
        }
    }

    private suspend fun runOne(model: InstalledModel, job: DownloadJob): Outcome = try {
        attempt(model, job)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything unexpected (a full disk on a progress write, a Keystore error reading a token, a
        // malformed server answer) fails this model, never the app; the queue moves on.
        withContext(NonCancellable) {
            runCatching { library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.FAILED, e.message ?: e.javaClass.simpleName) }
        }
        Outcome.NEXT
    }

    private suspend fun attempt(model: InstalledModel, job: DownloadJob): Outcome {
        val waiting = DownloadQueue.queued(library.all()).count { it.id != model.id }
        resetSpeed()
        return try {
            // Paused or removed since it was picked: leave it be.
            if (!library.start(model.id, model.downloadedBytes)) return Outcome.NEXT
            requireSpace(model)
            showJobNotification(job, notifications.running(null, model.title, waiting, null))
            var manifest = model.manifest
            val done = mutableListOf<Long>()
            for ((index, file) in manifest.files.withIndex()) {
                val sizeBefore = done.sum()
                val result = downloadFile(model, file) { written, fileTotal ->
                    val total = manifest.files.sumOf { it.sizeBytes }.coerceAtLeast(sizeBefore + fileTotal)
                    report(model, sizeBefore + written, total, waiting)
                }
                // Adopt the authoritative size/hash learned from the server, and keep it: a resume
                // after a later file fails skips this one instead of fetching it again.
                manifest = manifest.copy(files = manifest.files.toMutableList().also { it[index] = result })
                done += result.sizeBytes
                if (result != file) library.update(model, manifest, manifest.files.sumOf { it.sizeBytes })
            }
            val total = manifest.files.sumOf { it.sizeBytes }
            library.update(model, manifest, total)
            library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.READY, total)
            attempts.remove(model.id)
            if (!visibility.visible) notifications.finished(model.id, model.title, ok = true)
            Outcome.NEXT
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // A pause already stored PAUSED and a removal deleted the entry: only a stop by
                // Android (or the job being replaced) still finds it DOWNLOADING.
                val status = if (requeueOnStop) ModelStatus.QUEUED else ModelStatus.PAUSED
                library.transition(model.id, ModelStatus.DOWNLOADING, status, library.bytesOnDisk(model.id))
            }
            throw e
        } catch (e: DownloadException) {
            val status = if (e.retryable) ModelStatus.PAUSED else ModelStatus.FAILED
            library.transition(model.id, ModelStatus.DOWNLOADING, status, library.bytesOnDisk(model.id), e.message)
            if (status == ModelStatus.FAILED && !visibility.visible) notifications.finished(model.id, model.title, ok = false)
            Outcome.NEXT
        } catch (e: NetworkBlockedException) {
            if (policy.mode.value == NetworkMode.Offline) {
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.PAUSED, library.bytesOnDisk(model.id), DownloadQueue.KILL_SWITCH_ERROR)
                Outcome.KILL_SWITCH
            } else {
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.PAUSED, library.bytesOnDisk(model.id), "Blocked by the network policy (${e.message})")
                Outcome.NEXT
            }
        } catch (e: IOException) {
            if (DownloadQueue.isDiskFull(e)) {
                // The disk filled up while writing: retrying can't help until space is freed.
                val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L)
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.PAUSED, library.bytesOnDisk(model.id), DownloadQueue.diskFullMessage(free))
                return Outcome.NEXT
            }
            if (!environment.current().connected) {
                // The job waits for a network and continues from the part file.
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.QUEUED, library.bytesOnDisk(model.id))
                return Outcome.WAIT_FOR_NETWORK
            }
            val attempt = (attempts[model.id] ?: 0) + 1
            attempts[model.id] = attempt
            if (attempt < MAX_ATTEMPTS) {
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.QUEUED, library.bytesOnDisk(model.id))
                delay(retryDelayMs shl (attempt - 1))
            } else {
                attempts.remove(model.id)
                library.transition(model.id, ModelStatus.DOWNLOADING, ModelStatus.PAUSED, library.bytesOnDisk(model.id), e.message ?: "Network error")
            }
            Outcome.NEXT
        }
    }

    /** Requeues the queue's models when there is something to run and no job runs it. */
    private suspend fun kick() {
        lock.withLock {
            when (DownloadQueue.kick(starting, worker?.isActive == true, closing)) {
                DownloadQueue.Kick.SCHEDULE -> Unit
                DownloadQueue.Kick.SKIP -> return
                DownloadQueue.Kick.AFTER_CLOSE -> {
                    kickAfterClose = true
                    return
                }
            }
            val queued = DownloadQueue.queued(library.all())
            if (queued.isEmpty()) return
            val remaining = queued.sumOf { (it.totalBytes - it.downloadedBytes).coerceAtLeast(0) }
            // Refused while the app is not visible; the next time it is, [visibility] tries again.
            ModelDownloadJob.schedule(context, environment.wifiOnlyNow(), remaining)
        }
    }

    /**
     * In a process the job started, settings may not be mirrored into the [NetworkPolicy] yet (it
     * fails closed as Offline): wait briefly for the persisted mode before the first request.
     */
    private suspend fun awaitNetworkPolicy() {
        val wanted = withTimeoutOrNull(5_000) { settings.settings.first().networkMode } ?: return
        withTimeoutOrNull(3_000) { policy.mode.first { it == wanted } }
    }

    private var lastReport = 0L
    private var lastBytes = -1L
    private var lastNotify = 0L
    private var lastDbWrite = 0L
    private var speed = 0L

    private fun resetSpeed() {
        lastBytes = -1L
        speed = 0L
    }

    private suspend fun report(model: InstalledModel, downloaded: Long, total: Long, waiting: Int) {
        val now = System.nanoTime()
        val dt = (now - lastReport) / 1e9
        if (dt < 0.25 && downloaded < total) return
        // The first sample after a (re)start only sets the baseline: resumed bytes are not speed.
        if (lastBytes >= 0 && dt > 0 && downloaded >= lastBytes) speed = DownloadQueue.smooth(speed, ((downloaded - lastBytes) / dt).toLong())
        lastReport = now
        lastBytes = downloaded
        val p = DownloadProgress(model.id, model.title, downloaded, total, speed)
        _progress.value = p
        if (now - lastNotify > 1_000_000_000L) {
            lastNotify = now
            notifications.update(notifications.running(p, model.title, waiting, null))
        }
        if (now - lastDbWrite > 1_000_000_000L) {
            lastDbWrite = now
            library.downloaded(model.id, downloaded)
        }
    }

    private fun requireSpace(model: InstalledModel) {
        val free = StatFs(context.filesDir.path).availableBytes
        val needed = (model.totalBytes - library.bytesOnDisk(model.id)).coerceAtLeast(0) + RESERVE_BYTES
        if (free < needed) {
            throw DownloadException("${DownloadQueue.LOW_STORAGE_PREFIX}: needs ${formatBytes(needed)}, ${formatBytes(free)} free", retryable = true)
        }
    }

    /** Before the first retry; doubled for each next one. Tests shorten it. */
    @Volatile
    internal var retryDelayMs = RETRY_DELAY_MS

    /** Downloads one file (resuming) and returns it with its verified size and SHA-256. */
    private suspend fun downloadFile(model: InstalledModel, file: ModelFile, onProgress: suspend (Long, Long) -> Unit): ModelFile {
        val target = library.fileOf(model.id, file)
        if (target.isFile && file.sha256 != null && (file.sizeBytes == 0L || target.length() == file.sizeBytes)) {
            onProgress(target.length(), target.length())
            return file.copy(sizeBytes = target.length())
        }
        val credential = when (file.auth) {
            AuthHost.HUGGING_FACE -> accounts.huggingFaceToken()
            AuthHost.CIVITAI -> accounts.civitaiKey()
            null -> null
        }
        return fetcher.fetch(file, target, credential, onProgress)
    }

    private companion object {
        const val TAG = "ModelDownloader"
        const val RESERVE_BYTES = 512L * 1024 * 1024
        const val MAX_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}

/** The running [ModelDownloadJob] as the queue loop reports to it. */
internal interface DownloadJob {
    fun notify(notification: android.app.Notification)

    fun finished(reschedule: Boolean)
}

/**
 * The downloads panel state from the registry, the live transfer and the environment, computed on
 * [dispatcher] once per change and shared by every collector (latest value replayed).
 */
internal fun downloadsState(
    models: Flow<List<InstalledModel>>,
    progress: Flow<DownloadProgress?>,
    environment: Flow<DownloadEnvironment>,
    scope: CoroutineScope,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
): Flow<DownloadsState> = combine(models, progress, environment) { m, p, env -> DownloadQueue.state(m, p, env) }
    .distinctUntilChanged()
    .flowOn(dispatcher)
    .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
