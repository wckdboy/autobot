package dev.wckdboy.autobot.engine.speech

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class SpeechException(message: String) : Exception(message)

/**
 * Records the microphone and transcribes it on the phone with whisper.cpp. The `:asr` process
 * (up to 1.6 GB for the largest model) is unbound after [idleMillis] without transcriptions.
 */
class LocalSpeech(context: Context, private val idleMillis: Long = 60_000L) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: ISpeechEngine? = null
    private var connection: ServiceConnection? = null

    /** Transcriptions in flight; guarded by `this`, as are [engine] and [connection] changes. */
    private var active = 0
    private val unbindLater = Runnable { unbindIfIdle() }

    /** Called before each transcription so the app can free other engines' memory first. */
    @Volatile
    var beforeJob: () -> Unit = {}

    val isBusy: Boolean get() = synchronized(this) { active > 0 }

    private suspend fun acquire(): ISpeechEngine = mutex.withLock {
        main.removeCallbacks(unbindLater)
        synchronized(this) {
            engine?.takeIf { it.asBinder().isBinderAlive }?.let {
                active++
                return it
            }
        }
        val ready = CompletableDeferred<ISpeechEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                ISpeechEngine.Stub.asInterface(service).also { engine = it; ready.complete(it) }
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
                synchronized(this@LocalSpeech) { if (connection === this) connection = null }
            }

            override fun onBindingDied(name: ComponentName) {
                engine = null
                runCatching { app.unbindService(this) }
                synchronized(this@LocalSpeech) { if (connection === this) connection = null }
            }
        }
        if (!runCatching { app.bindService(Intent(app, SpeechService::class.java), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)) {
            runCatching { app.unbindService(conn) }
            throw SpeechException("Cannot start the speech engine")
        }
        synchronized(this) { connection = conn }
        val e = withTimeoutOrNull(15_000) { ready.await() }
        if (e == null) {
            synchronized(this) { if (connection === conn) connection = null }
            runCatching { app.unbindService(conn) }
            throw SpeechException("The speech engine did not start in time")
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

    /**
     * Records 16 kHz mono audio until [stopped] returns true (or [maxSeconds] pass),
     * writing float PCM to a private cache file. Requires RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(stopped: () -> Boolean, maxSeconds: Int = 120, onLevel: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) throw SpeechException("Microphone unavailable")
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, min * 4)
        } catch (e: Exception) {
            throw SpeechException("Microphone unavailable")
        }
        // From here on the recorder is released whatever happens (a leaked one blocks the microphone).
        var file: File? = null
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw SpeechException("Microphone unavailable")
            val out = File.createTempFile("dictation", ".pcm", File(app.cacheDir, "speech").apply { mkdirs() })
            file = out
            val chunk = FloatArray(rate / 10)
            val bytes = ByteBuffer.allocate(chunk.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            var total = 0
            try {
                recorder.startRecording()
            } catch (e: IllegalStateException) {
                throw SpeechException("Microphone unavailable")
            }
            FileOutputStream(out).use { stream ->
                while (!stopped() && currentCoroutineContext().isActive && total < rate * maxSeconds) {
                    val n = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    bytes.clear()
                    var peak = 0f
                    for (i in 0 until n) {
                        bytes.putFloat(chunk[i])
                        peak = maxOf(peak, kotlin.math.abs(chunk[i]))
                    }
                    stream.write(bytes.array(), 0, n * 4)
                    total += n
                    onLevel(peak)
                }
            }
            out
        } catch (t: Throwable) {
            file?.delete()
            throw t
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Transcribes a recording made by [record] (and deletes it). Cancelling returns at once;
     * whisper.cpp cannot stop mid-run, so the engine finishes in its process, which is then
     * released.
     */
    suspend fun transcribe(modelPath: String, pcm: File, language: String = "auto"): String {
        beforeJob()
        val e = try {
            acquire()
        } catch (t: Throwable) {
            pcm.delete()
            throw t
        }
        val raw = suspendCancellableCoroutine { cont ->
            thread(name = "whisper-call") {
                try {
                    val result = e.transcribe(modelPath, pcm.path, language)
                    if (cont.isActive) cont.resume(result)
                } catch (t: RemoteException) {
                    if (cont.isActive) cont.resumeWithException(SpeechException("The speech engine stopped (likely out of memory)"))
                } catch (t: Exception) {
                    if (cont.isActive) cont.resumeWithException(t)
                } finally {
                    pcm.delete()
                    done()
                }
            }
        }
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { throw SpeechException(it) }
        return json.optString("text").trim()
    }

    /** Frees the loaded model now (memory pressure, another engine needs the memory). */
    fun unloadModel() {
        runCatching { engine?.unload() }
    }

    /** Ends the engine process now if nothing runs; the next transcription restarts it. */
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
}
