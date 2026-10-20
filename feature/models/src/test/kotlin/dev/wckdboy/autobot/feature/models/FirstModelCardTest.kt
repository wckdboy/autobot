package dev.wckdboy.autobot.feature.models

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.DeviceProfile
import dev.wckdboy.autobot.core.models.DownloadProgress
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.FirstModel
import dev.wckdboy.autobot.core.models.FirstModelStep
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.ModelStatus
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h3000dp-xhdpi")
class FirstModelCardTest {
    @get:Rule
    val compose = createComposeRule()

    /** The emulator's 2 GB phone: the 0.8B is the pick. */
    private val phone = DeviceProfile("", null, 2_100_000_000, 3_500_000_000, emptySet(), 4)
    private val pick: ModelPlan = Catalog.chatModels.first { it.id == "catalog:qwen3.5-0.8b-q4_0" }

    private val calls = mutableListOf<String>()
    private val actions: ModelsActions = Proxy.newProxyInstance(ModelsActions::class.java.classLoader, arrayOf(ModelsActions::class.java)) { proxy, m, args ->
        when {
            m.name == "equals" -> proxy === args?.firstOrNull()
            m.name == "hashCode" -> System.identityHashCode(proxy)
            m.name == "toString" -> "FakeModelsActions"
            m.name == "getRecommended" -> Catalog.all
            m.returnType == List::class.java -> emptyList<Any>()
            else -> {
                calls += m.name + (args?.firstOrNull()?.let { a -> ":" + ((a as? InstalledModel)?.id ?: (a as? ModelPlan)?.id ?: a) } ?: "")
                null
            }
        }
    } as ModelsActions

    private fun installed(status: ModelStatus, downloaded: Long = 0) = InstalledModel(
        id = pick.id, kind = pick.kind, source = pick.source, title = pick.title, subtitle = pick.subtitle, format = pick.format, engine = pick.engine,
        baseModel = null, license = null, nsfw = false, totalBytes = pick.totalBytes, downloadedBytes = downloaded, status = status, error = null,
        manifest = pick.manifest, addedAt = 0, paths = emptyMap(),
    )

    private fun show(state: ModelsUiState) = compose.setContent {
        AutobotTheme {
            CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true)) {
                ModelsScreen(state, actions, onOpenAccounts = {}, onOpenCompute = {})
            }
        }
    }

    @Test
    fun aNewPhoneIsOfferedTheModelThatFits() {
        val card = firstModelCard(ModelsUiState(device = phone))!!
        assertEquals(pick.id, card.plan?.id)
        assertEquals(FirstModelStep.OFFER, card.step)
        // No device yet: no card (rather than a guess).
        assertNull(firstModelCard(ModelsUiState()))
    }

    @Test
    fun offlineByDefaultIsOneSentenceAndOneTap() {
        show(ModelsUiState(device = phone, offline = true))
        compose.onNodeWithText(FirstModel.OFFLINE_FIRST).assertIsDisplayed()
        compose.onNodeWithText(FirstModel.ALLOW_AND_DOWNLOAD).performClick()
        assertEquals(listOf("allowNetwork", "install:${pick.id}"), calls.filter { it.startsWith("allowNetwork") || it.startsWith("install") })
    }

    @Test
    fun onlineItIsJustDownload() {
        show(ModelsUiState(device = phone, offline = false))
        compose.onNodeWithText(FirstModel.OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(FirstModel.DOWNLOAD).performClick()
        assertTrue(calls.toString(), "install:${pick.id}" in calls)
    }

    @Test
    fun theDownloadShowsOnceWithProgressAndPause() {
        val downloads = DownloadsState(active = DownloadProgress(pick.id, pick.title, 250_000_000, 500_000_000, 1_000_000))
        val state = ModelsUiState(device = phone, installed = listOf(installed(ModelStatus.DOWNLOADING, 250_000_000)), downloads = downloads)
        assertTrue(downloads.onlyFor(pick.id))
        show(state)
        compose.onNodeWithText("250 MB of 500 MB · 50%").assertIsDisplayed()
        // The card is the only place the model shows: no downloads panel, no installed row.
        compose.onNodeWithText("installed ·", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("downloads", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText(FirstModel.PAUSE).performClick()
        assertTrue(calls.toString(), "pause:${pick.id}" in calls)
    }

    @Test
    fun aPausedDownloadResumes() {
        show(ModelsUiState(device = phone, installed = listOf(installed(ModelStatus.PAUSED, 100_000_000))))
        compose.onNodeWithText(FirstModel.RESUME).performClick()
        assertTrue(calls.toString(), "resume:${pick.id}" in calls)
    }

    @Test
    fun readyStartsTheFirstChatThenGoes() {
        show(ModelsUiState(device = phone, installed = listOf(installed(ModelStatus.READY, pick.totalBytes))))
        compose.onNodeWithText(FirstModel.readyLine(pick.title)).assertIsDisplayed()
        compose.onNodeWithText(FirstModel.BACK_OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(FirstModel.START_CHAT).performClick()
        assertTrue(calls.toString(), "use:${pick.id}" in calls)
        assertNull(firstModelCard(ModelsUiState(device = phone, installed = listOf(installed(ModelStatus.READY, pick.totalBytes)), hasChats = true)))
    }

    @Test
    fun otherDownloadsKeepThePanel() {
        val other = DownloadsState(active = DownloadProgress("catalog:other", "Other", 1, 2, 0))
        assertFalse(other.onlyFor(pick.id))
        assertTrue(DownloadsState().onlyFor(pick.id))
    }
}
