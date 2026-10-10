// JNI bridge of the NPU/GPU image engine, used only inside the isolated ":npu" process.

#include <android/log.h>
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "qnn_runtime.h"
#include "sd_pipeline.h"

#define TAG "autobot-npu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

std::mutex g_lock;
std::unique_ptr<npu::QnnRuntime> g_qnn;
std::string g_qnn_error;
std::unique_ptr<npu::Engine> g_engine;
std::mutex g_run_lock;  // one job at a time

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jbyteArray bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::string quote(const std::string & s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        o += (unsigned char) c < 0x20 ? ' ' : c;
    }
    return o + "\"";
}

}  // namespace

/** Opens the HTP backend; returns {"npu":bool,"arch":"v79","error":...}. */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_npu_NativeNpu_init(JNIEnv * env, jobject, jstring jlib_dir) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_qnn) {
        g_qnn = std::make_unique<npu::QnnRuntime>();
        if (!g_qnn->init(jstr(env, jlib_dir), &g_qnn_error)) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "NPU unavailable: %s", g_qnn_error.c_str());
        }
    }
    const bool ok = g_qnn->ready();
    std::string json = std::string("{\"npu\":") + (ok ? "true" : "false") + ",\"arch\":" + quote(g_qnn->arch()) +
                       ",\"error\":" + quote(ok ? "" : g_qnn_error) + "}";
    return bytes(env, json);
}

/**
 * Runs one job (see sd_pipeline.h) and returns the result JSON. Progress goes to [sink]:
 * onStatus(String), onProgress(int, int), onImage(int, String, int, int, long).
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_npu_NativeNpu_run(JNIEnv * env, jobject, jbyteArray jrequest, jstring jcache_dir, jobject sink) {
    std::string request;
    {
        const jsize n = env->GetArrayLength(jrequest);
        request.resize((size_t) n);
        env->GetByteArrayRegion(jrequest, 0, n, reinterpret_cast<jbyte *>(&request[0]));
    }
    jclass cls = env->GetObjectClass(sink);
    jmethodID on_status = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
    jmethodID on_progress = env->GetMethodID(cls, "onProgress", "(II)V");
    jmethodID on_image = env->GetMethodID(cls, "onImage", "(ILjava/lang/String;IIJ)V");
    npu::Events events;
    events.status = [&](const std::string & s) {
        jstring js = env->NewStringUTF(s.c_str());
        env->CallVoidMethod(sink, on_status, js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    events.progress = [&](int step, int steps) {
        env->CallVoidMethod(sink, on_progress, step, steps);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    events.image = [&](int index, const std::string & path, int w, int h, int64_t seed) {
        jstring jp = env->NewStringUTF(path.c_str());
        env->CallVoidMethod(sink, on_image, index, jp, w, h, (jlong) seed);
        env->DeleteLocalRef(jp);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };
    std::string result;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        if (!g_engine) g_engine = std::make_unique<npu::Engine>(g_qnn.get(), jstr(env, jcache_dir));
    }
    std::lock_guard<std::mutex> run_guard(g_run_lock);
    result = g_engine->run(request, events);
    return bytes(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_npu_NativeNpu_cancel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_engine) g_engine->cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_npu_NativeNpu_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> run_guard(g_run_lock);
    if (g_engine) g_engine->unload();
}

/** Graphs, tensors, SoC and SDK build of a QNN context binary, as JSON (does not load it on the NPU). */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_npu_NativeNpu_inspect(JNIEnv * env, jobject, jstring jpath) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_qnn || !g_qnn->ready()) return bytes(env, "{\"error\":" + quote("NPU unavailable: " + g_qnn_error) + "}");
    std::string error;
    std::string json = g_qnn->inspect(jstr(env, jpath), &error);
    return bytes(env, json.empty() ? "{\"error\":" + quote(error) + "}" : json);
}
