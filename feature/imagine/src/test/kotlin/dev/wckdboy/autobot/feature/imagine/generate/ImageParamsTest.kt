package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.diffusion.Aspect
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.core.diffusion.SizeSupport
import dev.wckdboy.autobot.core.models.Recommended
import dev.wckdboy.autobot.feature.imagine.gallery.GalleryViewModel
import dev.wckdboy.autobot.feature.imagine.viewer.GalleryImages
import dev.wckdboy.autobot.feature.imagine.viewer.ViewerAction
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The studio's size and handoff plumbing between the UI, the agent tool, the gallery and the engines. */
class ImageParamsTest {
    private val base1024 = DiffusionRequest(prompt = "x", width = 1024, height = 1024, steps = 8, cfgScale = 1f, sampler = "LCM", model = "sdxl")

    @Test
    fun choosingAModelKeepsTheShapeTheUserPicked() {
        val square = Recommended(steps = 8, cfg = 1f, sampler = "LCM", width = 1024, height = 1024)
        // 16:9 picked, then the model chip tapped: it used to reset to 1024² (the reported bug).
        val wide = DiffusionRequest(width = 912, height = 512).withRecommended("sdxl", square, SizeSupport.SD_CPP)
        assertEquals(1368 to 768, wide.width to wide.height)
        assertEquals(8, wide.steps)
        // A fresh request takes the model's own size.
        assertEquals(1024 to 1024, DiffusionRequest().withRecommended("sdxl", square).let { it.width to it.height })
        // A QNN package renders its square: the 16:9 shape is its widest band.
        val fixed = SizeSupport(fixedSide = 1024)
        assertEquals(1024 to 576, DiffusionRequest(width = 912, height = 512).withRecommended("q", square, fixed).let { it.width to it.height })
        // A portrait recommendation on an untouched request is kept as is.
        val portrait = Recommended(width = 832, height = 1216)
        assertEquals(832 to 1216, DiffusionRequest().withRecommended("p", portrait).let { it.width to it.height })
    }

    @Test
    fun anInputImageKeepsItsShapeAtTheCurrentBudget() {
        assertEquals(1368 to 768, initSize(base1024, SizeSupport.SD_CPP, 1920, 1080))
        assertEquals(768 to 1368, initSize(base1024, SizeSupport.SD_CPP, 1080, 1920))
        assertEquals(1024 to 576, initSize(base1024, SizeSupport(fixedSide = 1024), 1920, 1080))
        assertEquals(512 to 512, initSize(DiffusionRequest(), SizeSupport.SD_CPP, 4000, 4000))
    }

    @Test
    fun theAgentToolKeepsTheShape() {
        fun req(vararg pairs: Pair<String, Any>) = GenerateImageTool.agentRequest(
            base1024,
            buildJsonObject {
                put("prompt", "a harbour at dusk")
                pairs.forEach { (k, v) -> if (v is Number) put(k, JsonPrimitive(v)) else put(k, v.toString()) }
            },
        )
        assertEquals(1368 to 768, req("aspect_ratio" to "16:9").let { it.width to it.height })
        assertEquals(768 to 1368, req("aspect_ratio" to "9:16").let { it.width to it.height })
        assertEquals(1024 to 1024, req().let { it.width to it.height })
        assertEquals(1344 to 1344, req("width" to 1344).let { it.width to it.height })
        assertEquals(1344 to 760, req("width" to 1344, "aspect_ratio" to "wide").let { it.width to it.height })
        assertEquals(1216 to 832, req("width" to 1216, "height" to 832).let { it.width to it.height })
        val r = req("count" to 9, "steps" to 500, "seed" to 7, "negative_prompt" to "fog")
        assertEquals(4, r.batchCount)
        assertEquals(100, r.steps)
        assertEquals(7L, r.seed)
        assertEquals("fog", r.negativePrompt)
        assertEquals(DiffusionMode.TXT2IMG, r.mode)
        // The user's sampler, guidance and model carry over.
        assertEquals("LCM", r.sampler)
        assertEquals(1f, r.cfgScale)
        assertEquals("sdxl", r.model)
    }

    @Test
    fun aFixedSizeResultIsCutToTheRequestedShape() {
        val wide = DiffusionRequest(width = 1368, height = 768)
        assertArrayEquals(intArrayOf(0, 224, 1024, 576), shapeCrop(wide, 1024, 1024))
        assertNull(shapeCrop(wide, 1368, 768))
        assertNull(shapeCrop(DiffusionRequest(width = 512, height = 512), 512, 512))
        // An input-image job keeps the backend's framing.
        assertNull(shapeCrop(wide.copy(mode = DiffusionMode.IMG2IMG), 1024, 1024))
    }

    private fun item(id: String = "g1", mode: String = "TXT2IMG") = GalleryItem(
        id = id,
        createdAt = 0,
        mode = mode,
        prompt = "a red fox",
        negativePrompt = "blurry",
        width = 1368,
        height = 768,
        seed = 1234,
        steps = 30,
        cfgScale = 6f,
        sampler = "DPM++ 2M · Karras",
        engine = "This phone",
        model = "sdxl",
        durationMs = 1000,
        paramsJson = "{}",
    )

    @Test
    fun handoffsCarryTheSettingsForEachAction() {
        val params = DiffusionRequest(prompt = "a red fox", negativePrompt = "blurry", width = 1368, height = 768, steps = 30, cfgScale = 6f, seed = 1234, batchCount = 4, loras = listOf(LoraRef("film", 0.6f)), model = "sdxl")
        val reuse = GalleryImages.handoffRequest(item(), params, ViewerAction.REUSE)
        assertEquals(-1L, reuse.seed)
        assertEquals(DiffusionMode.TXT2IMG, reuse.mode)
        assertEquals(1368 to 768, reuse.width to reuse.height)
        assertEquals(1, reuse.batchCount)
        assertEquals(listOf(LoraRef("film", 0.6f)), reuse.loras)
        assertEquals(1234L, GalleryImages.handoffRequest(item(), params, ViewerAction.SAME_SEED).seed)
        val img2img = GalleryImages.handoffRequest(item(), params, ViewerAction.IMG2IMG)
        assertEquals(DiffusionMode.IMG2IMG, img2img.mode)
        assertEquals("a red fox", img2img.prompt)
        assertEquals(params.denoiseStrength, img2img.denoiseStrength)
        assertEquals(DiffusionMode.INPAINT, GalleryImages.handoffRequest(item(), params, ViewerAction.INPAINT).mode)
        val vary = GalleryImages.handoffRequest(item(), params, ViewerAction.VARY)
        assertEquals(DiffusionMode.IMG2IMG, vary.mode)
        assertEquals(GalleryImages.VARY_DENOISE, vary.denoiseStrength)
        // Rows from before params were stored still hand over their columns.
        val old = GalleryImages.handoffRequest(item(), null, ViewerAction.SAME_SEED)
        assertEquals(30, old.steps)
        assertEquals(6f, old.cfgScale)
        assertEquals("blurry", old.negativePrompt)
        assertEquals("sdxl", old.model)
    }

    @Test
    fun unsavedIncognitoImagesOfferNothingThatWritesACopy() {
        val unsaved = ViewerAction.forImage(IncognitoImages.ID_PREFIX + "1", canUpscale = true)
        assertTrue(ViewerAction.KEEP in unsaved)
        assertTrue(ViewerAction.IMG2IMG in unsaved)
        listOf(ViewerAction.SAVE, ViewerAction.SHARE, ViewerAction.UPSCALE, ViewerAction.DELETE, ViewerAction.FAVORITE).forEach { assertFalse("$it", it in unsaved) }
        val saved = ViewerAction.forImage("g1", canUpscale = false)
        assertFalse(ViewerAction.KEEP in saved)
        assertFalse(ViewerAction.UPSCALE in saved)
        assertTrue(ViewerAction.SAVE in saved && ViewerAction.SHARE in saved && ViewerAction.DELETE in saved)
        assertTrue(ViewerAction.UPSCALE in ViewerAction.forImage("g1", canUpscale = true))
    }

    @Test
    fun viewerPagesFollowTheScope() {
        val a = item("a")
        val b = item("b")
        val c = item("c")
        val u = item(IncognitoImages.ID_PREFIX + "u")
        val byId = listOf(a, b, c).associateBy { it.id }
        // A chat's batch, unsaved images included, in its order.
        assertEquals(listOf("c", u.id, "a"), GalleryViewModel.viewerPages(listOf("c", u.id, "a"), byId, mapOf(u.id to u), listOf(a, b, c), "c").map { it.id })
        // The grid.
        assertEquals(listOf("a", "b"), GalleryViewModel.viewerPages(emptyList(), byId, emptyMap(), listOf(a, b), "a").map { it.id })
        // An image filtered out of the grid still opens.
        assertEquals(listOf("c"), GalleryViewModel.viewerPages(emptyList(), byId, emptyMap(), listOf(a, b), "c").map { it.id })
    }

    @Test
    fun aspectChipsShowTheirSizes() {
        val sizes = SizeSupport.SD_CPP
        assertEquals(1024, sizes.baseOf(1368, 768))
        assertEquals(Aspect.ALL.size, Aspect.ALL.map { sizes.size(1024, it) }.toSet().size)
    }
}
