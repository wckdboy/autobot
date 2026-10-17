package dev.wckdboy.autobot.core.diffusion.engine

import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.engine.diffusion.LocalSdEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceEventsTest {
    private var now = 1_000L
    private val events = OnDeviceEngine.SdEvents { now }

    private fun progress(event: LocalSdEvent) = events.map(event) as DiffusionEvent.Progress

    @Test
    fun loadingIsAPhaseAndSamplingIsNot() {
        assertEquals(OnDeviceEngine.LOADING, (events.map(LocalSdEvent.Status("loading")) as DiffusionEvent.Phase).text)
        assertNull(events.map(LocalSdEvent.Status("sampling")))
        assertEquals(OnDeviceEngine.DECODING, OnDeviceEngine.phaseOf("decoding"))
        assertNull(OnDeviceEngine.phaseOf("denoising"))
    }

    @Test
    fun aBatchAdvancesTheImageAsResultsArrive() {
        assertEquals(0, progress(LocalSdEvent.Progress(3, 20)).image)
        now = 4_000L
        val first = events.map(LocalSdEvent.Image(0, byteArrayOf(1), 7, 512, 512)) as DiffusionEvent.Result
        assertEquals(3_000L, first.durationMs)
        // sd.cpp samples the second image next, without saying which one it is.
        val next = progress(LocalSdEvent.Progress(1, 20))
        assertEquals(1, next.image)
        assertEquals(1, next.step)
    }

    @Test
    fun aPreviewHasNoStep() {
        events.map(LocalSdEvent.Image(0, byteArrayOf(1), 7, 512, 512))
        val preview = progress(LocalSdEvent.Preview(byteArrayOf(9)))
        assertEquals(1, preview.image)
        assertTrue(preview.step < 0 && preview.totalSteps < 0)
        assertEquals(9, preview.preview!!.single().toInt())
    }
}
