package dev.wckdboy.autobot.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Android side of model management; the shared rules are tested in `:shared` (`core.models`). */
class ModelsTest {

    @Test
    fun deviceProfileFeedsTheSharedFitRules() {
        val features = DeviceProfile.parseCpuFeatures("processor\t: 0\nFeatures\t: fp asimd asimddp i8mm sve2\nCPU part\t: 0x001\n")
        assertEquals(setOf("fp", "asimd", "asimddp", "i8mm", "sve2"), features)
        val phone = DeviceProfile("SM8850", DeviceProfile.socName("SM8850"), 16L shl 30, 200L shl 30, features, 8)
        assertEquals("SM8850 · Snapdragon 8 Elite Gen 5", phone.label)
        assertEquals("v81", phone.htpArch)
        assertEquals(AppPlatform.ANDROID, phone.capabilities.platform)
        assertEquals(Fit.GOOD, phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA))
        assertEquals(Fit.TOO_BIG, phone.fit(ModelKind.CHAT, 9_000_000_000, EngineKind.LLAMA))
        assertEquals(Fit.UNSUPPORTED, phone.fit(ModelKind.IMAGE, 1, EngineKind.NONE))
        val old = phone.copy(cpuFeatures = setOf("fp", "asimd"))
        assertEquals(Fit.UNSUPPORTED, old.fit(ModelKind.IMAGE, 1_000_000_000, EngineKind.DIFFUSION))
        // The JVM target of :shared is Android's: plans without an explicit platform use its engines.
        assertEquals(EngineKind.DIFFUSION, engineFor(ModelKind.IMAGE, ModelFormat.GGUF))
    }

    @Test
    fun fastestBackendWins() {
        val now = 1_000L
        val results = listOf(
            Benchmark("m", Backend.CPU, generationTps = 12.0, createdAt = now),
            Benchmark("m", Backend.GPU, generationTps = 30.0, createdAt = now),
            Benchmark("m", Backend.GPU, generationTps = 5.0, createdAt = now - 10),
            Benchmark("m", Backend.NPU, error = "failed", createdAt = now + 5),
        )
        assertEquals(Backend.GPU, ComputeProfile.fastest(results))
        assertEquals(Backend.NPU, ComputeProfile.fastest(listOf(Benchmark("i", Backend.NPU, stepMs = 120.0), Benchmark("i", Backend.GPU, stepMs = 900.0))))
        assertNull(ComputeProfile.fastest(emptyList()))
    }
}
