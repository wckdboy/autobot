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

/**
 * A live partial transcription (streaming dictation): the window is still growing and its text
 * will be revised. The encoder then looks at the window's own length plus a margin instead of
 * whisper's padded 30 s (whisper.cpp `audio_ctx`, in steps of 5.12 s), which makes short windows
 * several times cheaper; the text may differ slightly from a full pass. Partial passes run in a
 * whisper state of their own (its memory, about 170 MB for base, is held while the model stays
 * loaded), so passes without the flag compute exactly what they did before. Ignored when an
 * external encoder runs (Core ML: fixed 30 s input).
 */
#define AUTOBOT_WHISPER_PARTIAL 1

/** As autobot_whisper_transcribe, with AUTOBOT_WHISPER_* `flags`. */
AUTOBOT_WHISPER_API char * autobot_whisper_transcribe_ex(const char * model_path, const float * pcm, size_t n_samples,
                                                         const char * language, int use_gpu, int flags);

/**
 * The encoder context (whisper `audio_ctx`, 50 frames a second, at most 1500) a partial pass over
 * `n_samples` of 16 kHz audio uses; 0 means the full context.
 */
AUTOBOT_WHISPER_API int autobot_whisper_partial_audio_ctx(size_t n_samples);

/** Asks a running transcription to stop (it then returns {error:"cancelled"}). Safe from any thread. */
AUTOBOT_WHISPER_API void autobot_whisper_cancel(void);

/** Frees the loaded model. */
AUTOBOT_WHISPER_API void autobot_whisper_free(void);

AUTOBOT_WHISPER_API void autobot_whisper_string_free(char * s);

#ifdef __cplusplus
}
#endif

#endif  // AUTOBOT_WHISPER_H
