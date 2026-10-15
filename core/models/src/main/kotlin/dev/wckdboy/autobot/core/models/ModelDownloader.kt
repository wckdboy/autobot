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
    private var running: Pair<JobService, JobParameters>? = null

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

    fun resume(id: String) {
        scope.launch(guard) {
            notifications.clearPaused()
            attempts.remove(id)
            library.get(id)?.let { library.progress(id, it.downloadedBytes, ModelStatus.QUEUED) }
            kick()
        }
    }

    /** Pauses [id]; the rest of the queue carries on. */
    fun pause(id: String, fromNotification: Boolean = false) {
        scope.launch(guard) {
            if (currentId == id) current?.cancelAndJoin()
            library.get(id)?.let {
                library.progress(id, library.bytesOnDisk(id), ModelStatus.PAUSED)
                if (fromNotification) notifications.paused(id, it.title)
            }
        }
    }

    /** Cancels [id] and deletes what it downloaded. */
    fun remove(id: String) {
        scope.launch(guard) {
            if (currentId == id) current?.cancelAndJoin()
            notifications.clearPaused()
            library.remove(id)
        }
    }

    /** Pauses everything queued or running. */
    fun pauseAll() {
        scope.launch(guard) {
            library.all().filter { it.status == ModelStatus.QUEUED }.forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED) }
            currentId?.let { id ->
                current?.cancelAndJoin()
                library.progress(id, library.bytesOnDisk(id), ModelStatus.PAUSED)
            }
        }
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
            }.onFailure { Log.w(TAG, "queue recovery failed", it) }
        }
        // Rows still QUEUED (and the ones just restored) get a job if none is waiting for them.
        if (!ModelDownloadJob.isScheduled(context)) runCatching { kick() }
    }

    // ---- The job

    /** [ModelDownloadJob] started: show its notification right away, then run the queue. */
    fun onJobStarted(service: JobService, params: JobParameters) {
        starting = true
        showJobNotification(service, params, notifications.running(null, null, 0, null))
        scope.launch(guard) {
            try {
                lock.withLock { worker }?.cancelAndJoin()
                lock.withLock {
                    running = service to params
                    closing = false
                    worker = scope.launch(Dispatchers.IO + guard) { drain(service, params) }
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
                library.all().filter { it.status == ModelStatus.QUEUED }.forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED) }
            }
        }
        return !byUser
    }

    private enum class Outcome { NEXT, WAIT_FOR_NETWORK, KILL_SWITCH }

    private fun showJobNotification(service: JobService, params: JobParameters, notification: android.app.Notification) {
        runCatching {
            service.setNotification(params, DownloadNotifications.NOTIFICATION_ID, notification, JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
        }
    }

    private suspend fun drain(service: JobService, params: JobParameters) {
        val self = currentCoroutineContext()[Job]
        _busy.value = true
        requeueOnStop = false
        var reschedule = false
        try {
            recoverAfterRestart()
            awaitNetworkPolicy()
            while (true) {
                val next = lock.withLock {
                    DownloadQueue.next(library.all()).also { if (it == null) closing = true }
                } ?: break
                var outcome = Outcome.NEXT
                coroutineScope {
                    val child = launch { outcome = runOne(next, service, params) }
                    current = child
                    currentId = next.id
                    child.join()
                }
                current = null
                currentId = null
                if (outcome == Outcome.WAIT_FOR_NETWORK) {
                    reschedule = true
                    break
                }
                if (outcome == Outcome.KILL_SWITCH) {
                    library.all().filter { it.status == ModelStatus.QUEUED }
                        .forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED, DownloadQueue.KILL_SWITCH_ERROR) }
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
                    if (running?.second === params) {
                        running = null
                        runCatching { service.jobFinished(params, reschedule) }
                    }
                    if (worker === self) worker = null
                    closing = false
                    kickAfterClose.also { kickAfterClose = false }
                }
                if (again) kick()
            }
        }
    }

    private suspend fun runOne(model: InstalledModel, service: JobService, params: JobParameters): Outcome = try {
        attempt(model, service, params)
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

    private suspend fun attempt(model: InstalledModel, service: JobService, params: JobParameters): Outcome {
        val waiting = DownloadQueue.queued(library.all()).count { it.id != model.id }
        resetSpeed()
        return try {
            requireSpace(model)
            library.progress(model.id, model.downloadedBytes, ModelStatus.DOWNLOADING)
            showJobNotification(service, params, notifications.running(null, model.title, waiting, null))
            var manifest = model.manifest
            val done = mutableListOf<Long>()
            for ((index, file) in manifest.files.withIndex()) {
                val sizeBefore = done.sum()
                val result = downloadFile(model, file) { written, fileTotal ->
                    val total = manifest.files.sumOf { it.sizeBytes }.coerceAtLeast(sizeBefore + fileTotal)
                    report(model, sizeBefore + written, total, waiting)
                }
                // Adopt the authoritative size/hash learned from the server.
                manifest = manifest.copy(files = manifest.files.toMutableList().also { it[index] = result })
                done += result.sizeBytes
            }
            val total = manifest.files.sumOf { it.sizeBytes }
            library.update(model, manifest, total)
            library.progress(model.id, total, ModelStatus.READY)
            attempts.remove(model.id)
            if (!visibility.visible) notifications.finished(model.id, model.title, ok = true)
            Outcome.NEXT
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                library.progress(model.id, library.bytesOnDisk(model.id), if (requeueOnStop) ModelStatus.QUEUED else ModelStatus.PAUSED)
            }
            throw e
        } catch (e: DownloadException) {
            val status = if (e.retryable) ModelStatus.PAUSED else ModelStatus.FAILED
            library.progress(model.id, library.bytesOnDisk(model.id), status, e.message)
            if (status == ModelStatus.FAILED && !visibility.visible) notifications.finished(model.id, model.title, ok = false)
            Outcome.NEXT
        } catch (e: NetworkBlockedException) {
            if (policy.mode.value == NetworkMode.Offline) {
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED, DownloadQueue.KILL_SWITCH_ERROR)
                Outcome.KILL_SWITCH
            } else {
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED, "Blocked by the network policy (${e.message})")
                Outcome.NEXT
            }
        } catch (e: IOException) {
            if (!environment.current().connected) {
                // The job waits for a network and continues from the part file.
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.QUEUED)
                return Outcome.WAIT_FOR_NETWORK
            }
            val attempt = (attempts[model.id] ?: 0) + 1
            attempts[model.id] = attempt
            if (attempt < MAX_ATTEMPTS) {
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.QUEUED)
                delay(RETRY_DELAY_MS shl (attempt - 1))
            } else {
                attempts.remove(model.id)
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED, e.message ?: "Network error")
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
            library.progress(model.id, downloaded, ModelStatus.DOWNLOADING)
        }
    }

    private fun requireSpace(model: InstalledModel) {
        val free = StatFs(context.filesDir.path).availableBytes
        val needed = (model.totalBytes - library.bytesOnDisk(model.id)).coerceAtLeast(0) + RESERVE_BYTES
        if (free < needed) {
            throw DownloadException("${DownloadQueue.LOW_STORAGE_PREFIX}: needs ${formatBytes(needed)}, ${formatBytes(free)} free", retryable = true)
        }
    }

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
