package dev.wckdboy.autobot.engine.llama

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

/** Events of one on-device completion. */
sealed interface LocalLlmEvent {
    data class Status(val status: String) : LocalLlmEvent
    data class Content(val text: String) : LocalLlmEvent
    data class Reasoning(val text: String) : LocalLlmEvent

    /** Raw final JSON from the engine (content, reasoning, tool_calls, usage | error). */
    data class Result(val json: String) : LocalLlmEvent
}

class EngineDiedException : RemoteException("The on-device engine stopped (likely out of memory)")

/**
 * Client for [LlamaService]. Binds on first use and unbinds after [idleMillis] without
 * requests, which lets Android reclaim the model's memory. If the engine process dies
 * mid-request (OOM, native crash), the running flow fails with [EngineDiedException].
 */
class LocalLlm(context: Context, private val idleMillis: Long = 5 * 60_000L) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: ILlamaEngine? = null
    private var connection: ServiceConnection? = null
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    private suspend fun connect(): ILlamaEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        engine?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<ILlamaEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val e = ILlamaEngine.Stub.asInterface(service)
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
        if (!app.bindService(Intent(app, LlamaService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw IllegalStateException("Cannot start the on-device engine")
        }
        connection = conn
        withTimeout(CONNECT_TIMEOUT_MS) { ready.await() }
    }

    suspend fun systemInfo(): String = connect().systemInfo()

    /** Compute devices the engine can use (JSON array, see [ILlamaEngine.devices]). */
    suspend fun devices(): String = connect().devices()

    /** Streams one completion on [backend] (cpu, gpu, npu, auto). Cancelling the collector cancels generation. */
    fun chat(modelPath: String, nCtx: Int, backend: String, requestJson: String): Flow<LocalLlmEvent> = callbackFlow {
        val e = connect()
        synchronized(this@LocalLlm) { active++ }
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient { close(EngineDiedException()) }
        binder.linkToDeath(death, 0)
        val callback = object : ILlamaCallback.Stub() {
            override fun onStatus(status: String) {
                trySend(LocalLlmEvent.Status(status))
            }

            override fun onDelta(kind: Int, text: String) {
                trySend(if (kind == 1) LocalLlmEvent.Reasoning(text) else LocalLlmEvent.Content(text))
            }

            override fun onResult(json: String) {
                trySend(LocalLlmEvent.Result(json))
                close()
            }
        }
        e.chat(modelPath, nCtx, backend, requestJson.toByteArray(Charsets.UTF_8), callback)
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            runCatching { if (binder.isBinderAlive) e.cancel() }
            synchronized(this@LocalLlm) {
                active--
                if (active == 0) main.postDelayed(unbindLater, idleMillis)
            }
        }
    }

    /** Frees the resident model now (e.g. before image generation needs the memory). */
    fun unloadModel() {
        runCatching { engine?.unload() }
    }

    private fun unbindIfIdle() {
        synchronized(this) { if (active > 0) return }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        engine = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
