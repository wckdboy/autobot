// JNI bridge for whisper.cpp, used only inside the isolated ":asr" process.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "autobot-whisper"

namespace {

std::mutex g_lock;
whisper_context * g_ctx = nullptr;
std::string g_path;

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
        if (c == '\n') { o += "\\n"; continue; }
        o += (unsigned char) c < 0x20 ? ' ' : c;
    }
    return o + "\"";
}

}  // namespace

/**
 * Transcribes 16 kHz mono float PCM from [pcm_path] with the model at [model_path] (kept loaded
 * between calls). [language] is a code like "en", or "auto". Returns {text, language} or {error}.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_transcribe(JNIEnv * env, jobject, jstring jmodel, jstring jpcm, jstring jlang) {
    std::lock_guard<std::mutex> guard(g_lock);
    const std::string model = jstr(env, jmodel);
    if (!g_ctx || g_path != model) {
        if (g_ctx) whisper_free(g_ctx);
        auto cp = whisper_context_default_params();
        cp.use_gpu = false;
        g_ctx = whisper_init_from_file_with_params(model.c_str(), cp);
        g_path = g_ctx ? model : std::string();
        if (!g_ctx) return bytes(env, "{\"error\":" + quote("cannot load speech model") + "}");
    }
    std::vector<float> pcm;
    if (FILE * f = std::fopen(jstr(env, jpcm).c_str(), "rb")) {
        float buf[4096];
        size_t n;
        while ((n = std::fread(buf, sizeof(float), 4096, f)) > 0) pcm.insert(pcm.end(), buf, buf + n);
        std::fclose(f);
    }
    if (pcm.size() < 1600) return bytes(env, "{\"text\":\"\"}");

    const std::string lang = jstr(env, jlang);
    auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(2, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.no_timestamps = true;
    p.translate = false;
    p.language = lang.empty() ? "auto" : lang.c_str();
    p.detect_language = false;
    if (whisper_full(g_ctx, p, pcm.data(), (int) pcm.size()) != 0) {
        return bytes(env, "{\"error\":" + quote("transcription failed") + "}");
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(g_ctx); i++) text += whisper_full_get_segment_text(g_ctx, i);
    const int lang_id = whisper_full_lang_id(g_ctx);
    const char * detected = lang_id >= 0 ? whisper_lang_str(lang_id) : "";
    // Trim the leading space whisper puts before the first word.
    const auto start = text.find_first_not_of(' ');
    text = start == std::string::npos ? std::string() : text.substr(start);
    return bytes(env, "{\"text\":" + quote(text) + ",\"language\":" + quote(detected ? detected : "") + "}");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_wckdboy_autobot_engine_speech_NativeWhisper_free(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx) whisper_free(g_ctx);
    g_ctx = nullptr;
    g_path.clear();
}
