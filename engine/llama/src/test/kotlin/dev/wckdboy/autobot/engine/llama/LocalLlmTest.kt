package dev.wckdboy.autobot.engine.llama

import android.app.Application
import android.content.ComponentName
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [LocalLlm] against a fake `:llm` engine binder: the engine dying mid-generation, never starting,
 * a cancel while it starts, and two requests racing to start it. Virtual time throughout; the
 * main looper (where Android delivers the connection) is pumped by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalLlmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName(app, LlamaService::class.java)

    /** The engine process as the app sees it: a binder that can die. */
    private class FakeEngine : ILlamaEngine.Stub() {
        @Volatile var alive = true
        val recipients = CopyOnWriteArrayList<IBinder.DeathRecipient>()
        val callbacks = CopyOnWriteArrayList<ILlamaCallback>()
        var cancels = 0
        var unloads = 0
        var failCalls = false

        override fun isBinderAlive() = alive
        override fun pingBinder() = alive

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (!alive) throw DeadObjectException()
            recipients += recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = recipients.remove(recipient)

        override fun systemInfo() = "fake"
        override fun devices() = "[]"

        override fun chat(modelPath: String, nCtx: Int, backend: String, request: ParcelFileDescriptor, callback: ILlamaCallback) {
            if (!alive || failCalls) throw DeadObjectException()
            request.close()
            callbacks += callback
        }

        override fun cancel() {
            cancels++
        }

        override fun unload() {
            unloads++
        }

        override fun embed(modelPath: String, texts: Array<String>): FloatArray? = null

        /** Callbacks of the completions the app cancelled, in order. */
        val cancelled = CopyOnWriteArrayList<IBinder>()
        val prepared = CopyOnWriteArrayList<String>()

        override fun cancelChat(callback: ILlamaCallback) {
            cancelled += callback.asBinder()
        }

        override fun prepare(modelPath: String, nCtx: Int, backend: String) {
            prepared += modelPath
        }

        /** The process is killed (OOM, native crash). */
        fun die() {
            alive = false
            recipients.forEach { it.binderDied() }
        }
    }

    private fun serve(engine: FakeEngine) = shadowOf(app).setComponentNameAndServiceForBindService(component, engine)

    private fun TestScope.llm() = LocalLlm(app, idleMillis = 60_000, io = StandardTestDispatcher(testScheduler))

    /** Lets Android deliver what it posted (connections, idle unbinds) and the coroutines react. */
    private fun TestScope.pump() {
        repeat(3) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
        runCurrent()
    }

    private class Run(val events: MutableList<LocalLlmEvent> = CopyOnWriteArrayList(), var error: Throwable? = null, var job: Job? = null)

    private fun CoroutineScope.chat(llm: LocalLlm): Run = Run().also { run ->
        run.job = launch {
            try {
                llm.chat("/models/m.gguf", 2048, "cpu", "{}").collect { run.events += it }
            } catch (t: Throwable) {
                run.error = t
            }
        }
    }

    private val bound get() = shadowOf(app).boundServiceConnections
    private val unbound get() = shadowOf(app).unboundServiceConnections

    @Test
    fun engineDeathMidGenerationSurfacesAndTheNextRequestStartsAFreshEngine() = runTest(timeout = 10.seconds) {
        val first = FakeEngine()
        serve(first)
        val llm = llm()
        val run = chat(llm)
        pump()
        assertTrue(llm.isBusy)
        first.callbacks.single().onDelta(0, "Hel")
        pump()

        first.die()
        pump()

        assertTrue("the flow ends, it does not hang", run.job!!.isCompleted)
        assertTrue("${run.error}", run.error is EngineDiedException)
        assertEquals(LocalLlmEvent.Content("Hel"), run.events.single())
        assertFalse(llm.isBusy)

        // Android reports the process gone: its binding is dropped, so it is not restarted and held.
        val dead = bound.single()
        dead.onServiceDisconnected(component)
        assertTrue(dead in unbound)

        val second = FakeEngine()
        serve(second)
        val retry = chat(llm)
        pump()
        second.callbacks.single().onResult("""{"content":"ok"}""")
        pump()
        assertTrue(retry.job!!.isCompleted)
        assertEquals(null, retry.error)
        assertEquals(LocalLlmEvent.Result("""{"content":"ok"}"""), retry.events.last())
        // Only the fresh binding is held.
        assertEquals(1, bound.size)
        assertTrue(bound.single() !== dead)
    }

    @Test
    fun aCallOnADeadEngineFailsAsEngineDiedAndReleasesItsUse() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine().apply { failCalls = true }
        serve(engine)
        val llm = llm()
        val run = chat(llm)
        pump()
        assertTrue(run.error is EngineDiedException)
        assertFalse(llm.isBusy)
    }

    @Test
    fun anEngineThatNeverStartsTimesOutAndLeavesNoBinding() = runTest(timeout = 10.seconds) {
        serve(FakeEngine())
        val llm = llm()
        val run = chat(llm)
        runCurrent()
        // The connection is never delivered (the process hangs in its native init).
        advanceTimeBy(15_001)
        runCurrent()

        assertTrue("${run.error}", run.error is EngineUnavailableException)
        assertTrue(run.error!!.message!!.contains("did not start in time"))
        assertTrue("the binding does not linger", bound.isEmpty())
        assertEquals(1, unbound.size)
        assertFalse(llm.isBusy)
    }

    @Test
    fun cancellingWhileTheEngineStartsLeaksNothingAndTheNextRequestUsesIt() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        val run = chat(llm)
        runCurrent()
        run.job!!.cancel()
        runCurrent()
        assertFalse(llm.isBusy)

        // The engine comes up after all; the next request takes it without binding again.
        pump()
        val next = chat(llm)
        pump()
        assertEquals(1, bound.size)
        assertEquals(1, engine.callbacks.size)
        engine.callbacks.single().onResult("{}")
        pump()
        assertTrue(next.job!!.isCompleted)
    }

    @Test
    fun twoRequestsRacingToStartTheEngineBindItOnce() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        val a = chat(llm)
        val b = chat(llm)
        pump()
        assertEquals(1, bound.size)
        assertEquals(2, engine.callbacks.size)
        engine.callbacks.forEach { it.onResult("{}") }
        pump()
        assertTrue(a.job!!.isCompleted && b.job!!.isCompleted)
        assertFalse(llm.isBusy)
    }

    @Test
    fun cancellingMidGenerationStopsTheEngineAndTheIdleEngineIsReleasedLater() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        val run = chat(llm)
        pump()
        run.job!!.cancel()
        pump()
        // Exactly this completion is cancelled, by its callback (never "whatever runs now").
        assertEquals(listOf(engine.callbacks.single().asBinder()), engine.cancelled.toList())
        assertEquals(0, engine.cancels)
        assertFalse(llm.isBusy)
        assertTrue(unbound.isEmpty())

        // Idle for the window: the process is let go so Android can reclaim the model's memory.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60_000))
        assertTrue(bound.isEmpty())
        assertTrue(unbound.isNotEmpty())
    }

    @Test
    fun aFinishedCompletionNeverCancelsTheNextOne() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        val a = chat(llm)
        val b = chat(llm)
        pump()
        val (first, second) = engine.callbacks.toList()
        first.onResult("{}")
        pump()
        assertTrue(a.job!!.isCompleted)
        assertFalse(second.asBinder() in engine.cancelled)
        assertEquals(0, engine.cancels)
        second.onResult("{}")
        pump()
        assertTrue(b.job!!.isCompleted)
    }

    @Test
    fun prepareAsksTheEngineToLoadAheadUnlessAnotherEngineWorks() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        llm.mayPrepare = { false }
        launch { llm.prepare("/m.gguf", 4096, "auto") }
        pump()
        assertTrue(engine.prepared.isEmpty())
        llm.mayPrepare = { true }
        launch { llm.prepare("/m.gguf", 4096, "auto") }
        pump()
        assertEquals(listOf("/m.gguf"), engine.prepared.toList())
        assertFalse(llm.isBusy)
    }

    @Test
    fun memoryPressureUnloadsAndReleasesAnIdleEngineOnly() = runTest(timeout = 10.seconds) {
        val engine = FakeEngine()
        serve(engine)
        val llm = llm()
        val run = chat(llm)
        pump()
        // Busy: the release waits for the completion.
        llm.unloadModel()
        llm.releaseIfIdle()
        pump()
        assertEquals(1, engine.unloads)
        assertTrue(unbound.isEmpty())
        engine.callbacks.single().onResult("{}")
        pump()
        assertTrue(run.job!!.isCompleted)
        llm.releaseIfIdle()
        pump()
        assertTrue(bound.isEmpty())
        assertTrue(unbound.isNotEmpty())
    }
}
