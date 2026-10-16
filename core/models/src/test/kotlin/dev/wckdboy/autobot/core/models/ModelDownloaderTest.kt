package dev.wckdboy.autobot.core.models

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.db.AutobotDatabase
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.core.security.SecretStore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowStatFs

/**
 * The real download queue end to end: registry in Room, the job driven by hand (as JobScheduler
 * would), files from a scripted server. A "process" is a downloader with its own scope over the
 * same database and files; killing it cancels the scope without any cleanup, like process death.
 * Sockets need real time: every wait is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelDownloaderTest {

    @get:Rule
    val timeout: Timeout = Timeout(90, TimeUnit.SECONDS)

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private lateinit var db: AutobotDatabase
    private lateinit var rows: ModelRowRepository
    private lateinit var dataStore: DataStore<Preferences>
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val policy = NetworkPolicy()
    private val audit = AuditLog(capacity = 200, clock = { 0L })
    private val processes = mutableListOf<Process>()

    /** Bodies by path; a script, when present, answers the next requests for its path first. */
    private val blobs = ConcurrentHashMap<String, ByteArray>()
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedDeque<(RecordedRequest) -> MockResponse>>()
    private val requests = CopyOnWriteArrayList<Pair<String, String?>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += path to request.headers["Range"]
                scripts[path]?.pollFirst()?.let { return it(request) }
                val body = blobs[path] ?: return MockResponse.Builder().code(404).build()
                return serve(body, request)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, AutobotDatabase::class.java).build()
        rows = ModelRowRepository(DatabaseHolder(context, KeyManager(context)).apply { useForTesting(db) }, Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { File(context.filesDir, "test.preferences_pb") }
        network(connected = true, metered = false)
        storage(availableBytes = 40L shl 30)
        // Downloads need a network mode; the kill switch test turns it off again.
        runBlocking { SettingsRepository(dataStore).setNetworkMode(NetworkMode.Direct) }
        policy.setMode(NetworkMode.Direct)
    }

    @After
    fun tearDown() {
        processes.forEach { it.kill() }
        storeScope.cancel()
        server.close()
        db.close()
    }

    // ---- Harness

    private fun serve(body: ByteArray, request: RecordedRequest): MockResponse {
        val from = request.headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        if (from == 0) return MockResponse.Builder().code(200).setHeader("ETag", "\"${sha(body).take(12)}\"").body(Buffer().write(body)).build()
        return MockResponse.Builder().code(206).setHeader("ETag", "\"${sha(body).take(12)}\"")
            .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
            .body(Buffer().write(body.copyOfRange(from, body.size))).build()
    }

    private fun script(path: String, answer: (RecordedRequest) -> MockResponse) {
        scripts.getOrPut(path) { ConcurrentLinkedDeque() }.add(answer)
    }

    /** The next answer for [path] sends part of the body and then goes silent. */
    private fun stallOnce(path: String) = script(path) { r ->
        serve(blobs.getValue(path), r).newBuilder().onResponseBody(SocketEffect.Stall).build()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    /** A model of [files] (name → bytes) served under `/<id>/<name>`; hashes known up front unless [hashes] is false. */
    private fun plan(id: String, vararg files: Pair<String, ByteArray>, hashes: Boolean = true, kind: ModelKind = ModelKind.CHAT): ModelPlan {
        files.forEach { (name, body) -> blobs["/$id/$name"] = body }
        return ModelPlan(
            id = id,
            kind = kind,
            source = ModelSource.HUGGING_FACE,
            title = "Model $id",
            subtitle = "",
            format = ModelFormat.GGUF,
            engine = EngineKind.LLAMA,
            manifest = ModelManifest(
                files.mapIndexed { i, (name, body) ->
                    ModelFile(if (i == 0) FileRole.MODEL else FileRole.MMPROJ, name, url("/$id/$name"), body.size.toLong(), if (hashes) sha(body) else null)
                },
            ),
        )
    }

    private fun network(connected: Boolean, metered: Boolean) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        if (connected) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!metered) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun storage(availableBytes: Long) {
        val blocks = (availableBytes / 4096).toInt().coerceAtLeast(0)
        ShadowStatFs.registerStats(context.filesDir, Int.MAX_VALUE, blocks, blocks)
    }

    private val jobs: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    /** The job JobScheduler would run, recording what the queue reports to it. */
    private inner class FakeJob : DownloadJob {
        val notifications = CopyOnWriteArrayList<Notification>()
        val finished = CopyOnWriteArrayList<Boolean>()

        override fun notify(notification: Notification) {
            notifications += notification
        }

        override fun finished(reschedule: Boolean) {
            finished += reschedule
            if (!reschedule) jobs.cancel(ModelDownloadJob.JOB_ID)
        }
    }

    /** One app process: a downloader over the shared database and files. */
    private inner class Process {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val library = ModelLibrary(context, rows, scope)
        val visibility = AppVisibility()
        val downloader = ModelDownloader(
            context = context,
            library = library,
            clients = HttpClientFactory(policy, audit),
            accounts = ModelAccounts(SecretStore(context, KeyManager(context)), dataStore),
            scope = scope,
            environment = DownloadEnvironmentSource(context, policy, dataStore, scope),
            notifications = DownloadNotifications(context),
            settings = SettingsRepository(dataStore),
            policy = policy,
            visibility = visibility,
        ).apply { retryDelayMs = 20 }

        /** Runs the job the queue scheduled (as JobScheduler does once its constraints are met). */
        suspend fun runJob(): FakeJob {
            until { jobs.getPendingJob(ModelDownloadJob.JOB_ID) != null }
            return FakeJob().also { downloader.onJobStarted(it) }
        }

        fun kill() = scope.cancel()
    }

    private fun process() = Process().also { processes += it }

    private suspend fun until(limit: Duration = 20.seconds, condition: suspend () -> Boolean) {
        withTimeout(limit) { while (!condition()) delay(10) }
    }

    private suspend fun ModelLibrary.awaitStatus(id: String, status: ModelStatus, limit: Duration = 20.seconds): InstalledModel {
        var last: InstalledModel? = null
        try {
            until(limit) { get(id).also { last = it }?.status == status }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$id never became $status (last: ${last?.status} ${last?.error})", e)
        }
        return get(id)!!
    }

    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(60.seconds) { block() } }

    private fun requestsFor(path: String) = requests.filter { it.first == path }

    // ---- Multi-file models and integrity

    @Test
    fun multiFileModelInstallsEveryFileAndOpensAsReady() = bounded {
        val p = process()
        val gguf = bytes(400_000, 1)
        val mmproj = bytes(120_000, 2)
        p.downloader.install(plan("vlm", "m.gguf" to gguf, "mmproj.gguf" to mmproj))
        val job = p.runJob()

        val model = p.library.awaitStatus("vlm", ModelStatus.READY)

        assertTrue(File(model.paths.getValue(FileRole.MODEL)).readBytes().contentEquals(gguf))
        assertTrue(File(model.paths.getValue(FileRole.MMPROJ)).readBytes().contentEquals(mmproj))
        assertEquals(520_000L, model.totalBytes)
        // No part or validator files are left next to the model.
        assertEquals(setOf(File(model.paths.getValue(FileRole.MODEL)).name, File(model.paths.getValue(FileRole.MMPROJ)).name), p.library.dirOf("vlm").list()!!.toSet())
        until { job.finished.isNotEmpty() }
        assertEquals(listOf(false), job.finished.toList())
        assertTrue(job.notifications.any { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Model vlm" })
    }

    @Test
    fun partialFailureLeavesNoHalfInstalledModelAndTheRetryKeepsTheFinishedFile() = bounded {
        val p = process()
        val gguf = bytes(300_000, 3)
        val mmproj = bytes(90_000, 4)
        // Hashes come from the server (the Hub's X-Linked-ETag), not from the plan.
        val plan = plan("vlm", "m.gguf" to gguf, "mmproj.gguf" to mmproj, hashes = false)
        script("/vlm/mmproj.gguf") { MockResponse.Builder().code(404).build() }
        p.downloader.install(plan)
        p.runJob()

        val failed = p.library.awaitStatus("vlm", ModelStatus.FAILED)
        assertTrue(failed.error!!, failed.error!!.contains("no longer available"))
        assertFalse(failed.isReady)
        assertTrue("a failed model has no usable paths", failed.paths.isEmpty())
        // The first file's learned hash was kept: a retry does not fetch it again.
        assertEquals(sha(gguf), failed.manifest.files[0].sha256)

        p.downloader.resume("vlm")
        p.runJob()
        val ready = p.library.awaitStatus("vlm", ModelStatus.READY)
        assertEquals(1, requestsFor("/vlm/m.gguf").size)
        assertEquals(2, requestsFor("/vlm/mmproj.gguf").size)
        assertTrue(File(ready.paths.getValue(FileRole.MMPROJ)).readBytes().contentEquals(mmproj))
    }

    @Test
    fun hashMismatchFailsClearlyAndDeletesThePartialFile() = bounded {
        val p = process()
        val body = bytes(200_000, 5)
        val bad = plan("bad", "m.gguf" to body).let { it.copy(manifest = it.manifest.copy(files = it.manifest.files.map { f -> f.copy(sha256 = "0".repeat(64)) })) }
        p.downloader.install(bad)
        p.runJob()

        val paused = p.library.awaitStatus("bad", ModelStatus.PAUSED)
        assertTrue(paused.error!!, paused.error!!.startsWith("Checksum mismatch") && paused.error!!.contains("deleted"))
        assertEquals(0L, p.library.bytesOnDisk("bad"))
        assertTrue(paused.paths.isEmpty())
    }

    @Test
    fun gatedRepoFailsWithAnActionableMessage() = bounded {
        val p = process()
        val plan = plan("gated", "m.gguf" to bytes(1000, 6)).let { it.copy(manifest = it.manifest.copy(files = it.manifest.files.map { f -> f.copy(auth = null) })) }
        script("/gated/m.gguf") { MockResponse.Builder().code(401).setHeader("X-Error-Code", "GatedRepo").body("gated").build() }
        p.downloader.install(plan)
        p.runJob()

        val failed = p.library.awaitStatus("gated", ModelStatus.FAILED)
        assertTrue(failed.error!!, failed.error!!.contains("accept its terms") && failed.error!!.contains("Remote → Accounts"))
        // Not retried: the same answer would come back.
        assertEquals(1, requestsFor("/gated/m.gguf").size)
    }

    @Test
    fun aDroppedConnectionIsRetriedWithBackoffFromWhereItStopped() = bounded {
        val p = process()
        val body = bytes(500_000, 7)
        script("/drop/m.gguf") { r -> serve(body, r).newBuilder().onResponseBody(SocketEffect.CloseSocket()).build() }
        p.downloader.install(plan("drop", "m.gguf" to body))
        p.runJob()

        val model = p.library.awaitStatus("drop", ModelStatus.READY)
        val sent = requestsFor("/drop/m.gguf")
        assertEquals(2, sent.size)
        assertNull(sent[0].second)
        assertNotNull("the retry resumes with Range", sent[1].second)
        assertTrue(File(model.paths.getValue(FileRole.MODEL)).readBytes().contentEquals(body))
    }

    @Test
    fun alreadyInstalledModelIsNotDownloadedAgain() = bounded {
        val p = process()
        val plan = plan("dup", "m.gguf" to bytes(50_000, 8))
        p.downloader.install(plan)
        p.runJob()
        p.library.awaitStatus("dup", ModelStatus.READY)

        // A second install of the same model (a double tap, or the catalog row again) keeps it ready.
        p.downloader.install(plan)
        delay(300)
        assertEquals(ModelStatus.READY, p.library.get("dup")!!.status)
        assertEquals(1, requestsFor("/dup/m.gguf").size)
    }

    @Test
    fun installingAgainWhileItDownloadsDoesNotQueueItTwice() = bounded {
        val p = process()
        val plan = plan("twice", "m.gguf" to bytes(2_000_000, 23))
        stallOnce("/twice/m.gguf")
        p.downloader.install(plan)
        p.runJob()
        until { p.library.get("twice")?.status == ModelStatus.DOWNLOADING }

        p.downloader.install(plan)
        delay(300)
        assertEquals(ModelStatus.DOWNLOADING, p.library.get("twice")!!.status)
        assertTrue(DownloadQueue.queued(p.library.all()).isEmpty())
        assertEquals(1, requestsFor("/twice/m.gguf").size)
        p.downloader.remove("twice").join()
    }

    @Test
    fun aReadyModelWhoseFilesWereDeletedIsOfferedForDownloadAgain() = bounded {
        val p = process()
        val gguf = bytes(30_000, 24)
        val mmproj = bytes(10_000, 25)
        p.downloader.install(plan("gone", "m.gguf" to gguf, "mmproj.gguf" to mmproj))
        p.runJob()
        val ready = p.library.awaitStatus("gone", ModelStatus.READY)
        // Deleted outside the app (a file manager, a cleaner, adb).
        File(ready.paths.getValue(FileRole.MMPROJ)).delete()
        assertEquals(1, p.library.missingFiles(ready).size)

        // The next start finds it and turns it into a download to retry.
        p.kill()
        jobs.cancelAll()
        val next = process()
        next.downloader.recoverAfterRestart()
        val failed = next.library.get("gone")!!
        assertEquals(ModelStatus.FAILED, failed.status)
        assertTrue(failed.error!!, failed.error!!.contains("deleted outside the app") && failed.error!!.contains("1 of 2"))
        assertTrue("not usable meanwhile", failed.paths.isEmpty())

        // Retry fetches only what is missing.
        next.downloader.resume("gone")
        next.runJob()
        val back = next.library.awaitStatus("gone", ModelStatus.READY)
        assertTrue(File(back.paths.getValue(FileRole.MMPROJ)).readBytes().contentEquals(mmproj))
        assertEquals(1, requestsFor("/gone/m.gguf").size)
        assertEquals(2, requestsFor("/gone/mmproj.gguf").size)
    }

    @Test
    fun aFileAlreadyOnDiskWithItsHashIsKept() = bounded {
        val p = process()
        val gguf = bytes(80_000, 9)
        val mmproj = bytes(20_000, 10)
        val plan = plan("half", "m.gguf" to gguf, "mmproj.gguf" to mmproj)
        p.library.fileOf("half", plan.manifest.files[0]).apply { parentFile!!.mkdirs() }.writeBytes(gguf)
        p.downloader.install(plan)
        p.runJob()

        p.library.awaitStatus("half", ModelStatus.READY)
        assertTrue(requestsFor("/half/m.gguf").isEmpty())
        assertEquals(1, requestsFor("/half/mmproj.gguf").size)
    }

    @Test
    fun lowStorageIsReportedBeforeAnythingIsFetched() = bounded {
        storage(availableBytes = 100L shl 20)
        val p = process()
        p.downloader.install(plan("big", "m.gguf" to bytes(10_000, 11)))
        p.runJob()

        val paused = p.library.awaitStatus("big", ModelStatus.PAUSED)
        assertTrue(paused.error!!, paused.error!!.startsWith(DownloadQueue.LOW_STORAGE_PREFIX))
        assertEquals(DownloadBlock.LOW_STORAGE, DownloadQueue.pausedReason(paused))
        assertTrue(requests.isEmpty())
    }

    // ---- Cancel and pause

    @Test
    fun cancelWhileRunningStopsAtOnceDeletesEverythingAndSticksAcrossARestart() = bounded {
        val p = process()
        val body = bytes(2_000_000, 12)
        stallOnce("/run/m.gguf")
        p.downloader.install(plan("run", "m.gguf" to body))
        p.runJob()
        until { p.library.bytesOnDisk("run") > 0 && p.library.get("run")?.status == ModelStatus.DOWNLOADING }

        // The read is stalled (its timeout is minutes): the removal must not wait for it.
        withTimeout(5.seconds) { p.downloader.remove("run").join() }

        assertNull(p.library.get("run"))
        assertFalse(p.library.dirOf("run").exists())
        // The next process finds nothing to resume.
        p.kill()
        val next = process()
        next.downloader.recoverAfterRestart()
        assertNull(next.library.get("run"))
        assertTrue(DownloadQueue.queued(next.library.all()).isEmpty())
    }

    @Test
    fun cancelSticksWhenTheProcessDiesRightAfterIt() = bounded {
        val p = process()
        val body = bytes(2_000_000, 13)
        stallOnce("/crash/m.gguf")
        p.downloader.install(plan("crash", "m.gguf" to body))
        p.runJob()
        until { p.library.bytesOnDisk("crash") > 0 }

        // The user cancels; the process dies the moment the registry has it (before the files go).
        p.downloader.remove("crash")
        until { p.library.get("crash") == null }
        p.kill()

        val next = process()
        next.downloader.recoverAfterRestart()
        assertNull("the cancelled download does not come back", next.library.get("crash"))
        assertFalse("its files are swept", next.library.dirOf("crash").exists())
        delay(200)
        assertEquals(1, requestsFor("/crash/m.gguf").size)
    }

    @Test
    fun cancelWhileQueuedNeverStartsItAndTheRunningOneCarriesOn() = bounded {
        val p = process()
        val first = bytes(1_500_000, 14)
        stallOnce("/first/m.gguf")
        p.downloader.install(plan("first", "m.gguf" to first))
        until { p.library.get("first") != null }
        delay(5)
        p.downloader.install(plan("second", "m.gguf" to bytes(10_000, 15)))
        until { p.library.get("second") != null }
        p.runJob()
        until { p.library.get("first")?.status == ModelStatus.DOWNLOADING }

        p.downloader.remove("second").join()

        assertNull(p.library.get("second"))
        assertEquals(ModelStatus.DOWNLOADING, p.library.get("first")!!.status)
        assertTrue(requestsFor("/second/m.gguf").isEmpty())
    }

    @Test
    fun aRemovedModelIsNeverBroughtBackByALateFinish() = bounded {
        val p = process()
        val plan = plan("late", "m.gguf" to bytes(1000, 16))
        val model = p.library.add(plan)
        p.library.start("late", 0)
        // The user removes it while its last bytes are being verified…
        p.library.forget("late")
        // …and the transfer then finishes: neither write may recreate it.
        p.library.update(model, plan.manifest, 1000)
        assertFalse(p.library.transition("late", ModelStatus.DOWNLOADING, ModelStatus.READY, 1000))
        p.library.downloaded("late", 1000)
        assertNull(p.library.get("late"))
    }

    @Test
    fun pauseIsStoredFirstSurvivesARestartAndResumesWithRange() = bounded {
        val p = process()
        val body = bytes(2_000_000, 17)
        stallOnce("/pause/m.gguf")
        p.downloader.install(plan("pause", "m.gguf" to body))
        p.runJob()
        until { p.library.bytesOnDisk("pause") > 0 }

        withTimeout(5.seconds) { p.downloader.pause("pause").join() }
        val paused = p.library.get("pause")!!
        assertEquals(ModelStatus.PAUSED, paused.status)
        val kept = p.library.bytesOnDisk("pause")
        assertTrue(kept > 0)

        // A restart does not undo the pause.
        p.kill()
        val next = process()
        next.downloader.recoverAfterRestart()
        assertEquals(ModelStatus.PAUSED, next.library.get("pause")!!.status)

        next.downloader.resume("pause")
        next.runJob()
        val ready = next.library.awaitStatus("pause", ModelStatus.READY)
        assertNotNull(requestsFor("/pause/m.gguf").last().second)
        assertTrue(File(ready.paths.getValue(FileRole.MODEL)).readBytes().contentEquals(body))
    }

    // ---- Process death

    @Test
    fun processDeathMidDownloadIsRebuiltFromTheRegistryAndResumes() = bounded {
        val p = process()
        val body = bytes(2_000_000, 18)
        stallOnce("/death/m.gguf")
        p.downloader.install(plan("death", "m.gguf" to body))
        p.runJob()
        until { p.library.bytesOnDisk("death") > 0 && p.library.get("death")?.status == ModelStatus.DOWNLOADING }
        // Killed: no pause, no bookkeeping. Cancelling the scope would still run the handlers, so
        // the registry is put back to the moment of death: DOWNLOADING, with the part on disk.
        val atDeath = rows.get("death")!!
        p.kill()
        p.scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        rows.upsert(atDeath)
        jobs.cancelAll()

        val next = process()
        next.downloader.recoverAfterRestart()
        assertEquals(ModelStatus.QUEUED, next.library.get("death")!!.status)
        next.runJob()

        val ready = next.library.awaitStatus("death", ModelStatus.READY)
        val resumed = requestsFor("/death/m.gguf").last()
        assertTrue("resumed with ${resumed.second}", resumed.second!!.startsWith("bytes=") && resumed.second != "bytes=0-")
        assertTrue(File(ready.paths.getValue(FileRole.MODEL)).readBytes().contentEquals(body))
        assertEquals(sha(body), ready.manifest.files.single().sha256)
    }

    // ---- Queue and policy

    @Test
    fun queueRunsOldestFirst() = bounded {
        val p = process()
        listOf("c", "a", "b").forEach { id ->
            p.downloader.install(plan(id, "m.gguf" to bytes(5_000, id[0].code)))
            until { p.library.get(id) != null }
            delay(5)
        }
        p.runJob()
        listOf("c", "a", "b").forEach { p.library.awaitStatus(it, ModelStatus.READY) }
        assertEquals(listOf("/c/m.gguf", "/a/m.gguf", "/b/m.gguf"), requests.map { it.first })
    }

    @Test
    fun wifiOnlyWaitsForUnmeteredWithAReason() = bounded {
        network(connected = true, metered = true)
        val p = process()
        p.downloader.setWifiOnly(true)
        p.downloader.install(plan("wifi", "m.gguf" to bytes(5_000, 19)))

        val state = withTimeout(10.seconds) { p.downloader.downloads.first { it.block != null } }
        assertEquals(DownloadBlock.WAITING_FOR_WIFI, state.block)
        assertTrue(state.wifiOnly && state.metered)
        until { jobs.getPendingJob(ModelDownloadJob.JOB_ID) != null }
        // The job itself waits for an unmetered network: Android runs it only on Wi-Fi.
        val info = jobs.getPendingJob(ModelDownloadJob.JOB_ID)!!
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, info.networkType)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun killSwitchPausesTheQueueWithAReasonFetchesNothingAndContinuesWhenAllowed() = bounded {
        SettingsRepository(dataStore).setNetworkMode(NetworkMode.Offline)
        policy.setMode(NetworkMode.Offline)
        policy.setAllowLoopbackWhenOffline(false)
        val p = process()
        p.downloader.install(plan("k1", "m.gguf" to bytes(5_000, 20)))
        until { p.library.get("k1") != null }
        delay(5)
        p.downloader.install(plan("k2", "m.gguf" to bytes(5_000, 21)))
        until { p.library.get("k2") != null }
        p.runJob()

        listOf("k1", "k2").forEach {
            val paused = p.library.awaitStatus(it, ModelStatus.PAUSED)
            assertEquals(DownloadQueue.KILL_SWITCH_ERROR, paused.error)
            assertEquals(DownloadBlock.KILL_SWITCH, DownloadQueue.pausedReason(paused))
        }
        assertTrue("nothing reached the server", requests.isEmpty())
        assertTrue(audit.entries.value.isNotEmpty() && audit.entries.value.all { it.blocked && it.tag == "models:download" })

        // A network mode is allowed again: the kill switch's pauses queue up and run by themselves.
        jobs.cancelAll()
        SettingsRepository(dataStore).setNetworkMode(NetworkMode.Direct)
        policy.setMode(NetworkMode.Direct)
        until { p.library.get("k1")?.status == ModelStatus.QUEUED }
        p.runJob()
        p.library.awaitStatus("k1", ModelStatus.READY)
        p.library.awaitStatus("k2", ModelStatus.READY)
    }

    @Test
    fun aFailureWhileTheAppIsAwayIsNotified() = bounded {
        val p = process()
        script("/gone/m.gguf") { MockResponse.Builder().code(410).build() }
        p.downloader.install(plan("gone", "m.gguf" to bytes(1000, 22)))
        p.runJob()
        p.library.awaitStatus("gone", ModelStatus.FAILED)

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(
            posted.any {
                it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Download failed" &&
                    it.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Model gone"
            },
        )
    }

    @Test
    fun diskFullIsRecognised() {
        assertTrue(DownloadQueue.isDiskFull(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(DownloadQueue.isDiskFull(java.io.IOException("x", java.io.IOException("No space left on device"))))
        assertFalse(DownloadQueue.isDiskFull(java.io.IOException("Connection reset")))
        val message = DownloadQueue.diskFullMessage(1_000_000)
        assertTrue(message.startsWith(DownloadQueue.LOW_STORAGE_PREFIX))
        val model = InstalledModel(
            id = "m", kind = ModelKind.CHAT, source = ModelSource.CATALOG, title = "m", subtitle = "", format = ModelFormat.GGUF,
            engine = EngineKind.LLAMA, baseModel = null, license = null, nsfw = false, totalBytes = 0, downloadedBytes = 0,
            status = ModelStatus.PAUSED, error = message, manifest = ModelManifest(emptyList()), addedAt = 0, paths = emptyMap(),
        )
        assertEquals(DownloadBlock.LOW_STORAGE, DownloadQueue.pausedReason(model))
    }
}
