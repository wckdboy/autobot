// whisper.cpp engine core. No JNI or platform APIs in here.

#include "autobot_whisper.h"

#include <sys/stat.h>
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
bool g_external_encoder = false;      // the loaded model's encoder runs outside ggml (Core ML, ANEForge)
whisper_state * g_partial = nullptr;  // partial passes' own state (made on the first one)
std::atomic<bool> g_cancel{false};

// Partial passes: whisper's encoder covers 30 s (1500 frames, 50 a second) and pads shorter
// audio with silence; a partial pass encodes the window plus PARTIAL_MARGIN_FRAMES (1.28 s of the
// silence whisper expects after speech), rounded up to a multiple of 256 frames (5.12 s), up to
// the full context. The multiple matters: whisper.cpp's cross-attention reads the encoder output
// padded to 256 frames without a mask, so a context of 256 * k frames reads only what this pass
// encoded, while any other length would also read whatever an earlier pass left in the padding.
// For the same reason partial passes run in a whisper state of their own: a full-context pass
// reads 36 padded frames, which a shorter pass's encoding would overwrite in a shared state. So
// every pass without the flag (each window's closing and final pass, a voice note) computes
// exactly what it did before partial passes existed.
constexpr int FULL_AUDIO_CTX = 1500;
constexpr int PARTIAL_MARGIN_FRAMES = 64;
constexpr int PARTIAL_ROUND = 256;

int partial_audio_ctx(size_t n_samples) {
    const size_t frames = (n_samples * 50 + 15999) / 16000;
    const size_t ctx = (frames + PARTIAL_MARGIN_FRAMES + PARTIAL_ROUND - 1) / PARTIAL_ROUND * PARTIAL_ROUND;
    return ctx >= (size_t) FULL_AUDIO_CTX ? 0 : (int) ctx;
}

void free_model() {
    if (g_partial) whisper_free_state(g_partial);
    g_partial = nullptr;
    if (g_ctx) whisper_free(g_ctx);
    g_ctx = nullptr;
    g_path.clear();
}

// Whether whisper.cpp will run the encoder outside ggml for `model` (it then ignores audio_ctx
// and writes a full 30 s encoding): a Core ML encoder next to the model (the name whisper.cpp
// looks for: the model's name without extension and quantization suffix, plus
// "-encoder.mlmodelc"), or an ANEForge encoder.
bool external_encoder(const std::string & model) {
    if (std::getenv("ANEFORGE_ENCODER")) return true;
#if defined(__APPLE__)
    std::string path = model;
    auto pos = path.rfind('.');
    if (pos != std::string::npos) path = path.substr(0, pos);
    pos = path.rfind('-');
    if (pos != std::string::npos) {
        const std::string sub = path.substr(pos);
        if (sub.size() == 5 && sub[1] == 'q' && sub[3] == '_') path = path.substr(0, pos);
    }
    path += "-encoder.mlmodelc";
    struct stat st;
    return stat(path.c_str(), &st) == 0;
#else
    return false;
#endif
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

std::string transcribe(const std::string & model, const float * pcm, size_t n, const std::string & lang, bool use_gpu, int flags) {
    if (!g_ctx || g_path != model || g_gpu != use_gpu) {
        free_model();
        auto cp = whisper_context_default_params();
        cp.use_gpu = use_gpu;
        g_ctx = whisper_init_from_file_with_params(model.c_str(), cp);
        g_path = g_ctx ? model : std::string();
        g_gpu = use_gpu;
        g_external_encoder = g_ctx && external_encoder(model);
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
    whisper_state * state = nullptr;  // null: the context's own state
    if ((flags & AUTOBOT_WHISPER_PARTIAL) && !g_external_encoder && partial_audio_ctx(n) > 0) {
        if (!g_partial) g_partial = whisper_init_state(g_ctx);
        // No memory for a second state: this pass runs as a full one.
        if (g_partial) {
            state = g_partial;
            p.audio_ctx = partial_audio_ctx(n);
        }
    }
    const int rc = state ? whisper_full_with_state(g_ctx, state, p, pcm, (int) n) : whisper_full(g_ctx, p, pcm, (int) n);
    if (rc != 0) {
        return error(g_cancel.load() ? "cancelled" : "transcription failed");
    }
    std::string text;
    if (state) {
        for (int i = 0; i < whisper_full_n_segments_from_state(state); i++) text += whisper_full_get_segment_text_from_state(state, i);
    } else {
        for (int i = 0; i < whisper_full_n_segments(g_ctx); i++) text += whisper_full_get_segment_text(g_ctx, i);
    }
    const int lang_id = state ? whisper_full_lang_id_from_state(state) : whisper_full_lang_id(g_ctx);
    const char * detected = lang_id >= 0 ? whisper_lang_str(lang_id) : "";
    // Trim the leading space whisper puts before the first word.
    const auto start = text.find_first_not_of(' ');
    text = start == std::string::npos ? std::string() : text.substr(start);
    return "{\"text\":" + quote(text) + ",\"language\":" + quote(detected ? detected : "") + "}";
}

}  // namespace

extern "C" {

char * autobot_whisper_transcribe(const char * model_path, const float * pcm, size_t n_samples, const char * language, int use_gpu) {
    return autobot_whisper_transcribe_ex(model_path, pcm, n_samples, language, use_gpu, 0);
}

char * autobot_whisper_transcribe_ex(const char * model_path, const float * pcm, size_t n_samples, const char * language, int use_gpu,
                                     int flags) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_cancel.store(false);
    return dup(transcribe(model_path ? model_path : "", pcm, n_samples, language ? language : "", use_gpu != 0, flags));
}

int autobot_whisper_partial_audio_ctx(size_t n_samples) {
    return partial_audio_ctx(n_samples);
}

void autobot_whisper_cancel(void) {
    g_cancel.store(true);
}

void autobot_whisper_free(void) {
    std::lock_guard<std::mutex> guard(g_lock);
    free_model();
}

void autobot_whisper_string_free(char * s) {
    std::free(s);
}

}  // extern "C"
