package dev.wckdboy.autobot.feature.studio.engine

import android.graphics.Bitmap
import android.graphics.Color
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionEngine
import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.Upscaler
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.audiogen.AudioGenException
import dev.wckdboy.autobot.engine.audiogen.LocalAudioGen
import dev.wckdboy.autobot.engine.tts.LocalVoice
import dev.wckdboy.autobot.engine.tts.VoiceException
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.studio.media.ImageSniff
import dev.wckdboy.autobot.shared.audiogen.AudioGenRequest
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.audiogen.Wav
import dev.wckdboy.autobot.shared.studio.AssetReader
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineCapabilities
import dev.wckdboy.autobot.shared.studio.EngineEvent
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.EngineOutput
import dev.wckdboy.autobot.shared.studio.Seeds
import dev.wckdboy.autobot.shared.studio.StudioEngine
import dev.wckdboy.autobot.shared.studio.StudioEngineException
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.tts.KokoroFrontend
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Android engines as Studio engines: each turns a queued job's recipe into its engine's
 * request and the result into outputs. On-device engines share the queue's one on-device slot,
 * so the GPU / NPU / CPU-heavy models never run side by side (the engine memory coordinator
 * frees the others before each one loads).
 */

/** Diffusion on one configured image backend (this phone, A1111 / Forge, Local SSE). */
class DiffusionStudioEngine(
    private val backend: DiffusionBackend,
    private val engine: DiffusionEngine,
    private val assets: AssetReader,
) : StudioEngine {
    private val onDevice = backend.kind == BackendKind.ON_DEVICE

    override val capabilities = EngineCapabilities(
        kind = EngineKinds.DIFFUSION,
        backendId = if (onDevice) null else backend.id,
        label = if (onDevice) ON_DEVICE_LABEL else backend.name,
        outputs = setOf(AssetType.IMAGE),
        onDevice = onDevice,
        previews = engine.capabilities.livePreview,
    )

    /** The backend's capabilities (modes, LoRA, hires, sizes) for the image room. */
    val diffusion get() = engine.capabilities

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        val request = StudioRecipes.diffusionRequest(job.recipe)
        if (request.mode !in engine.capabilities.modes) throw StudioEngineException("${capabilities.label} can't do ${request.mode.label}")
        val inputs = inputs(job, request.width, request.height, request.mode)
        val outputs = ArrayList<EngineOutput>()
        try {
            engine.generate(request, inputs).collect { event ->
                when (event) {
                    is DiffusionEvent.Progress -> {
                        if (event.totalSteps > 0) onEvent(EngineEvent.Progress(event.step, event.totalSteps))
                        event.preview?.let { onEvent(EngineEvent.Preview(ImageSniff.mimeType(it), it)) }
                    }
                    is DiffusionEvent.Result -> outputs += EngineOutput(AssetType.IMAGE, ImageSniff.mimeType(event.image), event.image, event.width, event.height)
                }
            }
        } catch (e: DiffusionException) {
            throw StudioEngineException(e.message ?: "The image backend failed")
        }
        if (outputs.isEmpty()) throw StudioEngineException("${capabilities.label} finished without an image")
        return outputs
    }

    private suspend fun inputs(job: StudioJob, width: Int, height: Int, mode: DiffusionMode): DiffusionInputs {
        if (mode == DiffusionMode.TXT2IMG) return DiffusionInputs()
        fun ref(role: String) = job.recipe.inputs.firstOrNull { it.role == role }
        val image = ref(StudioRecipes.ROLE_IMAGE)?.let { assets.read(it.assetId) }
            ?: throw StudioEngineException("The input image is no longer in the library")
        val mask = if (mode == DiffusionMode.INPAINT) {
            ref(StudioRecipes.ROLE_MASK)?.let { assets.read(it.assetId) } ?: throw StudioEngineException("The mask is no longer in the library")
        } else {
            null
        }
        return withContext(Dispatchers.Default) {
            val init = ImageCodec.decode(image.bytes, 2048) ?: throw StudioEngineException("Can't read the input image")
            val maskBitmap = mask?.let { ImageCodec.decode(it.bytes, 2048) ?: throw StudioEngineException("Can't read the mask") }
            DiffusionInputs(
                initImage = ImageCodec.png(ImageCodec.cover(init, width, height)),
                mask = maskBitmap?.let { ImageCodec.png(encodeMask(ImageCodec.cover(it, width, height), engine.capabilities.maskEncoding)) },
            )
        }
    }

    companion object {
        const val ON_DEVICE_LABEL = "This phone"

        /**
         * Masks are stored backend-neutral (opaque: white = repaint, black = keep) and encoded
         * for the backend that runs the job.
         */
        fun encodeMask(mask: Bitmap, encoding: MaskEncoding): Bitmap {
            if (encoding == MaskEncoding.OPAQUE_BLACK_WHITE) return mask
            val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
            val row = IntArray(mask.width)
            for (y in 0 until mask.height) {
                mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
                for (x in row.indices) {
                    val c = row[x]
                    val luma = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                    row[x] = Color.argb(luma, 255, 255, 255)
                }
                out.setPixels(row, 0, mask.width, 0, y, mask.width, 1)
            }
            return out
        }
    }
}

/** 4× on-device upscaling (NPU upscaler from Models); `scale` 2 or 3 scales the result down. */
class UpscaleStudioEngine(private val upscaler: Upscaler, private val assets: AssetReader) : StudioEngine {
    override val capabilities = EngineCapabilities(EngineKinds.UPSCALE, null, DiffusionStudioEngine.ON_DEVICE_LABEL, setOf(AssetType.IMAGE), onDevice = true)

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        val input = job.recipe.inputs.firstOrNull { it.role == StudioRecipes.ROLE_IMAGE }?.let { assets.read(it.assetId) }
            ?: throw StudioEngineException("Upscaling needs an input image")
        val scale = job.recipe.params[StudioRecipes.SCALE]?.asDouble()?.coerceIn(1.0, 4.0) ?: 4.0
        onEvent(EngineEvent.Status("Upscaling on the NPU"))
        val png = try {
            upscaler.upscale(input.bytes, job.recipe.model) { done, total -> onEvent(EngineEvent.Progress(done, total)) }
        } catch (e: DiffusionException) {
            throw StudioEngineException(e.message ?: "Upscaling failed")
        }
        return withContext(Dispatchers.Default) {
            val big = ImageCodec.decode(png) ?: throw StudioEngineException("The upscaler returned no image")
            val out = if (scale < 4.0) {
                val f = scale / 4.0
                Bitmap.createScaledBitmap(big, (big.width * f).toInt().coerceAtLeast(1), (big.height * f).toInt().coerceAtLeast(1), true)
            } else {
                big
            }
            listOf(EngineOutput(AssetType.IMAGE, "image/png", ImageCodec.png(out), out.width, out.height))
        }
    }
}

/**
 * Text to audio with Stable Audio Open Small on the phone. A `batch` param makes several clips in
 * one job, seeds walking from the recipe's seed. Output: 44.1 kHz stereo 16-bit WAV.
 */
class AudioStudioEngine(private val audio: LocalAudioGen, private val library: ModelLibrary) : StudioEngine {
    override val capabilities = EngineCapabilities(
        EngineKinds.AUDIO, null, DiffusionStudioEngine.ON_DEVICE_LABEL, setOf(AssetType.AUDIO), onDevice = true,
        params = setOf(StudioRecipes.PROMPT, StudioRecipes.SECONDS, StudioRecipes.STEPS, StudioRecipes.CFG, StudioRecipes.BATCH),
        maxBatch = 4,
    )

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        val folder = folder(library) ?: throw StudioEngineException(MISSING)
        val r = job.recipe
        val prompt = r.string(StudioRecipes.PROMPT)?.trim().orEmpty()
        if (prompt.isEmpty()) throw StudioEngineException("Describe the sound first")
        val seconds = r.params[StudioRecipes.SECONDS]?.asDouble() ?: 10.0
        val steps = (r.params[StudioRecipes.STEPS]?.asLong()?.toInt() ?: StableAudio.DEFAULT_STEPS).coerceIn(1, StableAudio.MAX_STEPS)
        val cfg = (r.params[StudioRecipes.CFG]?.asDouble()?.toFloat() ?: StableAudio.DEFAULT_CFG).coerceIn(0f, 20f)
        val batch = (r.params[StudioRecipes.BATCH]?.asLong()?.toInt() ?: 1).coerceIn(1, capabilities.maxBatch)
        val seeds = Seeds.plan(dev.wckdboy.autobot.shared.studio.SeedMode.WALK, r.seed ?: Seeds.random(), batch)
        val outputs = ArrayList<EngineOutput>()
        for ((i, seed) in seeds.withIndex()) {
            if (batch > 1) onEvent(EngineEvent.Status("Clip ${i + 1} of $batch"))
            val clip = try {
                audio.generate(folder, AudioGenRequest(prompt, seconds, steps, cfg, seed)) { p ->
                    onEvent(EngineEvent.Progress(((i + p.fraction) * 1000 / batch).toInt(), 1000))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioGenException) {
                throw StudioEngineException(e.message ?: "Audio generation failed")
            }
            val wav = Wav.encode16(clip)
            outputs += EngineOutput(AssetType.AUDIO, "audio/wav", wav, durationMs = clip.left.size * 1000L / clip.sampleRate)
        }
        return outputs
    }

    companion object {
        const val MISSING = "Get the audio model (Stable Audio Open Small) in Models first"

        suspend fun folder(library: ModelLibrary): File? = library.all()
            .firstOrNull { it.isReady && it.kind == ModelKind.AUDIO && it.engine == EngineKind.ONNX }
            ?.let { library.dirOf(it.id) }
    }
}

/** Kokoro voiceover on the phone: `text`, `voice`, `speed` → 24 kHz mono WAV. */
class SpeechStudioEngine(private val voice: LocalVoice, private val library: ModelLibrary) : StudioEngine {
    override val capabilities = EngineCapabilities(
        EngineKinds.SPEECH, null, DiffusionStudioEngine.ON_DEVICE_LABEL, setOf(AssetType.AUDIO), onDevice = true,
        params = setOf(StudioRecipes.TEXT, StudioRecipes.VOICE, StudioRecipes.SPEED),
    )

    override suspend fun run(job: StudioJob, onEvent: (EngineEvent) -> Unit): List<EngineOutput> {
        val folder = folder(library) ?: throw StudioEngineException(MISSING)
        val r = job.recipe
        val text = r.string(StudioRecipes.TEXT)?.trim().orEmpty()
        if (text.isEmpty()) throw StudioEngineException("Write the text to read first")
        val name = r.string(StudioRecipes.VOICE)?.takeIf { it.isNotBlank() } ?: KokoroFrontend.DEFAULT_VOICE
        val speed = (r.params[StudioRecipes.SPEED]?.asDouble()?.toFloat() ?: 1f).coerceIn(0.5f, 2f)
        onEvent(EngineEvent.Status("Reading with $name"))
        val pcm = try {
            voice.synthesizeAll(folder, text, name, speed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceException) {
            throw StudioEngineException(e.message ?: "The voice failed")
        }
        val wav = Wav.encode16(listOf(pcm), LocalVoice.SAMPLE_RATE)
        return listOf(EngineOutput(AssetType.AUDIO, "audio/wav", wav, durationMs = pcm.size * 1000L / LocalVoice.SAMPLE_RATE))
    }

    companion object {
        const val MISSING = "Get the Kokoro voice in Models first"

        suspend fun folder(library: ModelLibrary): File? = library.all()
            .firstOrNull { it.isReady && it.kind == ModelKind.VOICE && it.engine == EngineKind.ONNX }
            ?.let { library.dirOf(it.id) }
    }
}
