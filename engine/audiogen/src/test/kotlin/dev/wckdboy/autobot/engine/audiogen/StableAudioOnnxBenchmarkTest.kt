package dev.wckdboy.autobot.engine.audiogen

import dev.wckdboy.autobot.shared.audiogen.AudioGenRequest
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.audiogen.Wav
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real model on the desktop ONNX Runtime when `-Paudiogen.dir=<installed Stable Audio
 * ONNX folder>` is given (skipped otherwise): checks the audio is sound, prints the time per stage,
 * the real-time factor and the process's peak memory. Writes `audiogen-test.wav` into that folder.
 */
class StableAudioOnnxBenchmarkTest {
    @Test
    fun generatesAClip() = runBlocking {
        val dir = System.getProperty("audiogen.dir")?.let(::File)?.takeIf { StableAudioOnnx.installed(it) }
        assumeTrue("set -Paudiogen.dir to an installed Stable Audio folder", dir != null)
        val gen = LocalAudioGen(keepLoaded = true)
        val request = AudioGenRequest("128 BPM tech house drum loop", seconds = 10.0, seed = 42)
        val marks = mutableListOf<Pair<String, Long>>()
        val start = System.nanoTime()
        val audio = gen.generate(dir!!, request) { p -> marks += "${p.stage} ${p.step}" to System.nanoTime() }
        val cold = (System.nanoTime() - start) / 1e9
        marks.zipWithNext().forEach { (a, b) -> println("audiogen onnx: %-12s → %-12s %.2f s".format(a.first, b.first, (b.second - a.second) / 1e9)) }

        val warmStart = System.nanoTime()
        val again = gen.generate(dir, request)
        val warm = (System.nanoTime() - warmStart) / 1e9
        gen.unload()
        println(
            "audiogen onnx, %d threads: %.1f s audio, cold %.2f s (RTF %.2f), warm %.2f s (RTF %.2f), peak RSS %s".format(
                StableAudioOnnx.defaultThreads(), audio.seconds, cold, cold / audio.seconds, warm, warm / audio.seconds, peakRss(),
            ),
        )

        assertEquals(10 * StableAudio.SAMPLE_RATE, audio.frames)
        assertTrue("same seed, same audio", audio.left.contentEquals(again.left))
        for (channel in listOf(audio.left, audio.right)) {
            assertTrue(channel.all { it.isFinite() })
            val peak = channel.maxOf { abs(it) }
            val rms = sqrt(channel.sumOf { (it * it).toDouble() } / channel.size)
            println("audiogen onnx: peak %.3f rms %.3f".format(peak, rms))
            assertTrue("peak $peak rms $rms", peak > 0.5f && rms > 0.02)
        }
        // Stereo, not two copies of one channel.
        assertTrue(audio.left.indices.any { abs(audio.left[it] - audio.right[it]) > 1e-3f })
        File(dir, "audiogen-test.wav").writeBytes(Wav.encode16(audio))
    }

    private fun rssMb(): Long = runCatching {
        ProcessBuilder("ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()).start().inputStream.bufferedReader().readText().trim().toLong() / 1024
    }.getOrDefault(0L)

    private val baseline = rssMb()

    /** Peak resident set of the test process, sampled every 100 ms (ps). */
    @Volatile
    private var peakMb = baseline

    init {
        Thread {
            while (true) {
                peakMb = maxOf(peakMb, rssMb())
                Thread.sleep(100)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun peakRss(): String = "$peakMb MB (before loading $baseline MB)"
}
