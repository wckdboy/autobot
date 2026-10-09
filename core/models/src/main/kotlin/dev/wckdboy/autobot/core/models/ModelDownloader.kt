package dev.wckdboy.autobot.core.models

import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live progress of the running download. */
data class DownloadProgress(val id: String, val title: String, val downloaded: Long, val total: Long, val bytesPerSecond: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Downloads queued models one at a time.
 *
 * Per file: resolve the URL hop by hop with redirects handled here (each hop passes the kill
 * switch and is audited; credentials are only sent to their own hub), append to `*.part` with
 * `Range` resume, hash while writing, verify SHA-256 (from the catalog/API, or the Hub's
 * `X-Linked-ETag`), then atomically rename. A foreground service keeps the process alive.
 */
@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: ModelLibrary,
    private val clients: HttpClientFactory,
    private val accounts: ModelAccounts,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val fetcher = FileFetcher(clients)
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val lock = Mutex()
    private var worker: Job? = null
    private var currentId: String? = null

    /** Queues [plan] and starts the worker. */
    fun install(plan: ModelPlan) {
        scope.launch {
            library.add(plan)
            kick()
        }
    }

    fun resume(id: String) {
        scope.launch {
            library.get(id)?.let { library.progress(id, it.downloadedBytes, ModelStatus.QUEUED) }
            kick()
        }
    }

    fun pause(id: String) {
        scope.launch {
            if (currentId == id) worker?.cancel(CancellationException("paused"))
            library.get(id)?.let { library.progress(id, it.downloadedBytes, ModelStatus.PAUSED) }
        }
    }

    fun remove(id: String) {
        scope.launch {
            if (currentId == id) {
                worker?.cancel(CancellationException("removed"))
                worker?.join()
            }
            library.remove(id)
            kick()
        }
    }

    /** Called when Android ends the foreground service (e.g. the dataSync time limit). */
    fun pauseAll() {
        scope.launch {
            worker?.cancel(CancellationException("paused by system"))
            library.all().filter { it.status == ModelStatus.DOWNLOADING || it.status == ModelStatus.QUEUED }
                .forEach { library.progress(it.id, it.downloadedBytes, ModelStatus.PAUSED) }
        }
    }

    /** After a process restart nothing is running: interrupted downloads become PAUSED. */
    suspend fun recoverAfterRestart() {
        library.all().filter { it.status == ModelStatus.DOWNLOADING }.forEach {
            library.progress(it.id, library.bytesOnDisk(it.id), ModelStatus.PAUSED)
        }
    }

    private suspend fun kick() = lock.withLock {
        if (worker?.isActive == true) return@withLock
        worker = scope.launch(Dispatchers.IO) {
            _busy.value = true
            ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java))
            try {
                while (true) {
                    val next = library.all().filter { it.status == ModelStatus.QUEUED }.minByOrNull { it.addedAt } ?: break
                    currentId = next.id
                    runOne(next)
                    currentId = null
                }
            } finally {
                currentId = null
                _progress.value = null
                _busy.value = false
            }
        }
    }

    private suspend fun runOne(model: InstalledModel) {
        try {
            requireSpace(model)
            library.progress(model.id, model.downloadedBytes, ModelStatus.DOWNLOADING)
            var manifest = model.manifest
            val done = mutableListOf<Long>()
            for ((index, file) in manifest.files.withIndex()) {
                val sizeBefore = done.sum()
                val result = downloadFile(model, file) { written, fileTotal ->
                    val total = manifest.files.sumOf { it.sizeBytes }.coerceAtLeast(sizeBefore + fileTotal)
                    report(model, sizeBefore + written, total)
                }
                // Adopt the authoritative size/hash learned from the server.
                manifest = manifest.copy(files = manifest.files.toMutableList().also { it[index] = result })
                done += result.sizeBytes
            }
            val total = manifest.files.sumOf { it.sizeBytes }
            library.update(model, manifest, total)
            library.progress(model.id, total, ModelStatus.READY)
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED)
            }
            throw e
        } catch (e: DownloadException) {
            library.progress(model.id, library.bytesOnDisk(model.id), if (e.retryable) ModelStatus.PAUSED else ModelStatus.FAILED, e.message)
        } catch (e: NetworkBlockedException) {
            library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED, "Network is off (${e.message}). Allow a network mode in Privacy Center, then resume.")
        } catch (e: IOException) {
            library.progress(model.id, library.bytesOnDisk(model.id), ModelStatus.PAUSED, e.message ?: "Network error")
        }
    }

    private var lastReport = 0L
    private var lastBytes = 0L
    private var lastDbWrite = 0L

    private suspend fun report(model: InstalledModel, downloaded: Long, total: Long) {
        val now = System.nanoTime()
        val dt = (now - lastReport) / 1e9
        if (dt < 0.25 && downloaded < total) return
        val speed = if (dt > 0 && downloaded >= lastBytes) ((downloaded - lastBytes) / dt).toLong() else 0
        lastReport = now
        lastBytes = downloaded
        _progress.value = DownloadProgress(model.id, model.title, downloaded, total, speed)
        if (now - lastDbWrite > 1_000_000_000L) {
            lastDbWrite = now
            library.progress(model.id, downloaded, ModelStatus.DOWNLOADING)
        }
    }

    private fun requireSpace(model: InstalledModel) {
        val free = StatFs(context.filesDir.path).availableBytes
        val needed = (model.totalBytes - library.bytesOnDisk(model.id)).coerceAtLeast(0) + RESERVE_BYTES
        if (free < needed) {
            throw DownloadException("Not enough storage: needs ${formatBytes(needed)}, ${formatBytes(free)} free", retryable = true)
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
        const val RESERVE_BYTES = 512L * 1024 * 1024
    }
}
