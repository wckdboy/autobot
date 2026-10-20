package dev.wckdboy.autobot.feature.studio.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import dev.wckdboy.autobot.core.designsystem.motion.InkMotion
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.SystemBarsBase
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioActions
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioRoomState
import dev.wckdboy.autobot.feature.studio.ui.image.ImageActions
import dev.wckdboy.autobot.feature.studio.ui.image.ImageRoomState
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryActions
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryState
import dev.wckdboy.autobot.feature.studio.ui.video.VideoActions
import dev.wckdboy.autobot.feature.studio.ui.video.VideoRoomState
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsActions
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsState
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Studio room switching (with each room's system bar icons) and the queue strip's job states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xhdpi")
class StudioScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private inline fun <reified T> recording(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args ->
        calls += "${m.name}(${args.orEmpty().filter { it !is kotlin.coroutines.Continuation<*> }.joinToString(",")})"
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as T

    private val loader = object : AssetLoader {
        override suspend fun thumbnail(id: String): ImageBitmap? = null
        override suspend fun peaks(id: String): FloatArray? = null
        override suspend fun bytes(id: String): ByteArray? = null
    }

    private val recipe = StudioRecipes.diffusionRecipe(DiffusionRequest(prompt = "lanterns", steps = 20, cfgScale = 6f, seed = 1), null)

    private fun job(id: String, state: JobState, label: String, error: String? = null, progress: Float = 0f) =
        StudioJob(id, "p-1", recipe, label, state, progress, step = if (state == JobState.RUNNING) 9 else 0, steps = if (state == JobState.RUNNING) 20 else 0, error = error, createdAt = id.last().code.toLong())

    private fun show(jobs: List<StudioJob> = emptyList(), initial: StudioRoom = StudioRoom.IMAGE) {
        val shell = StudioShellState(listOf(StudioProject("p-1", "Night market", null, 1, 2)), "p-1", jobs, emptyList())
        compose.setContent {
            // A light app theme: the darkroom must still get light icons, paper dark ones.
            AutobotTheme(darkTheme = false) {
                SystemBarsBase(lightSurface = true)
                CompositionLocalProvider(LocalInkMotion provides InkMotion(reduced = true), LocalAssetLoader provides loader) {
                    StudioScreen(
                        initialRoom = initial,
                        shell = shell,
                        shellActions = recording<ShellActions>(),
                        image = ImageRoomState(loaded = true),
                        imageActions = recording<ImageActions>(),
                        audio = AudioRoomState(loaded = true, hasAudioModel = true, hasVoice = true, prompt = "rain", seconds = 10f),
                        audioActions = recording<AudioActions>(),
                        video = VideoRoomState(loaded = true),
                        videoActions = recording<VideoActions>(),
                        workflows = WorkflowsState(loaded = true, backends = listOf(ComfyBackendConfig("pc", "Studio PC", "https://pc.example.ts.net"))),
                        workflowsActions = recording<WorkflowsActions>(),
                        newBackend = { ComfyBackendConfig("new", "ComfyUI", it) },
                        auth = { AuthUpdate.Keep },
                        library = LibraryState(),
                        libraryActions = recording<LibraryActions>(),
                        onLoadRecipe = {},
                        onBack = {},
                        onOpenModels = {},
                        onOpenImageBackends = {},
                    )
                }
            }
        }
    }

    /** True when the status and navigation bars draw dark icons (a light surface behind them). */
    private fun darkIcons(): Boolean {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("status and navigation bars agree", controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars)
        return controller.isAppearanceLightStatusBars
    }

    private fun goTo(room: StudioRoom) =
        compose.onNode(hasText(room.label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()

    @Test
    fun switchingRoomsSwitchesContentAndBarIcons() {
        show()
        compose.waitForIdle()
        assertFalse("image is a darkroom: light icons", darkIcons())

        goTo(StudioRoom.AUDIO)
        compose.onNodeWithText("Make the sound", ignoreCase = true).assertIsDisplayed()
        assertTrue("audio follows the (light) app theme", darkIcons())

        goTo(StudioRoom.VIDEO)
        compose.onNodeWithText("Add a ComfyUI PC", ignoreCase = true).assertIsDisplayed()
        assertFalse("video is a darkroom", darkIcons())

        goTo(StudioRoom.LIBRARY)
        compose.waitForIdle()
        assertTrue("the library is paper", darkIcons())

        goTo(StudioRoom.WORKFLOWS)
        compose.onNodeWithText("comfyui backends", substring = true, ignoreCase = true).assertIsDisplayed()
        assertTrue(darkIcons())

        // Straight from paper to the darkroom: the room leaving must not restore its stale value.
        goTo(StudioRoom.LIBRARY)
        compose.waitForIdle()
        goTo(StudioRoom.IMAGE)
        compose.waitForIdle()
        assertFalse(darkIcons())
    }

    @Test
    fun queuePlatesSayWhatEachJobIsDoing() {
        show(
            listOf(
                job("j-1", JobState.DONE, "seed 1"),
                job("j-2", JobState.RUNNING, "seed 2", progress = 0.45f),
                job("j-3", JobState.QUEUED, "seed 3"),
                job("j-4", JobState.FAILED, "seed 4", error = "Can't reach Studio PC: connection refused"),
                job("j-5", JobState.FAILED, "seed 5", error = "write failed: ENOSPC (No space left on device)"),
                job("j-6", JobState.CANCELLED, "seed 6"),
            ),
            initial = StudioRoom.AUDIO,
        )
        compose.onNodeWithText("Queue · 2 working").assertIsDisplayed()
        compose.onNodeWithContentDescription("Done: seed 1").assertExists()
        compose.onNodeWithContentDescription("Running 45 percent: seed 2").assertExists()
        compose.onNodeWithContentDescription("Queued: seed 3").assertExists()
        compose.onNodeWithContentDescription("Failed: Can't reach Studio PC: connection refused").assertExists()
        compose.onNodeWithContentDescription("Failed: Storage is full — free some space on this phone, then retry").assertExists()
        compose.onNodeWithContentDescription("Cancelled").assertExists()
        compose.onNodeWithText("Clear").performClick()
        assertTrue(calls.toString(), "clearFinished()" in calls)
    }

    @Test
    fun anIdleQueueSaysDone() {
        show(listOf(job("j-1", JobState.DONE, "seed 1")), initial = StudioRoom.AUDIO)
        compose.onNodeWithText("Queue · done").assertIsDisplayed()
    }

    @Test
    fun jobErrorsNameAFullDisk() {
        assertEquals("Storage is full — free some space on this phone, then retry", jobError("open failed: ENOSPC"))
        assertEquals("Can't reach Studio PC", jobError("Can't reach Studio PC"))
    }
}
