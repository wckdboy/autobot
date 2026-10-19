package dev.wckdboy.autobot.feature.home

import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.NpuPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Run screen's header fits one line, and recent images name the engine that made them. */
class RunStatusTest {
    @Test
    fun headerIsShort() {
        val line = RunViewModel.runStatus("SM8850", busy = false, freeBytes = 142_800_000_000)
        assertEquals("SM8850 · idle · 143 GB free", line)
        // The line is well under the ~44 characters that showed before the old line truncated.
        assertTrue(line.length + 3 <= 32)
        assertEquals("busy · 9.5 GB free", RunViewModel.runStatus("", busy = true, freeBytes = 9_500_000_000))
        assertEquals("SM8850 · idle", RunViewModel.runStatus("SM8850", busy = false, freeBytes = null))
    }

    @Test
    fun compactSizes() {
        assertEquals("143 GB", RunViewModel.compactBytes(142_800_000_000))
        assertEquals("10 GB", RunViewModel.compactBytes(10_000_000_000))
        assertEquals("2.4 GB", RunViewModel.compactBytes(2_400_000_000))
    }

    @Test
    fun recentImagesNameTheirEngine() {
        assertEquals(Route.LOCAL_NPU, RunViewModel.galleryRoute("This phone", image("qnn")))
        assertEquals(Route.LOCAL_GPU, RunViewModel.galleryRoute("This phone", image("mnn")))
        assertEquals(Route.LOCAL_CPU, RunViewModel.galleryRoute("This phone", image(null)))
        assertEquals("model since deleted", Route.LOCAL_CPU, RunViewModel.galleryRoute("This phone", null))
        assertEquals("upscaled copies", Route.LOCAL_NPU, RunViewModel.galleryRoute("This phone · 4× npu", image("qnn")))
        assertEquals(Route.PC_LAN, RunViewModel.galleryRoute("PC", image("qnn")))
    }

    private fun image(runtime: String?) = InstalledModel(
        id = "m",
        kind = ModelKind.IMAGE,
        source = ModelSource.HUGGING_FACE,
        title = "M",
        subtitle = "",
        format = if (runtime == "qnn") ModelFormat.QNN else ModelFormat.GGUF,
        engine = if (runtime != null) EngineKind.NPU else EngineKind.DIFFUSION,
        baseModel = null,
        license = null,
        nsfw = false,
        totalBytes = 1,
        downloadedBytes = 1,
        status = ModelStatus.READY,
        error = null,
        manifest = ModelManifest(emptyList(), npu = runtime?.let { NpuPackage("sd15", it) }),
        addedAt = 0,
        paths = emptyMap(),
    )
}
