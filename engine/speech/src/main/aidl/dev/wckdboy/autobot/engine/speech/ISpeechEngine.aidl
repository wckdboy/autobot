package dev.wckdboy.autobot.engine.speech;

interface ISpeechEngine {
    /** Frees the loaded model. */
    oneway void unload();

    /**
     * Transcribes [samples] 16 kHz mono float32 samples (little-endian) read from [pcm], an
     * anonymous in-memory file (memfd), with the whisper model at [modelPath]: dictation never
     * writes audio to storage. [language] is an ISO code or "auto". Returns {text, language} or
     * {error}.
     */
    String transcribeMemory(String modelPath, in ParcelFileDescriptor pcm, int samples, String language);

    /**
     * Asks the running transcription to stop now (it returns {error:"cancelled"}). Synchronous, so
     * it cannot reach the engine after the caller has started the next transcription.
     */
    void cancel();
}
