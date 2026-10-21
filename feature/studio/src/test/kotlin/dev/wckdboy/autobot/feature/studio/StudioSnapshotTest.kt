package dev.wckdboy.autobot.feature.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.LoraRef
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
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
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.Recipe
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import dev.wckdboy.autobot.shared.studio.WorkflowImport
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each Studio room with fixture state to `build/studio-snapshots/` (for eyes; asserts
 * only that something was drawn), like the design system's ink snapshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioSnapshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/studio-snapshots").apply { mkdirs() }

    private inline fun <reified T> noop(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        if (m.returnType == Boolean::class.javaPrimitiveType) false else null
    } as T

    private fun gradient(seed: Int): ImageBitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(0xFF1D3B5A.toInt() + seed * 0x101000, 0xFFE8401C.toInt(), 0xFFEFEAE2.toInt())
        Canvas(b).drawRect(0f, 0f, 256f, 256f, Paint().apply { shader = LinearGradient(0f, 0f, 256f, 256f + seed * 40, colors, null, Shader.TileMode.CLAMP) })
        return b.asImageBitmap()
    }

    private val loader = object : AssetLoader {
        override suspend fun thumbnail(id: String) = gradient(id.last().code % 5)
        override suspend fun peaks(id: String) = FloatArray(128) { (0.15f + 0.8f * kotlin.math.abs(kotlin.math.sin(it / 7.0 + id.length)).toFloat()) * if (it % 9 == 0) 0.4f else 1f }
        override suspend fun bytes(id: String): ByteArray? = null
    }

    private val project = StudioProject("p-1", "Night market", null, 1, 2)
    private val prompt = "a night market in the rain, paper lanterns, {cinematic|risograph print}"
    private val image = StudioRecipes.diffusionRecipe(DiffusionRequest(prompt = prompt, steps = 20, cfgScale = 6f, seed = 1234), null)
    private val assets = listOf(
        StudioAsset("a-000000000001", "p-1", AssetType.IMAGE, image, 10, "image/png", 1000, width = 768, height = 512, favorite = true, label = "seed 1234"),
        StudioAsset("a-000000000002", "p-1", AssetType.IMAGE, image.copy(seed = 1235), 9, "image/png", 1000, width = 768, height = 512),
        StudioAsset("a-000000000003", "p-1", AssetType.AUDIO, StudioRecipes.audioRecipe("rain on lanterns, crowd murmur", 10.0, 8, 1f, 7), 8, "audio/wav", 1000, durationMs = 10_000),
        StudioAsset("a-000000000004", "p-1", AssetType.VIDEO, Recipe("comfyui", "pc", workflowId = "w-1", seed = 9, output = AssetType.VIDEO), 7, "video/mp4", 1000, durationMs = 4_000, width = 768, height = 432),
        StudioAsset("a-000000000005", "p-1", AssetType.IMAGE, image.copy(seed = 1236), 6, "image/png", 1000, width = 768, height = 512),
    )
    private val jobs = listOf(
        StudioJob("j-1", "p-1", image, "seed 1234", JobState.DONE, 1f, outputs = listOf("a-000000000001"), createdAt = 1),
        StudioJob("j-2", "p-1", image, "seed 1235", JobState.DONE, 1f, outputs = listOf("a-000000000002"), createdAt = 2),
        StudioJob("j-3", "p-1", image, "seed 1236", JobState.RUNNING, 0.45f, step = 9, steps = 20, createdAt = 3),
        StudioJob("j-4", "p-1", image, "seed 1237", JobState.QUEUED, createdAt = 4),
        StudioJob("j-5", "p-1", image, "seed 1238", JobState.FAILED, error = "Blocked by network policy", createdAt = 5),
    )
    private val shell = StudioShellState(listOf(project, StudioProject("p-2", "Posters", null, 1, 1)), "p-1", jobs, assets)
    private val caps = EngineCapabilities(DiffusionMode.entries.toSet(), runtimeLora = true, modelSwitching = true, livePreview = true, maskEncoding = MaskEncoding.OPAQUE_BLACK_WHITE, hires = true, vae = true)
    private val imageState = ImageRoomState(
        loaded = true,
        backends = listOf(BackendChoice("on-device", "This phone", true, caps.copy(runtimeLora = false, hires = false, vae = false), BackendKind.ON_DEVICE), BackendChoice("pc", "Studio PC", false, caps)),
        backendId = "pc",
        catalog = EngineCatalog(listOf("sdxl.safetensors"), samplers = listOf("Euler a", "DPM++ 2M"), upscalers = listOf("Latent")),
        models = listOf(ModelOption("sdxl.safetensors", "sdxl"), ModelOption("juggernaut.safetensors", "juggernaut")),
        request = DiffusionRequest(prompt = prompt, negativePrompt = "blurry, watermark", width = 768, height = 512, model = "sdxl.safetensors", loras = listOf(LoraRef("film-grain", 0.6f))),
        seedMode = SeedMode.WALK,
        seed = 1234,
        batch = 2,
        jobIds = listOf("j-1", "j-2", "j-3"),
    )
    private val workflow = WorkflowImport.create(
        "LTX image to video",
        """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"a lantern sways","clip":["4",1]}},
            "2":{"class_type":"CLIPTextEncode","inputs":{"text":"static","clip":["4",1]}},
            "3":{"class_type":"KSampler","inputs":{"seed":5,"steps":20,"cfg":3,"sampler_name":"euler","scheduler":"normal","denoise":1,"model":["4",0],"positive":["1",0],"negative":["2",0],"latent_image":["5",0]}},
            "4":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"ltx.safetensors"}},
            "5":{"class_type":"EmptyLatentImage","inputs":{"width":768,"height":512,"batch_size":1}},
            "6":{"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["4",2]}},
            "7":{"class_type":"SaveAnimatedWEBP","inputs":{"images":["6",0],"fps":24,"filename_prefix":"x"}}}""",
        id = "w-1",
        backendId = "pc",
    )

    @Test
    fun rendersEveryRoom() {
        val rooms = listOf(
            "image" to StudioRoom.IMAGE,
            "audio" to StudioRoom.AUDIO,
            "video-empty" to StudioRoom.VIDEO,
            "workflows" to StudioRoom.WORKFLOWS,
            "library" to StudioRoom.LIBRARY,
        )
        // Rendered through the Compose test rule, which leaves Material's infinite indicators
        // (loading, wavy progress) unstepped: a manually stepped looper would draw them forever.
        var current by mutableStateOf(rooms.first())
        compose.setContent {
            val (name, room) = current
            val video = if (name == "video-empty") VideoRoomState(loaded = true) else VideoRoomState(loaded = true, backends = listOf(ComfyBackendConfig("pc", "Studio PC", "https://pc.example.ts.net")), workflows = listOf(workflow))
                key(name) { AutobotTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalAssetLoader provides loader) {
                        StudioScreen(
                            initialRoom = room,
                            shell = shell,
                            shellActions = noop<ShellActions>(),
                            image = imageState,
                            imageActions = noop<ImageActions>(),
                            audio = AudioRoomState(loaded = true, hasAudioModel = true, hasVoice = true, prompt = "rain on paper lanterns, distant crowd", seconds = 10f),
                            audioActions = noop<AudioActions>(),
                            video = video,
                            videoActions = noop<VideoActions>(),
                            workflows = WorkflowsState(
                                loaded = true,
                                backends = listOf(ComfyBackendConfig("pc", "Studio PC", "https://pc.example.ts.net")),
                                tests = mapOf("pc" to "ok · comfyui 0.3.60 · RTX 4090 · 24 GB · 812 nodes"),
                                workflows = listOf(workflow),
                            ),
                            workflowsActions = noop<WorkflowsActions>(),
                            newBackend = { ComfyBackendConfig("new", "ComfyUI", it) },
                            auth = { AuthUpdate.Keep },
                            library = LibraryState(assets = assets, view = LibraryView.GRID),
                            libraryActions = noop<LibraryActions>(),
                            onLoadRecipe = {},
                            onBack = {},
                            onOpenModels = {},
                            onOpenImageBackends = {},
                        )
                    }
                } }
        }
        for (entry in rooms) {
            val name = entry.first
            current = entry
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "studio-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue("$name drew", distinctColors(bitmap) > 3)
        }
    }

    private fun distinctColors(bitmap: Bitmap): Int {
        val seen = HashSet<Int>()
        for (y in 0 until bitmap.height step 7) for (x in 0 until bitmap.width step 7) seen += bitmap.getPixel(x, y)
        return seen.size
    }
}
