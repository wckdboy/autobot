package dev.wckdboy.autobot.engine.diffusion

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [LocalSd] against a fake `:sd` engine binder: progress and previews, a cancel mid-steps, the
 * engine dying mid-steps or before the job starts, the engine never starting, and the request's
 * missing files named before a load. Virtual time; the main looper is pumped by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalSdTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(app, SdService::class.java)
    private val outDir = File(app.cacheDir, "sd-out").apply { mkdirs() }

    private class FakeEngine : ISdEngine.Stub() {
        @Volatile var alive = true

        /** Alive when connected, dead by the time the job is linked (it died in between). */
        @Volatile var dieBeforeLink = false
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val callbacks = CopyOnWriteArrayList<ISdCallback>()
        val requests = CopyOnWriteArrayList<Bundle>()
        var cancels = 0

        override fun isBinderAlive() = alive
        override fun pingBinder() = alive

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (dieBeforeLink) alive = false
            if (!alive) throw DeadObjectException()
            recipients += recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)

        override fun systemInfo() = "fake"

        override fun generate(request: Bundle, callback: ISdCallback) {
            if (!alive) throw DeadObjectException()
            requests += request
            callbacks += callback
        }

        override fun cancel() {
            cancels++
        }

        override fun unload() = Unit

        fun die() {
            alive = false
            recipients.forEach { it.binderDied() }
        }
    }

    private fun serve(engine: FakeEngine) = shadowOf(app).setComponentNameAndServiceForBindService(component, engine)

    private fun TestScope.pump() {
        repeat(3) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        runCurrent()
    }

    private class Run(val events: MutableList<LocalSdEvent> = CopyOnWriteArrayList(), var error: Throwable? = null, var job: Job? = null)

    private fun CoroutineScope.generate(sd: LocalSd, request: SdRequest = SdRequest(SdBundle(model = "/m.safetensors"), "a fox")): Run = Run().also { run ->
        run.job = launch {
            try {
                sd.generate(request).collect { run.events += it }
            } catch (t: Throwable) {
                run.error = t
            }
        }
    }

    @Test
    fun progressPreviewsAndTheImageArriveAndOutputFilesDoNotLinger() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val run = generate(sd)
        pump()
        val callback = engine.callbacks.single()
        callback.onProgress(1, 4)
        val preview = File(outDir, "preview-1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        callback.onPreview(preview.path)
        val png = File(outDir, "image-1.png").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        callback.onImage(0, png.path, 42L, 512, 512)
        // A path outside the engine's output folder is never read.
        val elsewhere = File(app.filesDir, "secret.png").apply { writeBytes(byteArrayOf(5)) }
        callback.onImage(1, elsewhere.path, 1L, 512, 512)
        callback.onDone(null)
        pump()

        assertTrue(run.job!!.isCompleted)
        assertEquals(null, run.error)
        assertEquals(LocalSdEvent.Progress(1, 4), run.events[0])
        assertArrayEquals(byteArrayOf(1, 2, 3), (run.events[1] as LocalSdEvent.Preview).jpeg)
        val image = run.events[2] as LocalSdEvent.Image
        assertEquals(42L, image.seed)
        assertArrayEquals(byteArrayOf(9, 8, 7), image.png)
        assertEquals(3, run.events.size)
        assertFalse("read and deleted", preview.exists() || png.exists())
        assertTrue(elsewhere.exists())
        assertFalse(sd.isBusy)
        // A finished job sends no cancel: the engine's cancel is global and would land on the next job.
        assertEquals(0, engine.cancels)
    }

    @Test
    fun preparingLoadsTheBundleOnlyAndIsSkippedWhileAnotherEngineWorks() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        var freed = 0
        sd.beforeJob = { freed++ }
        sd.mayPrepare = { false }
        assertFalse(sd.prepare(SdBundle(model = "/m.safetensors")))
        assertEquals(0, freed)
        assertTrue(engine.requests.isEmpty())

        sd.mayPrepare = { true }
        var ran: Boolean? = null
        val job = launch { ran = sd.prepare(SdBundle(model = "/m.safetensors")) }
        pump()
        assertTrue(sd.isPreparing)
        val request = engine.requests.single()
        assertTrue(request.getBoolean(SdRequest.K_PREPARE))
        assertEquals("/m.safetensors", request.getString(SdRequest.K_MODEL))
        // The other engines' memory goes first, as for a job.
        assertEquals(1, freed)
        engine.callbacks.single().onDone(null)
        pump()
        assertTrue(job.isCompleted)
        assertEquals(true, ran)
        assertFalse(sd.isPreparing)
        assertFalse(sd.isBusy)
        // The warm-up's end must not cancel the user's Generate queued behind it.
        assertEquals(0, engine.cancels)
        // A real job never carries the flag.
        assertFalse(SdRequest(SdBundle(model = "/m.safetensors"), "a fox").toBundle().getBoolean(SdRequest.K_PREPARE))
    }

    @Test
    fun aResidentModelIsNotLoadedAgainButOneFreedSinceIs() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val bundle = SdBundle(model = "/m.safetensors")
        launch { sd.prepare(bundle) }
        pump()
        engine.callbacks.single().onDone(null)
        pump()
        assertTrue(sd.isResident(bundle))
        // Typing on: the warm-up is asked again; the engine holds the model, nothing is sent.
        var ready: Boolean? = null
        launch { ready = sd.prepare(bundle) }
        pump()
        assertEquals(true, ready)
        assertEquals(1, engine.requests.size)
        // The coordinator frees it (another engine, memory pressure): the next warm-up loads it again.
        sd.unloadModel()
        assertFalse(sd.isResident(bundle))
        launch { sd.prepare(bundle) }
        pump()
        assertEquals(2, engine.requests.size)
        engine.callbacks.last().onDone(null)
        pump()
        assertTrue(sd.isResident(bundle))
        // A job that failed leaves the engine's state unknown.
        val run = generate(sd, SdRequest(bundle, "a fox"))
        pump()
        engine.callbacks.last().onDone("Generation failed or was cancelled")
        pump()
        assertTrue(run.job!!.isCompleted)
        assertFalse(sd.isResident(bundle))
    }

    @Test
    fun aModelUnloadedWhileItsJobRanIsNotResidentAfterIt() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val bundle = SdBundle(model = "/m.safetensors")
        val run = generate(sd, SdRequest(bundle, "a fox"))
        pump()
        // The coordinator frees the image engine for another one while the image is made: the
        // engine unloads once the job is over.
        sd.unloadModel()
        engine.callbacks.single().onDone(null)
        pump()
        assertTrue(run.job!!.isCompleted)
        assertFalse(sd.isResident(bundle))
        // So the next warm-up loads it again instead of answering "already loaded".
        launch { sd.prepare(bundle) }
        pump()
        assertEquals(2, engine.requests.size)
        engine.callbacks.last().onDone(null)
        pump()
        assertTrue(sd.isResident(bundle))
    }

    @Test
    fun aWarmUpOvertakenByAnotherModelIsSkipped() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val a = SdBundle(model = "/a.safetensors")
        val b = SdBundle(model = "/b.safetensors")
        val c = SdBundle(model = "/c.safetensors")
        launch { sd.prepare(a) }
        pump()
        // While a loads, the user picks b, then c: b is never loaded.
        var bReady: Boolean? = null
        launch { bReady = sd.prepare(b) }
        launch { sd.prepare(c) }
        pump()
        assertEquals(1, engine.requests.size)
        engine.callbacks.single().onDone(null)
        pump()
        assertEquals(false, bReady)
        assertEquals(listOf("/a.safetensors", "/c.safetensors"), engine.requests.map { it.getString(SdRequest.K_MODEL) })
        engine.callbacks.last().onDone(null)
        pump()
        assertTrue(sd.isResident(c))
        assertEquals(0, engine.cancels)
    }

    @Test
    fun keptResidentTheEngineSkipsTheIdleUnbindButNotTheCoordinator() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app, idleMillis = 1_000)
        val bundle = SdBundle(model = "/m.safetensors")
        val hold = sd.keepResident()
        launch { sd.prepare(bundle) }
        pump()
        engine.callbacks.single().onDone(null)
        pump()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
        assertTrue("held: still bound", shadowOf(app).boundServiceConnections.isNotEmpty())
        assertTrue(sd.isResident(bundle))
        // Closed (the Image screen went away): the idle unbind runs again.
        hold.close()
        hold.close()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_001))
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertFalse(sd.isResident(bundle))
        // Held again, the coordinator still frees it at once.
        val again = sd.keepResident()
        launch { sd.prepare(bundle) }
        pump()
        engine.callbacks.last().onDone(null)
        pump()
        assertTrue(shadowOf(app).boundServiceConnections.isNotEmpty())
        sd.releaseIfIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        again.close()
    }

    @Test
    fun cancellingMidStepsStopsTheEngine() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val run = generate(sd)
        pump()
        engine.callbacks.single().onProgress(3, 20)
        pump()
        run.job!!.cancel()
        pump()
        assertEquals(1, engine.cancels)
        assertFalse(sd.isBusy)
        // A late image of the cancelled job is not delivered to anyone (and its file is still removed).
        val late = File(outDir, "image-late.png").apply { writeBytes(byteArrayOf(1)) }
        engine.callbacks.single().onImage(0, late.path, 1, 64, 64)
        assertFalse(late.exists())
    }

    @Test
    fun theEngineDyingMidStepsFailsTheJobWithAClearMessage() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val sd = LocalSd(app)
        val run = generate(sd)
        pump()
        engine.callbacks.single().onProgress(5, 20)
        pump()
        engine.die()
        pump()
        assertTrue(run.job!!.isCompleted)
        assertTrue("${run.error}", run.error is SdEngineException)
        assertTrue(run.error!!.message!!.contains("stopped"))
        assertFalse(sd.isBusy)
    }

    @Test
    fun anEngineThatDiesBeforeTheJobIsLinkedDoesNotStayBusy() = runTest(timeout = 10.seconds) {
        // Busy forever would keep the coordinator from ever freeing the image engine.
        val engine = FakeEngine().apply { dieBeforeLink = true }
        serve(engine)
        val sd = LocalSd(app)
        val run = generate(sd)
        pump()
        assertTrue("${run.error}", run.error is SdEngineException)
        assertFalse(sd.isBusy)
    }

    @Test
    fun anEngineThatNeverStartsTimesOut() = runTest(timeout = 10.seconds) {
        serve(FakeEngine())
        val sd = LocalSd(app)
        val run = generate(sd)
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertTrue("${run.error}", run.error is SdEngineException)
        assertTrue(run.error!!.message!!.contains("did not start in time"))
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertFalse(sd.isBusy)
    }

    @Test
    fun missingModelAndLoraFilesAreNamedBeforeALoad() {
        val model = File(app.filesDir, "m.safetensors").apply { writeBytes(byteArrayOf(0)) }
        val vae = File(app.filesDir, "vae.safetensors")
        val lora = File(app.filesDir, "style.safetensors")
        val request = SdRequest(SdBundle(model = model.path, vae = vae.path), "x", loras = listOf(SdLora(lora.path, 0.8f))).toBundle()
        assertEquals(listOf(vae.path, lora.path), SdRequest.missingFiles(request))
        vae.writeBytes(byteArrayOf(0))
        lora.writeBytes(byteArrayOf(0))
        assertTrue(SdRequest.missingFiles(request).isEmpty())
    }
}
