package dev.wckdboy.autobot.feature.studio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit
import dev.wckdboy.autobot.core.designsystem.largetext.LargeTextAudit.Themed
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.ui.AssetLoader
import dev.wckdboy.autobot.feature.studio.ui.LocalAssetLoader
import dev.wckdboy.autobot.feature.studio.ui.ShellActions
import dev.wckdboy.autobot.feature.studio.ui.StudioRoom
import dev.wckdboy.autobot.feature.studio.ui.StudioScreen
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioActions
import dev.wckdboy.autobot.feature.studio.ui.audio.AudioRoomState
import dev.wckdboy.autobot.feature.studio.ui.image.BackendChoice
import dev.wckdboy.autobot.feature.studio.ui.image.ImageActions
import dev.wckdboy.autobot.feature.studio.ui.image.ImageRoomState
import dev.wckdboy.autobot.feature.studio.ui.image.ModelOption
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryActions
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryState
import dev.wckdboy.autobot.feature.studio.ui.library.LibraryView
import dev.wckdboy.autobot.feature.studio.ui.video.VideoActions
import dev.wckdboy.autobot.feature.studio.ui.video.VideoRoomState
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsActions
import dev.wckdboy.autobot.feature.studio.ui.workflows.WorkflowsState
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Each Studio room at the default and the largest system font, on a narrow and a common phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    private inline fun <reified T> noop(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as T

    private val loader = object : AssetLoader {
        override suspend fun thumbnail(id: String): ImageBitmap? = null
        override suspend fun peaks(id: String) = FloatArray(64) { 0.2f + 0.6f * ((it * 7) % 10) / 10f }
        override suspend fun bytes(id: String): ByteArray? = null
    }

    private val project = StudioProject("p-1", "Night market", null, 1, 2)
    private val image = StudioRecipes.diffusionRecipe(DiffusionRequest(prompt = "a night market in the rain, paper lanterns", steps = 20, cfgScale = 6f, seed = 1234), null)
    private val assets = listOf(
        StudioAsset("a-1", "p-1", AssetType.IMAGE, image, 10, "image/png", 1000, width = 768, height = 512, favorite = true, label = "seed 1234"),
        StudioAsset("a-2", "p-1", AssetType.IMAGE, image.copy(seed = 1235), 9, "image/png", 1000, width = 768, height = 512),
        StudioAsset("a-3", "p-1", AssetType.AUDIO, StudioRecipes.audioRecipe("rain on lanterns, crowd murmur", 10.0, 8, 1f, 7), 8, "audio/wav", 1000, durationMs = 10_000),
    )
    private val jobs = listOf(
        StudioJob("j-1", "p-1", image, "seed 1234", JobState.DONE, 1f, outputs = listOf("a-1"), createdAt = 1),
        StudioJob("j-2", "p-1", image, "seed 1235", JobState.RUNNING, 0.45f, step = 9, steps = 20, createdAt = 2),
        StudioJob("j-3", "p-1", image, "seed 1236", JobState.FAILED, error = "Blocked by network policy", createdAt = 3),
    )
    private val shell = StudioShellState(listOf(project, StudioProject("p-2", "Posters", null, 1, 1)), "p-1", jobs, assets)
    private val caps = EngineCapabilities(DiffusionMode.entries.toSet(), runtimeLora = true, modelSwitching = true, livePreview = true, maskEncoding = MaskEncoding.OPAQUE_BLACK_WHITE, hires = true, vae = true)
    private val imageState = ImageRoomState(
        loaded = true,
        backends = listOf(BackendChoice("on-device", "This phone", true, caps.copy(runtimeLora = false, hires = false, vae = false), BackendKind.ON_DEVICE), BackendChoice("pc", "Studio PC", false, caps)),
        backendId = "pc",
        catalog = EngineCatalog(listOf("sdxl.safetensors"), samplers = listOf("Euler a", "DPM++ 2M"), upscalers = listOf("Latent")),
        models = listOf(ModelOption("sdxl.safetensors", "sdxl")),
        request = DiffusionRequest(prompt = "lanterns", negativePrompt = "blurry", width = 768, height = 512, model = "sdxl.safetensors", loras = listOf(LoraRef("film-grain", 0.6f))),
        seedMode = SeedMode.WALK,
        seed = 1234,
        jobIds = listOf("j-1", "j-2"),
    )
    private val pc = ComfyBackendConfig("pc", "Studio PC", "https://pc.example.ts.net")

    private fun audit(room: StudioRoom, width: Int, scale: Float) = LargeTextAudit.render(compose, "studio-${room.name.lowercase()}", width, scale, heightDp = 800) {
        Themed {
            CompositionLocalProvider(LocalAssetLoader provides loader) {
                StudioScreen(
                    initialRoom = room,
                    shell = shell,
                    shellActions = noop<ShellActions>(),
                    image = imageState,
                    imageActions = noop<ImageActions>(),
                    audio = AudioRoomState(loaded = true, hasAudioModel = true, hasVoice = true, prompt = "rain on paper lanterns", seconds = 10f),
                    audioActions = noop<AudioActions>(),
                    video = VideoRoomState(loaded = true),
                    videoActions = noop<VideoActions>(),
                    workflows = WorkflowsState(loaded = true, backends = listOf(pc), tests = mapOf("pc" to "ok · comfyui 0.3.60")),
                    workflowsActions = noop<WorkflowsActions>(),
                    newBackend = { ComfyBackendConfig("new", "ComfyUI", it) },
                    auth = { AuthUpdate.Keep },
                    library = LibraryState(assets = assets, view = LibraryView.LIST),
                    libraryActions = noop<LibraryActions>(),
                    onLoadRecipe = {},
                    onBack = {},
                    onOpenModels = {},
                    onOpenImageBackends = {},
                )
            }
        }
    }

    private fun fits(room: StudioRoom) {
        assertEquals("${room.label} at the default size", emptyList<LargeTextAudit.Finding>(), audit(room, 411, 1f))
        assertEquals("${room.label} at the largest size", emptyList<LargeTextAudit.Finding>(), audit(room, 360, 2f))
    }

    @Test fun imageFits() = fits(StudioRoom.IMAGE)

    @Test fun audioFits() = fits(StudioRoom.AUDIO)

    @Test fun videoFits() = fits(StudioRoom.VIDEO)

    @Test fun workflowsFits() = fits(StudioRoom.WORKFLOWS)

    @Test fun libraryFits() = fits(StudioRoom.LIBRARY)
}
