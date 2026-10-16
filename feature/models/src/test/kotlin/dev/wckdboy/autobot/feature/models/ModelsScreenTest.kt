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
import dev.wckdboy.autobot.core.models.DownloadBlock
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
        // The running download's PAUSE button, then the queued row's PAUSE tag.
        val pauses = compose.onAllNodesWithText("PAUSE")
        pauses[0].performClick()
        pauses[1].performClick()
        assertEquals(listOf("pause(r)", "pause(q)"), calls.filter { it.startsWith("pause") })
        // Queued, kill-switch paused, failed (with its reason): each says so and offers its action.
        text("queued · 1").assertIsDisplayed()
        // The panel says what blocks the queue, and the paused row why it waits.
        compose.onAllNodesWithText(DownloadBlock.KILL_SWITCH.label, substring = true, ignoreCase = true).assertCountEquals(2)
        compose.onAllNodesWithText("sha-256 mismatch", substring = true, ignoreCase = true).assertCountEquals(2)
        compose.onAllNodesWithText("RESUME")[0].performClick()
        compose.onAllNodesWithText("RETRY")[0].performClick()
        assertTrue(calls.toString(), "resume(p)" in calls && "resume(f)" in calls)
        // Installed rows: one size each, downloaded / total while it is not done.
        text("chat · gguf · 250 MB / 500 MB").assertIsDisplayed()
        compose.onAllNodesWithText("chat · gguf · 500 MB · llama.cpp", substring = true, ignoreCase = true).assertCountEquals(2) // done, and queued (nothing yet)
        compose.onNodeWithText("READY").assertIsDisplayed()
        compose.onNodeWithText("50%").performClick()
        assertTrue(calls.toString(), calls.count { it == "pause(r)" } == 2)
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
        assertEquals("100 MB / 500 MB", installedSize(model("p", "Paused", ModelStatus.PAUSED, downloaded = 100_000_000)))
        assertEquals("500 MB", installedSize(model("q", "Queued", ModelStatus.QUEUED, downloaded = 0)))
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
