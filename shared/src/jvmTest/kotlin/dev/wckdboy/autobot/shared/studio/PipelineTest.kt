package dev.wckdboy.autobot.shared.studio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {
    private class Rig(val queue: JobQueue, val sink: MemorySink, val runner: PipelineRunner)

    private fun TestScope.rig(vararg engines: StudioEngine): Rig {
        val registry = EngineRegistry().apply { engines.forEach(::register) }
        val sink = MemorySink()
        var n = 0
        val queue = JobQueue(backgroundScope, registry, sink, clock = { 1L }, ids = { "j-${++n}" })
        return Rig(queue, sink, PipelineRunner(queue))
    }

    @Test
    fun txt2imgThenUpscaleBindsTheImage() = runTest {
        val diffusion = FakeEngine(EngineKinds.DIFFUSION)
        val upscale = FakeEngine(EngineKinds.UPSCALE)
        val r = rig(diffusion, upscale)
        val result = r.runner.run(BuiltInPipelines.TXT2IMG_UPSCALE, "p-1", mapOf("prompt" to "a lighthouse"), seed = 10)
        assertTrue(result.ok)
        val image = result.steps.first { it.stepId == "image" }.assetIds.single()
        val up = upscale.ran.single()
        assertEquals(listOf(AssetRef(image)), up.recipe.inputs)
        assertEquals(image, up.parentAssetId)
        assertEquals(ParamValue.of(2), up.recipe.params["scale"])
        assertEquals("a lighthouse", diffusion.ran.single().recipe.string("prompt"))
        assertEquals("empty optional variables leave no trace", "", diffusion.ran.single().recipe.string("negative"))
        assertEquals("Text → image → upscale · Upscale", up.label)
        assertEquals(2, result.assetIds.size)
        assertEquals(image, r.sink.store.asset(result.assetIds.last())!!.parentId)
    }

    @Test
    fun variationsRunFourTimesOnTheInputWithWalkingSeeds() = runTest {
        val diffusion = FakeEngine(EngineKinds.DIFFUSION)
        val r = rig(diffusion)
        val result = r.runner.run(BuiltInPipelines.IMAGE_VARIATIONS, "p-1", input = listOf("a-src"), seed = 500)
        assertTrue(result.ok)
        assertEquals(4, result.assetIds.size)
        assertEquals(listOf(500L, 501L, 502L, 503L), diffusion.ran.map { it.recipe.seed })
        assertTrue(diffusion.ran.all { it.recipe.inputs == listOf(AssetRef("a-src")) && it.parentAssetId == "a-src" })
        assertEquals(ParamValue.of(0.55), diffusion.ran.first().recipe.params["strength"])
        assertEquals("Image → 4 variations · Variation 3/4", diffusion.ran[2].label)
    }

    @Test
    fun imageAndAmbienceRunSideBySideOnTheirOwnEngines() = runTest {
        val gate = CompletableDeferred<Unit>()
        val diffusion = FakeEngine(EngineKinds.DIFFUSION, backendId = "pc", gate = gate)
        val audio = FakeEngine(EngineKinds.AUDIO, output = AssetType.AUDIO, gate = gate)
        val r = rig(diffusion, audio)
        val run = async { r.runner.run(BuiltInPipelines.IMAGE_WITH_AMBIENCE, "p-1", mapOf("prompt" to "rain on a tin roof")) }
        runCurrent()
        assertEquals("both steps started before either finished", 2, r.queue.jobs.value.count { it.state == JobState.RUNNING })
        gate.complete(Unit)
        val result = run.await()
        assertTrue(result.ok)
        assertEquals("ambient soundscape, rain on a tin roof", audio.ran.single().recipe.string("prompt"))
        assertEquals(AssetType.AUDIO, audio.ran.single().recipe.output)
        assertEquals(AssetType.AUDIO, r.sink.store.asset(result.steps.last().assetIds.single())!!.type)
    }

    @Test
    fun voiceoverUsesTheDefaultVoice() = runTest {
        val speech = FakeEngine(EngineKinds.SPEECH, output = AssetType.AUDIO)
        val r = rig(speech)
        val result = r.runner.run(BuiltInPipelines.VOICEOVER, "p-1", mapOf("text" to "Hello there."))
        assertTrue(result.ok)
        val recipe = speech.ran.single().recipe
        assertEquals("Hello there.", recipe.string("text"))
        assertEquals("af_heart", recipe.string("voice"))
    }

    @Test
    fun aFailedStepSkipsItsDependents() = runTest {
        val diffusion = FakeEngine(EngineKinds.DIFFUSION, fail = "No model installed")
        val upscale = FakeEngine(EngineKinds.UPSCALE)
        val r = rig(diffusion, upscale)
        val steps = ArrayList<StepResult>()
        val result = r.runner.run(BuiltInPipelines.TXT2IMG_UPSCALE, "p-1", mapOf("prompt" to "x")) { steps += it }
        assertFalse(result.ok)
        assertEquals(StepState.FAILED, result.steps[0].state)
        assertEquals("No model installed", result.error)
        assertEquals(StepState.SKIPPED, result.steps[1].state)
        assertTrue(upscale.ran.isEmpty())
        assertEquals(listOf("image", "upscale"), steps.map { it.stepId })
    }

    @Test
    fun missingVariablesInputsAndBadReferencesAreRefused() = runTest {
        val r = rig(FakeEngine())
        fun refused(block: suspend () -> Unit): String = try {
            kotlinx.coroutines.runBlocking { block() }
            fail()
            ""
        } catch (e: PipelineException) {
            e.message!!
        }
        assertEquals("Text → image → upscale needs prompt", refused { r.runner.run(BuiltInPipelines.TXT2IMG_UPSCALE, "p") })
        assertEquals("Image → 4 variations needs an input image", refused { r.runner.run(BuiltInPipelines.IMAGE_VARIATIONS, "p") })
        val loop = Pipeline("x", "Loop", steps = listOf(PipelineStep("a", "A", EngineKinds.DIFFUSION, inputs = listOf(StepInput("b"))), PipelineStep("b", "B", EngineKinds.DIFFUSION)))
        assertEquals("Step a takes input from b, which does not run before it", refused { r.runner.run(loop, "p") })
    }

    @Test
    fun cancellingTheRunCancelsItsJobs() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = rig(FakeEngine(gate = gate))
        val run = async { r.runner.run(BuiltInPipelines.IMAGE_VARIATIONS, "p", input = listOf("a-src")) }
        runCurrent()
        assertEquals(4, r.queue.jobs.value.size)
        run.cancel()
        runCurrent()
        assertTrue(r.queue.jobs.value.all { it.state == JobState.CANCELLED })
    }

    @Test
    fun builtInsSerialize() {
        for (p in BuiltInPipelines.all) {
            val json = StudioJson.encodeToString(Pipeline.serializer(), p)
            assertEquals(p, StudioJson.decodeFromString(Pipeline.serializer(), json))
        }
        assertEquals(4, BuiltInPipelines.all.map { it.id }.toSet().size)
    }
}
