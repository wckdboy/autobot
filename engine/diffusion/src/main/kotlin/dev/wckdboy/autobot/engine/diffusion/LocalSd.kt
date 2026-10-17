package dev.wckdboy.autobot.engine.diffusion

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

sealed interface LocalSdEvent {
    data class Status(val status: String) : LocalSdEvent
    data class Progress(val step: Int, val steps: Int) : LocalSdEvent

    /** JPEG bytes of the current latent preview. */
    class Preview(val jpeg: ByteArray) : LocalSdEvent

    /** PNG bytes of a finished image. */
    class Image(val index: Int, val png: ByteArray, val seed: Long, val width: Int, val height: Int) : LocalSdEvent
}

class SdEngineException(message: String) : Exception(message)

/**
 * Client for [SdService]. Binds on demand and unbinds after [idleMillis] so Android can reclaim
 * the model's memory. Output files are read and deleted as they arrive; nothing generated
 * lingers in the cache.
 */
class LocalSd(context: Context, private val idleMillis: Long = 3 * 60_000L, private val connectTimeoutMillis: Long = CONNECT_TIMEOUT_MS) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: ISdEngine? = null
    private var connection: ServiceConnection? = null

    /** Calls and jobs in flight; guarded by `this`, as are [engine] and [connection] changes. */
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    /** Called before each job so the app can free other engines' memory first. */
    @Volatile
    var beforeJob: () -> Unit = {}

    /** Whether loading a model ahead of time may evict the others now (none of them is working). */
    @Volatile
    var mayPrepare: () -> Boolean = { true }

    val isBusy: Boolean get() = synchronized(this) { active > 0 }

    /** Connects and counts one use; pair with [done]. */
    private suspend fun acquire(): ISdEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        synchronized(this) {
            engine?.takeIf { it.asBinder().isBinderAlive }?.let {
                active++
                return it
            }
        }
        val ready = CompletableDeferred<ISdEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val e = ISdEngine.Stub.asInterface(service)
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
                synchronized(this@LocalSd) { if (connection === this) connection = null }
            }

            override fun onBindingDied(name: ComponentName) {
                engine = null
                runCatching { app.unbindService(this) }
                synchronized(this@LocalSd) { if (connection === this) connection = null }
            }
        }
        if (!runCatching { app.bindService(Intent(app, SdService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
            runCatching { app.unbindService(conn) }
            throw SdEngineException("Cannot start the on-device image engine")
        }
        synchronized(this) { connection = conn }
        val e = withTimeoutOrNull(connectTimeoutMillis) { ready.await() }
        if (e == null) {
            // A domain error, not a cancellation: callers must show it, and the binding must not linger.
            synchronized(this) { if (connection === conn) connection = null }
            runCatching { app.unbindService(conn) }
            throw SdEngineException("The image engine did not start in time")
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

    suspend fun systemInfo(): String {
        val e = acquire()
        try {
            return withContext(Dispatchers.IO) { e.systemInfo() }
        } finally {
            done()
        }
    }

    /** Runs one request. Cancelling the collector cancels sampling. */
    fun generate(request: SdRequest): Flow<LocalSdEvent> = callbackFlow {
        beforeJob()
        val e = acquire()
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient {
            close(SdEngineException("The image engine stopped (likely out of memory). Try a smaller size or model."))
        }
        // The engine's cancel stops whatever it runs now: once this job ended, that is the next
        // job (a Generate queued behind a warm-up), so only a job still running is cancelled.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback = object : ISdCallback.Stub() {
            override fun onStatus(status: String) {
                trySend(LocalSdEvent.Status(status))
            }

            override fun onProgress(step: Int, steps: Int) {
                trySend(LocalSdEvent.Progress(step, steps))
            }

            override fun onPreview(jpegPath: String) {
                consume(jpegPath)?.let { trySend(LocalSdEvent.Preview(it)) }
            }

            override fun onImage(index: Int, pngPath: String, seed: Long, width: Int, height: Int) {
                consume(pngPath)?.let { trySend(LocalSdEvent.Image(index, it, seed, width, height)) }
            }

            override fun onDone(error: String?) {
                finished.set(true)
                if (error == null) close() else close(SdEngineException(error))
            }
        }
        try {
            // Inside the try: an engine that died since [acquire] must not keep this use counted.
            binder.linkToDeath(death, 0)
            e.generate(request.toBundle(), callback)
        } catch (t: RemoteException) {
            runCatching { binder.unlinkToDeath(death, 0) }
            done()
            throw SdEngineException("The image engine stopped")
        }
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            if (!finished.get()) runCatching { if (binder.isBinderAlive) e.cancel() }
            done()
        }
    }.buffer(Channel.UNLIMITED) // never drop an image

    /**
     * Loads [bundle] ahead of the first image (the prompt is being written) so Generate starts
     * sampling instead of loading. Frees the other engines' memory first like a job would, and is
     * skipped while one of them works. The model stays resident until the idle unbind or memory
     * pressure. Returns whether it ran.
     */
    suspend fun prepare(bundle: SdBundle): Boolean {
        if (!mayPrepare()) return false
        preparing.incrementAndGet()
        try {
            generate(SdRequest(bundle = bundle, prompt = "", prepareOnly = true)).collect {}
        } finally {
            preparing.decrementAndGet()
        }
        return true
    }

    private val preparing = java.util.concurrent.atomic.AtomicInteger()

    /** A [prepare] runs: a job sent now waits for that load (the engine runs requests in order). */
    val isPreparing: Boolean get() = preparing.get() > 0

    fun unloadModel() {
        runCatching { engine?.unload() }
    }

    private fun consume(path: String): ByteArray? {
        val file = File(path)
        // Only accept files from our own output directory.
        if (file.parentFile?.canonicalPath != File(app.cacheDir, "sd-out").canonicalPath) return null
        return try {
            file.readBytes()
        } catch (_: java.io.IOException) {
            null
        } finally {
            file.delete()
        }
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
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
