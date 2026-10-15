package dev.wckdboy.autobot.shared.audiogen

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioSystem
import org.junit.Assert.assertEquals
import org.junit.Test

class WavTest {
    @Test
    fun writesA16BitStereoHeader() {
        val wav = Wav.encode16(StereoAudio(floatArrayOf(0f, 1f, -1f), floatArrayOf(0.5f, -0.5f, Float.NaN)))
        assertEquals(44 + 3 * 2 * 2, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 12, b.getInt(4))
        assertEquals("WAVEfmt ", String(wav, 8, 8))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(2, b.getShort(22).toInt())
        assertEquals(44_100, b.getInt(24))
        assertEquals(44_100 * 4, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(12, b.getInt(40))
        val samples = (0 until 6).map { b.getShort(44 + 2 * it).toInt() }
        assertEquals(listOf(0, 16384, 32767, -16383, -32767, 0), samples)
    }

    @Test
    fun clampsAndRounds() {
        assertEquals(32767, Wav.sample16(3f))
        assertEquals(-32767, Wav.sample16(-3f))
        assertEquals(0, Wav.sample16(Float.POSITIVE_INFINITY))
        assertEquals(3, Wav.sample16(0.0001f))
    }

    @Test
    fun javaSoundReadsIt() {
        val left = FloatArray(4410) { kotlin.math.sin(it * 0.05f) }
        val wav = Wav.encode16(StereoAudio(left, left.copyOf()))
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))
        assertEquals(2, stream.format.channels)
        assertEquals(44_100f, stream.format.sampleRate, 0f)
        assertEquals(16, stream.format.sampleSizeInBits)
        assertEquals(4410L, stream.frameLength)
    }
}
