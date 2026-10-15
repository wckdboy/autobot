package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.Cancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobQueueTest {
    private val image = Recipe(EngineKinds.DIFFUSION)

    private class Setup(val queue: JobQueue, val sink: MemorySink, val registry: EngineRegistry)

    private fun TestScope.setup(vararg engines: StudioEngine, persistence: JobPersistence? = null, scope: CoroutineScope = backgroundScope, limits: Map<String, Int> = emptyMap()): Setup {
        val registry = EngineRegistry().apply { engines.forEach(::register) }
        val sink = MemorySink()
        var n = 0
        val queue = JobQueue(scope, registry, sink, persistence, limits, clock = { 1_000L }, ids = { "j-${++n}" })
        return Setup(queue, sink, registry)
    }

    @Test
    fun runsAJobAndSavesItsOutputAsAnAsset() = runTest {
        val engine = FakeEngine()
        val s = setup(engine)
        val job = s.queue.submit("p-1", image.copy(seed = 9), "first")
        runCurrent()
        val done = s.queue.job(job.id)!!
        assertEquals(JobState.DONE, done.state)
        assertEquals(1f, done.progress)
        assertEquals(1, done.outputs.size)
        val asset = s.sink.store.asset(done.outputs.single())!!
        assertEquals("p-1", asset.projectId)
        assertEquals(9L, asset.recipe.seed)
        assertEquals("first", asset.label)
        assertEquals(64, asset.width)
    }

    @Test
    fun onDeviceEnginesShareOneSlotAndRemoteBackendsRunBesideIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val diffusion = FakeEngine(EngineKinds.DIFFUSION, gate = gate)
        val audio = FakeEngine(EngineKinds.AUDIO, output = AssetType.AUDIO, gate = gate)
        val pc = FakeEngine(EngineKinds.COMFYUI, backendId = "pc", gate = gate)
        val s = setup(diffusion, audio, pc)
        val a = s.queue.submit("p", image)
        val b = s.queue.submit("p", Recipe(EngineKinds.AUDIO, output = AssetType.AUDIO))
        val c = s.queue.submit("p", Recipe(EngineKinds.COMFYUI, backendId = "pc"))
        val d = s.queue.submit("p", Recipe(EngineKinds.COMFYUI, backendId = "pc"))
        runCurrent()
        assertEquals(JobState.RUNNING, s.queue.job(a.id)!!.state)
        assertEquals("on-device work waits for the shared slot", JobState.QUEUED, s.queue.job(b.id)!!.state)
        assertEquals(JobState.RUNNING, s.queue.job(c.id)!!.state)
        assertEquals("one job per backend by default", JobState.QUEUED, s.queue.job(d.id)!!.state)
        gate.complete(Unit)
        runCurrent()
        assertTrue(s.queue.jobs.value.all { it.state == JobState.DONE })
        assertEquals(1, diffusion.maxRunning)
        assertEquals(1, audio.maxRunning)
        assertEquals(1, pc.maxRunning)
    }

    @Test
    fun slotLimitsAllowMoreThanOnePerBackend() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pc = FakeEngine(EngineKinds.COMFYUI, backendId = "pc", gate = gate)
        val s = setup(pc, limits = mapOf("backend:pc" to 2))
        repeat(3) { s.queue.submit("p", Recipe(EngineKinds.COMFYUI, backendId = "pc")) }
        runCurrent()
        assertEquals(listOf(JobState.RUNNING, JobState.RUNNING, JobState.QUEUED), s.queue.jobs.value.map { it.state })
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, pc.maxRunning)
    }

    @Test
    fun jobsInASlotStartInSubmissionOrder() = runTest {
        val engine = FakeEngine()
        val s = setup(engine)
        val ids = (1..4).map { s.queue.submit("p", image.copy(seed = it.toLong())).id }
        runCurrent()
        assertEquals(ids, engine.ran.map { it.id })
    }

    @Test
    fun cancellingAQueuedJobNeverRunsIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val s = setup(engine)
        val first = s.queue.submit("p", image)
        val second = s.queue.submit("p", image)
        runCurrent()
        assertTrue(s.queue.cancel(second.id))
        gate.complete(Unit)
        runCurrent()
        assertEquals(JobState.DONE, s.queue.job(first.id)!!.state)
        assertEquals(JobState.CANCELLED, s.queue.job(second.id)!!.state)
        assertEquals(listOf(first.id), engine.ran.map { it.id })
        assertFalse("finished jobs cannot be cancelled", s.queue.cancel(first.id))
    }

    @Test
    fun cancellingARunningJobStopsTheEngineAndFreesTheSlot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val s = setup(engine)
        val first = s.queue.submit("p", image)
        val second = s.queue.submit("p", image)
        runCurrent()
        assertEquals(JobState.RUNNING, s.queue.job(first.id)!!.state)
        assertEquals("working", s.queue.job(first.id)!!.status)
        assertEquals(0.5f, s.queue.job(first.id)!!.progress)
        assertTrue(s.queue.cancel(first.id))
        runCurrent()
        assertEquals(JobState.CANCELLED, s.queue.job(first.id)!!.state)
        assertTrue(s.queue.job(first.id)!!.outputs.isEmpty())
        assertEquals(JobState.RUNNING, s.queue.job(second.id)!!.state)
        gate.complete(Unit)
        runCurrent()
        assertEquals(JobState.DONE, s.queue.job(second.id)!!.state)
    }

    @Test
    fun failuresAndMissingEnginesAreReportedAndRetryQueuesAgain() = runTest {
        val engine = FakeEngine(fail = "Out of memory")
        val s = setup(engine)
        val failed = s.queue.submit("p", image)
        val orphan = s.queue.submit("p", Recipe(EngineKinds.COMFYUI, backendId = "gone"))
        runCurrent()
        assertEquals(JobState.FAILED, s.queue.job(failed.id)!!.state)
        assertEquals("Out of memory", s.queue.job(failed.id)!!.error)
        assertEquals("The backend gone isn't available for comfyui", s.queue.job(orphan.id)!!.error)
        engine.fail = null
        val again = s.queue.retry(failed.id)!!
        runCurrent()
        assertEquals(JobState.DONE, s.queue.job(again.id)!!.state)
        assertNull(s.queue.retry(again.id))
        s.queue.clearFinished()
        assertTrue(s.queue.jobs.value.isEmpty())
    }

    @Test
    fun awaitReturnsTheFinishedJob() = runTest {
        val s = setup(FakeEngine())
        val job = s.queue.submit("p", image)
        val done = s.queue.await(job.id)!!
        assertEquals(JobState.DONE, done.state)
        assertNull(s.queue.await("unknown"))
    }

    @Test
    fun previewsReachTheJobAndAreDroppedWhenDone() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = object : StudioEngine {
            override val capabilities = EngineCapabilities(EngineKinds.DIFFUSION, null, "phone", setOf(AssetType.IMAGE), true, previews = true)
            override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
                onEvent(EngineEvent.Preview("image/jpeg", byteArrayOf(9)))
                gate.await()
                return emptyList()
            }
        }
        val s = setup(engine)
        val job = s.queue.submit("p", image)
        runCurrent()
        assertEquals("image/jpeg", s.queue.job(job.id)!!.preview!!.mimeType)
        gate.complete(Unit)
        runCurrent()
        assertNull(s.queue.job(job.id)!!.preview)
        assertEquals(JobState.DONE, s.queue.job(job.id)!!.state)
    }

    private class MemoryPersistence(initial: List<StudioJob> = emptyList()) : JobPersistence {
        val rows = LinkedHashMap<String, String>().apply { initial.forEach { put(it.id, StudioCodec.encodeJob(it)) } }
        override suspend fun load() = rows.values.map(StudioCodec::decodeJob)
        override suspend fun save(job: StudioJob) {
            rows[job.id] = StudioCodec.encodeJob(job)
        }
        override suspend fun remove(id: String) {
            rows.remove(id)
        }
    }

    @Test
    fun persistedJobsRestoreQueuedAndMarkInterruptedOnesFailed() = runTest {
        val persistence = MemoryPersistence(
            listOf(
                StudioJob("j-old-1", "p", image, state = JobState.RUNNING, createdAt = 1),
                StudioJob("j-old-2", "p", image, state = JobState.QUEUED, createdAt = 2),
            ),
        )
        val engine = FakeEngine()
        val s = setup(engine, persistence = persistence)
        s.queue.restore()
        runCurrent()
        assertEquals(JobState.FAILED, s.queue.job("j-old-1")!!.state)
        assertEquals(JobQueue.INTERRUPTED, s.queue.job("j-old-1")!!.error)
        assertEquals(JobState.DONE, s.queue.job("j-old-2")!!.state)
        assertEquals(listOf("j-old-2"), engine.ran.map { it.id })
        // Every transition was written.
        assertEquals(JobState.DONE, StudioCodec.decodeJob(persistence.rows.getValue("j-old-2")).state)
        assertEquals(JobState.FAILED, StudioCodec.decodeJob(persistence.rows.getValue("j-old-1")).state)
        s.queue.clearFinished()
        runCurrent()
        assertTrue(persistence.rows.isEmpty())
    }

    @Test
    fun registryPrefersExactBackendThenOnDevice() {
        val registry = EngineRegistry()
        val local = FakeEngine(EngineKinds.DIFFUSION)
        val remote = FakeEngine(EngineKinds.DIFFUSION, backendId = "sdapi")
        registry.register(remote)
        registry.register(local)
        assertEquals(local, registry.resolve(Recipe(EngineKinds.DIFFUSION)))
        assertEquals(remote, registry.resolve(Recipe(EngineKinds.DIFFUSION, backendId = "sdapi")))
        assertNull(registry.resolve(Recipe(EngineKinds.DIFFUSION, backendId = "other")))
        registry.unregister(EngineKinds.DIFFUSION, null)
        assertEquals(remote, registry.resolve(Recipe(EngineKinds.DIFFUSION)))
    }

    @Test
    fun hostEnginesAdaptCallbacksAndCancellation() = runTest {
        var cancelled = false
        val host = object : HostStudioEngine {
            override val capabilities = EngineCapabilities(EngineKinds.SPEECH, null, "phone", setOf(AssetType.AUDIO), true)
            var pending: HostEngineSink? = null
            override fun run(jobJson: String, sink: HostEngineSink): Cancellable {
                assertTrue(StudioCodec.decodeJob(jobJson).id.startsWith("j-"))
                sink.onProgress(1, 4)
                sink.onOutput(EngineOutput(AssetType.AUDIO, "audio/wav", byteArrayOf(1)))
                if (jobJson.contains("finish")) sink.onComplete() else pending = sink
                return Cancellable { cancelled = true }
            }
        }
        val s = setup(HostEngineAdapter(host))
        val ok = s.queue.submit("p", Recipe(EngineKinds.SPEECH, params = mapOf("text" to ParamValue.of("finish"))))
        runCurrent()
        assertEquals(JobState.DONE, s.queue.job(ok.id)!!.state)
        val hanging = s.queue.submit("p", Recipe(EngineKinds.SPEECH))
        runCurrent()
        s.queue.cancel(hanging.id)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(JobState.CANCELLED, s.queue.job(hanging.id)!!.state)
    }
}
