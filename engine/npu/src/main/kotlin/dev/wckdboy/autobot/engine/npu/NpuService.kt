package dev.wckdboy.autobot.engine.npu

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Hosts the NPU/GPU image engine (QNN + MNN) in its own process. Jobs run serially on one
 * worker thread; loaded models stay resident between jobs until [INpuEngine.unload].
 */
class NpuService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "npu-worker") }

    /**
     * QNN/MNN start on first use, not in onCreate: a native failure there (unsupported SoC) would
     * crash the service again on every restart. [Init.status] is the `{npu, arch, error}` JSON.
     */
    private class Init(val ready: Boolean, val status: String)

    private val init: Init by lazy {
        try {
            // The DSP loads the HTP skeleton libraries (libQnnHtpV*Skel.so) from these directories.
            val dsp = listOf(applicationInfo.nativeLibraryDir, "/vendor/dsp/cdsp", "/vendor/lib/rfsa/adsp", "/system/lib/rfsa/adsp", "/vendor/dsp/dsp", "/vendor/dsp")
            Os.setenv("ADSP_LIBRARY_PATH", dsp.joinToString(";"), true)
            Init(true, String(NativeNpu.init(applicationInfo.nativeLibraryDir), Charsets.UTF_8).also { Log.i(TAG, "engine status $it") })
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
            Init(false, """{"npu":false,"arch":"","error":${JSONObject.quote("cannot start: ${t.javaClass.simpleName}")}}""")
        }
    }

    private val nativeReady: Boolean get() = init.ready

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private val binder = object : INpuEngine.Stub() {
        override fun status(): String = init.status

        override fun inspect(path: String): String =
            if (nativeReady) String(NativeNpu.inspect(path), Charsets.UTF_8) else """{"error":"engine unavailable"}"""

        override fun generate(request: ByteArray, callback: INpuCallback) {
            worker.execute { run(request, callback) }
        }

        override fun cancel() {
            if (nativeReady) NativeNpu.cancel()
        }

        override fun unload() {
            worker.execute { if (nativeReady) NativeNpu.unload() }
        }
    }

    private fun run(request: ByteArray, callback: INpuCallback) {
        val sink = object : NativeNpu.Sink {
            override fun onStatus(status: String) = report { callback.onStatus(status) }
            override fun onProgress(step: Int, steps: Int) = report { callback.onProgress(step, steps) }
            override fun onImage(index: Int, path: String, width: Int, height: Int, seed: Long) =
                report { callback.onImage(index, path, width, height, seed) }

            private fun report(block: () -> Unit) {
                try {
                    block()
                } catch (_: RemoteException) {
                    NativeNpu.cancel()  // the client is gone: stop working for nobody
                }
            }
        }
        val result = try {
            check(nativeReady) { "the NPU engine cannot start on this phone" }
            File(codeCacheDir, "mnn").mkdirs()
            String(NativeNpu.run(request, File(codeCacheDir, "mnn").path, sink), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.e(TAG, "job failed", t)
            """{"error":{"code":"ENGINE_ERROR","message":${JSONObject.quote(t.message ?: t.javaClass.simpleName)}}}"""
        }
        runCatching { callback.onResult(result) }
    }

    private companion object {
        const val TAG = "NpuService"
    }
}
