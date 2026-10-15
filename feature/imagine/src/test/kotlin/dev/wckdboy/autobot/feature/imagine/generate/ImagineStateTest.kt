package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import org.junit.Assert.assertEquals
import org.junit.Test

class ImagineStateTest {
    private val pc = DiffusionBackend("pc", BackendKind.SD_API, "PC", "http://192.168.1.2:7860")
    private val caps = EngineCapabilities(setOf(DiffusionMode.TXT2IMG), runtimeLora = true, modelSwitching = true, livePreview = true, maskEncoding = MaskEncoding.OPAQUE_BLACK_WHITE)

    @Test
    fun capabilitiesAreCreatedOncePerBackend() {
        var created = 0
        val cache = CapabilityCache { created++; caps }
        // One state per keystroke / sampling step used to create an engine each time.
        repeat(200) { cache.get(pc) }
        assertEquals(1, created)
        // An edited backend is a different key.
        cache.get(pc.copy(baseUrl = "http://192.168.1.3:7860"))
        assertEquals(2, created)
    }

    @Test
    fun chipsListCatalogModelsOrTheBackend() {
        assertEquals(listOf(ModelChip("pc", null, "PC", onDevice = false)), buildChips(listOf(pc), emptyList(), emptyMap()))
        val chips = buildChips(listOf(pc), emptyList(), mapOf("pc" to EngineCatalog(models = listOf("sd_xl_turbo_1.0_fp16.safetensors [abc]"))))
        assertEquals(listOf(ModelChip("pc", "sd_xl_turbo_1.0_fp16.safetensors [abc]", "sd xl turbo 1.0 fp16 · PC", onDevice = false)), chips)
    }
}
