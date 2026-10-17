package dev.wckdboy.autobot.engine.speech

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
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

/** What whisper heard and the language it heard it in ("" when unknown). */
data class SpeechText(val text: String, val language: String)

/** Capture and engine sample rate. */
const val SAMPLE_RATE = 16_000

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
     * Captures 16 kHz mono audio until [stopped] returns true (or [maxSeconds] pass), handing
     * each ~100 ms buffer to [onAudio] (reused: copy what you keep). Nothing is written anywhere.
     * [echoCancel]: the voice-communication source with the platform's acoustic echo canceller
     * and noise suppressor, for listening while an answer plays (barge-in). Requires RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    suspend fun capture(
        stopped: () -> Boolean,
        maxSeconds: Int = 600,
        echoCancel: Boolean = false,
        onAudio: (samples: FloatArray, count: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val rate = SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) throw SpeechException("Microphone unavailable")
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val recorder = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, min * 4)
        } catch (e: Exception) {
            throw SpeechException("Microphone unavailable")
        }
        // From here on the recorder (and its effects) are released whatever happens: a leaked
        // recorder blocks the microphone.
        val effects = mutableListOf<AudioEffect>()
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw SpeechException("Microphone unavailable")
            if (echoCancel) {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder.audioSessionId)?.let { effects += it }
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)?.let { effects += it }
                effects.forEach { runCatching { it.enabled = true } }
            }
            val chunk = FloatArray(rate / 10)
            var total = 0
            try {
                recorder.startRecording()
            } catch (e: IllegalStateException) {
                throw SpeechException("Microphone unavailable")
            }
            while (!stopped() && currentCoroutineContext().isActive && total < rate * maxSeconds) {
                val n = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                total += n
                onAudio(chunk, n)
            }
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    /**
     * Transcribes [samples] (16 kHz mono) in [language] ("auto" detects). The audio goes to the
     * engine process through an anonymous in-memory file, never storage. Cancelling returns at
     * once and asks the engine to stop the run.
     */
    suspend fun transcribe(modelPath: String, samples: FloatArray, language: String = "auto"): SpeechText {
        beforeJob()
        val e = acquire()
        // A cancel as withContext returns throws after the file was made: it must still be closed
        // (the audio would stay in RAM) and this use uncounted (the engine would never unbind).
        var made: ParcelFileDescriptor? = null
        val pcm = try {
            withContext(Dispatchers.IO) { memoryFile(samples).also { made = it } }
        } catch (t: Throwable) {
            made?.let { runCatching { it.close() } }
            done()
            throw t
        }
        val raw = suspendCancellableCoroutine { cont ->
            thread(name = "whisper-call") {
                try {
                    val result = e.transcribeMemory(modelPath, pcm, samples.size, language)
                    if (cont.isActive) cont.resume(result)
                } catch (t: RemoteException) {
                    if (cont.isActive) cont.resumeWithException(SpeechException("The speech engine stopped (likely out of memory)"))
                } catch (t: Exception) {
                    if (cont.isActive) cont.resumeWithException(t)
                } finally {
                    runCatching { pcm.close() }
                    done()
                }
            }
            // A synchronous binder call: not on the thread that cancels (often the main one).
            cont.invokeOnCancellation { thread(name = "whisper-cancel") { runCatching { e.cancel() } } }
        }
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { throw SpeechException(it) }
        return SpeechText(json.optString("text").trim(), json.optString("language"))
    }

    /** [samples] as little-endian floats in an anonymous memory file (memfd): RAM only, gone when closed. */
    private fun memoryFile(samples: FloatArray): ParcelFileDescriptor {
        val fd = Os.memfd_create("dictation", 0)
        try {
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(samples)
            FileOutputStream(fd).write(bytes.array())
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
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
