package dev.wckdboy.autobot.engine.diffusion;

/** Events of one generation, in order. Image payloads are files in the app's private cache. */
oneway interface ISdCallback {
    /** "loading", "sampling", "decoding". */
    void onStatus(String status);
    void onProgress(int step, int steps);
    /** Low-res JPEG of the current latent. */
    void onPreview(String jpegPath);
    void onImage(int index, String pngPath, long seed, int width, int height);
    /** Last event: null on success, else an error message. */
    void onDone(String error);
}
