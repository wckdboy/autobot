package dev.wckdboy.autobot.core.diffusion.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.LocalDream
import dev.wckdboy.autobot.engine.npu.LocalNpu
import dev.wckdboy.autobot.engine.npu.NpuEngineDiedException
import dev.wckdboy.autobot.engine.npu.NpuEngineUnavailableException
import dev.wckdboy.autobot.engine.npu.NpuEvent
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.FlowCollector
import org.json.JSONObject

/**
 * Image generation through the NPU/GPU engine (`:npu` process): QNN context binaries on the
 * Hexagon NPU, or MNN graphs on the GPU, for packages in the Local Dream layout.
 */
internal class NpuImages(private val npu: LocalNpu, private val scratchDir: File, private val clock: () -> Long) {

    suspend fun generate(out: FlowCollector<DiffusionEvent>, model: InstalledModel, request: DiffusionRequest, inputs: DiffusionInputs) {
        val pkg = model.manifest.npu ?: throw DiffusionException("${model.title} is not an NPU package", DiffusionException.Code.UNSUPPORTED)
        val qnn = pkg.runtime == "qnn"
        val native = if (pkg.arch == "sdxl") 1024 else 512
        // QNN binaries are compiled for one resolution; MNN graphs take any multiple of 64.
        val width = if (qnn) native else request.width.coerceIn(256, 1536) / 64 * 64
        val height = if (qnn) native else request.height.coerceIn(256, 1536) / 64 * 64
        scratchDir.mkdirs()
        val temp = mutableListOf<File>()
        try {
            val json = JSONObject()
                .put("op", "generate")
                .put("arch", pkg.arch)
                .put("runtime", pkg.runtime)
                .put("gpu", model.backend != Backend.CPU)
                .put("vpred", pkg.vpred)
                .put("files", files(model))
                .put("prompt", PromptSyntax.stripLoraTags(request.prompt))
                .put("negative", request.negativePrompt)
                .put("steps", request.steps)
                .put("cfg", request.cfgScale.toDouble())
                .put("seed", if (request.seed < 0) Random.nextLong(0, Int.MAX_VALUE.toLong()) else request.seed)
                .put("batch", request.batchCount.coerceIn(1, 8))
                .put("sampler", request.sampler)
                .put("karras", request.scheduler?.contains("karras", ignoreCase = true) == true)
                .put("width", width)
                .put("height", height)
                .put("strength", request.denoiseStrength.toDouble())
                .put("out_dir", scratchDir.path)
            if (request.mode != DiffusionMode.TXT2IMG && inputs.initImage != null) {
                val init = File.createTempFile("init", ".rgb", scratchDir).also(temp::add)
                init.writeBytes(rgb(inputs.initImage, width, height))
                json.put("init_image", init.path)
                if (request.mode == DiffusionMode.INPAINT && inputs.mask != null) {
                    val mask = File.createTempFile("mask", ".gray", scratchDir).also(temp::add)
                    mask.writeBytes(gray(inputs.mask, width, height))
                    json.put("mask", mask.path)
                }
            }
            val started = clock()
            // A batch samples one image after another and steps carry no index.
            var done = 0
            npu.generate(json.toString()).collect { event ->
                when (event) {
                    is NpuEvent.Progress -> out.emit(DiffusionEvent.Progress(done, event.step, event.steps))
                    is NpuEvent.Status -> OnDeviceEngine.phaseOf(event.status)?.let { out.emit(DiffusionEvent.Phase(it)) }
                    is NpuEvent.Image -> {
                        done = event.index + 1
                        val file = File(event.path)
                        val png = try {
                            png(file.readBytes(), event.width, event.height)
                        } finally {
                            file.delete()
                        }
                        out.emit(DiffusionEvent.Result(event.index, png, event.seed, event.width, event.height, clock() - started, info(model)))
                    }
                    is NpuEvent.Result -> JSONObject(event.json).optJSONObject("error")?.let { e ->
                        if (e.optString("code") != "CANCELLED") throw DiffusionException(failure(model, e), DiffusionException.Code.BACKEND)
                    }
                }
            }
        } catch (e: NpuEngineDiedException) {
            throw DiffusionException("The NPU engine stopped (likely out of memory). Close other apps and try again.", DiffusionException.Code.BACKEND)
        } catch (e: NpuEngineUnavailableException) {
            throw DiffusionException(e.message ?: "The NPU engine is not available", DiffusionException.Code.BACKEND)
        } finally {
            temp.forEach { it.delete() }
        }
    }

    /** Upscales an encoded image with an NPU upscaler model; returns PNG bytes. */
    suspend fun upscale(upscaler: InstalledModel, image: ByteArray, onProgress: (Int, Int) -> Unit): ByteArray {
        // Check the size before decoding: the 4× result is assembled in this process.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DiffusionException("Unreadable image", DiffusionException.Code.UNSUPPORTED)
        upscaleProblem(bounds.outWidth, bounds.outHeight)?.let { throw DiffusionException(it, DiffusionException.Code.UNSUPPORTED) }
        val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: throw DiffusionException("Unreadable image", DiffusionException.Code.UNSUPPORTED)
        scratchDir.mkdirs()
        val input = File.createTempFile("up", ".rgb", scratchDir)
        try {
            input.writeBytes(rgbOf(bitmap))
            val (width, height) = bitmap.width to bitmap.height
            bitmap.recycle()
            val json = JSONObject()
                .put("op", "upscale")
                .put("model", upscaler.paths[FileRole.MODEL])
                .put("image", input.path)
                .put("width", width)
                .put("height", height)
                .put("out_dir", scratchDir.path)
            var result: ByteArray? = null
            npu.generate(json.toString()).collect { event ->
                when (event) {
                    is NpuEvent.Progress -> onProgress(event.step, event.steps)
                    is NpuEvent.Image -> {
                        val file = File(event.path)
                        result = try { png(file.readBytes(), event.width, event.height) } finally { file.delete() }
                    }
                    is NpuEvent.Result -> JSONObject(event.json).optJSONObject("error")?.let { e ->
                        throw DiffusionException(failure(upscaler, e), DiffusionException.Code.BACKEND)
                    }
                    is NpuEvent.Status -> Unit
                }
            }
            return result ?: throw DiffusionException("Upscale produced no image", DiffusionException.Code.BACKEND)
        } finally {
            input.delete()
        }
    }

    /**
     * The engine's error as the user sees it: which stage failed, the runtime's own words, and —
     * when the package was built for a Hexagon generation this phone cannot load — what to install.
     */
    private suspend fun failure(model: InstalledModel, error: JSONObject): String {
        val pkg = model.manifest.npu
        val message = error.optString("message").ifBlank { "NPU engine error" }
        val where = if (pkg?.runtime == "qnn") "on the NPU" else "on the GPU"
        val text = when (error.optString("code")) {
            "LOAD_FAILED" -> "${model.title} could not be loaded $where: $message"
            "UNSUPPORTED" -> "${model.title} is not a layout this engine can run: $message"
            "ENCODE_FAILED" -> "The text encoder of ${model.title} failed: $message"
            "UNET_FAILED", "DECODE_FAILED" -> "${model.title} failed while generating $where: $message"
            else -> message
        }
        if (pkg?.runtime != "qnn") return text
        val arch = runCatching { JSONObject(npu.status()).optString("arch") }.getOrNull()?.takeIf { it.isNotBlank() }
        return LocalDream.incompatibility(pkg.htpArch, arch)?.let { "$text ($it)" } ?: text
    }

    private fun info(model: InstalledModel): String {
        val pkg = model.manifest.npu
        return listOfNotNull(model.title, if (pkg?.runtime == "qnn") "npu" else "gpu", pkg?.htpArch).joinToString(" · ")
    }

    companion object {
        /** NPU upscalers enlarge 4×. */
        const val UPSCALE = 4

        /** Longest side of an upscaled image (4096² ARGB is 64 MB; 16384² would be 1 GB). */
        const val MAX_UPSCALED_SIDE = 4096

        /** Why a [width]×[height] image cannot be upscaled (too large, e.g. already upscaled), or null. */
        fun upscaleProblem(width: Int, height: Int): String? {
            val side = maxOf(width, height)
            return if (side.toLong() * UPSCALE > MAX_UPSCALED_SIDE) {
                "This image is already ${width}×$height — upscaling works up to ${MAX_UPSCALED_SIDE / UPSCALE} px on the long side"
            } else {
                null
            }
        }

        private val ROLES = mapOf(
            FileRole.TOKENIZER to "tokenizer",
            FileRole.TOKEN_EMB to "token_emb",
            FileRole.POS_EMB to "pos_emb",
            FileRole.TEXT_ENCODER to "text_encoder",
            FileRole.TOKEN_EMB_2 to "token_emb_2",
            FileRole.POS_EMB_2 to "pos_emb_2",
            FileRole.TEXT_ENCODER_2 to "text_encoder_2",
            FileRole.UNET to "unet",
            FileRole.VAE_DECODER to "vae_decoder",
            FileRole.VAE_ENCODER to "vae_encoder",
        )

        fun files(model: InstalledModel): JSONObject {
            val json = JSONObject()
            ROLES.forEach { (role, key) -> model.paths[role]?.let { json.put(key, it) } }
            return json
        }

        /**
         * The largest power-of-two subsampling that still decodes [srcW]×[srcH] to at least
         * [w]×[h], so a 12 MP photo used as a 512² init image is never decoded at full size.
         */
        internal fun sampleSize(srcW: Int, srcH: Int, w: Int, h: Int): Int {
            var n = 1
            while (srcW / (n * 2) >= w && srcH / (n * 2) >= h) n *= 2
            return n
        }

        /** [encoded] decoded at about [w]×[h] (subsampled), then scaled exactly; the intermediate is recycled. */
        private fun decodeScaled(encoded: ByteArray, w: Int, h: Int, filter: Boolean, what: String): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            val opts = BitmapFactory.Options().apply {
                if (bounds.outWidth > 0 && bounds.outHeight > 0) inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, w, h)
            }
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, opts) ?: throw DiffusionException("Unreadable $what", DiffusionException.Code.UNSUPPORTED)
            if (decoded.width == w && decoded.height == h) return decoded
            return Bitmap.createScaledBitmap(decoded, w, h, filter).also { if (it !== decoded) decoded.recycle() }
        }

        /** Decodes an encoded image, scales it to [w]×[h] and returns packed RGB bytes. */
        fun rgb(encoded: ByteArray, w: Int, h: Int): ByteArray {
            val scaled = decodeScaled(encoded, w, h, filter = true, what = "init image")
            return rgbOf(scaled).also { scaled.recycle() }
        }

        /** A mask: white (painted) = 255 = repaint, black = keep. */
        fun gray(encoded: ByteArray, w: Int, h: Int): ByteArray {
            val scaled = decodeScaled(encoded, w, h, filter = false, what = "mask")
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            scaled.recycle()
            return ByteArray(px.size) { i -> if (Color.red(px[i]) + Color.green(px[i]) + Color.blue(px[i]) > 3 * 127) 255.toByte() else 0 }
        }

        fun rgbOf(bitmap: Bitmap): ByteArray {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val out = ByteArray(px.size * 3)
            for (i in px.indices) {
                out[i * 3] = Color.red(px[i]).toByte()
                out[i * 3 + 1] = Color.green(px[i]).toByte()
                out[i * 3 + 2] = Color.blue(px[i]).toByte()
            }
            return out
        }

        fun png(rgb: ByteArray, w: Int, h: Int): ByteArray {
            val px = IntArray(w * h) { i ->
                Color.rgb(rgb[i * 3].toInt() and 0xff, rgb[i * 3 + 1].toInt() and 0xff, rgb[i * 3 + 2].toInt() and 0xff)
            }
            val bitmap = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                bitmap.recycle()
                stream.toByteArray()
            }
        }
    }
}
