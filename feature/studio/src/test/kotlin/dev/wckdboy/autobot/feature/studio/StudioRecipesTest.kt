package dev.wckdboy.autobot.feature.studio

import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.HiresFix
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.Outpaint
import dev.wckdboy.autobot.feature.studio.media.WavFile
import dev.wckdboy.autobot.shared.audiogen.Wav
import dev.wckdboy.autobot.shared.studio.AssetRef
import dev.wckdboy.autobot.shared.studio.GridAxis
import dev.wckdboy.autobot.shared.studio.GridPlanner
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.StudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRecipesTest {
    private val request = DiffusionRequest(
        prompt = "a fox",
        negativePrompt = "blurry",
        width = 768,
        height = 512,
        steps = 12,
        cfgScale = 4.5f,
        seed = 42,
        sampler = "Euler a",
        scheduler = null,
        loras = listOf(LoraRef("film", 0.6f)),
        model = "sdxl",
        vae = "vae-ft-mse",
        hires = HiresFix(1.5f, "Latent", 8, 0.4f),
    )

    @Test
    fun diffusionRequestRoundTripsThroughARecipe() {
        val recipe = StudioRecipes.diffusionRecipe(request, "pc")
        assertEquals("pc", recipe.backendId)
        assertEquals(42L, recipe.seed)
        // Through JSON too: what the library stores is what "reuse recipe" gets back.
        val back = StudioRecipes.diffusionRequest(StudioCodec.decodeRecipe(StudioCodec.encodeRecipe(recipe)))
        assertEquals(request, back)
    }

    @Test
    fun inputsChooseTheMode() {
        val img2img = StudioRecipes.diffusionRecipe(request.copy(denoiseStrength = 0.3f), null, listOf(AssetRef("a-1", StudioRecipes.ROLE_IMAGE)))
        assertEquals(DiffusionMode.IMG2IMG, StudioRecipes.diffusionRequest(img2img).mode)
        assertEquals(0.3f, StudioRecipes.diffusionRequest(img2img).denoiseStrength)
        val inpaint = img2img.withInput("a-2", StudioRecipes.ROLE_MASK)
        assertEquals(DiffusionMode.INPAINT, StudioRecipes.diffusionRequest(inpaint).mode)
        assertNull(StudioRecipes.diffusionRecipe(request.copy(seed = -1), null).seed)
    }

    @Test
    fun aGridCanVaryALorasWeight() {
        val base = StudioRecipes.diffusionRecipe(request, null)
        val plan = GridPlanner.plan(base, GridAxis(StudioRecipes.LORA_PREFIX + "film", GridPlanner.parseValues("0-1 (+0.5)")))
        assertEquals(listOf(0f, 0.5f, 1f), plan.jobs.map { StudioRecipes.diffusionRequest(it.recipe).loras.single().weight })
        val models = GridPlanner.plan(base, GridAxis("model", listOf(ParamValue.of("a"), ParamValue.of("b"))))
        assertEquals(listOf("a", "b"), models.jobs.map { StudioRecipes.diffusionRequest(it.recipe).model })
    }

    @Test
    fun audioAndSpeechRecipes() {
        val audio = StudioRecipes.audioRecipe("rain", 8.0, 8, 1f, 7)
        assertEquals("8 s · 8 steps · seed 7", StudioRecipes.summary(audio))
        assertEquals("rain", StudioRecipes.title(audio))
        val speech = StudioRecipes.speechRecipe("hello there", "af_heart", 1.25f)
        assertEquals("af_heart · ×1.25", StudioRecipes.summary(speech))
    }

    @Test
    fun wavDecodesPeaksAndTrims() {
        val rate = 1000
        val left = FloatArray(rate) { if (it < 500) 0.5f else -0.25f }
        val right = FloatArray(rate) { 0.1f }
        val pcm = WavFile.decode(Wav.encode16(listOf(left, right), rate))!!
        assertEquals(2, pcm.channels.size)
        assertEquals(1000L, pcm.durationMs)
        val peaks = WavFile.peaks(pcm, 4)
        assertEquals(0.5f, peaks[0], 0.01f)
        assertEquals(0.25f, peaks[3], 0.01f)
        WavFile.decodePeaks(WavFile.encodePeaks(peaks)).forEachIndexed { i, p -> assertEquals(peaks[i], p, 1f / 255) }
        val trimmed = WavFile.decode(WavFile.trim(pcm, 250, 750))!!
        assertEquals(500, trimmed.frames)
        assertNull(WavFile.decode("not a wav".encodeToByteArray()))
    }

    @Test
    fun outpaintPlansACanvasAndItsMask() {
        val plan = Outpaint.plan(512, 512, left = 0, top = 0, right = 130, bottom = 0, multiple = 8)
        assertEquals(648, plan.width)
        assertEquals(512, plan.height)
        assertEquals(0, plan.left)
        assertTrue(Outpaint.masked(plan, 600, 100))
        assertTrue(Outpaint.masked(plan, 505, 100)) // the seam is repainted
        assertFalse(Outpaint.masked(plan, 100, 100))
        val left = Outpaint.plan(512, 512, left = 100, top = 0, right = 0, bottom = 0)
        assertEquals(616, left.width)
        assertEquals(104, left.left) // rounding slack on the extended side
    }
}
