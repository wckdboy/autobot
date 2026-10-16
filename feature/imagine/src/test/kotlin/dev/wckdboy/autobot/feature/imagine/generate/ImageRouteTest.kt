package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.NpuPackage
import dev.wckdboy.autobot.core.models.Recommended
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route tag names the engine a model actually runs on, and models bring their settings. */
class ImageRouteTest {
    private val phone = DiffusionBackend("this-phone", BackendKind.ON_DEVICE, "This phone", "local://this-phone")
    private val pc = DiffusionBackend("pc", BackendKind.SD_API, "PC", "http://192.168.1.2:7860")
    private val loopback = DiffusionBackend("lo", BackendKind.SD_API, "Termux", "http://127.0.0.1:7860")

    private val illustrious = model(
        "hf:xororz/sdxl-qnn:illustrious_v17_dmd2_qnn2.28_8gen4.zip",
        "Illustrious V17 Dmd2",
        EngineKind.NPU,
        ModelFormat.QNN,
        NpuPackage("sdxl", "qnn", htpArch = "v79"),
        Recommended(steps = 8, cfg = 1f, sampler = "LCM", width = 1024, height = 1024),
    )
    private val sd15Gpu = model("hf:x:anything_mnn.zip", "Anything", EngineKind.NPU, ModelFormat.MNN, NpuPackage("sd15", "mnn"))
    private val cpuModel = model("catalog:sd-turbo", "SD Turbo", EngineKind.DIFFUSION, ModelFormat.GGUF, null)

    @Test
    fun onDeviceRoutesNameTheEngine() {
        assertEquals(Route.LOCAL_NPU, imageRoute(phone, illustrious))
        assertEquals(Route.LOCAL_GPU, imageRoute(phone, sd15Gpu))
        assertEquals(Route.LOCAL_CPU, imageRoute(phone, cpuModel))
        // No model known yet: stable-diffusion.cpp's CPU is the honest default.
        assertEquals(Route.LOCAL_CPU, imageRoute(phone, null as InstalledModel?))
    }

    @Test
    fun localRoutesByAccelerator() {
        assertEquals(Route.LOCAL_NPU, Route.local("npu"))
        assertEquals(Route.LOCAL_GPU, Route.local("gpu"))
        assertEquals(Route.LOCAL_CPU, Route.local("cpu"))
        assertEquals("not known yet", Route.LOCAL, Route.local(null))
        assertEquals("LOCAL", Route.LOCAL.label.uppercase())
    }

    @Test
    fun remoteRoutes() {
        assertEquals(Route.PC_LAN, imageRoute(pc, illustrious))
        assertEquals(Route.LOCAL_CPU, imageRoute(loopback, null as InstalledModel?))
        assertNull(imageRoute(null, illustrious))
    }

    @Test
    fun chipsCarryTheAccelerator() {
        val chips = buildChips(listOf(phone), listOf(illustrious, sd15Gpu, cpuModel), emptyMap())
        assertEquals(listOf("npu", "gpu", null), chips.map { it.accelerator })
        assertEquals("Illustrious V17 Dmd2 · npu", chips.first().label)
    }

    @Test
    fun theStudiosModelIsTheSelectedOneElseTheFirst() {
        val models = listOf(cpuModel, illustrious)
        assertEquals(illustrious, onDeviceImageModel(models, illustrious.id))
        assertEquals(cpuModel, onDeviceImageModel(models, null))
        assertEquals("a deleted selection falls back", cpuModel, onDeviceImageModel(models, "gone"))
        assertNull(onDeviceImageModel(listOf(cpuModel.copy(status = ModelStatus.DOWNLOADING)), null))
    }

    @Test
    fun recommendedSettingsApplyWhenAModelIsSelected() {
        val applied = DiffusionRequest(prompt = "a fox").withRecommended(illustrious.id, recommendedFor(illustrious))
        assertEquals(8, applied.steps)
        assertEquals(1f, applied.cfgScale)
        assertEquals("LCM", applied.sampler)
        assertNull("the sampler's own schedule", applied.scheduler)
        assertEquals(1024, applied.width)
        assertEquals(1024, applied.height)
        assertEquals(illustrious.id, applied.model)
        assertEquals("the prompt stays", "a fox", applied.prompt)
    }

    @Test
    fun openingTheStudioAppliesThemUnlessTheUserChangedThem() {
        // The reported case: an SDXL DMD2 model shown, but the request still at 20 steps and 512².
        assertTrue(needsRecommended(DiffusionRequest(), illustrious.id))
        assertTrue("selected before, never tuned", needsRecommended(DiffusionRequest(model = illustrious.id), illustrious.id))
        assertTrue("set up for another model", needsRecommended(DiffusionRequest(model = "other", steps = 30), illustrious.id))
        assertFalse("tuned by the user", needsRecommended(DiffusionRequest(model = illustrious.id, steps = 12, width = 1024, height = 1024), illustrious.id))
        val applied = DiffusionRequest().withRecommended(illustrious.id, recommendedFor(illustrious))
        assertFalse("applied once, then left alone", needsRecommended(applied, illustrious.id))
    }

    @Test
    fun packagesWithoutAManifestRecommendationStillGetTheirSize() {
        val old = illustrious.copy(manifest = illustrious.manifest.copy(recommended = null))
        val rec = recommendedFor(old)!!
        assertEquals(8, rec.steps)
        assertEquals(1024, rec.width)
        assertEquals(512, recommendedFor(sd15Gpu)?.width)
        assertNull(recommendedFor(cpuModel))
    }

    @Test
    fun aNegativePromptTheUserWroteIsKept() {
        val rec = Recommended(negativePrompt = "lowres")
        assertEquals("blurry", DiffusionRequest(negativePrompt = "blurry").withRecommended("m", rec).negativePrompt)
        assertEquals("lowres", DiffusionRequest().withRecommended("m", rec).negativePrompt)
    }

    private fun model(id: String, title: String, engine: EngineKind, format: ModelFormat, npu: NpuPackage?, rec: Recommended? = null) = InstalledModel(
        id = id,
        kind = ModelKind.IMAGE,
        source = ModelSource.HUGGING_FACE,
        title = title,
        subtitle = "",
        format = format,
        engine = engine,
        baseModel = null,
        license = null,
        nsfw = false,
        totalBytes = 1,
        downloadedBytes = 1,
        status = ModelStatus.READY,
        error = null,
        manifest = ModelManifest(emptyList(), recommended = rec, npu = npu),
        addedAt = 0,
        paths = emptyMap(),
    )
}
