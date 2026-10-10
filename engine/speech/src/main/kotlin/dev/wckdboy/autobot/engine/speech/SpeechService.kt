package dev.wckdboy.autobot.engine.speech

import android.app.Service
import android.content.Intent
import android.os.IBinder

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
        NativeWhisper.free()
        super.onDestroy()
    }

    private val binder = object : ISpeechEngine.Stub() {
        override fun transcribe(modelPath: String, pcmPath: String, language: String): String =
            String(NativeWhisper.transcribe(modelPath, pcmPath, language), Charsets.UTF_8)

        override fun unload() = NativeWhisper.free()
    }
}
