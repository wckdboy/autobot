package dev.wckdboy.autobot.core.diffusion

import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.NpuPackage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceBackendSyncTest {

    private fun model(kind: ModelKind, engine: EngineKind, status: ModelStatus = ModelStatus.READY) = InstalledModel(
        id = "m-$kind-$engine", kind = kind, source = ModelSource.HUGGING_FACE, title = "m", subtitle = "",
        format = if (engine == EngineKind.NPU) ModelFormat.QNN else ModelFormat.GGUF, engine = engine, baseModel = null,
        license = null, nsfw = false, totalBytes = 1, downloadedBytes = 1, status = status, error = null,
        manifest = ModelManifest(emptyList(), npu = if (engine == EngineKind.NPU) NpuPackage("sdxl", "qnn", htpArch = "v75") else null),
        addedAt = 0, paths = emptyMap(),
    )

    @Test
    fun npuPackagesAloneGiveThePhoneAnImageBackend() {
        // The reported bug: a phone with only Local Dream (QNN) packages had no "This phone" backend.
        assertTrue(OnDeviceBackendSync.hasOnDeviceImageModel(listOf(model(ModelKind.IMAGE, EngineKind.NPU))))
        assertTrue(OnDeviceBackendSync.hasOnDeviceImageModel(listOf(model(ModelKind.IMAGE, EngineKind.DIFFUSION))))
    }

    @Test
    fun upscalersAndUnfinishedDownloadsDoNot() {
        assertFalse(OnDeviceBackendSync.hasOnDeviceImageModel(listOf(model(ModelKind.UPSCALER, EngineKind.NPU))))
        assertFalse(OnDeviceBackendSync.hasOnDeviceImageModel(listOf(model(ModelKind.IMAGE, EngineKind.NPU, ModelStatus.DOWNLOADING))))
        assertFalse(OnDeviceBackendSync.hasOnDeviceImageModel(listOf(model(ModelKind.CHAT, EngineKind.LLAMA))))
    }
}
