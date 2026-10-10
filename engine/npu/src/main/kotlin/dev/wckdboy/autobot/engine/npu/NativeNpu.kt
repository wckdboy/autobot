package dev.wckdboy.autobot.engine.npu

/** JNI surface of `libautobot_npu.so`. Only used inside the ":npu" process. */
internal object NativeNpu {
    init {
        System.loadLibrary("autobot_npu")
    }

    /** Opens the Hexagon NPU through QNN; JSON `{npu, arch, error}`. */
    external fun init(libDir: String): ByteArray

    /** Describes a QNN context binary (graphs, tensors, SoC, build) as JSON. */
    external fun inspect(path: String): ByteArray

    /** Runs one job (`generate` or `upscale`) and returns the result JSON. */
    external fun run(request: ByteArray, cacheDir: String, sink: Sink): ByteArray

    external fun cancel()

    external fun unload()

    /** Called from native code during a job (looked up by name: keep in sync with npu_jni.cpp). */
    interface Sink {
        fun onStatus(status: String)
        fun onProgress(step: Int, steps: Int)
        fun onImage(index: Int, path: String, width: Int, height: Int, seed: Long)
    }
}
