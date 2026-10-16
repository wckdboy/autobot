package dev.wckdboy.autobot.engine.llama

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.io.File
import java.io.IOException
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

/** Events of one on-device completion. */
sealed interface LocalLlmEvent {
    data class Status(val status: String) : LocalLlmEvent
    data class Content(val text: String) : LocalLlmEvent
    data class Reasoning(val text: String) : LocalLlmEvent

    /** Raw final JSON from the engine (content, reasoning, tool_calls, usage | error). */
    data class Result(val json: String) : LocalLlmEvent
}

class EngineDiedException : RemoteException("The on-device engine stopped (likely out of memory)")

/** The engine process could not be started or did not answer in time (not a cancellation). */
class EngineUnavailableException(message: String) : RemoteException(message)

/**
 * Client for [LlamaService]. Binds on first use and unbinds after [idleMillis] without
 * requests, which lets Android reclaim the model's memory. If the engine process dies
 * mid-request (OOM, native crash), the running flow fails with [EngineDiedException]; if it
 * cannot be started, with [EngineUnavailableException].
 *
 * The idle window is long ([IDLE_UNBIND_MILLIS]): reloading a multi-GB model costs seconds, and
 * real memory pressure frees it sooner through the app's memory coordinator.
 */
class LocalLlm(context: Context, private val idleMillis: Long = IDLE_UNBIND_MILLIS) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private val requestDir = File(app.cacheDir, "llm-io")

    @Volatile
    private var engine: ILlamaEngine? = null
    private var connection: ServiceConnection? = null

    /** Calls and completions in flight; guarded by `this`, as are [engine] and [connection] changes. */
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    /** Called before each completion so the app can free other engines' memory first. */
    @Volatile
    var beforeJob: () -> Unit = {}

    /** Whether a [prepare] may load now (false while another large engine works). */
    @Volatile
    var mayPrepare: () -> Boolean = { true }

    /** Whether a completion or call is running. */
    val isBusy: Boolean get() = synchronized(this) { active > 0 }

    /** Connects and counts one use; every successful call must be paired with [done]. */
    private suspend fun acquire(): ILlamaEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        synchronized(this) {
            engine?.takeIf { it.asBinder().isBinderAlive }?.let {
                active++
                return it
            }
        }
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
                synchronized(this@LocalLlm) { if (connection === this) connection = null }
            }

            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(EngineUnavailableException("The on-device engine refused the connection"))
            }
        }
        val bound = try {
            app.bindService(Intent(app, LlamaService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) {
            runCatching { app.unbindService(conn) }
            throw EngineUnavailableException("Cannot start the on-device engine")
        }
        synchronized(this) { connection = conn }
        val e = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        if (e == null) {
            // Not a cancellation: the engine process did not come up (e.g. its native init hangs).
            synchronized(this) { if (connection === conn) connection = null }
            runCatching { app.unbindService(conn) }
            throw EngineUnavailableException("The on-device engine did not start in time")
        }
        synchronized(this) { active++ }
        e
    }

    private fun done() {
        synchronized(this) {
            active--
            if (active == 0) main.postDelayed(unbindLater, idleMillis)
        }
    }

    /** One short call on the engine, off the main thread (binder calls block). */
    private suspend fun <T> call(block: (ILlamaEngine) -> T): T {
        val e = acquire()
        try {
            return withContext(Dispatchers.IO) { block(e) }
        } finally {
            done()
        }
    }

    suspend fun systemInfo(): String = call { it.systemInfo() }

    /** Compute devices the engine can use (JSON array, see [ILlamaEngine.devices]). */
    suspend fun devices(): String = call { it.devices() }

    /**
     * Streams one completion on [backend] (cpu, gpu, npu, auto). Cancelling the collector cancels
     * generation. The request goes to the engine as a file descriptor: a long history does not fit
     * in one binder transaction.
     */
    fun chat(modelPath: String, nCtx: Int, backend: String, requestJson: String): Flow<LocalLlmEvent> = callbackFlow {
        beforeJob()
        val request = withContext(Dispatchers.IO) { requestFile(requestJson) }
        val e = try {
            acquire()
        } catch (t: Throwable) {
            request.close()
            throw t
        }
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient { close(EngineDiedException()) }
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
        try {
            binder.linkToDeath(death, 0)
            e.chat(modelPath, nCtx, backend, request, callback)
        } catch (t: RemoteException) {
            runCatching { binder.unlinkToDeath(death, 0) }
            done()
            throw EngineDiedException()
        } finally {
            // The engine received its own duplicate of the descriptor.
            request.close()
        }
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            // Stops this completion only (queued, loading or running); a no-op once it finished,
            // so it never reaches the next request.
            runCatching { if (binder.isBinderAlive) e.cancelChat(callback) }
            done()
        }
    }.buffer(Channel.UNLIMITED) // never drop an event, least of all the final Result

    /** Writes [json] to a private file and returns a read descriptor for it; the file itself is gone at once. */
    private fun requestFile(json: String): ParcelFileDescriptor {
        requestDir.mkdirs()
        val file = File.createTempFile("request", ".json", requestDir)
        try {
            file.writeText(json, Charsets.UTF_8)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw EngineUnavailableException("Cannot hand the request to the engine: ${e.message}")
        } finally {
            file.delete()
        }
    }

    /**
     * Embeds [texts] with the GGUF embedding model at [modelPath], in the engine process: one
     * L2-normalised vector per text. Throws when the model cannot be loaded or run.
     */
    suspend fun embed(modelPath: String, texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        // The embedding model is loaded next to the chat model: free the image engines first.
        beforeJob()
        val flat = call { it.embed(modelPath, texts.toTypedArray()) }
            ?: throw IllegalStateException("The embedding model could not be loaded or run")
        val dims = flat.size / texts.size
        return List(texts.size) { i -> flat.copyOfRange(i * dims, (i + 1) * dims) }
    }

    /**
     * Loads [modelPath] in the background (a chat with it was opened) so the first message does
     * not wait for it. Frees the other engines' memory first, like a completion would; the engine
     * skips it when a completion is already queued, and a chat for another model stops it.
     */
    suspend fun prepare(modelPath: String, nCtx: Int, backend: String) {
        // Never next to a running image or audio job: two large models at once can get the app killed.
        if (!mayPrepare()) return
        beforeJob()
        call { it.prepare(modelPath, nCtx, backend) }
    }

    /** Frees the resident model now (e.g. before image generation needs the memory). */
    fun unloadModel() {
        runCatching { engine?.unload() }
    }

    /** Ends the engine process now if nothing runs (memory pressure); the next call restarts it. */
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** Unbind (and free the model) after this long without requests: 12 minutes. */
        const val IDLE_UNBIND_MILLIS = 12 * 60_000L
    }
}
