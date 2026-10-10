package dev.wckdboy.autobot.engine.speech

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class SpeechException(message: String) : Exception(message)

/** Records the microphone and transcribes it on the phone with whisper.cpp. */
class LocalSpeech(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var engine: ISpeechEngine? = null

    private suspend fun connect(): ISpeechEngine = mutex.withLock {
        engine?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<ISpeechEngine>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                ISpeechEngine.Stub.asInterface(service).also { engine = it; ready.complete(it) }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                engine = null
            }
        }
        if (!app.bindService(Intent(app, SpeechService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            throw SpeechException("Cannot start the speech engine")
        }
        withTimeout(15_000) { ready.await() }
    }

    /**
     * Records 16 kHz mono audio until [stopped] returns true (or [maxSeconds] pass),
     * writing float PCM to a private cache file. Requires RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(stopped: () -> Boolean, maxSeconds: Int = 120, onLevel: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, min * 4)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) throw SpeechException("Microphone unavailable")
        val file = File.createTempFile("dictation", ".pcm", File(app.cacheDir, "speech").apply { mkdirs() })
        val chunk = FloatArray(rate / 10)
        val bytes = ByteBuffer.allocate(chunk.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        var total = 0
        recorder.startRecording()
        try {
            FileOutputStream(file).use { out ->
                while (!stopped() && currentCoroutineContext().isActive && total < rate * maxSeconds) {
                    val n = recorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    bytes.clear()
                    var peak = 0f
                    for (i in 0 until n) {
                        bytes.putFloat(chunk[i])
                        peak = maxOf(peak, kotlin.math.abs(chunk[i]))
                    }
                    out.write(bytes.array(), 0, n * 4)
                    total += n
                    onLevel(peak)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        file
    }

    /** Transcribes a recording made by [record] (and deletes it). */
    suspend fun transcribe(modelPath: String, pcm: File, language: String = "auto"): String = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(connect().transcribe(modelPath, pcm.path, language))
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw SpeechException(it) }
            json.optString("text").trim()
        } finally {
            pcm.delete()
        }
    }
}
