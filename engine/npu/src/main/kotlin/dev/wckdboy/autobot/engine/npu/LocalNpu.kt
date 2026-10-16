package dev.wckdboy.autobot.engine.npu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Events of one NPU/GPU image job. */
sealed interface NpuEvent {
    data class Status(val status: String) : NpuEvent
    data class Progress(val step: Int, val steps: Int) : NpuEvent
    data class Image(val index: Int, val path: String, val width: Int, val height: Int, val seed: Long) : NpuEvent

    /** Raw final JSON from the engine (timings, backend | error). */
    data class Result(val json: String) : NpuEvent
}

class NpuEngineDiedException : RemoteException("The NPU engine stopped (likely out of memory)")

/** The NPU engine process could not be started or did not answer in time (not a cancellation). */
class NpuEngineUnavailableException(message: String) : RemoteException(message)

/**
 * Client for [NpuService]: binds on first use and unbinds after [idleMillis] without jobs so
 * Android can reclaim the models' memory. A crash of the engine process fails the running job
 * with [NpuEngineDiedException].
 */
class LocalNpu(context: Context, private val idleMillis: Long = 3 * 60_000L) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: INpuEngine? = null
    private var connection: ServiceConnection? = null

    /** Calls and jobs in flight; guarded by `this`, as are [engine] and [connection] changes. */
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    /** Called before each job so the app can free other engines' memory first. */
    @Volatile
    var beforeJob: () -> Unit = {}

    val isBusy: Boolean get() = synchronized(this) { active > 0 }

    /** Connects and counts one use; pair with [done]. */
    private suspend fun acquire(): INpuEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        synchronized(this) {
            engine?.takeIf { it.asBinder().isBinderAlive }?.let {
                active++
                return it
            }
        }
        val ready = CompletableDeferred<INpuEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val e = INpuEngine.Stub.asInterface(service)
                engine = e
                ready.complete(e)
            }

            /** Unbound after its process died (once: the binding is gone after that). */
            @Volatile
            private var released = false

            override fun onServiceDisconnected(name: ComponentName) {
                // The engine process died. Unbind: a binding kept to it makes Android restart the
                // process and hold it forever, out of reach of the idle unbind and the memory
                // coordinator. The next request binds afresh.
                engine = null
                if (!released) {
                    released = true
                    runCatching { app.unbindService(this) }
                }
                synchronized(this@LocalNpu) { if (connection === this) connection = null }
            }

            override fun onBindingDied(name: ComponentName) {
                engine = null
                runCatching { app.unbindService(this) }
                synchronized(this@LocalNpu) { if (connection === this) connection = null }
            }
        }
        if (!runCatching { app.bindService(Intent(app, NpuService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
            runCatching { app.unbindService(conn) }
            throw NpuEngineUnavailableException("Cannot start the NPU engine")
        }
        synchronized(this) { connection = conn }
        val e = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (e == null) {
            // A domain error, not a cancellation: callers must show it, and the binding must not linger.
            synchronized(this) { if (connection === conn) connection = null }
            runCatching { app.unbindService(conn) }
            throw NpuEngineUnavailableException("The NPU engine did not start in time")
        }
        synchronized(this) { active++ }
        e
    }

    private fun done() {
        synchronized(this) {
            active--
        }
        scheduleUnbind()
    }

    private suspend fun <T> call(block: (INpuEngine) -> T): T {
        val e = acquire()
        try {
            return withContext(Dispatchers.IO) { block(e) }
        } finally {
            done()
        }
    }

    /** `{npu, arch, error}` JSON. Starting the engine process also opens the NPU. */
    suspend fun status(): String = call { it.status() }

    suspend fun inspect(path: String): String = call { it.inspect(path) }

    /** Runs one job. Cancelling the collector cancels the job at the next step. */
    fun generate(requestJson: String): Flow<NpuEvent> = callbackFlow {
        beforeJob()
        val e = acquire()
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient { close(NpuEngineDiedException()) }
        val callback = object : INpuCallback.Stub() {
            override fun onStatus(status: String) {
                trySend(NpuEvent.Status(status))
            }

            override fun onProgress(step: Int, steps: Int) {
                trySend(NpuEvent.Progress(step, steps))
            }

            override fun onImage(index: Int, path: String, width: Int, height: Int, seed: Long) {
                // Nobody will take the file once the job is cancelled: delete it rather than leak it.
                if (trySend(NpuEvent.Image(index, path, width, height, seed)).isFailure) File(path).delete()
            }

            override fun onResult(json: String) {
                trySend(NpuEvent.Result(json))
                close()
            }
        }
        try {
            // Inside the try: an engine that died since [acquire] must not keep this use counted.
            binder.linkToDeath(death, 0)
            e.generate(requestJson.toByteArray(Charsets.UTF_8), callback)
        } catch (t: RemoteException) {
            runCatching { binder.unlinkToDeath(death, 0) }
            done()
            throw NpuEngineDiedException()
        }
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            runCatching { if (binder.isBinderAlive) e.cancel() }
            done()
        }
    }.buffer(Channel.UNLIMITED) // never drop an event: a lost Image leaks its file, a lost Result hangs the job

    /** Frees the resident models now (e.g. before a large LLM needs the memory). */
    fun unloadModels() {
        runCatching { engine?.unload() }
    }

    private fun scheduleUnbind() {
        synchronized(this) { if (active == 0) main.postDelayed(unbindLater, idleMillis) }
    }

    /** Ends the engine process now if nothing runs (memory pressure); the next job restarts it. */
    fun releaseIfIdle() {
        main.removeCallbacks(unbindLater)
        main.post(unbindLater)
    }

    private fun unbindIfIdle() = synchronized(this) {
        if (active > 0) return@synchronized
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        engine = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
