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
 * the model's memory, unless someone [keepResident]s it (the Image screen while it is open: the
 * next image starts sampling instead of reloading the model); the memory coordinator can still
 * free it at any time. Output files are read and deleted as they arrive; nothing generated
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

    /** [keepResident] handles open; guarded by `this`. */
    private var holds = 0

    /** The idle unbind: skipped while someone keeps the engine resident. */
    private val unbindLater = Runnable { synchronized(this) { if (holds == 0) unbindIfIdle() } }

    /** [releaseIfIdle]: the memory coordinator's, whoever keeps the engine resident. */
    private val releaseNow = Runnable { unbindIfIdle() }

    /**
     * The bundle the engine holds loaded, as far as this client knows (the last job that finished
     * without an error loaded it); null when unknown or gone (unbound, unloaded, process died).
     */
    @Volatile
    private var resident: SdBundle? = null

    /**
     * Bumped by [unloadModel]: a job that was running when the model was unloaded (the engine
     * unloads after it) must not mark its bundle resident when it finishes.
     */
    private val unloads = java.util.concurrent.atomic.AtomicInteger()

    /** The bundle the latest [prepare] asked for: an older warm-up still waiting is skipped. */
    @Volatile
    private var wanted: SdBundle? = null
    private val prepareLock = Mutex()

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
        main.removeCallbacks(releaseNow)
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
                resident = null
                if (!released) {
                    released = true
                    runCatching { app.unbindService(this) }
                }
                synchronized(this@LocalSd) { if (connection === this) connection = null }
            }

            override fun onBindingDied(name: ComponentName) {
                engine = null
                resident = null
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
            if (active == 0 && holds == 0) main.postDelayed(unbindLater, idleMillis)
        }
    }

    /**
     * Keeps the engine process and its model resident until the handle is closed, instead of
     * unbinding [idleMillis] after the last job: the Image screen holds it while it is open, so an
     * image made minutes after the last one does not reload the model. Nothing is loaded by this
     * alone, and the memory coordinator still frees the engine when another one needs the memory,
     * under memory pressure or when the app goes to the background ([releaseIfIdle]).
     */
    fun keepResident(): AutoCloseable {
        synchronized(this) {
            holds++
            main.removeCallbacks(unbindLater)
        }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                synchronized(this) {
                    holds--
                    if (holds == 0 && active == 0) main.postDelayed(unbindLater, idleMillis)
                }
            }
        }
    }

    /** Whether the engine holds [bundle] loaded (as far as this client knows). */
    fun isResident(bundle: SdBundle): Boolean = resident == bundle && engine?.asBinder()?.isBinderAlive == true

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
        val unloadsAtStart = unloads.get()
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient {
            resident = null
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
                // A job that finished loaded its bundle; after an error the engine may hold anything.
                // An unload asked for meanwhile runs after this job: the bundle is gone then.
                resident = if (error == null && unloads.get() == unloadsAtStart) request.bundle else null
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
            if (!finished.get()) {
                resident = null
                runCatching { if (binder.isBinderAlive) e.cancel() }
            }
            done()
        }
    }.buffer(Channel.UNLIMITED) // never drop an image

    /**
     * Loads [bundle] ahead of the first image (the prompt is being written) so Generate starts
     * sampling instead of loading. Frees the other engines' memory first like a job would, and is
     * skipped while one of them works. Nothing is sent when the engine already holds [bundle]
     * (cheap to call again: a model freed since then is loaded again). Warm-ups run one at a
     * time, and one still waiting when a newer warm-up asks for another bundle is skipped (the
     * user switched models: loading the old one would be wasted). The model stays resident until
     * the idle unbind, the memory coordinator or memory pressure. Returns whether [bundle] is
     * loaded now.
     */
    suspend fun prepare(bundle: SdBundle): Boolean {
        wanted = bundle
        if (isResident(bundle)) return true
        if (!mayPrepare()) return false
        preparing.incrementAndGet()
        try {
            prepareLock.withLock {
                if (wanted != bundle) return false
                if (isResident(bundle)) return true
                generate(SdRequest(bundle = bundle, prompt = "", prepareOnly = true)).collect {}
            }
        } finally {
            preparing.decrementAndGet()
        }
        return true
    }

    private val preparing = java.util.concurrent.atomic.AtomicInteger()

    /** A [prepare] runs: a job sent now waits for that load (the engine runs requests in order). */
    val isPreparing: Boolean get() = preparing.get() > 0

    fun unloadModel() {
        unloads.incrementAndGet()
        resident = null
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
        main.removeCallbacks(releaseNow)
        main.post(releaseNow)
    }

    private fun unbindIfIdle() = synchronized(this) {
        if (active > 0) return@synchronized
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        engine = null
        resident = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
