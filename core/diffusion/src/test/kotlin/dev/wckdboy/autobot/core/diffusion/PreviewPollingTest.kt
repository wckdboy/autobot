package dev.wckdboy.autobot.core.diffusion

import dev.wckdboy.autobot.core.diffusion.engine.NpuImages
import dev.wckdboy.autobot.core.diffusion.engine.PreviewPolling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPollingTest {

    /** Simulates polls against a backend at the given steps; returns how many previews were requested and decoded. */
    private fun run(steps: List<Int>): Pair<Int, Int> {
        val p = PreviewPolling()
        var requested = 0
        var decoded = 0
        for (step in steps) {
            val withImage = p.wantImage()
            if (withImage) requested++
            val key = 0 to step
            if (!p.isNew(key, withImage)) continue
            val decode = p.decode(key, withImage)
            if (decode) decoded++
            p.record(key, previewed = decode)
        }
        return requested to decoded
    }

    @Test
    fun aStuckSamplerIsPolledWithoutItsPreview() {
        // Loading the model / VAE decode: the same step for 20 polls.
        val (requested, decoded) = run(listOf(1) + List(20) { 1 })
        assertEquals(1, requested)
        assertEquals(1, decoded)
    }

    @Test
    fun aMovingSamplerGetsAFreshPreviewAtMostEveryOtherPoll() {
        val (requested, decoded) = run((1..20).toList())
        // Before: 20 previews fetched and decoded.
        assertEquals(10, requested)
        assertEquals(10, decoded)
    }

    @Test
    fun newStepWithoutPreviewIsStillReportedAndNextPollAsksForTheImage() {
        val p = PreviewPolling()
        assertTrue(p.wantImage())
        p.record(0 to 1, previewed = true)
        assertFalse(p.wantImage())
        assertTrue(p.isNew(0 to 2, withImage = false))
        assertFalse(p.decode(0 to 2, withImage = false))
        p.record(0 to 2, previewed = false)
        assertTrue(p.wantImage())
    }

    @Test
    fun initImagesAreSubsampledToTheTargetSize() {
        // A 12 MP photo for a 512² img2img: decoded at 1/4 (1008×756 → still ≥ 512).
        assertEquals(4, NpuImages.sampleSize(4032, 3024, 512, 512))
        assertEquals(1, NpuImages.sampleSize(512, 512, 512, 512))
        assertEquals(1, NpuImages.sampleSize(300, 300, 512, 512))
        assertEquals(2, NpuImages.sampleSize(2048, 1024, 512, 512))
    }
}
