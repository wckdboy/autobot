package dev.wckdboy.autobot.feature.studio.media

import dev.wckdboy.autobot.shared.audiogen.Wav
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Decoded PCM: one array per channel, samples in -1…1. */
class Pcm(val channels: List<FloatArray>, val sampleRate: Int) {
    val frames: Int get() = channels.firstOrNull()?.size ?: 0
    val durationMs: Long get() = if (sampleRate <= 0) 0 else frames * 1000L / sampleRate
}

/**
 * WAV reading for the audio room (the Studio's own outputs are 16-bit PCM WAV: Stable Audio at
 * 44.1 kHz stereo, Kokoro at 24 kHz mono): waveform peaks, trimming, and the length. Other
 * formats (FLAC / MP3 from a ComfyUI workflow) play but are not trimmed. Pure, JVM-tested.
 */
object WavFile {
    /** The PCM of a 16-bit (or 32-bit float) PCM WAV, or null for anything else. */
    fun decode(bytes: ByteArray): Pcm? {
        if (bytes.size < 12 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WAVE") return null
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        while (at + 8 <= bytes.size) {
            val id = ascii(bytes, at)
            val size = le32(bytes, at + 4).toInt().coerceAtLeast(0)
            val body = at + 8
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    format = le16(bytes, body)
                    channels = le16(bytes, body + 2)
                    rate = le32(bytes, body + 4).toInt()
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    if (channels <= 0 || rate <= 0) return null
                    val end = (body + size).coerceAtMost(bytes.size)
                    return when {
                        (format == 1 || format == 0xFFFE) && bits == 16 -> {
                            val frames = (end - body) / (2 * channels)
                            val out = List(channels) { FloatArray(frames) }
                            for (f in 0 until frames) for (c in 0 until channels) {
                                val i = body + (f * channels + c) * 2
                                out[c][f] = (((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()) / 32768f
                            }
                            Pcm(out, rate)
                        }
                        format == 3 && bits == 32 -> {
                            val frames = (end - body) / (4 * channels)
                            val out = List(channels) { FloatArray(frames) }
                            for (f in 0 until frames) for (c in 0 until channels) {
                                out[c][f] = java.lang.Float.intBitsToFloat(le32(bytes, body + (f * channels + c) * 4).toInt())
                            }
                            Pcm(out, rate)
                        }
                        else -> null
                    }
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }

    /** [count] peak heights (0…1) across the clip, the louder channel per bucket. */
    fun peaks(pcm: Pcm, count: Int = 256): FloatArray {
        val frames = pcm.frames
        if (frames == 0 || count <= 0) return FloatArray(count.coerceAtLeast(0))
        return FloatArray(count) { b ->
            val from = (b.toLong() * frames / count).toInt()
            val to = max(from + 1, ((b + 1).toLong() * frames / count).toInt()).coerceAtMost(frames)
            var peak = 0f
            for (ch in pcm.channels) for (i in from until to) peak = max(peak, abs(ch[i]))
            peak.coerceIn(0f, 1f)
        }
    }

    /** Peaks as bytes (0…255) for the encrypted preview file, and back. */
    fun encodePeaks(peaks: FloatArray): ByteArray = ByteArray(peaks.size) { (peaks[it] * 255f).roundToInt().coerceIn(0, 255).toByte() }

    fun decodePeaks(bytes: ByteArray): FloatArray = FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }

    /** [pcm] between [startMs] and [endMs] as a 16-bit WAV (short fades so the cut doesn't click). */
    fun trim(pcm: Pcm, startMs: Long, endMs: Long): ByteArray {
        val from = (startMs * pcm.sampleRate / 1000).toInt().coerceIn(0, pcm.frames)
        val to = (endMs * pcm.sampleRate / 1000).toInt().coerceIn(from, pcm.frames)
        val fade = (pcm.sampleRate / 200).coerceAtMost((to - from) / 2) // 5 ms
        val channels = pcm.channels.map { ch ->
            val out = ch.copyOfRange(from, to)
            for (i in 0 until fade) {
                val g = i.toFloat() / fade
                out[i] *= g
                out[out.size - 1 - i] *= g
            }
            out
        }
        return Wav.encode16(channels, pcm.sampleRate)
    }

    private fun ascii(b: ByteArray, at: Int) = if (at + 4 <= b.size) String(CharArray(4) { (b[at + it].toInt() and 0xFF).toChar() }) else ""

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}

/** Sniffing image bytes (engines answer PNG, JPEG or WEBP). */
object ImageSniff {
    fun mimeType(bytes: ByteArray): String = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        else -> "image/png"
    }
}

/**
 * Outpainting as inpainting: the source image placed on a larger canvas and a mask covering the
 * new margins (plus [overlap] px into the image so the seam is repainted). Sizes only; the room
 * draws the pixels.
 */
object Outpaint {
    data class Plan(val width: Int, val height: Int, val left: Int, val top: Int, val imageWidth: Int, val imageHeight: Int)

    /** Extends a [w]×[h] image by [left]/[top]/[right]/[bottom] px, rounded up to multiples of [multiple]. */
    fun plan(w: Int, h: Int, left: Int, top: Int, right: Int, bottom: Int, multiple: Int = 8): Plan {
        val rawW = w + left + right
        val rawH = h + top + bottom
        val width = (rawW + multiple - 1) / multiple * multiple
        val height = (rawH + multiple - 1) / multiple * multiple
        // Rounding slack goes to the side being extended (right / bottom when both or neither).
        val extraW = width - rawW
        val extraH = height - rawH
        val l = left + if (left > 0 && right == 0) extraW else 0
        val t = top + if (top > 0 && bottom == 0) extraH else 0
        return Plan(width, height, l, t, w, h)
    }

    /** Whether pixel ([x], [y]) of the canvas is repainted (outside the image, or within [overlap] of its new edges). */
    fun masked(plan: Plan, x: Int, y: Int, overlap: Int = 16): Boolean {
        val inX = x >= plan.left && x < plan.left + plan.imageWidth
        val inY = y >= plan.top && y < plan.top + plan.imageHeight
        if (!inX || !inY) return true
        val nearLeft = plan.left > 0 && x < plan.left + overlap
        val nearTop = plan.top > 0 && y < plan.top + overlap
        val nearRight = plan.width > plan.left + plan.imageWidth && x >= plan.left + plan.imageWidth - overlap
        val nearBottom = plan.height > plan.top + plan.imageHeight && y >= plan.top + plan.imageHeight - overlap
        return nearLeft || nearTop || nearRight || nearBottom
    }
}
