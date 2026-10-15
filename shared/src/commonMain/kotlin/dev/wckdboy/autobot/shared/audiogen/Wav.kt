package dev.wckdboy.autobot.shared.audiogen

import kotlin.math.roundToInt

/** RIFF/WAVE files with 16-bit PCM samples (the format every player and editor opens). */
object Wav {
    const val HEADER_BYTES = 44

    /** [audio] as a 16-bit stereo WAV file. */
    fun encode16(audio: StereoAudio): ByteArray = encode16(listOf(audio.left, audio.right), audio.sampleRate)

    /**
     * [channels] (planar float PCM, equal lengths, nominally -1…1) as a 16-bit WAV file: samples are
     * clamped, scaled by 32767 and rounded; non-finite samples become silence.
     */
    fun encode16(channels: List<FloatArray>, sampleRate: Int): ByteArray {
        require(channels.isNotEmpty() && channels.all { it.size == channels[0].size }) { "channels must be non-empty and equally long" }
        require(sampleRate > 0) { "bad sample rate" }
        val count = channels.size
        val frames = channels[0].size
        val dataBytes = frames.toLong() * count * 2
        require(dataBytes + HEADER_BYTES - 8 <= 0xFFFF_FFFFL) { "too long for a WAV file" }
        val out = ByteArray(HEADER_BYTES + dataBytes.toInt())
        var at = 0
        fun ascii(s: String) = s.forEach { out[at++] = it.code.toByte() }
        fun le16(v: Int) {
            out[at++] = v.toByte()
            out[at++] = (v shr 8).toByte()
        }
        fun le32(v: Long) {
            for (k in 0 until 4) out[at++] = (v shr (8 * k)).toByte()
        }
        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(count); le32(sampleRate.toLong())
        le32(sampleRate.toLong() * count * 2); le16(count * 2); le16(16)
        ascii("data"); le32(dataBytes)
        for (i in 0 until frames) {
            for (c in 0 until count) le16(sample16(channels[c][i]))
        }
        return out
    }

    /** A float sample as a signed 16-bit value. */
    fun sample16(v: Float): Int = if (v.isFinite()) (v.coerceIn(-1f, 1f) * 32767f).roundToInt() else 0
}
