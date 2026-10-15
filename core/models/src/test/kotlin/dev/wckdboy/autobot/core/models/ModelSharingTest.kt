package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.data.model.ModelRow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry flow and the downloads panel: one upstream and one decode for every watcher, off the caller's thread. */
class ModelSharingTest {

    private fun row(id: String, downloaded: Long = 0, file: String = "m.gguf") = ModelRow(
        id = id, kind = "CHAT", source = "HUGGING_FACE", title = id, subtitle = "", format = "GGUF", engine = "LLAMA",
        baseModel = null, license = null, nsfw = false, totalBytes = 100, downloadedBytes = downloaded, status = "DOWNLOADING",
        error = null, manifestJson = Json.encodeToString(ModelManifest.serializer(), ModelManifest(listOf(ModelFile(FileRole.MODEL, file, "https://x/$file", 100, null, null)))),
        addedAt = 1, updatedAt = 1,
    )

    @Test
    fun eightCollectorsShareOneUpstreamAndDecodeOffTheCallersThread() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "decode-worker") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val upstreamStarts = AtomicInteger()
        val decodeThreads = mutableSetOf<String>()
        val rows = MutableStateFlow(listOf(row("a")))
        val decoder = ModelRowDecoder()
        val shared = sharedModels(rows.onStart { upstreamStarts.incrementAndGet() }, scope, worker) { list ->
            synchronized(decodeThreads) { decodeThreads += Thread.currentThread().name.substringBefore(" @") }
            list.mapNotNull { r -> decoder.manifest(r.id, r.manifestJson)?.let { r.id to r.downloadedBytes } }
        }
        val collectors = List(8) { scope.launch { shared.collect { } } }
        withTimeout(5_000) { shared.first { it.isNotEmpty() } }

        // A download writes its progress every second: 10 writes, same manifest.
        for (bytes in 1L..10L) {
            rows.value = listOf(row("a", downloaded = bytes))
            withTimeout(5_000) { shared.first { it.single().second == bytes } }
        }

        assertEquals("one database query for all watchers", 1, upstreamStarts.get())
        assertEquals("mapping runs on the worker only", setOf("decode-worker"), decodeThreads)
        assertNotEquals(Thread.currentThread().name.substringBefore(" @"), decodeThreads.single())
        assertEquals("manifest parsed once across 11 emissions", 1, decoder.decoded)

        // A changed manifest is decoded again.
        rows.value = listOf(row("a", downloaded = 10, file = "other.gguf"))
        withTimeout(5_000) { shared.first { true } }
        assertTrue(decoder.decoded <= 2)
        collectors.forEach { it.cancel() }
        scope.cancel()
        worker.close()
    }

    @Test
    fun downloadsPanelIsComputedOncePerChangeForAllCollectors() = runBlocking {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "panel-worker") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        val starts = AtomicInteger()
        val models = MutableStateFlow(emptyList<InstalledModel>())
        val progress = MutableStateFlow<DownloadProgress?>(null)
        val env = MutableStateFlow(DownloadEnvironment())
        val panel = downloadsState(models.onStart { starts.incrementAndGet() }, progress, env, scope, worker)
        val collectors = List(5) { scope.launch { panel.collect { } } }
        withTimeout(5_000) { panel.first() }
        env.value = DownloadEnvironment(wifiOnly = true)
        withTimeout(5_000) { panel.first { it.wifiOnly } }
        assertEquals(1, starts.get())
        collectors.forEach { it.cancel() }
        scope.cancel()
        worker.close()
    }

    @Test
    fun kickNeverReschedulesAStartingOrRunningJob() {
        // The race: the job started (onJobStarted) but its loop is not assigned yet.
        assertEquals(DownloadQueue.Kick.SKIP, DownloadQueue.kick(starting = true, workerActive = false, closing = false))
        assertEquals(DownloadQueue.Kick.SKIP, DownloadQueue.kick(starting = true, workerActive = true, closing = true))
        assertEquals(DownloadQueue.Kick.SKIP, DownloadQueue.kick(starting = false, workerActive = true, closing = false))
        assertEquals(DownloadQueue.Kick.AFTER_CLOSE, DownloadQueue.kick(starting = false, workerActive = true, closing = true))
        assertEquals(DownloadQueue.Kick.SCHEDULE, DownloadQueue.kick(starting = false, workerActive = false, closing = false))
    }

    @Test
    fun visibilityCountsStartedActivitiesAndAnnouncesTheFirst() {
        val v = AppVisibility()
        var shown = 0
        v.whenVisible { shown++ }
        assertEquals(false, v.visible)
        v.activityStarted()
        v.activityStarted() // a second activity (e.g. a share sheet target)
        v.activityStopped()
        assertEquals(true, v.visible)
        v.activityStopped()
        v.activityStopped() // never below zero
        assertEquals(false, v.visible)
        v.activityStarted()
        assertEquals(2, shown)
    }
}
