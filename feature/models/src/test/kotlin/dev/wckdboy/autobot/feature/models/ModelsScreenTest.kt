package dev.wckdboy.autobot.feature.models

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.DownloadBlock
import dev.wckdboy.autobot.core.models.NpuPackage
import dev.wckdboy.autobot.core.models.DownloadProgress
import dev.wckdboy.autobot.core.models.DownloadQueue
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.HubException
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelManifest
import dev.wckdboy.autobot.core.models.ModelSource
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The models screen's empty, failed and download states, rendered from fixture state (no network). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp-xhdpi")
class ModelsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var openedNetwork = 0

    private val actions: ModelsActions = Proxy.newProxyInstance(ModelsActions::class.java.classLoader, arrayOf(ModelsActions::class.java)) { _, m, args ->
        val recorded = args.orEmpty().filter { it !is kotlin.coroutines.Continuation<*> }.joinToString(",") { (it as? InstalledModel)?.id ?: it.toString() }
        if (!m.name.startsWith("get")) calls += "${m.name}($recorded)"
        if (m.returnType == List::class.java) emptyList<Any>() else null
    } as ModelsActions

    private fun show(state: ModelsUiState) {
        compose.setContent {
            AutobotTheme {
                // Ink loops tick on every frame; reduced motion keeps the test clock idle.
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                    ModelsScreen(state, actions, onOpenAccounts = {}, onOpenCompute = {}, onOpenNetwork = { openedNetwork++ })
                }
            }
        }
    }

    private fun text(value: String) = compose.onNodeWithText(value, substring = true, ignoreCase = true)

    @Test
    fun offlineHuggingFaceSaysWhyAndOpensTheNetworkSetting() {
        show(ModelsUiState(tab = ModelsTab.HUGGING_FACE, offline = true))
        text("Offline mode is on, so the kill switch blocks Hugging Face").assertIsDisplayed()
        compose.onNodeWithText("NETWORK").performClick()
        assertEquals(1, openedNetwork)
    }

    @Test
    fun offlineCivitaiSaysWhy() {
        show(ModelsUiState(tab = ModelsTab.CIVITAI, offline = true))
        text("kill switch blocks Civitai").assertIsDisplayed()
    }

    @Test
    fun aFailedSearchShowsTheErrorAndRetries() {
        show(ModelsUiState(tab = ModelsTab.HUGGING_FACE, hf = HfState(searched = true, failure = SearchFailure("HTTP 503"))))
        text("Hugging Face search failed: HTTP 503").assertIsDisplayed()
        text("retry").performClick()
        assertTrue(calls.toString(), "searchHf()" in calls)
    }

    @Test
    fun aBlockedSearchWhileOnlinePointsAtTheNetworkSetting() {
        show(ModelsUiState(tab = ModelsTab.CIVITAI, civitai = CivitaiState(searched = true, failure = SearchFailure("Network route changed", blocked = true))))
        text("The kill switch blocked Civitai: Network route changed").assertIsDisplayed()
        compose.onNodeWithText("NETWORK").performClick()
        assertEquals(1, openedNetwork)
    }

    @Test
    fun noResultsNamesTheQuery() {
        show(ModelsUiState(tab = ModelsTab.HUGGING_FACE, hf = HfState(query = "zzqx", searched = true)))
        text("No models on Hugging Face match “zzqx”").assertIsDisplayed()
    }

    @Test
    fun anUnsearchedTabOffersToSearch() {
        show(ModelsUiState(tab = ModelsTab.CIVITAI))
        text("Search Civitai above").assertIsDisplayed()
        compose.onNodeWithText("SEARCH").performClick()
        assertTrue(calls.toString(), "searchCivitai(true)" in calls)
    }

    @Test
    fun downloadRowsShowEveryState() {
        val queued = model("q", "Queued model", ModelStatus.QUEUED, downloaded = 0)
        val paused = model("p", "Paused model", ModelStatus.PAUSED, downloaded = 100_000_000, error = DownloadQueue.KILL_SWITCH_ERROR)
        val failed = model("f", "Failed model", ModelStatus.FAILED, downloaded = 10_000_000, error = "sha-256 mismatch")
        val running = model("r", "Running model", ModelStatus.DOWNLOADING, downloaded = 250_000_000)
        val done = model("d", "Done model", ModelStatus.READY, downloaded = 500_000_000)
        val downloads = DownloadsState(
            active = DownloadProgress("r", "Running model", 250_000_000, 500_000_000, 5_000_000),
            queued = listOf(queued),
            paused = listOf(paused, failed),
            block = DownloadBlock.KILL_SWITCH,
        )
        show(ModelsUiState(tab = ModelsTab.HUGGING_FACE, installed = listOf(running, queued, paused, failed, done), downloads = downloads, progress = downloads.active, hf = HfState(searched = true)))

        // Running: progress, speed and time left, with pause.
        text("250 MB / 500 MB · 5 MB/s · ").assertIsDisplayed()
        // PAUSE on the running download (panel and its row) and on the queued one: each pauses its own download.
        val pauses = compose.onAllNodesWithText("PAUSE")
        repeat(pauses.fetchSemanticsNodes().size) { pauses[it].performClick() }
        assertEquals(setOf("pause(r)", "pause(q)"), calls.filter { it.startsWith("pause") }.toSet())
        // Queued, kill-switch paused, failed (with its reason): each says so and offers its action.
        text("queued · 1").assertIsDisplayed()
        // The panel says what blocks the queue, and the paused row why it waits.
        compose.onAllNodesWithText(DownloadBlock.KILL_SWITCH.label, substring = true, ignoreCase = true).assertCountEquals(2)
        compose.onAllNodesWithText("sha-256 mismatch", substring = true, ignoreCase = true).assertCountEquals(2)
        compose.onAllNodesWithText("RESUME")[0].performClick()
        compose.onAllNodesWithText("RETRY")[0].performClick()
        assertTrue(calls.toString(), "resume(p)" in calls && "resume(f)" in calls)
        // Installed rows: their size only, never the progress again (the panel shows it once).
        compose.onAllNodesWithText("250 MB / 500 MB", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("chat · gguf · 500 MB", substring = true, ignoreCase = true).assertCountEquals(5)
        compose.onNodeWithText("READY").assertIsDisplayed()
        compose.onAllNodesWithText("50%").assertCountEquals(0)
    }

    @Test
    fun failuresMapToBlockedOrFailed() {
        assertTrue(searchFailure(HubException("Offline mode: network access is disabled", HubException.Code.BLOCKED)).blocked)
        assertTrue(searchFailure(NetworkBlockedException("Offline mode")).blocked)
        val rejected = searchFailure(HubException("Hugging Face rejected the token", HubException.Code.UNAUTHORIZED))
        assertFalse(rejected.blocked)
        assertEquals("Hugging Face rejected the token", rejected.message)
        assertEquals("Could not reach the hub: timeout", searchFailure(IOException("timeout")).message)
    }

    @Test
    fun installedSizeIsOneFigure() {
        assertEquals("500 MB", installedSize(model("d", "Done", ModelStatus.READY, downloaded = 500_000_000)))
        assertEquals("progress is in the downloads panel only", "500 MB", installedSize(model("p", "Paused", ModelStatus.PAUSED, downloaded = 100_000_000)))
        assertEquals("500 MB", installedSize(model("q", "Queued", ModelStatus.QUEUED, downloaded = 0)))
    }

    @Test
    fun installedRowIsOneCleanLine() {
        val npu = model("n", "Illustrious", ModelStatus.READY, downloaded = 500_000_000).copy(
            kind = ModelKind.IMAGE,
            format = ModelFormat.QNN,
            engine = EngineKind.NPU,
            totalBytes = 4_500_000_000,
            manifest = ModelManifest(emptyList(), npu = NpuPackage("sdxl", "qnn")),
        )
        // Was "image · QNN · 4.5 GB · qnn · mnn".
        assertEquals("image · NPU · 4.5 GB", installedSubtitle(npu))
        assertEquals("image · GPU · 4.5 GB", installedSubtitle(npu.copy(manifest = ModelManifest(emptyList(), npu = NpuPackage("sd15", "mnn")))))
        assertEquals("image · CPU · 4.5 GB", installedSubtitle(npu.copy(engine = EngineKind.DIFFUSION, manifest = ModelManifest(emptyList()))))
        val chat = model("c", "Qwen", ModelStatus.READY, downloaded = 500_000_000)
        assertEquals("chat · GGUF · 500 MB", installedSubtitle(chat))
        assertEquals("a picked backend", "chat · GPU · 500 MB", installedSubtitle(chat.copy(backend = Backend.GPU)))
    }

    @Test
    fun deviceHeaderFitsOnTwoShortLines() {
        val (label, lines) = deviceLines("SM8850", "Snapdragon 8 Elite Gen 5", 8, true, 15_800_000_000, 142_800_000_000)
        assertEquals("this phone · SM8850", label)
        assertEquals("Snapdragon 8 Elite Gen 5 · 8 cores · i8mm", lines.first)
        assertEquals("15.8 GB ram · 143 GB free", lines.second)
        assertEquals("this phone", deviceLines("", null, 4, false, 4_000_000_000, 5_000_000_000).first)
    }

    private fun model(id: String, title: String, status: ModelStatus, downloaded: Long, error: String? = null) = InstalledModel(
        id = id,
        kind = ModelKind.CHAT,
        source = ModelSource.HUGGING_FACE,
        title = title,
        subtitle = "",
        format = ModelFormat.GGUF,
        engine = EngineKind.LLAMA,
        baseModel = null,
        license = null,
        nsfw = false,
        totalBytes = 500_000_000,
        downloadedBytes = downloaded,
        status = status,
        error = error,
        manifest = ModelManifest(emptyList()),
        addedAt = 0,
        paths = emptyMap(),
    )
}
