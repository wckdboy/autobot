package dev.wckdboy.autobot.feature.models

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.DownloadProgress
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Counts how often the Models screen's rows compose while a download ticks and when the model
 * registry is read again (Room hands out new, equal objects for every row on each change): only
 * the row whose model actually changed may compose again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h3000dp-xhdpi")
class ModelsScreenRecompositionTest {
    @get:Rule
    val compose = createComposeRule()

    private val counts = HashMap<String, Int>()

    @After
    fun tearDown() {
        modelsCompositionProbe = null
    }

    private fun idle() = compose.waitForIdle()

    private val actions: ModelsActions = Proxy.newProxyInstance(ModelsActions::class.java.classLoader, arrayOf(ModelsActions::class.java)) { proxy, m, args ->
        when {
            m.name == "equals" -> proxy === args?.firstOrNull()
            m.name == "hashCode" -> System.identityHashCode(proxy)
            m.name == "toString" -> "FakeModelsActions"
            m.name == "getRecommended" -> Catalog.all
            m.returnType == List::class.java -> emptyList<Any>()
            else -> null
        }
    } as ModelsActions

    private fun model(id: String, status: ModelStatus = ModelStatus.READY, downloaded: Long = 500_000_000) = InstalledModel(
        id = id, kind = ModelKind.CHAT, source = ModelSource.HUGGING_FACE, title = "Model $id", subtitle = "",
        format = ModelFormat.GGUF, engine = EngineKind.LLAMA, baseModel = null, license = null, nsfw = false,
        totalBytes = 500_000_000, downloadedBytes = downloaded, status = status, error = null,
        manifest = ModelManifest(emptyList()), addedAt = 0, paths = emptyMap(),
    )

    /**
     * Before the shared model classes were declared stable (compose-stability.conf), every
     * re-read of the registry recomposed all installed rows (6 of 6 for one changed model, and
     * 6 for none) because rows compared the new objects by identity.
     */
    @Test
    fun downloadTicksAndRegistryRereadsRedrawOnlyWhatChanged() {
        modelsCompositionProbe = { counts[it] = (counts[it] ?: 0) + 1 }

        val installed = (1..5).map { model("m$it") } + model("dl", ModelStatus.DOWNLOADING, 100_000_000)
        val state = mutableStateOf(ModelsUiState(installed = installed, downloads = DownloadsState(active = DownloadProgress("dl", "Model dl", 100_000_000, 500_000_000, 1_000_000))))
        compose.setContent {
            AutobotTheme {
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ModelsScreen(state.value, actions, onOpenAccounts = {}, onOpenCompute = {})
                }
            }
        }
        idle()
        println("INITIAL: $counts")
        val shownCatalog = counts["catalogRow"] ?: 0
        assertTrue("catalog rows show: $counts", shownCatalog > 0)

        // Ten progress ticks: a new downloads state each time, the same installed list.
        counts.clear()
        for (i in 1..10) {
            val p = DownloadProgress("dl", "Model dl", 100_000_000L + i * 10_000_000L, 500_000_000, 1_000_000)
            state.value = state.value.copy(downloads = DownloadsState(active = p))
            idle()
        }
        val ticks = HashMap(counts)
        println("TICKS 10: $ticks")

        // The registry is read again with no row changed (new objects, equal values), along with
        // a progress tick, so the screen itself does recompose.
        counts.clear()
        val p = DownloadProgress("dl", "Model dl", 300_000_000, 500_000_000, 2_000_000)
        state.value = state.value.copy(installed = state.value.installed.map { it.copy() }, downloads = DownloadsState(active = p))
        idle()
        val reread = HashMap(counts)
        println("REREAD: $reread")

        // One model really changes (the download finishes).
        counts.clear()
        state.value = state.value.copy(installed = state.value.installed.map { if (it.id == "dl") it.copy(status = ModelStatus.READY, downloadedBytes = 500_000_000) else it.copy() })
        idle()
        val changed = HashMap(counts)
        println("ONE CHANGED: $changed")

        assertEquals("installed rows recomposed by download ticks: $ticks", 0, ticks["installedRow"] ?: 0)
        assertEquals("catalog rows recomposed by download ticks: $ticks", 0, ticks["catalogRow"] ?: 0)
        assertEquals("rows recomposed by an unchanged re-read: $reread", 0, reread["installedRow"] ?: 0)
        assertEquals("only the changed row recomposes: $changed", 1, changed["installedRow"] ?: 0)
    }
}
