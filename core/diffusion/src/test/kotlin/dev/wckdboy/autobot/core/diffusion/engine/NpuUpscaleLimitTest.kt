package dev.wckdboy.autobot.core.diffusion.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Upscaling an upscaled image (4096² → 16384², a 1 GB pixel array) is refused up front. */
class NpuUpscaleLimitTest {
    @Test
    fun allowsUpTo4096Out() {
        assertNull(NpuImages.upscaleProblem(512, 768))
        assertNull(NpuImages.upscaleProblem(1024, 1024))
    }

    @Test
    fun refusesWhatWouldExceedIt() {
        assertNotNull(NpuImages.upscaleProblem(2048, 2048))
        assertNotNull(NpuImages.upscaleProblem(4096, 512))
    }
}
