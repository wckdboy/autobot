package dev.wckdboy.autobot.engine.llama;

import dev.wckdboy.autobot.engine.llama.ILlamaCallback;

interface ILlamaEngine {
    String systemInfo();

    /** Loads [modelPath] if needed (one model at a time) and runs an OpenAI-shaped request. */
    oneway void chat(String modelPath, int nCtx, in byte[] request, ILlamaCallback callback);

    /** Stops the running completion at the next token. */
    oneway void cancel();

    /** Frees the loaded model. */
    oneway void unload();
}
