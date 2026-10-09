package dev.wckdboy.autobot.engine.diffusion

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/** JNI surface of `libautobot_sd.so`. Only used inside the ":sd" process. */
internal object NativeSd {
    init {
        System.loadLibrary("autobot_sd")
    }

    external fun init()
    external fun systemInfo(): String
    external fun load(
        model: String?, diffusionModel: String?, llm: String?, clipL: String?, clipG: String?,
        t5xxl: String?, vae: String?, taesd: String?, nThreads: Int, flashAttn: Boolean,
    ): Long
    external fun modelVersion(handle: Long): String
    external fun generate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float,
        sampler: String, scheduler: String, seed: Long, batch: Int, strength: Float, clipSkip: Int,
        loraPaths: Array<String>, loraWeights: FloatArray, initRgb: ByteArray?, maskGray: ByteArray?, sink: Sink,
    ): Long
    external fun cancel(handle: Long)
    external fun free(handle: Long)

    interface Sink {
        fun onProgress(step: Int, steps: Int)
        fun onPreview(rgb: ByteArray, width: Int, height: Int, channels: Int)
        fun onImage(index: Int, rgb: ByteArray, width: Int, height: Int, channels: Int, seed: Long)
    }
}

/**
 * Hosts stable-diffusion.cpp in its own process. One model bundle stays resident between
 * generations; requests run serially. Outputs are written to `cache/sd-out/` as PNG files
 * (same app UID, so the client process can read and delete them).
 */
class SdService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sd-worker") }

    @Volatile
    private var handle = 0L
    private var loadedKey: String? = null

    private val outDir: File by lazy { File(cacheDir, "sd-out").apply { mkdirs() } }

    override fun onCreate() {
        super.onCreate()
        NativeSd.init()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        worker.execute { release() }
        worker.shutdown()
        super.onDestroy()
    }

    private val binder = object : ISdEngine.Stub() {
        override fun systemInfo(): String = NativeSd.systemInfo()

        override fun generate(request: Bundle, callback: ISdCallback) {
            worker.execute { run(request, callback) }
        }

        override fun cancel() {
            handle.takeIf { it != 0L }?.let(NativeSd::cancel)
        }

        override fun unload() {
            worker.execute { release() }
        }
    }

    private fun run(r: Bundle, callback: ISdCallback) {
        try {
            val key = SdRequest.BUNDLE_KEYS.joinToString("|") { r.getString(it).orEmpty() } + "|" + r.getBoolean(SdRequest.K_FLASH)
            if (handle == 0L || key != loadedKey) {
                release()
                callback.onStatus("loading")
                val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                handle = NativeSd.load(
                    r.getString(SdRequest.K_MODEL), r.getString(SdRequest.K_DIFFUSION), r.getString(SdRequest.K_LLM),
                    r.getString(SdRequest.K_CLIP_L), r.getString(SdRequest.K_CLIP_G), r.getString(SdRequest.K_T5),
                    r.getString(SdRequest.K_VAE), r.getString(SdRequest.K_TAESD), threads, r.getBoolean(SdRequest.K_FLASH),
                )
                if (handle == 0L) {
                    callback.onDone("Could not load the model (unsupported format, missing component or not enough memory)")
                    return
                }
                loadedKey = key
            }
            val width = r.getInt(SdRequest.K_WIDTH)
            val height = r.getInt(SdRequest.K_HEIGHT)
            val init = r.getString(SdRequest.K_INIT)?.let { decodeRgb(it, width, height) }
            val mask = r.getString(SdRequest.K_MASK)?.let { decodeMask(it, width, height) }
            callback.onStatus("sampling")
            val sink = object : NativeSd.Sink {
                override fun onProgress(step: Int, steps: Int) = safe { callback.onProgress(step, steps) }

                override fun onPreview(rgb: ByteArray, width: Int, height: Int, channels: Int) = safe {
                    val file = File(outDir, "preview-${UUID.randomUUID()}.jpg")
                    write(toBitmap(rgb, width, height, channels), file, Bitmap.CompressFormat.JPEG, 80)
                    callback.onPreview(file.path)
                }

                override fun onImage(index: Int, rgb: ByteArray, width: Int, height: Int, channels: Int, seed: Long) = safe {
                    val file = File(outDir, "image-${UUID.randomUUID()}.png")
                    write(toBitmap(rgb, width, height, channels), file, Bitmap.CompressFormat.PNG, 100)
                    callback.onImage(index, file.path, seed, width, height)
                }
            }
            val seed = NativeSd.generate(
                handle, r.getString(SdRequest.K_PROMPT).orEmpty(), r.getString(SdRequest.K_NEGATIVE).orEmpty(),
                width, height, r.getInt(SdRequest.K_STEPS), r.getFloat(SdRequest.K_CFG),
                r.getString(SdRequest.K_SAMPLER).orEmpty(), r.getString(SdRequest.K_SCHEDULER).orEmpty(),
                r.getLong(SdRequest.K_SEED), r.getInt(SdRequest.K_BATCH), r.getFloat(SdRequest.K_STRENGTH),
                r.getInt(SdRequest.K_CLIP_SKIP), r.getStringArray(SdRequest.K_LORA_PATHS) ?: emptyArray(),
                r.getFloatArray(SdRequest.K_LORA_WEIGHTS) ?: FloatArray(0), init, mask, sink,
            )
            callback.onDone(if (seed < 0) "Generation failed or was cancelled" else null)
        } catch (e: RemoteException) {
            Log.w(TAG, "client went away", e)
            handle.takeIf { it != 0L }?.let(NativeSd::cancel)
        } catch (t: Throwable) {
            Log.e(TAG, "generation failed", t)
            runCatching { callback.onDone(t.message ?: t.javaClass.simpleName) }
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: RemoteException) {
            handle.takeIf { it != 0L }?.let(NativeSd::cancel)
        }
    }

    private fun release() {
        if (handle != 0L) {
            NativeSd.free(handle)
            handle = 0L
            loadedKey = null
        }
    }

    private fun scaled(path: String, width: Int, height: Int): Bitmap {
        val src = BitmapFactory.decodeFile(path) ?: error("Cannot read $path")
        return if (src.width == width && src.height == height) src else Bitmap.createScaledBitmap(src, width, height, true)
    }

    private fun decodeRgb(path: String, width: Int, height: Int): ByteArray {
        val bmp = scaled(path, width, height)
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 3)
        for (i in px.indices) {
            out[i * 3] = Color.red(px[i]).toByte()
            out[i * 3 + 1] = Color.green(px[i]).toByte()
            out[i * 3 + 2] = Color.blue(px[i]).toByte()
        }
        return out
    }

    /** White (or opaque, for alpha masks) = 255 = repaint. */
    private fun decodeMask(path: String, width: Int, height: Int): ByteArray {
        val bmp = scaled(path, width, height)
        val px = IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        return ByteArray(px.size) { i ->
            val c = px[i]
            val on = Color.alpha(c) >= 128 && (Color.red(c) + Color.green(c) + Color.blue(c)) >= 384
            if (on) 255.toByte() else 0
        }
    }

    private fun toBitmap(rgb: ByteArray, width: Int, height: Int, channels: Int): Bitmap {
        val px = IntArray(width * height) { i ->
            val o = i * channels
            val r = rgb[o].toInt() and 0xFF
            val g = rgb[o + (if (channels > 1) 1 else 0)].toInt() and 0xFF
            val b = rgb[o + (if (channels > 2) 2 else 0)].toInt() and 0xFF
            Color.rgb(r, g, b)
        }
        return Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun write(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat, quality: Int) {
        FileOutputStream(file).use { bitmap.compress(format, quality, it) }
    }

    private companion object {
        const val TAG = "SdService"
    }
}
