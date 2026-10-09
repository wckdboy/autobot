package dev.wckdboy.autobot.engine.diffusion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

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
class LocalSd(context: Context, private val idleMillis: Long = 3 * 60_000L) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: ISdEngine? = null
    private var connection: ServiceConnection? = null
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    private suspend fun connect(): ISdEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        engine?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<ISdEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val e = ISdEngine.Stub.asInterface(service)
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
        if (!app.bindService(Intent(app, SdService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw SdEngineException("Cannot start the on-device image engine")
        }
        connection = conn
        withTimeout(15_000) { ready.await() }
    }

    suspend fun systemInfo(): String = connect().systemInfo()

    /** Runs one request. Cancelling the collector cancels sampling. */
    fun generate(request: SdRequest): Flow<LocalSdEvent> = callbackFlow {
        val e = connect()
        synchronized(this@LocalSd) { active++ }
        val binder = e.asBinder()
        val death = IBinder.DeathRecipient {
            close(SdEngineException("The image engine stopped (likely out of memory). Try a smaller size or model."))
        }
        binder.linkToDeath(death, 0)
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
                if (error == null) close() else close(SdEngineException(error))
            }
        }
        e.generate(request.toBundle(), callback)
        awaitClose {
            runCatching { binder.unlinkToDeath(death, 0) }
            runCatching { if (binder.isBinderAlive) e.cancel() }
            synchronized(this@LocalSd) {
                active--
                if (active == 0) main.postDelayed(unbindLater, idleMillis)
            }
        }
    }

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

    private fun unbindIfIdle() {
        synchronized(this) { if (active > 0) return }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        engine = null
    }
}
