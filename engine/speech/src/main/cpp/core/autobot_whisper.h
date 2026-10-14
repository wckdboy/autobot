// Platform-neutral C ABI over whisper.cpp, shared by the Android JNI bridge (whisper_jni.cpp)
// and the iOS AutobotWhisper framework. One model stays loaded between calls.

#ifndef AUTOBOT_WHISPER_H
#define AUTOBOT_WHISPER_H

#include <stddef.h>

#if defined(_WIN32)
#define AUTOBOT_WHISPER_API
#else
#define AUTOBOT_WHISPER_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Transcribes `n_samples` of 16 kHz mono float PCM with the model at `model_path` (kept loaded
 * while the path and `use_gpu` stay the same). `language` is a code like "en", or "auto"/NULL.
 * On Apple builds with Core ML, an `<model>-encoder.mlmodelc` next to the model runs the encoder
 * on the Neural Engine. Returns JSON {text, language} or {error}; free with autobot_whisper_string_free.
 */
AUTOBOT_WHISPER_API char * autobot_whisper_transcribe(const char * model_path, const float * pcm, size_t n_samples,
                                                      const char * language, int use_gpu);

/** Asks a running transcription to stop (it then returns {error:"cancelled"}). Safe from any thread. */
AUTOBOT_WHISPER_API void autobot_whisper_cancel(void);

/** Frees the loaded model. */
AUTOBOT_WHISPER_API void autobot_whisper_free(void);

AUTOBOT_WHISPER_API void autobot_whisper_string_free(char * s);

#ifdef __cplusplus
}
#endif

#endif  // AUTOBOT_WHISPER_H
