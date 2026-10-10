package dev.wckdboy.autobot.engine.npu;

/** Events of one image job. Delivered in order on a binder thread. */
oneway interface INpuCallback {
    /** Phase change: "loading", "encoding", "denoising", "decoding", "upscaling". */
    void onStatus(String status);

    /** Denoising progress. */
    void onProgress(int step, int steps);

    /** A finished image (PNG/RGB file written by the engine into the app cache). */
    void onImage(int index, String path, int width, int height, long seed);

    /** Final JSON: {timings:{...}, backend} or {error:{code,message}}. */
    void onResult(String json);
}
