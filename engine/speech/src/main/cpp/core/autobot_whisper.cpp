// whisper.cpp engine core. No JNI or platform APIs in here.

#include "autobot_whisper.h"

#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

#include "whisper.h"

namespace {

std::mutex g_lock;
whisper_context * g_ctx = nullptr;
std::string g_path;
bool g_gpu = false;
std::atomic<bool> g_cancel{false};

std::string quote(const std::string & s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if (c == '\n') { o += "\\n"; continue; }
        o += (unsigned char) c < 0x20 ? ' ' : c;
    }
    return o + "\"";
}

char * dup(const std::string & s) {
    char * out = static_cast<char *>(std::malloc(s.size() + 1));
    if (!out) return nullptr;
    std::memcpy(out, s.data(), s.size());
    out[s.size()] = '\0';
    return out;
}

std::string error(const std::string & message) {
    return "{\"error\":" + quote(message) + "}";
}

std::string transcribe(const std::string & model, const float * pcm, size_t n, const std::string & lang, bool use_gpu) {
    if (!g_ctx || g_path != model || g_gpu != use_gpu) {
        if (g_ctx) whisper_free(g_ctx);
        auto cp = whisper_context_default_params();
        cp.use_gpu = use_gpu;
        g_ctx = whisper_init_from_file_with_params(model.c_str(), cp);
        g_path = g_ctx ? model : std::string();
        g_gpu = use_gpu;
        if (!g_ctx) return error("cannot load speech model");
    }
    if (n < 1600) return "{\"text\":\"\"}";

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
    p.abort_callback = [](void *) { return g_cancel.load(); };
    if (whisper_full(g_ctx, p, pcm, (int) n) != 0) {
        return error(g_cancel.load() ? "cancelled" : "transcription failed");
    }
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(g_ctx); i++) text += whisper_full_get_segment_text(g_ctx, i);
    const int lang_id = whisper_full_lang_id(g_ctx);
    const char * detected = lang_id >= 0 ? whisper_lang_str(lang_id) : "";
    // Trim the leading space whisper puts before the first word.
    const auto start = text.find_first_not_of(' ');
    text = start == std::string::npos ? std::string() : text.substr(start);
    return "{\"text\":" + quote(text) + ",\"language\":" + quote(detected ? detected : "") + "}";
}

}  // namespace

extern "C" {

char * autobot_whisper_transcribe(const char * model_path, const float * pcm, size_t n_samples, const char * language, int use_gpu) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_cancel.store(false);
    return dup(transcribe(model_path ? model_path : "", pcm, n_samples, language ? language : "", use_gpu != 0));
}

void autobot_whisper_cancel(void) {
    g_cancel.store(true);
}

void autobot_whisper_free(void) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_ctx) whisper_free(g_ctx);
    g_ctx = nullptr;
    g_path.clear();
}

void autobot_whisper_string_free(char * s) {
    std::free(s);
}

}  // extern "C"
