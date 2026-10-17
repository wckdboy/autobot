package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationStateTest {
    private val request = DiffusionRequest(prompt = "a {red|blue} cat", steps = 20, batchCount = 2, seed = 42)
    private val running = GenerationState.Running(GenerationSource.USER, "This phone", request, totalSteps = 20, startedAt = 0)

    @Test
    fun aPreviewKeepsTheStepWhereItIs() {
        val sampling = running.on(DiffusionEvent.Progress(0, 5, 20))
        // stable-diffusion.cpp sends previews apart from steps (step -1): the bar used to jump back to "loading".
        val previewed = sampling.on(DiffusionEvent.Progress(0, -1, -1, byteArrayOf(1)))
        assertEquals(5, previewed.step)
        assertEquals(20, previewed.totalSteps)
        assertEquals(1, previewed.preview!!.single().toInt())
    }

    @Test
    fun theLoadIsAPhaseUntilTheFirstStep() {
        val loading = running.on(DiffusionEvent.Phase("Loading the model"))
        assertEquals("Loading the model", loading.phase)
        // A preview is not a step: still loading.
        assertEquals("Loading the model", loading.on(DiffusionEvent.Progress(0, -1, -1, byteArrayOf(1))).phase)
        assertNull(loading.on(DiffusionEvent.Progress(0, 1, 20)).phase)
    }

    @Test
    fun wildcardsRunOneCallPerPromptCountedAsOneJob() {
        val calls = request.calls()
        assertEquals(listOf("a red cat", "a blue cat"), calls.map { it.prompt })
        assertEquals(listOf(2, 2), calls.map { it.batchCount })
        assertEquals(listOf(42L, 42L), calls.map { it.seed })
        assertEquals(listOf(request.copy(prompt = "plain")), request.copy(prompt = "plain").calls())

        val job = running.copy(totalImages = calls.sumOf { it.batchCount })
        val second = job.copy(done = listOf("g1", "g2")).next(calls[1])
        assertEquals("a blue cat", second.request.prompt)
        assertEquals(2, second.image)
        // The second call's own image 1 is the job's fourth.
        assertEquals(3, second.on(DiffusionEvent.Progress(1, 4, 20)).image)
        assertEquals(4, second.totalImages)
    }
}
