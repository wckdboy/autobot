package dev.wckdboy.autobot.core.diffusion

import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.NpuImages
import dev.wckdboy.autobot.core.diffusion.engine.OnDeviceEngine
import dev.wckdboy.autobot.core.diffusion.engine.SdApiEngine
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.NpuPackage
import kotlin.math.abs
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shape × pixel budget × backend, down to the request each engine is sent: a 16:9 choice
 * is landscape at the backend, never square (the bug: "16:9 at 1024 gives a square image").
 */
class ImageSizesTest {

    private val qnnXl = NpuPackage(arch = "sdxl", runtime = "qnn")
    private val qnn15 = NpuPackage(arch = "sd15", runtime = "qnn")
    private val mnn = NpuPackage(arch = "sd15", runtime = "mnn")

    private fun shapeOf(w: Int, h: Int) = w.toDouble() / h

    private fun assertShape(what: String, aspect: Aspect, w: Int, h: Int, tolerance: Double = 0.04) {
        assertTrue("$what: ${w}×$h is not ${aspect.label}", abs(shapeOf(w, h) - aspect.ratio) / aspect.ratio < tolerance)
        if (aspect.w > aspect.h) assertTrue("$what: ${w}×$h is not landscape", w > h)
        if (aspect.w < aspect.h) assertTrue("$what: ${w}×$h is not portrait", w < h)
    }

    @Test
    fun sixteenByNineAt1024IsLandscapeOnEveryEngine() {
        val wide = Aspect.parse("16:9")!!
        assertEquals(1368 to 768, SizeSupport.SD_CPP.size(1024, wide))
        // MNN takes multiples of 64: SDXL's own 16:9 bucket.
        assertEquals(1344 to 768, SizeSupport.MNN.size(1024, wide))
        // QNN binaries render 1024² only: 16:9 is the widest band of the square.
        assertEquals(1024 to 576, OnDeviceEngine.npuSizes(qnnXl).size(1024, wide))
        assertEquals(512 to 288, OnDeviceEngine.npuSizes(qnn15).size(512, wide))
        assertEquals(576 to 1024, OnDeviceEngine.npuSizes(qnnXl).size(1024, Aspect.parse("9:16")!!))
    }

    @Test
    fun everyShapeAndBudgetKeepsItsShapeOnEverySizeSupport() {
        val supports = mapOf(
            "sd.cpp" to SizeSupport.SD_CPP,
            "mnn" to SizeSupport.MNN,
            "remote" to SizeSupport(),
            "qnn sdxl" to OnDeviceEngine.npuSizes(qnnXl),
            "qnn sd15" to OnDeviceEngine.npuSizes(qnn15),
        )
        for ((name, sizes) in supports) for (base in SizeSupport.BASES) for (aspect in Aspect.ALL) {
            val (w, h) = sizes.size(base, aspect)
            val what = "$name ${aspect.label} @$base"
            assertShape(what, aspect, w, h, tolerance = if (sizes == SizeSupport.MNN) 0.08 else 0.04)
            assertEquals("$what: ${w}×$h snapped", 0, w % sizes.multiple)
            assertEquals("$what: ${w}×$h snapped", 0, h % sizes.multiple)
            assertTrue("$what in range", w in sizes.min..sizes.max && h in sizes.min..sizes.max)
            // What the engine is asked for comes out as it is (a planned size is a stable one).
            assertEquals(what, w to h, sizes.output(w, h))
            if (!sizes.isFixed) assertTrue("$what keeps the budget", abs(w * h - base * base) < base * base * if (sizes == SizeSupport.MNN) 0.15 else 0.05)
        }
    }

    @Test
    fun outputOfAnySizeKeepsItsShape() {
        // An odd size from elsewhere (a photo, an agent) is snapped and kept in range, not squashed.
        assertEquals(2048 to 1152, SizeSupport.SD_CPP.output(4000, 2250))
        assertEquals(1536 to 896, SizeSupport.MNN.output(1920, 1080))
        assertEquals(1024 to 576, OnDeviceEngine.npuSizes(qnnXl).output(1368, 768))
        assertEquals(1024 to 1024, OnDeviceEngine.npuSizes(qnnXl).output(512, 512))
    }

    @Test
    fun baseFollowsTheRequest() {
        assertEquals(1024, SizeSupport.SD_CPP.baseOf(1368, 768))
        assertEquals(512, SizeSupport.SD_CPP.baseOf(512, 512))
        assertEquals(768, SizeSupport.SD_CPP.baseOf(896, 640))
        assertEquals(listOf(1024), OnDeviceEngine.npuSizes(qnnXl).bases)
    }

    @Test
    fun aspectsParse() {
        assertEquals(Aspect.ALL[6], Aspect.parse("16:9"))
        assertEquals(Aspect.ALL[6], Aspect.parse("16x9"))
        assertEquals(Aspect.ALL[6], Aspect.parse(" 16 / 9 "))
        assertEquals(Aspect.SQUARE, Aspect.parse("square"))
        assertEquals(Aspect.ALL[6], Aspect.parse("wide"))
        assertTrue(Aspect.parse("portrait")!!.let { it.h > it.w })
        assertEquals(21.0 / 9, Aspect.parse("21:9")!!.ratio, 0.01)
        assertNull(Aspect.parse("banana"))
        assertNull(Aspect.parse("10:1"))
        assertEquals(Aspect.ALL[6], Aspect.of(1368, 768))
        assertNull(Aspect.of(1000, 300))
    }

    @Test
    fun resolveSizeNeverLosesTheShape() {
        val square1024 = 1024 to 1024
        // An aspect alone keeps the user's budget.
        assertEquals(1368 to 768, resolveSize(square1024, null, null, Aspect.parse("16:9")))
        // One side alone keeps the shape (the agent asking for width 1344 on a 1024² setup got 1344×1024).
        assertEquals(1344 to 1344, resolveSize(square1024, 1344, null, null))
        assertEquals(1344 to 760, resolveSize(square1024, 1344, null, Aspect.parse("16:9")))
        assertEquals(1368 to 768, resolveSize(square1024, null, 768, Aspect.parse("16:9")))
        assertEquals(1216 to 832, resolveSize(square1024, 1216, 832, Aspect.parse("16:9")))
        assertEquals(1368 to 768, resolveSize(1368 to 768, null, null, null))
    }

    @Test
    fun aFixedSizeResultIsCutToTheShapeAsked() {
        assertArrayEquals(intArrayOf(0, 224, 1024, 576), SizeSupport.centerCrop(1024, 1024, 16.0 / 9))
        assertArrayEquals(intArrayOf(224, 0, 576, 1024), SizeSupport.centerCrop(1024, 1024, 9.0 / 16))
        assertNull(SizeSupport.centerCrop(1368, 768, 1368.0 / 768))
        assertNull(SizeSupport.centerCrop(1344, 768, 1368.0 / 768))
    }

    // -------------------------------------------------------------------------------------------
    // The request each backend gets

    private fun model(engine: EngineKind, npu: NpuPackage? = null, paths: Map<FileRole, String> = mapOf(FileRole.MODEL to "/m/model.gguf")) = InstalledModel(
        id = "m",
        kind = ModelKind.IMAGE,
        source = ModelSource.HUGGING_FACE,
        title = "Model",
        subtitle = "",
        format = if (npu != null) ModelFormat.QNN else ModelFormat.GGUF,
        engine = engine,
        baseModel = null,
        license = null,
        nsfw = false,
        totalBytes = 1,
        downloadedBytes = 1,
        status = ModelStatus.READY,
        error = null,
        manifest = ModelManifest(emptyList(), npu = npu),
        addedAt = 0,
        paths = paths,
    )

    private fun requests(sizes: SizeSupport): List<Triple<String, Aspect, DiffusionRequest>> = SizeSupport.BASES.flatMap { base ->
        Aspect.ALL.map { aspect ->
            val (w, h) = sizes.size(base, aspect)
            Triple("${aspect.label}@$base", aspect, DiffusionRequest(
                prompt = "a lighthouse <lora:film:0.8>",
                negativePrompt = "blurry",
                width = w,
                height = h,
                steps = 28,
                cfgScale = 5.5f,
                seed = 42,
                sampler = "DPM++ 2M SDE",
                scheduler = "Karras",
                batchCount = 3,
                denoiseStrength = 0.35f,
                clipSkip = 2,
                loras = listOf(LoraRef("film", 0.8f)),
            ))
        }
    }

    @Test
    fun stableDiffusionCppGetsEveryParameter() {
        for ((what, aspect, r) in requests(SizeSupport.SD_CPP)) {
            val sd = OnDeviceEngine.sdRequest(model(EngineKind.DIFFUSION), r, emptyList(), "/in.png", "/mask.png")
            assertEquals(what, r.width to r.height, sd.width to sd.height)
            assertShape(what, aspect, sd.width, sd.height)
            assertEquals("a lighthouse", sd.prompt)
            assertEquals("blurry", sd.negativePrompt)
            assertEquals(28, sd.steps)
            assertEquals(5.5f, sd.cfg)
            assertEquals(42L, sd.seed)
            assertEquals("dpm++2m_sde", sd.sampler)
            assertEquals("karras", sd.scheduler)
            assertEquals(3, sd.batch)
            assertEquals(2, sd.clipSkip)
            assertNull("txt2img sends no input image", sd.initImagePath)
            assertNull(OnDeviceEngine.validate(r, 60))
        }
        val img2img = OnDeviceEngine.sdRequest(model(EngineKind.DIFFUSION), requests(SizeSupport.SD_CPP)[0].third.copy(mode = DiffusionMode.IMG2IMG), emptyList(), "/in.png", "/mask.png")
        assertEquals("/in.png", img2img.initImagePath)
        assertEquals(0.35f, img2img.strength)
        assertNull(img2img.maskPath)
        val inpaint = OnDeviceEngine.sdRequest(model(EngineKind.DIFFUSION), requests(SizeSupport.SD_CPP)[0].third.copy(mode = DiffusionMode.INPAINT), emptyList(), "/in.png", "/mask.png")
        assertEquals("/mask.png", inpaint.maskPath)
    }

    @Test
    fun npuPackagesRenderTheShapeAskedFor() {
        for (pkg in listOf(qnnXl, qnn15, mnn)) {
            val sizes = OnDeviceEngine.npuSizes(pkg)
            for ((what, aspect, r) in requests(sizes)) {
                val plan = NpuImages.planFor(pkg, r)
                val label = "${pkg.runtime} ${pkg.arch} $what"
                assertShape(label, aspect, plan.outWidth, plan.outHeight, if (pkg == mnn) 0.08 else 0.04)
                assertEquals(label, r.width to r.height, plan.outWidth to plan.outHeight)
                if (pkg.runtime == "qnn") {
                    // The graph samples its square; the result is the band of it.
                    val side = if (pkg.arch == "sdxl") 1024 else 512
                    assertEquals(label, side to side, plan.width to plan.height)
                    assertEquals(label, r.width != r.height, plan.crops)
                } else {
                    assertFalse(label, plan.crops)
                }
            }
        }
        // A size the user typed for another engine still comes out in its shape on a QNN package.
        val plan = NpuImages.planFor(qnnXl, DiffusionRequest(width = 1368, height = 768))
        assertEquals(1024 to 576, plan.outWidth to plan.outHeight)
        assertEquals(224, plan.top)
        assertEquals(0, plan.left)
    }

    @Test
    fun cutTakesTheCentredBand() {
        val plan = NpuImages.Plan(4, 4, 4, 2)
        val rgb = ByteArray(4 * 4 * 3) { (it / 12).toByte() } // each row filled with its index
        val out = NpuImages.cut(rgb, 4, plan)
        assertEquals(4 * 2 * 3, out.size)
        assertEquals(1, out[0].toInt())
        assertEquals(2, out.last().toInt())
    }

    @Test
    fun npuTraitsTellTheUi() {
        val qnn = OnDeviceEngine.traits(model(EngineKind.NPU, qnnXl, paths = emptyMap()))
        assertEquals(1024, qnn.sizes.fixedSide)
        assertFalse(qnn.runtimeLora)
        assertEquals(setOf(DiffusionMode.TXT2IMG), qnn.modes)
        assertTrue(qnn.sizeNote!!.contains("1024²"))
        val withEncoder = OnDeviceEngine.traits(model(EngineKind.NPU, mnn, paths = mapOf(FileRole.VAE_ENCODER to "/e.mnn")))
        assertEquals(DiffusionMode.entries.toSet(), withEncoder.modes)
        assertEquals(SizeSupport.MNN, withEncoder.sizes)
        assertNull(withEncoder.sizeNote)
        val sd = OnDeviceEngine.traits(model(EngineKind.DIFFUSION))
        assertEquals(SizeSupport.SD_CPP, sd.sizes)
        assertTrue(sd.runtimeLora)
    }

    @Test
    fun remoteBackendsGetTheSizeAndParameters() {
        val client = { OkHttpClient() }
        val sdApi = SdApiEngine("http://127.0.0.1:7860", client)
        val sse = LocalSseEngine("http://127.0.0.1:8081", client)
        for ((what, aspect, r) in requests(sdApi.capabilities.sizes)) {
            val a1111 = sdApi.requestBody(r, DiffusionInputs())
            assertEquals(what, r.width, a1111["width"]!!.jsonPrimitive.content.toInt())
            assertEquals(what, r.height, a1111["height"]!!.jsonPrimitive.content.toInt())
            assertEquals("28", a1111["steps"]!!.jsonPrimitive.content)
            assertEquals(5.5, a1111["cfg_scale"]!!.jsonPrimitive.content.toDouble(), 1e-6)
            assertEquals("42", a1111["seed"]!!.jsonPrimitive.content)
            assertEquals("DPM++ 2M SDE", a1111["sampler_name"]!!.jsonPrimitive.content)
            assertEquals("Karras", a1111["scheduler"]!!.jsonPrimitive.content)
            assertEquals("3", a1111["n_iter"]!!.jsonPrimitive.content)
            assertEquals("blurry", a1111["negative_prompt"]!!.jsonPrimitive.content)
            assertTrue(a1111["prompt"]!!.jsonPrimitive.content.contains("<lora:film:0.8>"))

            val local = sse.requestBody(r, DiffusionInputs(), seed = 42)
            assertEquals(what, r.width, local["width"]!!.jsonPrimitive.content.toInt())
            assertEquals(what, r.height, local["height"]!!.jsonPrimitive.content.toInt())
            assertEquals("dpm_sde_karras", local["scheduler"]!!.jsonPrimitive.content)
            assertEquals("a lighthouse", local["prompt"]!!.jsonPrimitive.content)
        }
        val img2img = sdApi.requestBody(requests(SizeSupport())[0].third.copy(mode = DiffusionMode.IMG2IMG), DiffusionInputs(initImage = byteArrayOf(1, 2, 3)))
        assertEquals(0.35, img2img["denoising_strength"]!!.jsonPrimitive.content.toDouble(), 1e-6)
        assertTrue(img2img.containsKey("init_images"))
        val sseImg = sse.requestBody(requests(SizeSupport())[0].third.copy(mode = DiffusionMode.IMG2IMG), DiffusionInputs(initImage = byteArrayOf(1)), seed = 1)
        assertEquals(0.35, sseImg["denoise_strength"]!!.jsonPrimitive.content.toDouble(), 1e-6)
    }
}
