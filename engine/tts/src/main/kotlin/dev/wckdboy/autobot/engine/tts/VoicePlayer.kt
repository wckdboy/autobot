package dev.wckdboy.autobot.engine.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Plays mono float PCM (Kokoro's 24 kHz) as it arrives: a streaming [AudioTrack] fed block by
 * block, so the first sentence plays while the next is synthesized. [play] returns when the
 * audio has been heard to the end, or right away after [stop] / cancellation. One playback at a
 * time per player.
 */
class VoicePlayer(private val sampleRate: Int = LocalVoice.SAMPLE_RATE) {
    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    /** Plays [audio] (blocks of samples in -1…1); synthesis runs ahead by up to two blocks. */
    suspend fun play(audio: Flow<FloatArray>) = withContext(Dispatchers.IO) {
        stopped = false
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, PIECE * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        var written = 0L
        try {
            t.play()
            audio.buffer(2).collect { block ->
                var at = 0
                while (at < block.size && !stopped && currentCoroutineContext().isActive) {
                    // Small writes: after stop() (pause + flush) the next one fits the emptied buffer and returns.
                    val n = t.write(block, at, minOf(block.size - at, PIECE), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) return@collect
                    at += n
                }
                written += at
                if (stopped) throw StopPlayback()
            }
            // Let the tail play out: the track reports frames actually rendered.
            while (!stopped && currentCoroutineContext().isActive && (t.playbackHeadPosition.toLong() and 0xffffffffL) < written) delay(20)
        } catch (_: StopPlayback) {
            // Stopped by the user.
        } finally {
            withContext(NonCancellable) {
                track = null
                runCatching { t.pause(); t.flush(); t.stop() }
                t.release()
            }
        }
    }

    /** Stops playback now; a suspended [play] returns. */
    fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    private class StopPlayback : Exception()

    private companion object {
        /** Samples per write: 100 ms at 24 kHz. */
        const val PIECE = 2_400
    }
}
