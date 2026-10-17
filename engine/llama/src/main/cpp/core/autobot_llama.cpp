// llama.cpp engine core: chat templates, tool-call grammars and parsing come from llama.cpp
// `common` (the same code llama-server uses). The KV cache keeps the previous prompt so
// multi-step agent turns only decode the new suffix. Models whose memory cannot be cut back
// to an arbitrary position (hybrid/recurrent ones such as Qwen3.5) keep small checkpoints of
// that state near the end of each prompt instead, as llama-server does. No JNI or platform
// APIs in here.
//
// Thinking: the chat template's own switch (`enable_thinking`) turns reasoning on or off, and
// llama.cpp's reasoning-budget sampler bounds the think span: when the budget, the answer reserve
// or the loop guard (reasoning_guard.h) says stop, it forces a short wrap-up and the end tag, and
// the model goes on to answer instead of thinking until the output limit.
//
// Speculative decoding: with a small draft model of the same family attached (request field
// `draft`), the draft proposes a few tokens and the model checks them all in one batch, keeping
// the longest prefix it agrees with plus its own next token. Each emitted token is still sampled
// by the model's own sampler from the model's own logits, in order, so the output is what plain
// decoding gives (sampled: the same distribution; greedy: the same text as long as the model's
// logits for a batch equal those for a single token, which holds on Metal in every bench check,
// while llama.cpp's CPU kernels round a batch slightly differently and a near tie can tip the
// other way), and the reasoning budget, the loop guard, tool-call grammars, stop strings and
// streaming see every token one by one as before. speculative_sched.h picks how many
// tokens to draft from the measured acceptance and costs, and stops speculating when it does not
// pay. Rejected drafts are cut from both models' memories with llama_memory_seq_rm, so models
// whose memory cannot be cut back (hybrid/recurrent, e.g. Qwen3.5) do not speculate: rolling
// their state back would cost a state copy per round and per-token snapshots (see README).

#include "autobot_llama.h"
#include "reasoning_guard.h"
#include "speculative_sched.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
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
#include "gguf.h"
#include "json.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"
#include "speculative.h"

// A snapshot of the part of the memory that cannot be rolled back (recurrent state), taken
// after the first `n_tokens` tokens of `cached` were decoded.
struct Checkpoint {
    size_t n_tokens = 0;
    std::vector<uint8_t> data;
};

// What the model's chat template does about reasoning (found once per load by rendering it).
struct ThinkingCaps {
    bool supported = false;  // the template has a think span (start and end tags)
    bool toggle = false;     // enable_thinking changes the prompt (Qwen3, Qwen3.5, ...)
    bool effort = false;     // the template reads reasoning_effort (gpt-oss)
    std::string start;       // "<think>"
    std::string end;         // "</think>"
};

struct autobot_llama_session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached;  // tokens currently in the KV cache (sequence 0)
    bool partial_rm = true;           // the memory can drop any suffix (false: hybrid/recurrent models)
    std::vector<Checkpoint> checkpoints;  // oldest first; only when !partial_rm
    std::atomic<bool> cancel{false};
    std::atomic<uint64_t> token{0};           // the running request's token (0: none or untokened)
    std::atomic<uint64_t> cancel_token{0};    // last token asked to stop; kept until reused
    std::atomic<bool> prefilling{false};      // the abort callback may interrupt a decode
    int n_ctx = 0;
    int n_batch = 512;
    std::string backend;  // "cpu", "gpu", "npu" or "npu+cpu"/"gpu+cpu": where the model actually runs
    // Split placement ("npu+cpu"): prompts are decoded on the accelerator (`ctx`), generated
    // tokens on this CPU copy of the model, which decodes single tokens faster. The prompt's
    // memory is handed over before each reply (see hand_off); `ctx` stays the reference that
    // `cached` and the checkpoints describe.
    llama_model * dec_model = nullptr;
    llama_context * dec_ctx = nullptr;
    mtmd_context * mtmd = nullptr;  // vision projector (mmproj), loaded on the first image
    std::string mmproj;
    bool kv_dirty = false;          // KV holds image embeddings that `cached` cannot describe
    bool dec_partial_rm = true;     // the CPU decoder's memory can drop any suffix
    ThinkingCaps thinking;
    int n_threads = 4;
    // Speculative decoding: a small draft model of the same family, loaded on request next to
    // the model, with its own prompt cache.
    llama_model * draft_model = nullptr;
    llama_context * draft_ctx = nullptr;
    std::vector<llama_token> draft_cached;  // tokens in the draft's memory (sequence 0, from position 0)
    size_t draft_base = 0;                  // where draft_cached starts in the conversation (its window)
    std::string draft_path;                 // the loaded draft
    std::string draft_backend;              // where it runs: "cpu" or "gpu"
    std::string draft_refused;              // a draft that could not be used, and why
    std::string draft_refusal;
    std::mutex lock;
};

namespace {

using Session = autobot_llama_session;

constexpr int PARSE_EVERY = 3;  // re-parse the partial output every N tokens

// Checkpoints are taken this many tokens before the end of each prompt: the next turn's prompt
// usually re-renders the end of this one (generation prompt, reasoning) a little differently.
constexpr size_t CHECKPOINT_BACK = 4;
constexpr size_t MAX_CHECKPOINTS = 3;

// Closes a think span the engine ends early (budget, answer reserve, loop). Worded as the model's
// own decision so it goes on to answer from what it has; tried on Qwen3 / Qwen3.5 (see the bench).
constexpr const char * WRAP_UP = "\n\nI have thought about this enough; time to answer.\n";

// Reasoning progress is streamed every this many reasoning tokens (and when the span closes).
constexpr int STATUS_EVERY = 8;

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

bool cancelled(const Session & s) {
    const uint64_t token = s.token.load();
    return s.cancel.load() || (token != 0 && s.cancel_token.load() == token);
}

// ggml abort callback: stops a prompt decode between graph nodes, so a cancel during a long
// prefill lands within milliseconds instead of after the current batch. Generation steps are
// short and are not interrupted (an aborted step would cost a checkpoint restore).
bool abort_requested(void * user) {
    const auto * s = static_cast<const Session *>(user);
    return s->prefilling.load(std::memory_order_relaxed) && cancelled(*s);
}

// The caller's "stop loading" check; once it said yes, it stays yes.
struct LoadAbort {
    autobot_llama_abort_fn fn;
    void * user;
    bool fired = false;

    bool requested() {
        if (!fired && fn && fn(user) != 0) fired = true;
        return fired;
    }
};

void forget_cache(Session & s) {
    llama_memory_clear(llama_get_memory(s.ctx), true);
    s.cached.clear();
    s.checkpoints.clear();
}

// Cuts the cache back so that at most the first `keep` tokens of `cached` remain, and returns
// how many do (fewer when a hybrid model has no checkpoint close enough).
size_t truncate_cache(Session & s, size_t keep, bool resync = false) {
    llama_memory_t mem = llama_get_memory(s.ctx);
    keep = std::min(keep, s.cached.size());
    s.checkpoints.erase(std::remove_if(s.checkpoints.begin(), s.checkpoints.end(),
                                       [&](const Checkpoint & c) { return c.n_tokens > keep; }),
                        s.checkpoints.end());
    if (keep == s.cached.size() && !resync) return keep;
    if (s.partial_rm) {
        if (llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            s.cached.resize(keep);
            return keep;
        }
    } else if (!s.checkpoints.empty()) {
        // Restore the newest checkpoint, then drop the attention cache after it.
        const Checkpoint & c = s.checkpoints.back();
        if (llama_state_seq_set_data_ext(s.ctx, c.data.data(), c.data.size(), 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == c.data.size() &&
            llama_memory_seq_rm(mem, 0, (llama_pos) c.n_tokens, -1)) {
            s.cached.resize(c.n_tokens);
            return c.n_tokens;
        }
        LOGE("could not restore a checkpoint at %zu tokens", c.n_tokens);
    }
    forget_cache(s);
    return 0;
}

void save_checkpoint(Session & s) {
    for (const auto & c : s.checkpoints) {
        if (c.n_tokens == s.cached.size()) return;
    }
    const size_t size = llama_state_seq_get_size_ext(s.ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    if (size == 0) return;
    // The oldest one usually ends the system prompt, which every chat shares: keep it longest.
    while (s.checkpoints.size() >= MAX_CHECKPOINTS) s.checkpoints.erase(s.checkpoints.begin() + (s.checkpoints.size() > 1 ? 1 : 0));
    Checkpoint c;
    c.n_tokens = s.cached.size();
    c.data.resize(size);
    if (llama_state_seq_get_data_ext(s.ctx, c.data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) return;
    s.checkpoints.push_back(std::move(c));
}

// Split placement: copies sequence 0 of the prompt context's memory (attention cache and
// recurrent state, a few tens of MB) into the CPU decoder. Returns the context to generate on:
// the decoder, or `ctx` itself when there is none or the copy failed.
llama_context * hand_off(Session & s) {
    if (!s.dec_ctx) return s.ctx;
    llama_memory_clear(llama_get_memory(s.dec_ctx), true);
    const size_t size = llama_state_seq_get_size(s.ctx, 0);
    std::vector<uint8_t> state(size);
    if (size > 0 && llama_state_seq_get_data(s.ctx, state.data(), size, 0) == size &&
        llama_state_seq_set_data(s.dec_ctx, state.data(), size, 0) == size) {
        return s.dec_ctx;
    }
    LOGE("could not hand the prompt over to the CPU decoder; generating on %s", s.backend.c_str());
    llama_memory_clear(llama_get_memory(s.dec_ctx), true);
    return s.ctx;
}

// Decodes tokens[cached.size(), end) in batches, keeping `cached` in step with the memory.
int decode_range(Session & s, const std::vector<llama_token> & tokens, size_t end, bool last_logits) {
    common_batch batch(s.ctx);
    while (s.cached.size() < end) {
        const size_t from = s.cached.size();
        const size_t to = std::min(end, from + (size_t) s.n_batch);
        batch.clear();
        for (size_t j = from; j < to; ++j) {
            batch.add(tokens[j], (llama_pos) j, 0, last_logits && j == end - 1);
        }
        if (cancelled(s)) return -2;
        const int rc = llama_process(s.ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (rc == 2) return -2;  // aborted by abort_requested
        if (rc != 0) return rc;
        s.cached.insert(s.cached.end(), tokens.begin() + (long) from, tokens.begin() + (long) to);
    }
    return 0;
}

// Decodes the uncached part of `prompt` (`cached` must be a prefix of it). When the memory
// cannot be rolled back otherwise, takes checkpoints after the first `stable` tokens (the system
// prompt, shared by every chat) and shortly before the end of the prompt.
int decode_prompt(Session & s, const std::vector<llama_token> & prompt, size_t stable) {
    struct Prefill {
        Session & s;
        explicit Prefill(Session & session) : s(session) { s.prefilling.store(true); }
        ~Prefill() { s.prefilling.store(false); }
    } prefill(s);
    int rc = 0;
    if (!s.partial_rm) {
        const size_t near_end = prompt.size() > CHECKPOINT_BACK ? prompt.size() - CHECKPOINT_BACK : 0;
        for (const size_t at : {std::min(stable, near_end), near_end}) {
            if (at == 0 || at < s.cached.size()) continue;
            rc = decode_range(s, prompt, at, false);
            if (rc != 0) break;
            save_checkpoint(s);
        }
    }
    if (rc == 0) rc = decode_range(s, prompt, prompt.size(), true);
    // A failed or aborted batch leaves nothing of itself in an attention cache, but a recurrent
    // state cannot be rolled back: return to the last consistent point.
    if (rc != 0 && !s.partial_rm) truncate_cache(s, s.cached.size(), true);
    return rc;
}

// How many leading tokens of `prompt` render the system message(s) and tools alone: the part
// every chat of the same agent shares. 0 when there is no such part or it cannot be told apart.
size_t system_prefix(Session * s, const common_chat_templates_inputs & inputs, const std::string & text,
                     const std::vector<llama_token> & prompt) {
    size_t n_system = 0;
    while (n_system < inputs.messages.size() && inputs.messages[n_system].role == "system") ++n_system;
    if (n_system == 0 || n_system == inputs.messages.size()) return 0;
    try {
        // The same system part followed by a placeholder user turn (templates refuse a
        // conversation without one): the rendered texts agree up to the user's words.
        common_chat_templates_inputs head = inputs;
        head.messages.resize(n_system);
        common_chat_msg placeholder;
        placeholder.role = "user";
        placeholder.content = "\x01";
        head.messages.push_back(placeholder);
        head.add_generation_prompt = false;
        const std::string rendered = common_chat_templates_apply(s->templates.get(), head).prompt;
        size_t chars = 0;
        while (chars < rendered.size() && chars < text.size() && rendered[chars] == text[chars]) ++chars;
        const std::vector<llama_token> tokens = common_tokenize(s->ctx, text.substr(0, chars), true, true);
        size_t n = 0;
        while (n < tokens.size() && n < prompt.size() && tokens[n] == prompt[n]) ++n;
        return n;  // tokens compared, so a cut that tokenizes differently only shortens it
    } catch (const std::exception &) {
        return 0;
    }
}

ThinkingCaps thinking_caps(const common_chat_templates * templates) {
    ThinkingCaps caps;
    if (!templates) return caps;
    try {
        common_chat_templates_inputs in;
        common_chat_msg m;
        m.role = "user";
        m.content = "Hi";
        in.messages = {m};
        in.use_jinja = true;
        in.add_generation_prompt = true;
        in.reasoning_format = COMMON_REASONING_FORMAT_DEEPSEEK;
        in.enable_thinking = true;
        const common_chat_params on = common_chat_templates_apply(templates, in);
        in.enable_thinking = false;
        const common_chat_params off = common_chat_templates_apply(templates, in);
        caps.supported = on.supports_thinking && !on.thinking_start_tag.empty() && !on.thinking_end_tags.empty();
        caps.toggle = caps.supported && on.prompt != off.prompt;
        if (caps.supported) {
            caps.start = on.thinking_start_tag;
            caps.end = on.thinking_end_tags.front();
        }
    } catch (const std::exception & e) {
        LOGE("cannot inspect the chat template: %s", e.what());
    }
    caps.effort = common_chat_templates_source(templates).find("reasoning_effort") != std::string::npos;
    return caps;
}

// Follows the think span through the prompt's generation prefix and the generated tokens, and
// counts the tokens inside it (the tags themselves excluded).
struct ThinkSpan {
    std::vector<llama_token> start;
    std::vector<std::vector<llama_token>> ends;
    std::vector<llama_token> recent;
    bool inside = false;
    bool closed = false;
    int tokens = 0;

    static bool ends_with(const std::vector<llama_token> & seq, const std::vector<llama_token> & tail) {
        return !tail.empty() && seq.size() >= tail.size() && std::equal(tail.begin(), tail.end(), seq.end() - (long) tail.size());
    }

    // Returns true when this token closed the span.
    bool accept(llama_token t) {
        recent.push_back(t);
        if (recent.size() > 32) recent.erase(recent.begin(), recent.begin() + 16);
        if (!inside) {
            if (ends_with(recent, start)) inside = true;
            return false;
        }
        for (const auto & e : ends) {
            if (ends_with(recent, e)) {
                tokens = std::max(0, tokens - (int) e.size() + 1);  // the tag's own leading tokens were counted
                inside = false;
                closed = true;
                return true;
            }
        }
        ++tokens;
        return false;
    }
};

std::string reasoning_status(int tokens, bool done, const std::string & stop) {
    auto st = common_json::object();
    st["tokens"] = tokens;
    st["done"] = done;
    if (!stop.empty()) st["stop"] = stop;
    return st.dump();
}

// ---- speculative decoding: the draft model

// The draft sees a window of the most recent tokens, not the whole conversation: a small model
// guesses the next words from the last paragraphs about as well, its KV cache is per token about
// as large as the model's own (many layers and KV heads for its size), and catching it up on a
// long agent prompt would cost a prefill on the CPU (Android) every new chat. The window starts
// DRAFT_WINDOW tokens back and slides (a re-prefill of that many tokens) when it fills the
// draft's context.
constexpr int DRAFT_CTX = 1024;
constexpr int DRAFT_WINDOW = 512;

// A catch-up of the draft on more than DRAFT_CATCHUP_SHORT tokens (a new chat, a long tool
// result) waits until the reply has produced an eighth of that many tokens (at least 16): short
// replies such as tool calls do not pay a prefill they cannot earn back.
constexpr size_t DRAFT_CATCHUP_SHORT = 64;
constexpr int DRAFT_LONG_REPLY = 16;

// Most tokens one round drafts (SpeculativeSchedule::k_max), for the room left in the window.
constexpr int DRAFT_K_MAX = 8;

// Drafting stops at a token the draft itself is less sure of than this (after the first one):
// such tokens are rarely accepted, and each costs a draft step.
constexpr float DRAFT_P_MIN = 0.3f;

// Draft syncs longer than this are prompt prefills, not draft steps: kept out of the timings.
constexpr size_t DRAFT_SYNC_STEP = 16;

void free_draft(Session & s) {
    if (s.draft_ctx) llama_free(s.draft_ctx);
    if (s.draft_model) llama_model_free(s.draft_model);
    s.draft_ctx = nullptr;
    s.draft_model = nullptr;
    s.draft_cached.clear();
    s.draft_base = 0;
    s.draft_path.clear();
    s.draft_backend.clear();
}

// Why `draft` cannot draft for `target` ("" when it can): speculation needs the same tokenizer,
// token for token (llama.cpp's own check, as llama-server uses it).
std::string vocab_mismatch(const llama_model * target, const llama_model * draft) {
    if (common_speculative_are_compatible(target, draft)) return "";
    const llama_vocab * vt = llama_model_get_vocab(target);
    const llama_vocab * vd = llama_model_get_vocab(draft);
    char buf[320];
    std::snprintf(buf, sizeof(buf),
                  "the draft model's tokenizer differs from the chat model's (%d vs %d tokens, vocab type %d vs %d); "
                  "a draft model must come from the same model family",
                  llama_vocab_n_tokens(vd), llama_vocab_n_tokens(vt), (int) llama_vocab_type(vd), (int) llama_vocab_type(vt));
    return buf;
}

// Loads the draft model at `path` on `backend` ("cpu", "gpu" or "auto": the GPU on Apple when the
// model is on it, else the CPU) next to the session's model. Returns "" or why it cannot be used.
std::string load_draft(Session & s, const std::string & path, const std::string & backend) {
    free_draft(s);
    std::string kind = backend;
    if (kind.empty() || kind == "auto") {
#if defined(__APPLE__)
        kind = s.backend.rfind("gpu", 0) == 0 ? "gpu" : "cpu";
#else
        kind = "cpu";
#endif
    }
    std::vector<std::string> order;
    if (kind == "gpu" || kind == "npu") order = {kind, "cpu"};
    else order = {"cpu"};
    for (const auto & k : order) {
        std::vector<ggml_backend_dev_t> devs = devices_of(k);
        if (k != "cpu" && devs.empty()) continue;
        devs.push_back(nullptr);
        auto mparams = llama_model_default_params();
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        mparams.devices = devs.data();
        mparams.n_gpu_layers = k == "cpu" ? 0 : 999;
        mparams.progress_callback = [](float, void * data) { return !cancelled(*static_cast<const Session *>(data)); };
        mparams.progress_callback_user_data = &s;
        llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
        if (cancelled(s)) {
            if (model) llama_model_free(model);
            return "cancelled";
        }
        if (!model) {
            LOGE("failed to load the draft model on %s", k.c_str());
            continue;
        }
        const std::string mismatch = vocab_mismatch(s.model, model);
        if (!mismatch.empty()) {
            llama_model_free(model);
            return mismatch;
        }
        auto cparams = llama_context_default_params();
        cparams.n_ctx = (uint32_t) std::min(s.n_ctx, DRAFT_CTX);
        cparams.n_batch = 512;
        cparams.n_ubatch = 512;
        cparams.n_threads = s.n_threads;
        cparams.n_threads_batch = s.n_threads;
        // Halves the draft's KV cache; drafts barely change (and the model checks every token).
        cparams.type_k = GGML_TYPE_Q8_0;
        cparams.type_v = GGML_TYPE_Q8_0;
        llama_context * ctx = llama_init_from_model(model, cparams);
        if (!ctx) {
            cparams.type_k = GGML_TYPE_F16;
            cparams.type_v = GGML_TYPE_F16;
            ctx = llama_init_from_model(model, cparams);
        }
        if (!ctx) {
            LOGE("failed to create a draft context on %s", k.c_str());
            llama_model_free(model);
            continue;
        }
        if (common_context_can_seq_rm(ctx) != COMMON_CONTEXT_SEQ_RM_TYPE_PART) {
            llama_free(ctx);
            llama_model_free(model);
            return "the draft model's memory cannot be cut back (recurrent layers); use an attention-only draft";
        }
        // One throwaway step, so the first real draft step does not pay for kernel set-up (on
        // Metal that is several times a normal step, and would skew the schedule's costs).
        {
            const llama_vocab * vocab = llama_model_get_vocab(model);
            llama_token t = llama_vocab_bos(vocab);
            if (t == LLAMA_TOKEN_NULL) t = 0;
            common_batch warm(ctx);
            warm.add(t, 0, 0, true);
            llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, warm.get());
            llama_synchronize(ctx);
            llama_memory_clear(llama_get_memory(ctx), true);
        }
        llama_set_abort_callback(ctx, abort_requested, &s);
        s.draft_model = model;
        s.draft_ctx = ctx;
        s.draft_path = path;
        s.draft_backend = k;
        LOGI("loaded draft model %s on %s (context %u)", path.c_str(), k.c_str(), llama_n_ctx(ctx));
        return "";
    }
    return "the draft model could not be loaded";
}

// Where the draft's window over `seq` starts, and how many of its cached tokens stay: the current
// window while `seq` continues it and still fits the draft's context (with room for a round of
// drafts), else a new one DRAFT_WINDOW tokens back.
struct DraftPlan {
    size_t base = 0;
    size_t keep = 0;  // tokens of seq[base...] already in the draft's memory
};

DraftPlan draft_plan(const Session & s, const std::vector<llama_token> & seq) {
    const size_t n_ctx = s.draft_ctx ? llama_n_ctx(s.draft_ctx) : (size_t) DRAFT_CTX;
    const size_t window = std::min((size_t) DRAFT_WINDOW, n_ctx / 2);
    DraftPlan plan;
    plan.base = s.draft_base;
    if (plan.base > seq.size() || seq.size() - plan.base + DRAFT_K_MAX + 2 > n_ctx) {
        plan.base = seq.size() > window ? seq.size() - window : 0;
    }
    if (plan.base == s.draft_base) {
        const auto & c = s.draft_cached;
        while (plan.keep < c.size() && plan.base + plan.keep + 1 < seq.size() && c[plan.keep] == seq[plan.base + plan.keep]) ++plan.keep;
    }
    return plan;
}

// How many tokens draft_sync would decode now.
size_t draft_catchup(const Session & s, const std::vector<llama_token> & seq) {
    const DraftPlan plan = draft_plan(s, seq);
    return seq.size() - plan.base - plan.keep;
}

// Brings the draft's memory to its window over `seq` (draft_plan), reusing what it holds, and
// decodes the rest with logits for the last token. Returns how many tokens it decoded, or -2
// (cancelled) / -1 (failed; the draft's memory is then cleared).
int draft_sync(Session & s, const std::vector<llama_token> & seq) {
    if (seq.empty()) return 0;
    const DraftPlan plan = draft_plan(s, seq);
    size_t keep = plan.keep;
    llama_memory_t mem = llama_get_memory(s.draft_ctx);
    if (plan.base != s.draft_base) {
        llama_memory_clear(mem, true);
        s.draft_cached.clear();
        s.draft_base = plan.base;
    } else if (keep < s.draft_cached.size()) {
        if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
            llama_memory_clear(mem, true);
            keep = 0;
        }
        s.draft_cached.resize(keep);
    }
    const size_t base = s.draft_base;
    const size_t todo = seq.size() - base - keep;
    // A long catch-up (the prompt) may be interrupted by a cancel, as the model's prefill is.
    const bool long_sync = todo > DRAFT_SYNC_STEP;
    if (long_sync) s.prefilling.store(true);
    common_batch batch(s.draft_ctx);
    int rc = 0;
    const size_t n = seq.size() - base;  // the window's length
    while (s.draft_cached.size() < n) {
        const size_t from = s.draft_cached.size();
        const size_t to = std::min(n, from + 512);
        batch.clear();
        for (size_t j = from; j < to; ++j) batch.add(seq[base + j], (llama_pos) j, 0, j + 1 == n);
        if (cancelled(s)) {
            rc = -2;
            break;
        }
        const int r = llama_process(s.draft_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (r == 2) {
            rc = -2;
            break;
        }
        if (r != 0) {
            LOGE("draft decode failed (%d)", r);
            llama_memory_clear(mem, true);
            s.draft_cached.clear();
            rc = -1;
            break;
        }
        s.draft_cached.insert(s.draft_cached.end(), seq.begin() + (long) (base + from), seq.begin() + (long) (base + to));
    }
    if (long_sync) s.prefilling.store(false);
    return rc != 0 ? rc : (int) todo;
}

// Drafts up to `k` tokens after the window the draft was just synced to: greedy, stopping early
// after a token the draft is unsure of or an end-of-text token. Every drafted token but the last
// is decoded on the draft. Returns false when a draft decode failed.
bool draft_propose(Session & s, int k, std::vector<llama_token> & out) {
    out.clear();
    llama_pos pos = (llama_pos) s.draft_cached.size();
    const llama_vocab * vocab = llama_model_get_vocab(s.draft_model);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    common_batch batch(s.draft_ctx);
    for (int j = 0; j < k; ++j) {
        const float * logits = llama_get_logits_ith(s.draft_ctx, -1);
        if (!logits) return false;
        int best = 0;
        float top = logits[0];
        for (int i = 1; i < n_vocab; ++i) {
            if (logits[i] > top) {
                top = logits[i];
                best = i;
            }
        }
        if (j > 0) {
            double sum = 0.0;
            for (int i = 0; i < n_vocab; ++i) sum += std::exp((double) (logits[i] - top));
            if (1.0 / sum < DRAFT_P_MIN) break;
        }
        out.push_back(best);
        if (j + 1 == k || llama_vocab_is_eog(vocab, best)) break;
        batch.clear();
        batch.add(best, pos, 0, true);
        if (llama_process(s.draft_ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            llama_memory_clear(llama_get_memory(s.draft_ctx), true);
            s.draft_cached.clear();
            return false;
        }
        s.draft_cached.push_back(best);
        ++pos;
    }
    return true;
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
        const bool thinking_on = req.value("enable_thinking", true);
        inputs.enable_thinking = thinking_on;
        const std::string effort = req.value("reasoning_effort", std::string());
        if (!effort.empty() && s->thinking.effort) inputs.chat_template_kwargs["reasoning_effort"] = common_json(effort).dump();

        const common_chat_params chat = common_chat_templates_apply(s->templates.get(), inputs);

        const int max_tokens = req.value("max_tokens", 2048);

        // ---- speculative decoding: the draft model the app picked (none: free any loaded one)
        const std::string spec_mode = req.value("speculative", std::string("auto"));
        std::string draft_path;
        std::string draft_backend = "auto";
        if (req.contains("draft") && req.at("draft").is_object()) {
            draft_path = req.at("draft").value("path", std::string());
            draft_backend = req.at("draft").value("backend", std::string("auto"));
        }
        if (draft_path.empty()) {
            if (s->draft_model) free_draft(*s);
        } else if (spec_mode != "off" && draft_path != s->draft_path && draft_path != s->draft_refused) {
            const std::string why = load_draft(*s, draft_path, draft_backend);
            if (why == "cancelled") return error_json("CANCELLED", "cancelled");
            if (!why.empty()) {
                LOGE("speculative decoding off: %s", why.c_str());
                s->draft_refused = draft_path;
                s->draft_refusal = why;
            }
        }

        llama_memory_t mem = llama_get_memory(s->ctx);
        llama_pos pos = 0;      // position of the next generated token
        size_t keep = 0;
        size_t prompt_tokens = 0;
        int64_t prompt_us = 0;
        std::vector<llama_token> seq;  // the text prompt's tokens, then every generated token (speculation)
        if (s->kv_dirty) {
            forget_cache(*s);
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
            forget_cache(*s);
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
            const int64_t t_prompt = ggml_time_us();
            keep = truncate_cache(*s, keep);
            const size_t stable = s->partial_rm ? 0 : system_prefix(s, inputs, chat.prompt, prompt);
            const int rc = decode_prompt(*s, prompt, stable);
            prompt_us = ggml_time_us() - t_prompt;
            if (rc == -2) return error_json("CANCELLED", "cancelled");
            if (rc != 0) {
                forget_cache(*s);
                return error_json("DECODE_FAILED", "prompt decode failed (" + std::to_string(rc) + ")");
            }
            prompt_tokens = prompt.size();
            pos = (llama_pos) prompt.size();
            seq = std::move(prompt);
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

        // ---- thinking: the budget sampler watches the think span (and closes it on request)
        ThinkSpan span;
        int budget = req.value("reasoning_budget", -1);
        if (!thinking_on) budget = 0;  // a model that opens a span anyway gets it closed at once
        int reserve = req.value("min_answer_tokens", std::max(32, std::min(512, max_tokens / 4)));
        reserve = std::max(0, std::min(reserve, max_tokens / 2));
        const bool loop_guard = req.value("loop_guard", true);
        size_t forced_len = 0;
        if (!chat.thinking_start_tag.empty() && !chat.thinking_end_tags.empty()) {
            span.start = common_tokenize(vocab, chat.thinking_start_tag, false, true);
            for (const auto & e : chat.thinking_end_tags) span.ends.push_back(common_tokenize(vocab, e, false, true));
            llama_tokens forced;
            if (thinking_on) forced = common_tokenize(vocab, req.value("reasoning_budget_message", std::string(WRAP_UP)), false, true);
            forced.insert(forced.end(), span.ends.front().begin(), span.ends.front().end());
            forced_len = forced.size();
            sp.reasoning_budget_tokens = budget;
            sp.reasoning_budget_start = span.start;
            sp.reasoning_budget_end = span.ends;
            sp.reasoning_budget_forced = std::move(forced);
            sp.reasoning_control = true;  // lets the loop guard and the answer reserve close the span
            for (const llama_token t : common_tokenize(vocab, chat.generation_prompt, false, true)) span.accept(t);
            span.tokens = 0;
            span.closed = false;
        }
        // Past this many generated tokens an open think span is closed, so the answer keeps `reserve`
        // tokens of the output limit and of the context left after the prompt.
        const int out_room = std::min(max_tokens, s->n_ctx - 1 - (int) pos);
        const int think_cap = std::max(0, out_room - reserve - (int) forced_len);
        ReasoningLoopGuard guard;
        std::string reasoning_stop;   // why the engine closed the span early
        bool closing = false;         // the end sequence is being forced
        int reported = -1;
        int64_t reasoning_us = 0;

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
        // The first token comes from the prompt's logits; the rest are decoded on `gen` (the CPU
        // decoder with split placement, whose tokens `ctx` and `cached` then do not hold).
        llama_context * logits_ctx = s->ctx;
        llama_context * gen = hand_off(*s);
        const bool gen_is_ctx = gen == s->ctx;
        common_batch batch(gen);

        // ---- speculation: on when a draft is loaded, the prompt is text and both memories can
        // drop rejected drafts
        SpeculativeSchedule sched;
        sched.always = spec_mode == "on";
        std::string spec_status;  // why this reply does not speculate ("" = it does)
        if (spec_mode == "off") spec_status = "off";
        else if (!s->draft_model) spec_status = !draft_path.empty() && draft_path == s->draft_refused ? "refused" : "no_draft";
        else if (!images.empty()) spec_status = "images";
        else if (!(gen_is_ctx ? s->partial_rm : s->dec_partial_rm)) spec_status = "unsupported";
        bool speculating = spec_status.empty();
        std::vector<llama_token> drafts;

        // Handles one token sampled (and not yet accepted) by the model's sampler: thinking,
        // stops, streaming, limits. Returns true when the reply ends here; that token is then not
        // decoded.
        auto step = [&](llama_token tok) -> bool {
            common_sampler_accept(smpl, tok, true);
            const bool was_inside = span.inside;
            const bool span_closed = span.accept(tok);
            if (llama_vocab_is_eog(vocab, tok)) {
                finish = "stop";
                return true;
            }
            out += common_token_to_piece(s->ctx, tok);
            ++n_gen;
            if (speculating) seq.push_back(tok);

            if (span.inside && !closing) {
                if (budget >= 0 && span.tokens >= budget) {
                    // The budget sampler starts forcing by itself at this count.
                    closing = true;
                    if (thinking_on) reasoning_stop = "budget";
                } else if (n_gen >= think_cap && common_sampler_reasoning_budget_force(smpl)) {
                    closing = true;
                    reasoning_stop = "budget";
                } else if (loop_guard && guard.push(tok) && common_sampler_reasoning_budget_force(smpl)) {
                    closing = true;
                    reasoning_stop = "loop";
                    LOGI("reasoning loop (%s) after %d tokens: closing the think span", guard.reason(), span.tokens);
                }
            }
            if (span_closed) {
                reasoning_us = ggml_time_us() - t_gen;
                closing = false;
                guard.reset();
            }
            if ((was_inside || span.inside) && thinking_on &&
                (span_closed || reported < 0 || span.tokens - reported >= STATUS_EVERY)) {
                reported = span.tokens;
                sink.delta(AUTOBOT_LLAMA_DELTA_REASONING_STATUS, reasoning_status(span.tokens, span_closed, span_closed ? reasoning_stop : ""));
            }

            for (const auto & stop : chat.additional_stops) {
                if (!stop.empty() && out.size() >= stop.size() && out.compare(out.size() - stop.size(), stop.size(), stop) == 0) {
                    out.resize(out.size() - stop.size());
                    finish = "stop";
                    return true;
                }
            }

            // Streamed before this token is decoded, so text shows one step sooner; every token at
            // first (the first words appear as soon as they exist), then every few.
            if (n_gen <= PARSE_EVERY || n_gen % PARSE_EVERY == 0) {
                try {
                    const common_chat_msg partial = common_chat_parse(out, true, pp);
                    emit_extension(sink, AUTOBOT_LLAMA_DELTA_REASONING, emitted_reasoning, partial.reasoning_content);
                    emit_extension(sink, AUTOBOT_LLAMA_DELTA_CONTENT, emitted_content, partial.content);
                } catch (const std::exception &) {
                    // partial output not parseable yet; try again later
                }
                if (sink.stop) {
                    finish = "cancelled";
                    return true;
                }
            }
            if (n_gen >= max_tokens) {
                finish = "length";
                return true;
            }
            return false;
        };

        // A decode on `gen` failed: the memory may hold part of the batch; return to the tokens
        // that are known to be in it.
        auto decode_failed = [&]() {
            if (gen_is_ctx) {
                if (!s->partial_rm) truncate_cache(*s, s->cached.size(), true);
                else llama_memory_seq_rm(mem, 0, (llama_pos) s->cached.size(), -1);
            }
            finish = "error";
        };

        bool done = false;
        llama_token tok = LLAMA_TOKEN_NULL;  // sampled and handled, not yet decoded (at `pos`)
        if (cancelled(*s)) {
            finish = "cancelled";
            done = true;
        } else if ((int) pos + 1 >= s->n_ctx || max_tokens <= 0) {
            done = true;
        } else {
            tok = common_sampler_sample(smpl, logits_ctx, -1);
            done = step(tok);
        }
        while (!done) {
            if (cancelled(*s)) {
                finish = "cancelled";
                break;
            }
            // Drafts that fit: every verified position stays inside the context (with room for
            // the token sampled after the last of them) and the output limit. A long catch-up of
            // the draft waits until the reply turns out not to be short.
            const int room = std::min(s->n_ctx - 3 - (int) pos, max_tokens - n_gen - 1);
            int k = speculating && sched.speculate_now() ? sched.next_k(room) : 0;
            if (k > 0) {
                const size_t catchup = draft_catchup(*s, seq);
                if (catchup > DRAFT_CATCHUP_SHORT && (size_t) n_gen < std::max((size_t) DRAFT_LONG_REPLY, catchup / 8)) k = 0;
            }
            if (k > 0) {
                // ---- speculative round: draft k tokens after `tok`, verify [tok, drafts] in one batch
                const int64_t t0 = ggml_time_us();
                const int synced = draft_sync(*s, seq);
                if (synced == -2) {
                    finish = "cancelled";
                    break;
                }
                const int64_t t_synced = ggml_time_us();
                const bool ok = synced >= 0 && draft_propose(*s, k, drafts);
                if (!ok) {
                    LOGE("draft failed; generating the rest without speculation");
                    speculating = false;
                    spec_status = "draft_failed";
                    drafts.clear();
                }
                // A long sync was the prompt's prefill on the draft: not a draft step.
                const double draft_ms = (double) (ggml_time_us() - (synced > (int) DRAFT_SYNC_STEP ? t_synced : t0)) / 1000.0;
                if (!drafts.empty()) {
                    batch.clear();
                    batch.add(tok, pos, 0, true);
                    for (size_t i = 0; i < drafts.size(); ++i) batch.add(drafts[i], pos + 1 + (llama_pos) i, 0, true);
                    const int64_t t1 = ggml_time_us();
                    if (llama_process(gen, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                        decode_failed();
                        break;
                    }
                    llama_synchronize(gen);
                    const int64_t t2 = ggml_time_us();
                    logits_ctx = gen;
                    if (gen_is_ctx) s->cached.push_back(tok);
                    ++pos;
                    // Each token is sampled by the model's sampler from the model's logits at its
                    // position, exactly as plain decoding would; a draft token is kept while the
                    // sample equals it (its logits are then the next position's).
                    int n_accepted = 0;
                    int emitted = 0;
                    bool stop = false;
                    for (size_t i = 0; i <= drafts.size(); ++i) {
                        const llama_token t = common_sampler_sample(smpl, gen, (int) i);
                        ++emitted;
                        if (step(t)) {
                            stop = true;
                            break;
                        }
                        if (i < drafts.size() && t == drafts[i]) {
                            if (gen_is_ctx) s->cached.push_back(t);
                            ++pos;
                            ++n_accepted;
                            continue;
                        }
                        tok = t;
                        break;
                    }
                    const double verify_ms = (double) (t2 - t1) / 1000.0;
                    const double host_ms = (double) (ggml_time_us() - t2) / 1000.0;
                    // Rejected drafts (and a stopping token) leave the model's memory.
                    if (n_accepted < (int) drafts.size() && !llama_memory_seq_rm(llama_get_memory(gen), 0, pos, -1)) {
                        LOGE("could not drop rejected drafts");
                        decode_failed();
                        break;
                    }
                    sched.on_round((int) drafts.size(), n_accepted, draft_ms, verify_ms, !stop, host_ms, emitted);
                    if (stop) break;
                    continue;
                }
            }
            // ---- plain step: decode `tok`, sample the next one
            batch.clear();
            batch.add(tok, pos, 0, true);
            const int64_t t1 = ggml_time_us();
            if (llama_process(gen, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                decode_failed();
                break;
            }
            if (speculating) llama_synchronize(gen);
            const int64_t t2 = ggml_time_us();
            logits_ctx = gen;
            if (gen_is_ctx) s->cached.push_back(tok);
            ++pos;
            if ((int) pos + 1 >= s->n_ctx) {
                finish = "length";
                break;
            }
            tok = common_sampler_sample(smpl, logits_ctx, -1);
            done = step(tok);
            if (speculating) sched.on_plain_step((double) (t2 - t1) / 1000.0, (double) (ggml_time_us() - t2) / 1000.0);
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
        for (const auto & call : msg.tool_calls) {
            auto c = common_json::object();
            // Unique across steps, turns and restarts: the session log keys tool results by call id.
            static std::atomic<uint64_t> next_call_id{(uint64_t) std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count()};
            c["id"] = call.id.empty() ? ("local_" + std::to_string(next_call_id.fetch_add(1))) : call.id;
            c["name"] = call.name;
            c["arguments"] = call.arguments;
            calls.push_back(c);
        }
        result["tool_calls"] = calls;
        result["finish_reason"] = msg.tool_calls.empty() ? finish : std::string("tool_calls");
        if (!reasoning_stop.empty() && span.closed) result["reasoning_stop"] = reasoning_stop;
        auto usage = common_json::object();
        usage["prompt_tokens"] = (int) prompt_tokens;
        usage["cached_tokens"] = (int) keep;
        usage["completion_tokens"] = n_gen;
        usage["reasoning_tokens"] = span.tokens;
        if (reasoning_us > 0) usage["reasoning_ms"] = (double) reasoning_us / 1000.0;
        usage["backend"] = s->backend;
        usage["prompt_ms"] = (double) prompt_us / 1000.0;
        usage["generation_ms"] = (double) (ggml_time_us() - t_gen) / 1000.0;
        if (!draft_path.empty()) {
            auto sp = common_json::object();
            sp["status"] = spec_status.empty() ? std::string("active") : spec_status;
            if (spec_status == "refused") sp["reason"] = s->draft_refusal;
            if (spec_status == "unsupported") sp["reason"] = "the model's memory cannot drop rejected drafts (hybrid/recurrent layers)";
            if (s->draft_model) {
                char desc[256] = {0};
                llama_model_desc(s->draft_model, desc, sizeof(desc));
                sp["draft"] = std::string(desc);
                sp["backend"] = s->draft_backend;
            }
            sp["rounds"] = sched.rounds;
            sp["drafted"] = sched.drafted;
            sp["accepted"] = sched.accepted;
            sp["acceptance"] = sched.drafted > 0 ? (double) sched.accepted / sched.drafted : 0.0;
            sp["k"] = sched.k;
            sp["paused"] = sched.paused_once;
            // What the schedule measured on this device (ms): it picks k and pauses from these.
            sp["alpha"] = sched.alpha();
            sp["plain_ms"] = sched.t_plain;
            sp["draft_ms"] = sched.t_draft;
            sp["extra_ms"] = sched.t_extra;
            sp["host_ms"] = sched.t_host;
            usage["speculative"] = sp;
        }
        result["usage"] = usage;
        return result.dump();
    } catch (const std::exception & e) {
        LOGE("chat failed: %s", e.what());
        return error_json("BAD_REQUEST", e.what());
    }
}

// Bytes the system could still give this process without reclaiming much (0 = unknown).
size_t available_memory() {
#if defined(__linux__)
    FILE * f = std::fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char line[256];
    size_t kb = 0;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::sscanf(line, "MemAvailable: %zu kB", &kb) == 1) break;
    }
    std::fclose(f);
    return kb * 1024;
#else
    return 0;
#endif
}

// Split placement: a second, CPU-only copy of the model for generation. Its repacked weights
// take about the model's size again, so it is skipped when memory is short; the session then
// simply generates on the accelerator.
void load_decoder(Session & s, const std::string & path, int n_threads, LoadAbort & abort) {
    const size_t need = llama_model_size(s.model) + ((size_t) 512 << 20);
    const size_t avail = available_memory();
    if (avail != 0 && avail < 2 * need) {
        LOGI("not enough free memory for a CPU decoder (%zu MB free); generating on %s", avail >> 20, s.backend.c_str());
        return;
    }
    ggml_backend_dev_t cpu_only[] = {nullptr};
    auto mparams = llama_model_default_params();
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    mparams.devices = cpu_only;
    mparams.n_gpu_layers = 0;
    mparams.progress_callback = [](float, void * data) { return !static_cast<LoadAbort *>(data)->requested(); };
    mparams.progress_callback_user_data = &abort;
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        if (!abort.requested()) LOGE("could not load a CPU decoder; generating on %s", s.backend.c_str());
        return;
    }
    auto cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) s.n_ctx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("could not create a CPU decoder context; generating on %s", s.backend.c_str());
        llama_model_free(model);
        return;
    }
    s.dec_model = model;
    s.dec_ctx = ctx;
    s.dec_partial_rm = common_context_can_seq_rm(ctx) == COMMON_CONTEXT_SEQ_RM_TYPE_PART;
    s.backend += "+cpu";
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
    return autobot_llama_load_abortable(path_c, n_ctx, n_threads, backend, nullptr, nullptr);
}

autobot_llama_session * autobot_llama_load_abortable(const char * path_c, int n_ctx, int n_threads, const char * backend,
                                                     autobot_llama_abort_fn should_abort, void * user) {
    LoadAbort abort{should_abort, user};
    const std::string path = path_c ? path_c : "";
    std::string wanted = backend ? backend : "";
    // "npu+cpu" / "gpu+cpu": prompts on the accelerator, generation on the CPU.
    const bool split = wanted == "npu+cpu" || wanted == "gpu+cpu";
    if (split) wanted.resize(3);
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
        if (should_abort) {
            mparams.progress_callback = [](float, void * data) { return !static_cast<LoadAbort *>(data)->requested(); };
            mparams.progress_callback_user_data = &abort;
        }
        llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
        if (abort.requested()) {
            LOGI("loading %s was cancelled", path.c_str());
            if (model) llama_model_free(model);
            return nullptr;
        }
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
        if (abort.requested()) {
            LOGI("loading %s was cancelled", path.c_str());
            if (ctx) llama_free(ctx);
            llama_model_free(model);
            return nullptr;
        }
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
        s->n_threads = n_threads;
        s->templates = common_chat_templates_init(model, "");
        s->thinking = thinking_caps(s->templates.get());
        s->partial_rm = common_context_can_seq_rm(ctx) == COMMON_CONTEXT_SEQ_RM_TYPE_PART;
        llama_set_abort_callback(ctx, abort_requested, s);
        if (split && kind != "cpu") {
            load_decoder(*s, path, n_threads, abort);
            if (abort.requested()) {
                LOGI("loading %s was cancelled", path.c_str());
                autobot_llama_free(s);
                return nullptr;
            }
        }
        LOGI("loaded %s on %s%s", path.c_str(), s->backend.c_str(), s->partial_rm ? "" : " (prompt cache via checkpoints)");
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
    auto thinking = common_json::object();
    thinking["supported"] = s->thinking.supported;
    thinking["toggle"] = s->thinking.toggle;
    thinking["effort"] = s->thinking.effort;
    thinking["start"] = s->thinking.start;
    thinking["end"] = s->thinking.end;
    info["thinking"] = thinking;
    return dup(info.dump());
}

char * autobot_llama_chat(autobot_llama_session * s, const char * request, size_t request_len,
                          autobot_llama_delta_fn on_delta, void * user) {
    return autobot_llama_chat_request(s, 0, request, request_len, on_delta, user);
}

char * autobot_llama_chat_request(autobot_llama_session * s, uint64_t token, const char * request, size_t request_len,
                                  autobot_llama_delta_fn on_delta, void * user) {
    std::lock_guard<std::mutex> guard(s->lock);
    s->cancel.store(false);
    s->token.store(token);
    Sink sink{on_delta, user};
    char * result = dup(chat(s, std::string(request, request_len), sink));
    s->token.store(0);
    return result;
}

void autobot_llama_cancel(autobot_llama_session * s) {
    if (s) s->cancel.store(true);
}

void autobot_llama_cancel_request(autobot_llama_session * s, uint64_t token) {
    if (s && token != 0) s->cancel_token.store(token);
}

void autobot_llama_free(autobot_llama_session * s) {
    if (!s) return;
    {
        std::lock_guard<std::mutex> guard(s->lock);
        s->templates.reset();
        if (s->mtmd) mtmd_free(s->mtmd);
        llama_free(s->ctx);
        llama_model_free(s->model);
        if (s->dec_ctx) llama_free(s->dec_ctx);
        if (s->dec_model) llama_model_free(s->dec_model);
        free_draft(*s);
    }
    delete s;
}

void autobot_llama_string_free(char * str) {
    std::free(str);
}

char * autobot_llama_draft_check(const char * model_path, const char * draft_path) {
    auto out = common_json::object();
    auto mparams = llama_model_default_params();
    mparams.vocab_only = true;
    llama_model * target = model_path && *model_path ? llama_model_load_from_file(model_path, mparams) : nullptr;
    llama_model * draft = target && draft_path && *draft_path ? llama_model_load_from_file(draft_path, mparams) : nullptr;
    if (!target || !draft) {
        out["compatible"] = false;
        out["reason"] = !target ? "the chat model cannot be read" : "the draft model cannot be read";
    } else {
        const std::string why = vocab_mismatch(target, draft);
        out["compatible"] = why.empty();
        if (!why.empty()) out["reason"] = why;
    }
    if (draft) llama_model_free(draft);
    if (target) llama_model_free(target);
    return dup(out.dump());
}

char * autobot_llama_chat_template(const char * path) {
    if (!path || !*path) return nullptr;
    gguf_init_params params = {/*.no_alloc =*/ true, /*.ctx =*/ nullptr};
    gguf_context * g = gguf_init_from_file(path, params);
    if (!g) return nullptr;
    char * out = nullptr;
    const int64_t key = gguf_find_key(g, "tokenizer.chat_template");
    if (key >= 0 && gguf_get_kv_type(g, key) == GGUF_TYPE_STRING) out = dup(gguf_get_val_str(g, key));
    gguf_free(g);
    return out;
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
