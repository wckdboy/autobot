package dev.wckdboy.autobot.core.diffusion.engine

import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sizes and step counts stable-diffusion.cpp can't take are refused with a message, never sent to the engine. */
class OnDeviceValidationTest {
    private fun problem(request: DiffusionRequest) = OnDeviceEngine.validate(request, maxSteps = 60)

    @Test
    fun usualRequestsPass() {
        assertNull(problem(DiffusionRequest()))
        assertNull(problem(DiffusionRequest(width = 1024, height = 768, steps = 60)))
        assertNull(problem(DiffusionRequest(width = 64, height = 2048, steps = 1)))
    }

    @Test
    fun badSizesSayWhatWorks() {
        listOf(0 to 512, 512 to -8, 4096 to 512, 32 to 32).forEach { (w, h) ->
            val message = problem(DiffusionRequest(width = w, height = h))
            assertTrue("$w×$h: $message", message!!.contains("64–2048 px"))
        }
        val odd = problem(DiffusionRequest(width = 513, height = 512))
        assertTrue(odd!!, odd.contains("multiple of 8"))
    }

    @Test
    fun badStepsSayTheRange() {
        listOf(0, -1, 61, 1000).forEach { steps ->
            val message = problem(DiffusionRequest(steps = steps))
            assertTrue("$steps: $message", message!!.contains("between 1 and 60"))
        }
    }

    @Test
    fun denoiseStrengthMattersOnlyWithAnInputImage() {
        assertNull(problem(DiffusionRequest(denoiseStrength = 3f)))
        val message = problem(DiffusionRequest(mode = DiffusionMode.IMG2IMG, denoiseStrength = 1.5f))
        assertTrue(message!!, message.contains("between 0 and 1"))
    }
}
