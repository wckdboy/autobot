package dev.wckdboy.autobot.engine.llama

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** JNI surface of `libautobot_llama.so`. Only used inside the ":llm" process. */
internal object NativeLlama {
    init {
        System.loadLibrary("autobot_llama")
    }

    /** Loads the ggml backends found in [libDir]; returns how many devices are available. */
    external fun init(libDir: String): Int
    external fun systemInfo(): String
    external fun devices(): ByteArray

    /** Loads a model; 0 when it cannot be loaded or [abortLoad] stopped it. [loadId] is unique per load. */
    external fun load(path: String, nCtx: Int, nThreads: Int, backend: String, loadId: Long): Long

    /** Stops load [loadId] if it is still in progress (from any thread). */
    external fun abortLoad(loadId: Long)
    external fun modelInfo(handle: Long): ByteArray

    /** Runs one completion identified by [token] (non-zero, see [cancelRequest]). */
    external fun chat(handle: Long, token: Long, request: ByteArray, sink: DeltaSink): ByteArray
    external fun cancel(handle: Long)

    /** Stops the completion [token], running or yet to start (from any thread). */
    external fun cancelRequest(handle: Long, token: Long)
    external fun free(handle: Long)
    external fun embedLoad(path: String, nCtx: Int, nThreads: Int, backend: String): Long

    /** [texts] as UTF-8; n × dims floats, or null on failure. */
    external fun embed(handle: Long, texts: Array<ByteArray>): FloatArray?
    external fun embedFree(handle: Long)

    /** Called from native code for each streamed delta (UTF-8 bytes). */
    interface DeltaSink {
        fun onDelta(kind: Int, text: ByteArray)
    }
}

/**
 * Hosts llama.cpp in its own process. One chat model is resident at a time (plus the vault's
 * embedding model once used); requests run serially on a single worker thread so the KV cache
 * can be reused between agent steps.
 *
 * Each completion is known by its callback binder, so cancelling one (the client's flow closed)
 * stops exactly that one, also while it waits in the queue or while its model loads, and never
 * the completion that runs after it.
 */
class LlamaService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "llama-worker") }

    /** The loaded model; written by the worker, read by cancels on binder threads, both under [handleLock]. */
    @Volatile
    private var handle = 0L
    private val handleLock = Any()
    private var loadedPath: String? = null
    private var loadedCtx = 0
    private var loadedBackend: String? = null
    private var embedder = 0L
    private var embedderPath: String? = null

    /** Completions accepted and not finished, by callback binder; true once cancelled. */
    private val requests = ConcurrentHashMap<IBinder, Boolean>()

    /** The running completion's callback and native token (under [handleLock]). */
    private var running: IBinder? = null
    private var runningToken = 0L
    private val tokens = AtomicLong(1)

    /** A load in progress: for whom (a completion's callback, or [PREPARE]), which model ([modelKey]), its id. */
    private class Loading(val owner: Any, val model: String, val id: Long)

    @Volatile
    private var loading: Loading? = null
    private val loadIds = AtomicLong(1)

    /** Completions queued or running, so an ahead-of-time load never delays one. */
    private val queued = AtomicInteger(0)

    /**
     * Backends are initialized on first use, not in onCreate: a native failure there (OpenCL,
     * Vulkan or QNN on an unsupported SoC) would crash the service on every restart. 0 = none.
     */
    private val devices: Int by lazy {
        try {
            // Compiled OpenCL kernels are cached across launches (the first GPU load compiles them).
            Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", File(codeCacheDir, "opencl").path, true)
            // The Hexagon backend's DSP side (libggml-htp-v73…v81.so) is loaded by FastRPC from
            // ADSP_LIBRARY_PATH; the vendor directories keep the system's own DSP libraries reachable.
            val dsp = listOf(applicationInfo.nativeLibraryDir, "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp", "/vendor/dsp/dsp", "/vendor/dsp")
            Os.setenv("ADSP_LIBRARY_PATH", dsp.joinToString(";"), true)
            NativeLlama.init(applicationInfo.nativeLibraryDir)
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
            0
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        worker.execute { release() }
        worker.shutdown()
        super.onDestroy()
    }

    private val binder = object : ILlamaEngine.Stub() {
        override fun systemInfo(): String = if (devices == 0) "unavailable" else NativeLlama.systemInfo()

        override fun devices(): String = if (devices == 0) "[]" else String(NativeLlama.devices(), Charsets.UTF_8)

        override fun chat(modelPath: String, nCtx: Int, backend: String, request: ParcelFileDescriptor, callback: ILlamaCallback) {
            // Read now, on the binder thread: the descriptor is closed when this call returns.
            val bytes = try {
                ParcelFileDescriptor.AutoCloseInputStream(request).use { it.readBytes() }
            } catch (e: Exception) {
                runCatching { callback.onResult(errorJson("BAD_REQUEST", "cannot read the request: ${e.message}")) }
                return
            }
            val key = callback.asBinder()
            requests[key] = false
            queued.incrementAndGet()
            // An ahead-of-time load of another model only delays this request: stop it.
            loading?.takeIf { it.owner === PREPARE && it.model != modelKey(modelPath, nCtx, backend) }?.let { NativeLlama.abortLoad(it.id) }
            worker.execute {
                try {
                    run(modelPath, nCtx, backend, bytes, callback, key)
                } finally {
                    requests.remove(key)
                    queued.decrementAndGet()
                }
            }
        }

        /** Older clients: cancels the completion that runs or loads now (see [cancelChat]). */
        override fun cancel() {
            val load = loading
            val owner = (load?.owner as? IBinder) ?: synchronized(handleLock) { running }
            if (owner != null) this@LlamaService.cancelChat(owner) else if (load != null) NativeLlama.abortLoad(load.id)
        }

        override fun cancelChat(callback: ILlamaCallback) = this@LlamaService.cancelChat(callback.asBinder())

        override fun prepare(modelPath: String, nCtx: Int, backend: String) {
            worker.execute {
                // Completions came first: they load what they need themselves.
                if (queued.get() > 0 || devices == 0) return@execute
                runCatching { ensureLoaded(modelPath, nCtx, backend, PREPARE) }.onFailure { Log.w(TAG, "prepare failed", it) }
            }
        }

        override fun unload() {
            // A load in progress would only be freed again: stop it.
            loading?.let { NativeLlama.abortLoad(it.id) }
            worker.execute { release() }
        }

        override fun embed(modelPath: String, texts: Array<String>): FloatArray? =
            runCatching { worker.submit<FloatArray?> { runEmbed(modelPath, texts) }.get() }
                .onFailure { Log.e(TAG, "embed failed", it) }
                .getOrNull()
    }

    /** Stops the completion reporting to [key]: queued, loading its model, or running. Any thread. */
    private fun cancelChat(key: IBinder) {
        if (requests.replace(key, true) == null) return // finished already
        loading?.takeIf { it.owner === key }?.let { NativeLlama.abortLoad(it.id) }
        synchronized(handleLock) {
            if (running === key && handle != 0L) NativeLlama.cancelRequest(handle, runningToken)
        }
    }

    private fun sameModel(path: String, nCtx: Int, backend: String) = loadedPath == path && loadedCtx == nCtx && loadedBackend == backend

    private fun modelKey(path: String, nCtx: Int, backend: String) = "$path|$nCtx|$backend"

    private fun runEmbed(path: String, texts: Array<String>): FloatArray? {
        if (devices == 0) return null
        if (embedder == 0L || embedderPath != path) {
            releaseEmbedder()
            val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
            embedder = NativeLlama.embedLoad(path, EMBED_CTX, threads, "cpu")
            if (embedder == 0L) return null
            embedderPath = path
        }
        return NativeLlama.embed(embedder, Array(texts.size) { texts[it].toByteArray(Charsets.UTF_8) })
    }

    private fun releaseEmbedder() {
        if (embedder != 0L) {
            NativeLlama.embedFree(embedder)
            embedder = 0L
            embedderPath = null
        }
    }

    /** Loads the model unless it is resident (worker thread). False when it cannot be loaded or the load was stopped. */
    private fun ensureLoaded(path: String, nCtx: Int, backend: String, owner: Any): Boolean {
        if (handle != 0L && sameModel(path, nCtx, backend)) return true
        release()
        val threads = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
        val load = Loading(owner, modelKey(path, nCtx, backend), loadIds.getAndIncrement())
        loading = load
        val loaded = try {
            NativeLlama.load(path, nCtx, threads, backend, load.id)
        } finally {
            loading = null
        }
        synchronized(handleLock) { handle = loaded }
        if (loaded == 0L) return false
        loadedPath = path
        loadedCtx = nCtx
        loadedBackend = backend
        return true
    }

    private fun run(path: String, nCtx: Int, backend: String, request: ByteArray, callback: ILlamaCallback, key: IBinder) {
        try {
            if (requests[key] == true) {
                callback.onResult(CANCELLED)
                return
            }
            val reload = handle == 0L || !sameModel(path, nCtx, backend)
            // A file that cannot work says why, before (and instead of) a load that would fail or crash.
            val header = if (reload) {
                val (header, problem) = GgufCheck.inspect(path)
                if (problem != null) {
                    callback.onResult(errorJson("LOAD_FAILED", problem.message))
                    return
                }
                header
            } else {
                null
            }
            if (devices == 0) {
                callback.onResult(errorJson("LOAD_FAILED", "no CPU backend for this phone (native libraries missing)"))
                return
            }
            if (reload) {
                callback.onStatus("loading")
                if (!ensureLoaded(path, nCtx, backend, key)) {
                    val arch = header?.architecture?.let { " ($it)" }.orEmpty()
                    callback.onResult(
                        if (requests[key] == true) CANCELLED
                        else errorJson("LOAD_FAILED", "could not load model$arch: an architecture this version can't run, a corrupt file, or not enough memory"),
                    )
                    return
                }
            }
            val token = tokens.getAndIncrement()
            synchronized(handleLock) {
                running = key
                runningToken = token
                // A cancel that came before `running` was set did not reach the engine: pass it on.
                if (requests[key] == true) NativeLlama.cancelRequest(handle, token)
            }
            callback.onStatus("generating")
            val sink = object : NativeLlama.DeltaSink {
                override fun onDelta(kind: Int, text: ByteArray) {
                    try {
                        callback.onDelta(kind, String(text, Charsets.UTF_8))
                    } catch (_: RemoteException) {
                        // Client is gone: stop generating for nobody (same thread as release: no race).
                        NativeLlama.cancelRequest(handle, token)
                    }
                }
            }
            val result = try {
                NativeLlama.chat(handle, token, request, sink)
            } finally {
                synchronized(handleLock) {
                    running = null
                    runningToken = 0L
                }
            }
            callback.onResult(String(result, Charsets.UTF_8))
        } catch (e: RemoteException) {
            Log.w(TAG, "client went away", e)
        } catch (t: Throwable) {
            Log.e(TAG, "chat failed", t)
            runCatching { callback.onResult(errorJson("ENGINE_ERROR", t.message ?: t.javaClass.simpleName)) }
        }
    }

    private fun release() {
        releaseEmbedder()
        // Under the lock: a cancel from a binder thread must never see a freed handle.
        synchronized(handleLock) {
            if (handle != 0L) {
                NativeLlama.free(handle)
                handle = 0L
                loadedPath = null
            }
        }
    }

    private fun errorJson(code: String, message: String): String =
        """{"error":{"code":"$code","message":${org.json.JSONObject.quote(message)}}}"""

    private companion object {
        const val TAG = "LlamaService"

        /** Passages are about 512 tokens plus the file name; queries carry an instruction. */
        const val EMBED_CTX = 1024

        /** [Loading.owner] of an ahead-of-time load. */
        val PREPARE = Any()

        const val CANCELLED = """{"error":{"code":"CANCELLED","message":"cancelled"}}"""
    }
}
