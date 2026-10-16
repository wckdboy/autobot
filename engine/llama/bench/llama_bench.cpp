// Benchmark and correctness check for the shared llama engine core (the code both apps run).
//
//   llama_bench <model.gguf> [--backend cpu|gpu|auto] [--threads N] [--ctx N] [--gen N] [--quick]
//
// Measures load time, time-to-first-token on a ~2k-token agent prompt (turn 1 cold, turn 2 with
// the KV cache from turn 1), prompt and generation speed, and cancellation latency during prefill
// and decode. Checks with greedy sampling that every cache path (next turn, regenerate, edited
// history, other chat, tool step) produces exactly what a fresh session produces. Exits non-zero
// when a check fails. Everything stays on this machine.

#include "autobot_llama.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "json.h"

using json = common_json;
using Clock = std::chrono::steady_clock;

namespace {

double ms_since(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

struct Options {
    std::string model;
    std::string backend = "auto";
    int threads = 4;
    int ctx = 8192;
    int gen = 64;
    bool quick = false;
    bool verbose = false;
};

struct Run {
    json result;
    double ttft_ms = -1;   // first streamed delta
    double total_ms = 0;
    std::string streamed;
};

struct Stream {
    Clock::time_point t0;
    double first = -1;
    std::string text;
    std::atomic<bool> * stop_after_first = nullptr;
};

int on_delta(int, const char * bytes, size_t len, void * user) {
    auto * s = static_cast<Stream *>(user);
    if (s->first < 0) s->first = ms_since(s->t0);
    s->text.append(bytes, len);
    return 0;
}

Run chat(autobot_llama_session * session, const json & request, uint64_t token = 0) {
    const std::string body = request.dump();
    Stream stream;
    stream.t0 = Clock::now();
    char * out = autobot_llama_chat_request(session, token, body.data(), body.size(), on_delta, &stream);
    Run run;
    run.total_ms = ms_since(stream.t0);
    run.ttft_ms = stream.first;
    run.streamed = stream.text;
    run.result = json::parse(out ? out : "{}");
    autobot_llama_string_free(out);
    return run;
}

// A long, deterministic agent system prompt (instructions + memory) and a tool list: together
// about 2k tokens with a Qwen-style template, like the app's agent loop sends.
std::string system_prompt(const std::string & flavour) {
    std::string s = "You are Autobot (" + flavour + "), a private assistant running entirely on this phone. "
                    "Follow the user's instructions carefully, answer concisely, and use tools when they help.\n\n";
    const char * rules[] = {
        "Never send personal data anywhere; every tool runs on this device.",
        "Prefer short answers; expand only when asked.",
        "When a tool fails, explain the failure and suggest a next step.",
        "Quote file names exactly as the user wrote them.",
        "Dates are in the user's local time zone unless stated otherwise.",
        "When unsure, ask one clarifying question instead of guessing.",
        "Format lists with dashes and keep each item to one line.",
        "Do not invent tool results; call the tool or say you cannot.",
    };
    for (int round = 0; round < 9; ++round) {
        s += "## Section " + std::to_string(round + 1) + "\n";
        for (const char * r : rules) s += "- " + std::string(r) + " (rule " + std::to_string(round + 1) + ")\n";
        s += "Memory note " + std::to_string(round + 1) + ": the user likes concise replies, metric units, "
             "and keeps notes about gardening, cycling routes and a small Kotlin project.\n\n";
    }
    return s;
}

json tools() {
    json list = json::array();
    const char * names[] = {"search_notes", "read_file", "write_file", "calendar_add", "calendar_list",
                            "set_timer", "weather_cache", "calculator", "vault_search", "web_fetch_disabled"};
    for (const char * n : names) {
        json params = {
            {"type", "object"},
            {"properties", {
                {"query", {{"type", "string"}, {"description", std::string("What to look for with ") + n}}},
                {"limit", {{"type", "integer"}, {"description", "Maximum number of results (1-50)"}}},
                {"path", {{"type", "string"}, {"description", "A path inside the app's private storage"}}},
            }},
            {"required", json::array({"query"})},
        };
        list.push_back({{"type", "function"},
                        {"function", {{"name", n}, {"description", std::string("Tool ") + n + ": runs on device and returns JSON."}, {"parameters", params}}}});
    }
    return list;
}

json request(const json & messages, int max_tokens, bool with_tools = true) {
    json r = {{"messages", messages}, {"temperature", 0.0}, {"max_tokens", max_tokens}, {"enable_thinking", false}, {"seed", 1}};
    if (with_tools) r["tools"] = tools();
    return r;
}

json msg(const std::string & role, const std::string & content) {
    return {{"role", role}, {"content", content}};
}

// The assistant turn as the app stores it (content and tool calls, reasoning dropped).
json assistant_of(const json & result) {
    json m = {{"role", "assistant"}, {"content", result.value("content", std::string())}};
    if (result.contains("tool_calls") && !result.at("tool_calls").empty()) {
        json calls = json::array();
        for (const auto & c : result.at("tool_calls")) {
            calls.push_back({{"id", c.value("id", std::string())}, {"type", "function"},
                             {"function", {{"name", c.value("name", std::string())}, {"arguments", c.value("arguments", std::string())}}}});
        }
        m["tool_calls"] = calls;
    }
    return m;
}

const json & usage(const Run & r) {
    static const json empty = json::object();
    return r.result.contains("usage") ? r.result.at("usage") : empty;
}

void print_run(const char * label, const Run & r) {
    const json & u = usage(r);
    if (r.result.contains("error")) {
        std::printf("%-26s ERROR %s\n", label, r.result.at("error").dump().c_str());
        return;
    }
    const double prompt_ms = u.value("prompt_ms", 0.0);
    const double gen_ms = u.value("generation_ms", 0.0);
    const int prompt = u.value("prompt_tokens", 0);
    const int cached = u.value("cached_tokens", 0);
    const int gen = u.value("completion_tokens", 0);
    std::printf("%-26s prompt %5d (cached %5d)  prefill %8.1f ms (%7.1f tok/s)  ttft %8.1f ms  gen %4d tok %7.1f tok/s  total %8.1f ms\n",
                label, prompt, cached, prompt_ms, (prompt - cached) > 0 && prompt_ms > 0 ? (prompt - cached) * 1000.0 / prompt_ms : 0.0,
                r.ttft_ms, gen, gen_ms > 0 ? gen * 1000.0 / gen_ms : 0.0, r.total_ms);
}

int failures = 0;

void check_same(const char * what, const Run & cached, const Run & fresh) {
    const std::string a = cached.result.value("content", std::string()) + "|" + cached.result.value("tool_calls", json::array()).dump();
    const std::string b = fresh.result.value("content", std::string()) + "|" + fresh.result.value("tool_calls", json::array()).dump();
    // Tool call ids are generated per call; compare names and arguments only.
    auto strip_ids = [](json calls) {
        for (auto & c : calls) c.erase("id");
        return calls.dump();
    };
    const std::string ca = cached.result.value("content", std::string()) + "|" + strip_ids(cached.result.value("tool_calls", json::array()));
    const std::string cb = fresh.result.value("content", std::string()) + "|" + strip_ids(fresh.result.value("tool_calls", json::array()));
    const bool ok = ca == cb && !cached.result.contains("error") && !fresh.result.contains("error");
    std::printf("  check %-34s %s\n", what, ok ? "same as fresh session" : "MISMATCH");
    if (!ok) {
        ++failures;
        std::printf("    cached: %s\n    fresh:  %s\n", ca.substr(0, 300).c_str(), cb.substr(0, 300).c_str());
        if (cached.result.contains("error")) std::printf("    cached error: %s\n", cached.result.at("error").dump().c_str());
    }
}

autobot_llama_session * load(const Options & o, double * ms) {
    const auto t0 = Clock::now();
    autobot_llama_session * s = autobot_llama_load(o.model.c_str(), o.ctx, o.threads, o.backend.c_str());
    if (ms) *ms = ms_since(t0);
    if (!s) {
        std::fprintf(stderr, "cannot load %s\n", o.model.c_str());
        std::exit(2);
    }
    return s;
}

// Starts a chat on another thread and cancels it `after_ms` into the run; returns how long the
// chat took to return after the cancel and how it finished.
std::pair<double, std::string> cancel_latency(autobot_llama_session * s, const json & req, double after_ms) {
    std::atomic<bool> done{false};
    Clock::time_point cancelled_at;
    Run run;
    std::thread t([&] {
        run = chat(s, req, 77);
        done = true;
    });
    std::this_thread::sleep_for(std::chrono::microseconds((int64_t) (after_ms * 1000)));
    cancelled_at = Clock::now();
    autobot_llama_cancel_request(s, 77);
    t.join();
    const double latency = ms_since(cancelled_at) - 0.0;
    std::string how = run.result.contains("error") ? run.result.at("error").value("code", std::string())
                                                   : run.result.value("finish_reason", std::string());
    // ms_since measured after join: subtract nothing, the join returns as soon as the chat does.
    return {latency, how};
}

}  // namespace

int main(int argc, char ** argv) {
    Options o;
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "--backend") o.backend = next();
        else if (a == "--threads") o.threads = std::atoi(next().c_str());
        else if (a == "--ctx") o.ctx = std::atoi(next().c_str());
        else if (a == "--gen") o.gen = std::atoi(next().c_str());
        else if (a == "--quick") o.quick = true;
        else if (a == "-v") o.verbose = true;
        else o.model = a;
    }
    if (o.model.empty()) {
        std::fprintf(stderr, "usage: llama_bench <model.gguf> [--backend cpu|gpu|auto] [--threads N] [--ctx N] [--gen N] [--quick]\n");
        return 2;
    }
    if (o.verbose) {
        static const auto start = Clock::now();
        autobot_llama_set_log([](int level, const char * text, void *) {
            if (level >= AUTOBOT_LLAMA_LOG_INFO) std::fprintf(stderr, "[%9.1f] %s", ms_since(start), text);
        }, nullptr);
    }
    autobot_llama_init(nullptr);

    double load_cold = 0, load_warm = 0;
    autobot_llama_session * s = load(o, &load_cold);
    {
        char * info = autobot_llama_model_info(s);
        std::printf("model: %s\n", info);
        autobot_llama_string_free(info);
    }
    autobot_llama_free(s);
    s = load(o, &load_warm);
    std::printf("load: first %.1f ms, again (page cache warm) %.1f ms\n\n", load_cold, load_warm);

    // ---- agent conversation: turn 1 (cold), turn 2 (cached), regenerate, tool step
    const std::string sys = system_prompt("main");
    json m1 = json::array({msg("system", sys), msg("user", "List three things I could do this weekend, one line each.")});
    Run t1 = chat(s, request(m1, o.gen));
    print_run("turn 1 (cold cache)", t1);

    json m2 = m1;
    m2.push_back(assistant_of(t1.result));
    m2.push_back(msg("user", "Make the second one about cycling and keep it short."));
    Run t2 = chat(s, request(m2, o.gen));
    print_run("turn 2 (same chat)", t2);

    Run regen = chat(s, request(m2, o.gen));
    print_run("turn 2 regenerated", regen);

    json m3 = m2;
    m3.push_back(assistant_of(t2.result));
    m3.push_back(msg("user", "Search my notes for 'cycling routes' with the search_notes tool."));
    Run t3 = chat(s, request(m3, o.gen));
    print_run("turn 3 (tool request)", t3);

    json m4 = m3;
    m4.push_back(assistant_of(t3.result));
    if (t3.result.contains("tool_calls") && !t3.result.at("tool_calls").empty()) {
        const auto & call = t3.result.at("tool_calls").at(0);
        m4.push_back({{"role", "tool"}, {"tool_call_id", call.value("id", std::string())}, {"content", "{\"results\":[\"Lakeside loop, 32 km\",\"Old rail path, 18 km\"]}"}});
    } else {
        m4.push_back(msg("user", "Thanks. Now summarise in one sentence."));
    }
    Run t4 = chat(s, request(m4, o.gen));
    print_run("turn 4 (after tool result)", t4);

    // ---- edited history: the first user message changes (prefix up to it stays valid)
    json edited = m2;
    edited[1]["content"] = "List three things I could do this weekend with friends, one line each.";
    Run ed = chat(s, request(edited, o.gen));
    print_run("edited earlier message", ed);

    // ---- another chat with a different system prompt, then back to the first
    json other = json::array({msg("system", system_prompt("other")), msg("user", "What is 17 times 23?")});
    Run oc = chat(s, request(other, o.gen));
    print_run("switch to other chat", oc);
    Run back = chat(s, request(m4, o.gen));
    print_run("back to first chat", back);

    // ---- a new chat with the same agent (same system prompt and tools, new conversation)
    json sibling = json::array({msg("system", sys), msg("user", "Suggest a name for a red bicycle.")});
    Run sib = chat(s, request(sibling, o.gen));
    print_run("new chat, same agent", sib);

    // ---- truncation: an older history (shorter than what is cached)
    Run trunc = chat(s, request(m1, o.gen));
    print_run("truncated to turn 1", trunc);

    std::printf("\n");
    // ---- correctness: each of the above against a fresh session (empty cache)
    {
        autobot_llama_free(s);
        s = nullptr;
        autobot_llama_session * fresh = load(o, nullptr);
        auto fresh_run = [&](const json & m) {
            // Forget the cache by loading anew: the reference is a session that never saw anything.
            autobot_llama_free(fresh);
            fresh = load(o, nullptr);
            return chat(fresh, request(m, o.gen));
        };
        check_same("turn 2", t2, fresh_run(m2));
        check_same("regenerate", regen, fresh_run(m2));
        check_same("turn 3", t3, fresh_run(m3));
        check_same("turn 4 (tool result)", t4, fresh_run(m4));
        check_same("edited history", ed, fresh_run(edited));
        check_same("other chat", oc, fresh_run(other));
        check_same("back to first chat", back, fresh_run(m4));
        check_same("new chat, same agent", sib, fresh_run(sibling));
        check_same("truncated", trunc, fresh_run(m1));
        autobot_llama_free(fresh);
    }

    s = load(o, nullptr);
    if (!o.quick) {
        // ---- long generation (streaming/parse cost grows with output length)
        json longer = json::array({msg("system", "You are a helpful assistant."), msg("user", "Write a detailed 600-word story about a lighthouse keeper.")});
        json lr = request(longer, 512, false);
        Run lg = chat(s, lr);
        print_run("long generation (512)", lg);

        // ---- context limit: a prompt larger than the context is refused, not crashed
        {
            std::string words;
            for (int i = 0; i < o.ctx * 2; ++i) words += "w" + std::to_string(i) + " ";
            json huge = json::array({msg("system", words), msg("user", "hi")});
            Run h = chat(s, request(huge, 8, false));
            const bool ok = h.result.contains("error") && h.result.at("error").value("code", std::string()) == "CONTEXT_WINDOW_EXCEEDED";
            std::printf("  check %-34s %s\n", "context overflow", ok ? "refused cleanly" : "UNEXPECTED");
            if (!ok) ++failures;
        }

        // ---- cancellation latency: during prefill of a long prompt and during decode
        {
            json big = json::array({msg("system", system_prompt("cancel-a") + system_prompt("cancel-b") + system_prompt("cancel-c")), msg("user", "Summarise the rules.")});
            auto [lat_prefill, how_prefill] = cancel_latency(s, request(big, 256), 150);
            std::printf("cancel during prefill: returned %.1f ms after cancel (%s)\n", lat_prefill, how_prefill.c_str());
            json fresh_chat = json::array({msg("user", "Count from 1 to 500, one number per line.")});
            // Warm the prompt first so the cancel lands in decode.
            auto [lat_decode, how_decode] = cancel_latency(s, request(fresh_chat, 2000, false), 1500);
            std::printf("cancel during decode:  returned %.1f ms after cancel (%s)\n", lat_decode, how_decode.c_str());
            // The session still works after cancels.
            Run after = chat(s, request(m1, o.gen));
            const bool ok = !after.result.contains("error");
            std::printf("  check %-34s %s\n", "usable after cancel", ok ? "ok" : "FAILED");
            if (!ok) ++failures;
        }
    }
    autobot_llama_free(s);
    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "CHECKS FAILED");
    return failures == 0 ? 0 : 1;
}
