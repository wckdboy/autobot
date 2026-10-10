package dev.wckdboy.autobot.engine.npu;

import dev.wckdboy.autobot.engine.npu.INpuCallback;

interface INpuEngine {
    /** JSON {npu: bool, arch: "v79", error}: whether the Hexagon NPU is usable on this phone. */
    String status();

    /** JSON description of a QNN context binary (graphs, tensors, SoC, build) without loading it. */
    String inspect(String path);

    /** Runs one generation job described by an OpenAI-free JSON request (see NpuService). */
    oneway void generate(in byte[] request, INpuCallback callback);

    /** Stops the running job at the next step. */
    oneway void cancel();

    /** Frees all loaded models. */
    oneway void unload();
}
