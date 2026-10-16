// JNI bridge for llama.cpp, used only inside the isolated ":llm" process.
//
// A thin wrapper over the platform-neutral core (core/autobot_llama.h), which the iOS app links
// too. Handles are `autobot_llama_session *`; deltas reach the Kotlin sink as UTF-8 bytes.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "core/autobot_llama.h"

#define TAG "autobot-llama"

namespace {

void log_callback(int level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    if (level == AUTOBOT_LLAMA_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == AUTOBOT_LLAMA_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == AUTOBOT_LLAMA_LOG_INFO) prio = ANDROID_LOG_INFO;
    __android_log_write(prio, TAG, text);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Kotlin decodes these with `String(bytes, UTF_8)`: NewStringUTF would choke on 4-byte UTF-8.
jbyteArray bytes(JNIEnv * env, const char * s, size_t n) {
    jbyteArray arr = env->NewByteArray((jsize) n);
    env->SetByteArrayRegion(arr, 0, (jsize) n, reinterpret_cast<const jbyte *>(s));
    return arr;
}

// Takes ownership of a string returned by the core.
jbyteArray owned_bytes(JNIEnv * env, char * s) {
    if (!s) return bytes(env, "", 0);
    jbyteArray arr = bytes(env, s, std::char_traits<char>::length(s));
    autobot_llama_string_free(s);
    return arr;
}

// The load abortLoad() asked to stop (from a binder thread). Ids are unique per load, so a late
// abort for a finished load never reaches the next one.
std::atomic<int64_t> g_abort_load{0};

autobot_llama_session * session(jlong handle) {
    return reinterpret_cast<autobot_llama_session *>(handle);
}

struct Sink {
    JNIEnv * env;
    jobject obj;
    jmethodID on_delta;
};

// A Java exception thrown by the sink stops generation (the result then finishes "cancelled").
int on_delta(int kind, const char * text, size_t len, void * user) {
    auto * sink = static_cast<Sink *>(user);
    JNIEnv * env = sink->env;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 1;
    }
    jbyteArray arr = bytes(env, text, len);
    env->CallVoidMethod(sink->obj, sink->on_delta, (jint) kind, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 1;
    }
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_init(JNIEnv * env, jobject, jstring lib_dir) {
    autobot_llama_set_log(log_callback, nullptr);
    return (jint) autobot_llama_init(jstr(env, lib_dir).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(autobot_llama_system_info());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_devices(JNIEnv * env, jobject) {
    return owned_bytes(env, autobot_llama_devices_json());
}

/** Loads a model; [load_id] (unique per load) lets abortLoad stop exactly this load. */
extern "C" JNIEXPORT jlong JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_load(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jstring jbackend, jlong load_id) {
    const std::string path = jstr(env, jpath);
    const std::string backend = jstr(env, jbackend);
    int64_t id = load_id;
    autobot_llama_session * s = autobot_llama_load_abortable(path.c_str(), (int) n_ctx, (int) n_threads, backend.c_str(), [](void * user) {
        return g_abort_load.load(std::memory_order_relaxed) == *static_cast<int64_t *>(user) ? 1 : 0;
    }, &id);
    return reinterpret_cast<jlong>(s);
}

/** Stops load [load_id] if it is in progress (it then returns 0); otherwise a no-op. Any thread. */
extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_abortLoad(JNIEnv *, jobject, jlong load_id) {
    g_abort_load.store(load_id, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_modelInfo(JNIEnv * env, jobject, jlong handle) {
    return owned_bytes(env, autobot_llama_model_info(session(handle)));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_cancel(JNIEnv *, jobject, jlong handle) {
    autobot_llama_cancel(session(handle));
}

/** Stops the chat started with [token], whether it runs already or has yet to start. Any thread. */
extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_cancelRequest(JNIEnv *, jobject, jlong handle, jlong token) {
    autobot_llama_cancel_request(session(handle), (uint64_t) token);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_free(JNIEnv *, jobject, jlong handle) {
    autobot_llama_free(session(handle));
}

/**
 * Runs one chat completion identified by [token] (non-zero, for cancelRequest); see
 * autobot_llama_chat for the request and result shapes.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_chat(JNIEnv * env, jobject, jlong handle, jlong token, jbyteArray jrequest, jobject jsink) {
    std::string request;
    {
        const jsize n = env->GetArrayLength(jrequest);
        request.resize((size_t) n);
        env->GetByteArrayRegion(jrequest, 0, n, reinterpret_cast<jbyte *>(&request[0]));
    }
    Sink sink{env, jsink, env->GetMethodID(env->GetObjectClass(jsink), "onDelta", "(I[B)V")};
    return owned_bytes(env, autobot_llama_chat_request(session(handle), (uint64_t) token, request.data(), request.size(), on_delta, &sink));
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_embedLoad(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jstring jbackend) {
    const std::string path = jstr(env, jpath);
    const std::string backend = jstr(env, jbackend);
    return reinterpret_cast<jlong>(autobot_llama_embed_load(path.c_str(), (int) n_ctx, (int) n_threads, backend.c_str()));
}

/** Embeds UTF-8 texts: n × dims floats, each vector L2-normalised; null on failure. */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_embed(JNIEnv * env, jobject, jlong handle, jobjectArray jtexts) {
    auto * e = reinterpret_cast<autobot_llama_embedder *>(handle);
    const jsize n = env->GetArrayLength(jtexts);
    const int dims = autobot_llama_embed_dims(e);
    std::vector<std::string> texts((size_t) n);
    for (jsize i = 0; i < n; ++i) {
        auto arr = static_cast<jbyteArray>(env->GetObjectArrayElement(jtexts, i));
        const jsize len = env->GetArrayLength(arr);
        texts[(size_t) i].resize((size_t) len);
        if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&texts[(size_t) i][0]));
        env->DeleteLocalRef(arr);
    }
    std::vector<const char *> ptrs;
    std::vector<size_t> lens;
    for (const auto & t : texts) {
        ptrs.push_back(t.data());
        lens.push_back(t.size());
    }
    std::vector<float> out((size_t) n * (size_t) std::max(dims, 0));
    if (dims <= 0 || autobot_llama_embed(e, ptrs.data(), lens.data(), (int) n, out.data()) != 0) return nullptr;
    jfloatArray result = env->NewFloatArray((jsize) out.size());
    env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_llama_NativeLlama_embedFree(JNIEnv *, jobject, jlong handle) {
    autobot_llama_embed_free(reinterpret_cast<autobot_llama_embedder *>(handle));
}
