package dev.wckdboy.autobot.engine.llama;

import android.os.ParcelFileDescriptor;
import dev.wckdboy.autobot.engine.llama.ILlamaCallback;

interface ILlamaEngine {
    String systemInfo();

    /** JSON array of compute devices: {name, description, kind: cpu|gpu|npu, memory_free, memory_total}. */
    String devices();

    /**
     * Loads [modelPath] on [backend] ("cpu", "gpu", "npu" or "auto") if needed (one model at a
     * time) and runs an OpenAI-shaped request, read as UTF-8 JSON from [request] (a file: long
     * histories exceed a binder transaction). The result's usage reports where it really ran.
     */
    oneway void chat(String modelPath, int nCtx, String backend, in ParcelFileDescriptor request, ILlamaCallback callback);

    /** Stops the running completion at the next token. */
    oneway void cancel();

    /** Frees the loaded model. */
    oneway void unload();
}
