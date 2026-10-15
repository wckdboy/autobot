package dev.wckdboy.autobot.engine.tts

import dev.wckdboy.autobot.shared.tts.KokoroFrontend
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real model on the desktop ONNX Runtime when `-Pkokoro.dir=<installed Kokoro folder>`
 * is given (skipped otherwise): checks the audio is sound and prints the real-time factor. Writes
 * `kokoro-test.wav` into that folder to listen to.
 */
class KokoroOnnxBenchmarkTest {
    @Test
    fun synthesizesASentence() {
        val dir = System.getProperty("kokoro.dir")?.let(::File)?.takeIf { File(it, LocalVoice.MODEL).isFile }
        assumeTrue("set -Pkokoro.dir to an installed Kokoro folder", dir != null)
        val frontend = KokoroFrontend.load(dir!!.path)
        val voice = KokoroOnnx.readVoice(File(dir, "af_heart.bin"))
        val text = "Kokoro runs entirely on your phone. It turns 82 million parameters into speech, " +
            "and nothing you say ever leaves the device."
        for (threads in listOf(1, 2, 4)) {
            KokoroOnnx(File(dir, LocalVoice.MODEL), threads).use { model ->
                val chunks = frontend.chunks(text)
                model.run(chunks.first(), voice) // warm-up
                val start = System.nanoTime()
                val audio = chunks.map { model.run(it, voice) }
                val seconds = (System.nanoTime() - start) / 1e9
                val samples = audio.sumOf { it.size }
                val duration = samples / KokoroFrontend.SAMPLE_RATE.toDouble()
                println("kokoro onnx q8, $threads threads: %.2f s audio in %.2f s, RTF %.3f (%d chunks)".format(duration, seconds, seconds / duration, chunks.size))
                assertTrue(duration in 4.0..20.0)
                val all = audio.flatMap { it.asList() }
                val peak = all.maxOf { kotlin.math.abs(it) }
                val rms = kotlin.math.sqrt(all.sumOf { (it * it).toDouble() } / all.size)
                assertTrue("peak $peak rms $rms", peak in 0.05f..1.5f && rms > 0.01)
                if (threads == 4) writeWav(File(dir, "kokoro-test.wav"), all.toFloatArray())
            }
        }
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            fun le32(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = f.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            f.write("RIFF".toByteArray()); le32(36 + pcm.size * 2); f.write("WAVEfmt ".toByteArray())
            le32(16); le16(1); le16(1); le32(KokoroFrontend.SAMPLE_RATE); le32(KokoroFrontend.SAMPLE_RATE * 2); le16(2); le16(16)
            f.write("data".toByteArray()); le32(pcm.size * 2)
            val out = ByteArray(pcm.size * 2)
            pcm.forEachIndexed { i, v -> val s = (v.coerceIn(-1f, 1f) * 32767).toInt(); out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte() }
            f.write(out)
        }
    }
}
