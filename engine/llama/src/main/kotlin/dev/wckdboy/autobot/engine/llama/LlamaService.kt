package dev.wckdboy.autobot.engine.llama

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** JNI surface of `libautobot_llama.so`. Only used inside the ":llm" process. */
internal object NativeLlama {
    init {
        System.loadLibrary("autobot_llama")
    }

    /** Loads the ggml backends found in [libDir]; returns how many devices are available. */
    external fun init(libDir: String): Int
    external fun systemInfo(): String
    external fun devices(): ByteArray
    external fun load(path: String, nCtx: Int, nThreads: Int, backend: String): Long
    external fun modelInfo(handle: Long): ByteArray
    external fun chat(handle: Long, request: ByteArray, sink: DeltaSink): ByteArray
    external fun cancel(handle: Long)
    external fun free(handle: Long)

    /** Called from native code for each streamed delta (UTF-8 bytes). */
    interface DeltaSink {
        fun onDelta(kind: Int, text: ByteArray)
    }
}

/**
 * Hosts llama.cpp in its own process. One model is resident at a time; requests run serially
 * on a single worker thread so the KV cache can be reused between agent steps.
 */
class LlamaService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-worker") }

    @Volatile
    private var handle = 0L
    private var loadedPath: String? = null
    private var loadedCtx = 0
    private var loadedBackend: String? = null
    private var devices = 0

    override fun onCreate() {
        super.onCreate()
        // Compiled OpenCL kernels are cached across launches (the first GPU load compiles them).
        Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", File(codeCacheDir, "opencl").path, true)
        devices = NativeLlama.init(applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        worker.execute { release() }
        worker.shutdown()
        super.onDestroy()
    }

    private val binder = object : ILlamaEngine.Stub() {
        override fun systemInfo(): String = NativeLlama.systemInfo()

        override fun devices(): String = String(NativeLlama.devices(), Charsets.UTF_8)

        override fun chat(modelPath: String, nCtx: Int, backend: String, request: ByteArray, callback: ILlamaCallback) {
            worker.execute { run(modelPath, nCtx, backend, request, callback) }
        }

        override fun cancel() {
            handle.takeIf { it != 0L }?.let(NativeLlama::cancel)
        }

        override fun unload() {
            worker.execute { release() }
        }
    }

    private fun run(path: String, nCtx: Int, backend: String, request: ByteArray, callback: ILlamaCallback) {
        try {
            if (devices == 0) {
                callback.onResult(errorJson("LOAD_FAILED", "no CPU backend for this phone (native libraries missing)"))
                return
            }
            if (handle == 0L || loadedPath != path || loadedCtx != nCtx || loadedBackend != backend) {
                release()
                callback.onStatus("loading")
                val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
                handle = NativeLlama.load(path, nCtx, threads, backend)
                if (handle == 0L) {
                    callback.onResult(errorJson("LOAD_FAILED", "could not load model (corrupt file or not enough memory)"))
                    return
                }
                loadedPath = path
                loadedCtx = nCtx
                loadedBackend = backend
            }
            callback.onStatus("generating")
            val sink = object : NativeLlama.DeltaSink {
                override fun onDelta(kind: Int, text: ByteArray) {
                    try {
                        callback.onDelta(kind, String(text, Charsets.UTF_8))
                    } catch (_: RemoteException) {
                        // Client is gone: stop generating for nobody.
                        NativeLlama.cancel(handle)
                    }
                }
            }
            val result = NativeLlama.chat(handle, request, sink)
            callback.onResult(String(result, Charsets.UTF_8))
        } catch (e: RemoteException) {
            Log.w(TAG, "client went away", e)
        } catch (t: Throwable) {
            Log.e(TAG, "chat failed", t)
            runCatching { callback.onResult(errorJson("ENGINE_ERROR", t.message ?: t.javaClass.simpleName)) }
        }
    }

    private fun release() {
        if (handle != 0L) {
            NativeLlama.free(handle)
            handle = 0L
            loadedPath = null
        }
    }

    private fun errorJson(code: String, message: String): String =
        """{"error":{"code":"$code","message":${org.json.JSONObject.quote(message)}}}"""

    private companion object {
        const val TAG = "LlamaService"
    }
}
