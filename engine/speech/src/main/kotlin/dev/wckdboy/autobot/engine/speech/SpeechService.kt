package dev.wckdboy.autobot.engine.speech

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread
import org.json.JSONObject

/** JNI surface of `libautobot_whisper.so`. Only used inside the ":asr" process. */
internal object NativeWhisper {
    init {
        System.loadLibrary("autobot_whisper")
    }

    external fun transcribe(modelPath: String, pcmPath: String, language: String): ByteArray

    external fun free()
}

/** Hosts whisper.cpp in its own process; one transcription at a time. */
class SpeechService : Service() {
    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        // Not on the main thread: a transcription still running would block it until done.
        thread(name = "whisper-free") { synchronized(lock) { runCatching { NativeWhisper.free() } } }
        super.onDestroy()
    }

    /** Binder calls arrive on several threads: freeing the model must never overlap a transcription. */
    private val lock = Any()

    private val binder = object : ISpeechEngine.Stub() {
        override fun transcribe(modelPath: String, pcmPath: String, language: String): String = synchronized(lock) {
            try {
                String(NativeWhisper.transcribe(modelPath, pcmPath, language), Charsets.UTF_8)
            } catch (t: Throwable) {
                // Library missing on this phone, out of memory, …: an answer, not a crashed process.
                JSONObject().put("error", "Speech engine failed: ${t.message ?: t.javaClass.simpleName}").toString()
            }
        }

        override fun unload() = synchronized(lock) { runCatching { NativeWhisper.free() }; Unit }
    }
}
