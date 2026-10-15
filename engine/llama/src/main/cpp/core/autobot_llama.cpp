// llama.cpp engine core: chat templates, tool-call grammars and parsing come from llama.cpp
// `common` (the same code llama-server uses). The KV cache keeps the previous prompt so
// multi-step agent turns only decode the new suffix. No JNI or platform APIs in here.

#include "autobot_llama.h"

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

struct autobot_llama_session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached;  // tokens currently in the KV cache (sequence 0)
    std::atomic<bool> cancel{false};
    int n_ctx = 0;
    int n_batch = 512;
    std::string backend;  // "cpu", "gpu" or "npu": where the model actually loaded
    mtmd_context * mtmd = nullptr;  // vision projector (mmproj), loaded on the first image
    std::string mmproj;
    bool kv_dirty = false;          // KV holds image embeddings that `cached` cannot describe
    std::mutex lock;
};

namespace {

using Session = autobot_llama_session;

constexpr int PARSE_EVERY = 3;  // re-parse the partial output every N tokens

std::atomic<autobot_llama_log_fn> g_log{nullptr};
std::atomic<void *> g_log_user{nullptr};

void log_line(int level, const char * text) {
    if (auto fn = g_log.load()) fn(level, text, g_log_user.load());
}

#if defined(__GNUC__)
__attribute__((format(printf, 2, 3)))
#endif
void logf(int level, const char * fmt, ...) {
    if (!g_log.load()) return;
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    log_line(level, buf);
}

#define LOGI(...) logf(AUTOBOT_LLAMA_LOG_INFO, __VA_ARGS__)
#define LOGE(...) logf(AUTOBOT_LLAMA_LOG_ERROR, __VA_ARGS__)

void ggml_log(ggml_log_level level, const char * text, void *) {
    int out = AUTOBOT_LLAMA_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) out = AUTOBOT_LLAMA_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) out = AUTOBOT_LLAMA_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) out = AUTOBOT_LLAMA_LOG_INFO;
    log_line(out, text);
}

// "npu" = Hexagon HTP sessions, "gpu" = Adreno OpenCL, Metal (Apple) or another GPU, "cpu" = the rest.
// On Apple the only ACCEL device is BLAS (Accelerate), a CPU helper: there is no ggml NPU there.
const char * device_kind(ggml_backend_dev_t dev) {
    const std::string name = ggml_backend_dev_name(dev);
    if (name.rfind("HTP", 0) == 0) return "npu";
    switch (ggml_backend_dev_type(dev)) {
        case GGML_BACKEND_DEVICE_TYPE_GPU:
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return "gpu";
#if defined(__APPLE__)
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "cpu";
#else
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return "npu";
#endif
        default: return "cpu";
    }
}

// Offload devices for a backend kind; empty for "cpu" (llama.cpp then keeps every layer on the CPU).
std::vector<ggml_backend_dev_t> devices_of(const std::string & kind) {
    std::vector<ggml_backend_dev_t> out;
    if (kind == "cpu") return out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (kind == device_kind(dev)) out.push_back(dev);
    }
    return out;
}

char * dup(const std::string & s) {
    char * out = static_cast<char *>(std::malloc(s.size() + 1));
    if (!out) return nullptr;
    std::memcpy(out, s.data(), s.size());
    out[s.size()] = '\0';
    return out;
}

// Length of the longest prefix of `s` that does not end in the middle of a UTF-8 sequence.
size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = (unsigned char) s[i - 1];
        if ((c & 0xC0) != 0x80) {  // lead or ASCII byte
            int need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            return (back + 1 >= need) ? n : i - 1;
        }
        --i;
        ++back;
    }
    return n;
}

std::string error_json(const std::string & code, const std::string & message) {
    auto err = common_json::object();
    err["code"] = code;
    err["message"] = message;
    auto root = common_json::object();
    root["error"] = err;
    return root.dump();
}

struct Sink {
    autobot_llama_delta_fn fn;
    void * user;
    bool stop = false;  // the receiver asked to stop

    void delta(int kind, const std::string & text) {
        if (text.empty() || !fn) return;
        if (fn(kind, text.data(), text.size(), user) != 0) stop = true;
    }
};

// Emits whatever part of `now` extends `emitted` (only when it is a pure extension).
void emit_extension(Sink & sink, int kind, std::string & emitted, const std::string & now) {
    if (now.size() <= emitted.size() || now.compare(0, emitted.size(), emitted) != 0) return;
    std::string tail = now.substr(emitted.size());
    tail.resize(utf8_complete_prefix(tail));
    if (tail.empty()) return;
    emitted += tail;
    sink.delta(kind, tail);
}

int decode_tokens(Session & s, const std::vector<llama_token> & tokens, size_t from, bool last_logits) {
    common_batch batch(s.ctx);
    for (size_t i = from; i < tokens.size(); i += s.n_batch) {
        const size_t end = std::min(tokens.size(), i + (size_t) s.n_batch);
        batch.clear();
        for (size_t j = i; j < end; ++j) {
            batch.add(tokens[j], (llama_pos) j, 0, last_logits && j == tokens.size() - 1);
        }
        if (s.cancel.load()) return -2;
        const int rc = llama_process(s.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc != 0) return rc;
    }
    return 0;
}

std::string chat(Session * s, const std::string & request_text, Sink & sink) {
    const llama_vocab * vocab = llama_model_get_vocab(s->model);

    try {
        const common_json req = common_json::parse(request_text);

        // Image parts {type:"image", path} become media markers; the files are tokenized by mtmd.
        common_json messages = req.at("messages");
        std::vector<std::string> images;
        for (auto & m : messages) {
            if (!m.contains("content") || !m.at("content").is_array()) continue;
            for (auto & part : m["content"]) {
                if (part.value("type", std::string()) != "image") continue;
                images.push_back(part.value("path", std::string()));
                part = common_json{{"type", "text"}, {"text", mtmd_default_marker()}};
            }
        }

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(messages);
        if (req.contains("tools") && req.at("tools").is_array() && !req.at("tools").empty()) {
            inputs.tools = common_chat_tools_parse_oaicompat(req.at("tools"));
            inputs.parallel_tool_calls = true;
        }
        inputs.use_jinja = true;
        inputs.add_generation_prompt = true;
        inputs.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        inputs.enable_thinking = req.value("enable_thinking", true);

        const common_chat_params chat = common_chat_templates_apply(s->templates.get(), inputs);

        const int max_tokens = req.value("max_tokens", 2048);
        llama_memory_t mem = llama_get_memory(s->ctx);
        llama_pos pos = 0;      // position of the next generated token
        size_t keep = 0;
        size_t prompt_tokens = 0;
        int64_t prompt_us = 0;
        if (s->kv_dirty) {
            llama_memory_clear(mem, true);
            s->cached.clear();
            s->kv_dirty = false;
        }

        if (!images.empty()) {
            // ---- multimodal prompt: evaluated from scratch through the vision projector
            const std::string mmproj = req.value("mmproj", std::string());
            if (mmproj.empty()) return error_json("NO_VISION", "this model has no vision projector (mmproj)");
            if (!s->mtmd || s->mmproj != mmproj) {
                if (s->mtmd) mtmd_free(s->mtmd);
                auto mp = mtmd_context_params_default();
                mp.use_gpu = s->backend != "cpu";
                mp.print_timings = false;
                mp.warmup = false;
                mp.n_threads = std::max(2, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
                s->mtmd = mtmd_init_from_file(mmproj.c_str(), s->model, mp);
                s->mmproj = s->mtmd ? mmproj : std::string();
                if (!s->mtmd) return error_json("NO_VISION", "could not load the vision projector");
            }
            std::vector<mtmd::bitmap> bitmaps;
            for (const auto & path : images) {
                auto wrapped = mtmd_helper_bitmap_init_from_file(s->mtmd, path.c_str(), false, mtmd_helper_init_opt_default());
                if (!wrapped.bitmap) return error_json("BAD_IMAGE", "cannot read image " + path);
                bitmaps.emplace_back(wrapped.bitmap);
            }
            std::vector<const mtmd_bitmap *> raw;
            for (auto & b : bitmaps) raw.push_back(b.ptr.get());
            mtmd::input_chunks chunks(mtmd_input_chunks_init());
            mtmd_input_text text{chat.prompt.c_str(), chat.prompt.size(), true, true};
            if (mtmd_tokenize(s->mtmd, chunks.ptr.get(), &text, raw.data(), raw.size()) != 0) {
                return error_json("BAD_IMAGE", "could not tokenize the images");
            }
            prompt_tokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
            if ((int) prompt_tokens + 16 >= s->n_ctx) {
                return error_json("CONTEXT_WINDOW_EXCEEDED",
                    "prompt has " + std::to_string(prompt_tokens) + " tokens; context is " + std::to_string(s->n_ctx));
            }
            llama_memory_clear(mem, true);
            s->cached.clear();
            s->kv_dirty = true;
            const int64_t t_prompt = ggml_time_us();
            llama_pos n_past = 0;
            if (mtmd_helper_eval_chunks(s->mtmd, s->ctx, chunks.ptr.get(), 0, 0, s->n_batch, true, &n_past) != 0) {
                llama_memory_clear(mem, true);
                return error_json("DECODE_FAILED", "image prompt decode failed");
            }
            prompt_us = ggml_time_us() - t_prompt;
            pos = n_past;
        } else {
            // ---- text prompt: reuse the longest cached prefix, decode the rest
            std::vector<llama_token> prompt = common_tokenize(s->ctx, chat.prompt, true, true);
            if ((int) prompt.size() + 16 >= s->n_ctx) {
                return error_json("CONTEXT_WINDOW_EXCEEDED",
                    "prompt has " + std::to_string(prompt.size()) + " tokens; context is " + std::to_string(s->n_ctx));
            }
            while (keep < prompt.size() && keep < s->cached.size() && prompt[keep] == s->cached[keep]) ++keep;
            if (keep == prompt.size()) keep = prompt.size() - 1;  // always re-evaluate the last token for logits
            llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1);
            s->cached.resize(keep);
            const int64_t t_prompt = ggml_time_us();
            const int rc = decode_tokens(*s, prompt, keep, true);
            prompt_us = ggml_time_us() - t_prompt;
            if (rc == -2) return error_json("CANCELLED", "cancelled");
            if (rc != 0) {
                llama_memory_clear(mem, false);
                s->cached.clear();
                return error_json("DECODE_FAILED", "prompt decode failed (" + std::to_string(rc) + ")");
            }
            s->cached = prompt;
            prompt_tokens = prompt.size();
            pos = (llama_pos) prompt.size();
        }

        // ---- sampler (with the template's lazy tool-call grammar when tools are present)
        common_params_sampling sp;
        sp.temp = req.value("temperature", 0.7);
        sp.top_p = req.value("top_p", 0.95);
        sp.top_k = 40;
        sp.min_p = 0.05f;
        sp.seed = (uint32_t) req.value("seed", (int) LLAMA_DEFAULT_SEED);
        sp.generation_prompt = chat.generation_prompt;
        if (!chat.grammar.empty()) {
            sp.grammar = common_grammar(COMMON_GRAMMAR_TYPE_TOOL_CALLS, chat.grammar);
            sp.grammar_lazy = chat.grammar_lazy;
        }
        for (const auto & t : chat.preserved_tokens) {
            auto ids = common_tokenize(vocab, t, false, true);
            if (ids.size() == 1) sp.preserved_tokens.insert(ids[0]);
        }
        for (const auto & trigger : chat.grammar_triggers) {
            if (trigger.type == COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
                auto ids = common_tokenize(vocab, trigger.value, false, true);
                if (ids.size() == 1 && sp.preserved_tokens.count(ids[0])) {
                    common_grammar_trigger tok;
                    tok.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
                    tok.value = trigger.value;
                    tok.token = ids[0];
                    sp.grammar_triggers.push_back(tok);
                    continue;
                }
            }
            sp.grammar_triggers.push_back(trigger);
        }
        if (sp.grammar_lazy && sp.grammar_triggers.empty()) sp.grammar_lazy = false;
        common_sampler * smpl = common_sampler_init(s->model, sp);
        if (!smpl) return error_json("SAMPLER_FAILED", "could not initialise sampler");

        common_chat_parser_params pp(chat);
        pp.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        pp.parse_tool_calls = !inputs.tools.empty();
        if (!chat.parser.empty()) pp.parser.load(chat.parser);

        // ---- generate
        std::string out;
        std::string emitted_content;
        std::string emitted_reasoning;
        std::string finish = "length";
        int n_gen = 0;
        const int64_t t_gen = ggml_time_us();
        common_batch batch(s->ctx);
        while (n_gen < max_tokens) {
            if (s->cancel.load()) {
                finish = "cancelled";
                break;
            }
            if ((int) pos + 1 >= s->n_ctx) {
                finish = "length";
                break;
            }
            const llama_token tok = common_sampler_sample(smpl, s->ctx, -1);
            common_sampler_accept(smpl, tok, true);
            if (llama_vocab_is_eog(vocab, tok)) {
                finish = "stop";
                break;
            }
            out += common_token_to_piece(s->ctx, tok);
            ++n_gen;

            bool stopped = false;
            for (const auto & stop : chat.additional_stops) {
                if (!stop.empty() && out.size() >= stop.size() && out.compare(out.size() - stop.size(), stop.size(), stop) == 0) {
                    out.resize(out.size() - stop.size());
                    stopped = true;
                    break;
                }
            }
            if (stopped) {
                finish = "stop";
                break;
            }

            batch.clear();
            batch.add(tok, pos, 0, true);
            if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                finish = "error";
                break;
            }
            s->cached.push_back(tok);
            ++pos;

            if (n_gen % PARSE_EVERY == 0) {
                try {
                    const common_chat_msg partial = common_chat_parse(out, true, pp);
                    emit_extension(sink, AUTOBOT_LLAMA_DELTA_REASONING, emitted_reasoning, partial.reasoning_content);
                    emit_extension(sink, AUTOBOT_LLAMA_DELTA_CONTENT, emitted_content, partial.content);
                } catch (const std::exception &) {
                    // partial output not parseable yet; try again later
                }
                if (sink.stop) {
                    finish = "cancelled";
                    break;
                }
            }
        }
        common_sampler_free(smpl);

        common_chat_msg msg;
        try {
            msg = common_chat_parse(out, false, pp);
        } catch (const std::exception & e) {
            LOGE("final parse failed: %s", e.what());
            msg.content = out;
        }
        emit_extension(sink, AUTOBOT_LLAMA_DELTA_REASONING, emitted_reasoning, msg.reasoning_content);
        emit_extension(sink, AUTOBOT_LLAMA_DELTA_CONTENT, emitted_content, msg.content);

        auto result = common_json::object();
        result["content"] = msg.content;
        result["reasoning"] = msg.reasoning_content;
        auto calls = common_json::array();
        int i = 0;
        for (const auto & call : msg.tool_calls) {
            auto c = common_json::object();
            c["id"] = call.id.empty() ? ("local_" + std::to_string(n_gen) + "_" + std::to_string(i)) : call.id;
            c["name"] = call.name;
            c["arguments"] = call.arguments;
            calls.push_back(c);
            ++i;
        }
        result["tool_calls"] = calls;
        result["finish_reason"] = msg.tool_calls.empty() ? finish : std::string("tool_calls");
        auto usage = common_json::object();
        usage["prompt_tokens"] = (int) prompt_tokens;
        usage["cached_tokens"] = (int) keep;
        usage["completion_tokens"] = n_gen;
        usage["backend"] = s->backend;
        usage["prompt_ms"] = (double) prompt_us / 1000.0;
        usage["generation_ms"] = (double) (ggml_time_us() - t_gen) / 1000.0;
        result["usage"] = usage;
        return result.dump();
    } catch (const std::exception & e) {
        LOGE("chat failed: %s", e.what());
        return error_json("BAD_REQUEST", e.what());
    }
}

}  // namespace

extern "C" {

void autobot_llama_set_log(autobot_llama_log_fn fn, void * user) {
    g_log_user.store(user);
    g_log.store(fn);
}

int autobot_llama_init(const char * lib_dir) {
    llama_log_set(ggml_log, nullptr);
    const std::string dir = lib_dir ? lib_dir : "";
    if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    const size_t devices = ggml_backend_dev_count();
    LOGI("%zu backend devices loaded from %s", devices, dir.empty() ? "(linked)" : dir.c_str());
    return (int) devices;
}

const char * autobot_llama_system_info(void) {
    return llama_print_system_info();
}

char * autobot_llama_devices_json(void) {
    auto list = common_json::array();
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        auto d = common_json::object();
        d["name"] = std::string(ggml_backend_dev_name(dev));
        d["description"] = std::string(ggml_backend_dev_description(dev));
        d["kind"] = std::string(device_kind(dev));
        d["memory_free"] = (double) free;
        d["memory_total"] = (double) total;
        list.push_back(d);
    }
    return dup(list.dump());
}

autobot_llama_session * autobot_llama_load(const char * path_c, int n_ctx, int n_threads, const char * backend) {
    const std::string path = path_c ? path_c : "";
    const std::string wanted = backend ? backend : "";
    std::vector<std::string> order;
    if (wanted == "auto") order = {"npu", "gpu", "cpu"};
    else if (wanted == "gpu" || wanted == "npu") order = {wanted, "cpu"};
    else order = {"cpu"};

    for (const auto & kind : order) {
        std::vector<ggml_backend_dev_t> devs = devices_of(kind);
        if (kind != "cpu" && devs.empty()) continue;
        devs.push_back(nullptr);
        auto mparams = llama_model_default_params();
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // weights stay in the page cache, not the heap
        mparams.devices = devs.data();
        mparams.n_gpu_layers = kind == "cpu" ? 0 : 999;
        llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) {
            LOGE("failed to load %s on %s", path.c_str(), kind.c_str());
            continue;
        }
        auto cparams = llama_context_default_params();
        const int trained = llama_model_n_ctx_train(model);
        cparams.n_ctx = (uint32_t) std::max(512, std::min(n_ctx, trained > 0 ? trained : n_ctx));
        cparams.n_batch = 512;
        cparams.n_ubatch = 512;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        llama_context * ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            LOGE("failed to create a context on %s", kind.c_str());
            llama_model_free(model);
            continue;
        }
        auto * s = new Session();
        s->model = model;
        s->ctx = ctx;
        s->n_ctx = (int) llama_n_ctx(ctx);
        s->backend = kind;
        s->templates = common_chat_templates_init(model, "");
        LOGI("loaded %s on %s", path.c_str(), kind.c_str());
        return s;
    }
    return nullptr;
}

char * autobot_llama_model_info(autobot_llama_session * s) {
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    auto info = common_json::object();
    info["description"] = std::string(desc);
    info["n_params"] = (double) llama_model_n_params(s->model);
    info["size_bytes"] = (double) llama_model_size(s->model);
    info["n_ctx"] = s->n_ctx;
    info["n_ctx_train"] = llama_model_n_ctx_train(s->model);
    info["chat_template"] = common_chat_templates_was_explicit(s->templates.get());
    info["backend"] = s->backend;
    return dup(info.dump());
}

char * autobot_llama_chat(autobot_llama_session * s, const char * request, size_t request_len,
                          autobot_llama_delta_fn on_delta, void * user) {
    std::lock_guard<std::mutex> guard(s->lock);
    s->cancel.store(false);
    Sink sink{on_delta, user};
    return dup(chat(s, std::string(request, request_len), sink));
}

void autobot_llama_cancel(autobot_llama_session * s) {
    if (s) s->cancel.store(true);
}

void autobot_llama_free(autobot_llama_session * s) {
    if (!s) return;
    {
        std::lock_guard<std::mutex> guard(s->lock);
        s->templates.reset();
        if (s->mtmd) mtmd_free(s->mtmd);
        llama_free(s->ctx);
        llama_model_free(s->model);
    }
    delete s;
}

void autobot_llama_string_free(char * str) {
    std::free(str);
}

// ---- embeddings (private vault)

struct autobot_llama_embedder {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    int n_ctx = 0;
    int dims = 0;
    std::string backend;
    std::mutex lock;
};

autobot_llama_embedder * autobot_llama_embed_load(const char * path_c, int n_ctx, int n_threads, const char * backend) {
    const std::string path = path_c ? path_c : "";
    const std::string wanted = backend ? backend : "";
    std::vector<std::string> order;
    if (wanted == "auto") order = {"gpu", "cpu"};  // small encoders gain little from the NPU path
    else if (wanted == "gpu" || wanted == "npu") order = {wanted, "cpu"};
    else order = {"cpu"};

    for (const auto & kind : order) {
        std::vector<ggml_backend_dev_t> devs = devices_of(kind);
        if (kind != "cpu" && devs.empty()) continue;
        devs.push_back(nullptr);
        auto mparams = llama_model_default_params();
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        mparams.devices = devs.data();
        mparams.n_gpu_layers = kind == "cpu" ? 0 : 999;
        llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
        if (!model) {
            LOGE("failed to load embedding model %s on %s", path.c_str(), kind.c_str());
            continue;
        }
        if (llama_model_has_encoder(model) && llama_model_has_decoder(model)) {
            LOGE("encoder-decoder models cannot embed");
            llama_model_free(model);
            return nullptr;
        }
        const int trained = llama_model_n_ctx_train(model);
        // A whole passage must fit in one micro-batch for pooling.
        const uint32_t ctx_len = (uint32_t) std::max(64, std::min(n_ctx, trained > 0 ? trained : n_ctx));
        llama_context * ctx = nullptr;
        for (auto pooling : {LLAMA_POOLING_TYPE_UNSPECIFIED, LLAMA_POOLING_TYPE_MEAN}) {
            auto cparams = llama_context_default_params();
            cparams.n_ctx = ctx_len;
            cparams.n_batch = ctx_len;
            cparams.n_ubatch = ctx_len;
            cparams.n_seq_max = 1;
            cparams.n_threads = n_threads;
            cparams.n_threads_batch = n_threads;
            cparams.embeddings = true;
            cparams.pooling_type = pooling;
            ctx = llama_init_from_model(model, cparams);
            // A model without a pooling type in its metadata gets mean pooling.
            if (ctx && llama_pooling_type(ctx) == LLAMA_POOLING_TYPE_NONE) {
                llama_free(ctx);
                ctx = nullptr;
                continue;
            }
            break;
        }
        if (!ctx) {
            LOGE("failed to create an embedding context on %s", kind.c_str());
            llama_model_free(model);
            continue;
        }
        auto * e = new autobot_llama_embedder();
        e->model = model;
        e->ctx = ctx;
        e->n_ctx = (int) llama_n_ctx(ctx);
        e->dims = llama_model_n_embd_out(model);
        e->backend = kind;
        LOGI("loaded embedding model %s on %s (%d dims, pooling %d)", path.c_str(), kind.c_str(), e->dims, (int) llama_pooling_type(ctx));
        return e;
    }
    return nullptr;
}

int autobot_llama_embed_dims(autobot_llama_embedder * e) {
    return e ? e->dims : 0;
}

const char * autobot_llama_embed_backend(autobot_llama_embedder * e) {
    if (!e) return "cpu";
    if (e->backend == "gpu") return "gpu";
    if (e->backend == "npu") return "npu";
    return "cpu";
}

int autobot_llama_embed(autobot_llama_embedder * e, const char * const * texts, const size_t * lens, int n, float * out) {
    if (!e || n < 0 || (n > 0 && (!texts || !lens || !out))) return -1;
    std::lock_guard<std::mutex> guard(e->lock);
    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    const enum llama_pooling_type pooling = llama_pooling_type(e->ctx);
    llama_memory_t mem = llama_get_memory(e->ctx);
    common_batch batch(e->ctx);
    std::vector<float> raw((size_t) e->dims);
    for (int i = 0; i < n; ++i) {
        std::vector<llama_token> tokens = common_tokenize(vocab, std::string(texts[i], lens[i]), true, true);
        if (tokens.empty()) tokens.push_back(llama_vocab_bos(vocab) >= 0 ? llama_vocab_bos(vocab) : 0);
        if ((int) tokens.size() > e->n_ctx) {
            // Keep the end token: last-token pooling reads the model's summary from it.
            const llama_token last = tokens.back();
            tokens.resize((size_t) e->n_ctx);
            if (pooling == LLAMA_POOLING_TYPE_LAST) tokens.back() = last;
        }
        if (mem) llama_memory_clear(mem, true);
        batch.clear();
        for (size_t j = 0; j < tokens.size(); ++j) batch.add(tokens[j], (llama_pos) j, 0, true);
        if (llama_process(e->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) < 0) {
            LOGE("embedding decode failed");
            return -2;
        }
        const float * embd = llama_get_embeddings_seq(e->ctx, 0);
        if (!embd) return -3;
        std::memcpy(raw.data(), embd, sizeof(float) * (size_t) e->dims);
        common_embd_normalize(raw.data(), out + (size_t) i * (size_t) e->dims, e->dims, 2);
    }
    return 0;
}

void autobot_llama_embed_free(autobot_llama_embedder * e) {
    if (!e) return;
    {
        std::lock_guard<std::mutex> guard(e->lock);
        llama_free(e->ctx);
        llama_model_free(e->model);
    }
    delete e;
}

}  // extern "C"
