package dev.wckdboy.autobot.engine.speech;

interface ISpeechEngine {
    /**
     * Transcribes 16 kHz mono float32 PCM from [pcmPath] with the whisper model at [modelPath].
     * [language] is an ISO code or "auto". Returns {text, language} or {error}.
     */
    String transcribe(String modelPath, String pcmPath, String language);

    /** Frees the loaded model. */
    oneway void unload();
}
