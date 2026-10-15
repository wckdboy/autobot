package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.Fit
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemFallbackTest {

    private fun model(status: ModelStatus = ModelStatus.READY, engine: EngineKind = EngineKind.LLAMA, path: String? = "/m.gguf") = InstalledModel(
        id = "qwen", kind = ModelKind.CHAT, source = ModelSource.CATALOG, title = "Qwen", subtitle = "", format = ModelFormat.GGUF,
        engine = engine, baseModel = null, license = null, nsfw = false, totalBytes = 1, downloadedBytes = 1, status = status, error = null,
        manifest = ModelManifest(emptyList()), addedAt = 0, paths = listOfNotNull(path?.let { FileRole.MODEL to it }).toMap(),
    )

    @Test
    fun installedModelStateFollowsTheFitRules() {
        assertEquals(LocalModelState.READY, LocalChatProvider.modelState(model()) { Fit.TIGHT })
        assertEquals(LocalModelState.TOO_BIG, LocalChatProvider.modelState(model()) { Fit.TOO_BIG })
        assertEquals(LocalModelState.NOT_INSTALLED, LocalChatProvider.modelState(null) { Fit.GOOD })
        assertEquals(LocalModelState.NOT_INSTALLED, LocalChatProvider.modelState(model(status = ModelStatus.DOWNLOADING)) { Fit.GOOD })
        assertEquals(LocalModelState.NOT_INSTALLED, LocalChatProvider.modelState(model(engine = EngineKind.DIFFUSION)) { Fit.GOOD })
        assertEquals(LocalModelState.NOT_INSTALLED, LocalChatProvider.modelState(model(path = null)) { Fit.GOOD })
    }

    @Test
    fun thisPhoneOffersTheSystemModelOnlyWhenItCanRun() {
        val nano = SystemModelStatus(SystemModels.GEMINI_NANO, "Gemini Nano (on-device)", SystemAvailability.AVAILABLE)
        assertEquals(listOf("a", SystemModels.GEMINI_NANO), LocalProviderSync.localModels(listOf("a"), nano))
        assertEquals(listOf(SystemModels.GEMINI_NANO), LocalProviderSync.localModels(emptyList(), nano))
        assertEquals(emptyList<String>(), LocalProviderSync.localModels(emptyList(), nano.copy(availability = SystemAvailability.DOWNLOADABLE)))
        assertEquals(listOf("a"), LocalProviderSync.localModels(listOf("a"), null))
    }
}
