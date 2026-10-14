// Platform-neutral C ABI over llama.cpp, shared by the Android JNI bridge (llama_jni.cpp) and the
// iOS AutobotLlama framework.
//
// One session = one loaded model + context. Requests are OpenAI-shaped JSON (messages, tools,
// sampling); responses stream as UTF-8 deltas through a callback and end with a JSON result
// (content, reasoning, tool calls, usage). Strings returned as `char *` are heap-allocated and
// must be released with autobot_llama_string_free().

#ifndef AUTOBOT_LLAMA_H
#define AUTOBOT_LLAMA_H

#include <stddef.h>

#if defined(_WIN32)
#define AUTOBOT_LLAMA_API
#else
#define AUTOBOT_LLAMA_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct autobot_llama_session autobot_llama_session;

enum {
    AUTOBOT_LLAMA_DELTA_CONTENT = 0,
    AUTOBOT_LLAMA_DELTA_REASONING = 1,
};

enum {
    AUTOBOT_LLAMA_LOG_DEBUG = 0,
    AUTOBOT_LLAMA_LOG_INFO = 1,
    AUTOBOT_LLAMA_LOG_WARN = 2,
    AUTOBOT_LLAMA_LOG_ERROR = 3,
};

/**
 * Receives one streamed delta: `len` bytes of complete UTF-8 (never split inside a code point,
 * not NUL-terminated). Return non-zero to stop generating (the result then finishes "cancelled").
 */
typedef int (*autobot_llama_delta_fn)(int kind, const char * bytes, size_t len, void * user);

/** Receives engine and llama.cpp log lines (NUL-terminated, usually ending in '\n'). */
typedef void (*autobot_llama_log_fn)(int level, const char * text, void * user);

/** Routes logs to `fn` (NULL silences them). Call before autobot_llama_init to see its output. */
AUTOBOT_LLAMA_API void autobot_llama_set_log(autobot_llama_log_fn fn, void * user);

/**
 * Loads the ggml backends found in `lib_dir` (dynamically loaded backends, Android) and
 * initialises llama.cpp. `lib_dir` may be NULL or empty when backends are linked in (Apple).
 * Returns how many compute devices are available.
 */
AUTOBOT_LLAMA_API int autobot_llama_init(const char * lib_dir);

/** llama.cpp's system info line (static string, do not free). */
AUTOBOT_LLAMA_API const char * autobot_llama_system_info(void);

/** JSON array of devices: [{name, description, kind: "cpu"|"gpu"|"npu", memory_free, memory_total}]. */
AUTOBOT_LLAMA_API char * autobot_llama_devices_json(void);

/**
 * Loads a model on `backend` ("cpu", "gpu", "npu" or "auto" = npu → gpu → cpu). If the model or
 * its context cannot be created on an accelerator, it falls back to the CPU; the session records
 * where it actually runs. Returns NULL when the model cannot be loaded at all.
 */
AUTOBOT_LLAMA_API autobot_llama_session * autobot_llama_load(const char * path, int n_ctx, int n_threads, const char * backend);

/** JSON: {description, n_params, size_bytes, n_ctx, n_ctx_train, chat_template, backend}. */
AUTOBOT_LLAMA_API char * autobot_llama_model_info(autobot_llama_session * session);

/**
 * Runs one chat completion (blocking; calls on one session are serialised). `request` is
 * OpenAI-shaped: {messages, tools?, temperature?, top_p?, max_tokens?, seed?, enable_thinking?,
 * mmproj?}; image parts are {type:"image", path}. Returns JSON: {content, reasoning,
 * tool_calls:[{id,name,arguments}], finish_reason, usage:{prompt_tokens, completion_tokens,
 * cached_tokens, backend, prompt_ms, generation_ms}} or {error:{code,message}}.
 */
AUTOBOT_LLAMA_API char * autobot_llama_chat(autobot_llama_session * session, const char * request, size_t request_len,
                                            autobot_llama_delta_fn on_delta, void * user);

/** Asks a running chat on `session` to stop at the next token. Safe from any thread. */
AUTOBOT_LLAMA_API void autobot_llama_cancel(autobot_llama_session * session);

/** Frees the session (waits for a running chat to return). */
AUTOBOT_LLAMA_API void autobot_llama_free(autobot_llama_session * session);

AUTOBOT_LLAMA_API void autobot_llama_string_free(char * s);

#ifdef __cplusplus
}
#endif

#endif  // AUTOBOT_LLAMA_H
