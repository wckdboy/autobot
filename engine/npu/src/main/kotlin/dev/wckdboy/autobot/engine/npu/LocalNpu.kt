package dev.wckdboy.autobot.engine.npu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Events of one NPU/GPU image job. */
sealed interface NpuEvent {
    data class Status(val status: String) : NpuEvent
    data class Progress(val step: Int, val steps: Int) : NpuEvent
    data class Image(val index: Int, val path: String, val width: Int, val height: Int, val seed: Long) : NpuEvent

    /** Raw final JSON from the engine (timings, backend | error). */
    data class Result(val json: String) : NpuEvent
}

class NpuEngineDiedException : RemoteException("The NPU engine stopped (likely out of memory)")

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
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    private suspend fun connect(): INpuEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        engine?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<INpuEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val e = INpuEngine.Stub.asInterface(service)
                engine = e
                ready.complete(e)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                engine = null
            }

            override fun onBindingDied(name: ComponentName) {
                engine = null
                runCatching { app.unbindService(this) }
                if (connection === this) connection = null
            }
        }
        if (!app.bindService(Intent(app, NpuService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("Cannot start the NPU engine")
        }
        connection = conn
        withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
    }

    /** `{npu, arch, error}` JSON. Starting the engine process also opens the NPU. */
    suspend fun status(): String = connect().status().also { scheduleUnbind() }

    suspend fun inspect(path: String): String = connect().inspect(path).also { scheduleUnbind() }

    /** Runs one job. Cancelling the collector cancels the job at the next step. */
    fun generate(requestJson: String): Flow<NpuEvent> = callbackFlow {
        val e = connect()
        synchronized(this@LocalNpu) { active++ }
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient { close(NpuEngineDiedException()) }
        binder.linkToDeath(death, 0)
        val callback = object : INpuCallback.Stub() {
            override fun onStatus(status: String) {
                trySend(NpuEvent.Status(status))
            }

            override fun onProgress(step: Int, steps: Int) {
                trySend(NpuEvent.Progress(step, steps))
            }

            override fun onImage(index: Int, path: String, width: Int, height: Int, seed: Long) {
                trySend(NpuEvent.Image(index, path, width, height, seed))
            }

            override fun onResult(json: String) {
                trySend(NpuEvent.Result(json))
                close()
            }
        }
        e.generate(requestJson.toByteArray(Charsets.UTF_8), callback)
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            runCatching { if (binder.isBinderAlive) e.cancel() }
            synchronized(this@LocalNpu) {
                active--
            }
            scheduleUnbind()
        }
    }

    /** Frees the resident models now (e.g. before a large LLM needs the memory). */
    fun unloadModels() {
        runCatching { engine?.unload() }
    }

    private fun scheduleUnbind() {
        synchronized(this) { if (active == 0) main.postDelayed(unbindLater, idleMillis) }
    }

    private fun unbindIfIdle() {
        synchronized(this) { if (active > 0) return }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        engine = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
