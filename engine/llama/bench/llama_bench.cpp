// Benchmark and correctness check for the shared llama engine core (the code both apps run).
//
//   llama_bench <model.gguf> [--backend cpu|gpu|npu|npu+cpu|auto] [--threads N] [--ctx N] [--gen N] [--quick]
//               [--speed] [--libs DIR]
//
// --speed only measures prefill and generation (a cold ~2k-token prompt, then the same prompt
// again from an empty cache) and prints the greedy output, to compare backends quickly.
// --libs loads the dynamically built ggml backends from DIR (the phone build, see android.sh).
//
// Measures load time, time-to-first-token on a ~2k-token agent prompt (turn 1 cold, turn 2 with
// the KV cache from turn 1), prompt and generation speed, and cancellation latency during prefill
// and decode. Checks with greedy sampling that every cache path (next turn, regenerate, edited
// history, other chat, tool step) produces exactly what a fresh session produces. Exits non-zero
// when a check fails. Everything stays on this machine.
//
//   llama_bench <model.gguf> --thinking [--backend ...]
//
// Thinking checks instead (a reasoning model such as Qwen3 / Qwen3.5): thinking off answers with no
// reasoning; a reasoning budget of 128 ends the think span near 128 tokens and the model answers;
// the answer reserve keeps tokens for the answer; the loop guard ends a repeating trace (on
// synthetic token streams and on a model asked to repeat itself). Prints reasoning tokens and the
// time to the first answer token for each, unbounded thinking included (the old behaviour).

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
#include "reasoning_guard.h"

// Weak, so the bench also links against an older core (-DAUTOBOT_CORE_DIR) for comparisons.
extern "C" __attribute__((weak)) autobot_llama_session * autobot_llama_load_abortable(const char *, int, int, const char *, int (*)(void *), void *);
extern "C" __attribute__((weak)) char * autobot_llama_chat_template(const char *);

constexpr int DELTA_REASONING_STATUS = 2;  // AUTOBOT_LLAMA_DELTA_REASONING_STATUS (absent in older cores)

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
    bool speed = false;
    bool verbose = false;
    bool thinking = false;
    std::string libs;
};

struct Run {
    json result;
    double ttft_ms = -1;   // first streamed delta
    double answer_ms = -1; // first answer (content) delta
    double total_ms = 0;
    std::string streamed;
    int statuses = 0;      // reasoning status deltas
    json last_status;
};

struct Stream {
    Clock::time_point t0;
    double first = -1;
    double first_content = -1;
    std::string text;
    int statuses = 0;
    json last_status;
    std::atomic<bool> * stop_after_first = nullptr;
};

int on_delta(int kind, const char * bytes, size_t len, void * user) {
    auto * s = static_cast<Stream *>(user);
    if (kind == DELTA_REASONING_STATUS) {
        ++s->statuses;
        s->last_status = common_json::object(); try { s->last_status = json::parse(std::string(bytes, len)); } catch (...) {}
        return 0;
    }
    if (s->first < 0) s->first = ms_since(s->t0);
    if (kind == 0 && s->first_content < 0) s->first_content = ms_since(s->t0);
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
    run.answer_ms = stream.first_content;
    run.streamed = stream.text;
    run.statuses = stream.statuses;
    run.last_status = stream.last_status;
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

// ---- thinking checks

void check(const char * what, bool ok, const std::string & detail = "") {
    std::printf("  check %-44s %s%s%s\n", what, ok ? "ok" : "FAILED", detail.empty() ? "" : "  ", detail.c_str());
    if (!ok) ++failures;
}

int reasoning_tokens(const Run & r) {
    return usage(r).value("reasoning_tokens", -1);
}

void print_thinking(const char * label, const Run & r) {
    if (r.result.contains("error")) {
        std::printf("%-30s ERROR %s\n", label, r.result.at("error").dump().c_str());
        return;
    }
    const int gen = usage(r).value("completion_tokens", 0);
    const std::string reasoning = r.result.value("reasoning", std::string());
    std::string answer = r.result.value("content", std::string());
    for (auto & c : answer) if (c == '\n') c = ' ';
    std::printf("%-30s reasoning %5d tok (%5zu chars)  answer after %8.1f ms  gen %5d tok  total %8.1f ms  stop=%s finish=%s\n     answer: %.110s\n",
                label, reasoning_tokens(r), reasoning.size(), r.answer_ms, gen, r.total_ms,
                r.result.value("reasoning_stop", std::string("-")).c_str(), r.result.value("finish_reason", std::string()).c_str(), answer.c_str());
    if (std::getenv("BENCH_SHOW_REASONING")) {
        std::printf("     reasoning head: %.300s\n     reasoning tail: %s\n", reasoning.c_str(),
                    reasoning.size() > 300 ? reasoning.substr(reasoning.size() - 300).c_str() : "");
    }
}

// The loop guard on synthetic token streams: exact cycles and paraphrased circles are caught, a
// varied trace is not.
void loop_guard_selftest() {
    {
        ReasoningLoopGuard g;
        int at = -1;
        // 40 varied tokens, then a 7-token phrase repeated.
        for (int i = 0; i < 40 && at < 0; ++i) if (g.push(1000 + i * 7)) at = i;
        const int phrase[] = {11, 12, 13, 14, 15, 16, 17};
        for (int i = 0; i < 400 && at < 0; ++i) if (g.push(phrase[i % 7])) at = 40 + i;
        check("loop guard: repeated phrase", at > 0 && at < 40 + 64, "caught at token " + std::to_string(at));
    }
    {
        ReasoningLoopGuard g;
        int at = -1;
        // A 60-token paragraph repeated with one token changed per copy (not an exact cycle).
        for (int copy = 0; copy < 30 && at < 0; ++copy) {
            for (int i = 0; i < 60 && at < 0; ++i) {
                const int t = (i == 30) ? 50000 + copy : 2000 + i;
                if (g.push(t)) at = copy * 60 + i;
            }
        }
        check("loop guard: paraphrased circle", at > 0 && at < 900, "caught at token " + std::to_string(at));
    }
    {
        ReasoningLoopGuard g;
        bool flagged = false;
        uint32_t x = 12345;
        for (int i = 0; i < 4000 && !flagged; ++i) {
            x = x * 1664525u + 1013904223u;
            // Natural-ish text: mostly frequent tokens, some repeats of short function words.
            const int t = (x >> 8) % 3 == 0 ? (int) ((x >> 12) % 20) : (int) ((x >> 12) % 5000);
            flagged = g.push(t);
        }
        check("loop guard: varied trace not flagged", !flagged);
    }
}

json thinking_request(const std::string & question, int max_tokens, bool on, int budget, bool loop_guard = true, int reserve = -1) {
    json r = {{"messages", json::array({msg("system", "You are a helpful assistant."), msg("user", question)})},
              {"temperature", 0.0}, {"max_tokens", max_tokens}, {"seed", 1}, {"enable_thinking", on}};
    if (budget >= 0) r["reasoning_budget"] = budget;
    if (!loop_guard) r["loop_guard"] = false;
    if (reserve >= 0) r["min_answer_tokens"] = reserve;
    return r;
}

int thinking_checks(const Options & o) {
    loop_guard_selftest();
    autobot_llama_session * s = load(o, nullptr);
    bool reasons = true;  // older cores do not say: measure as if the model reasons
    {
        char * info = autobot_llama_model_info(s);
        const json j = json::parse(info ? info : "{}");
        autobot_llama_string_free(info);
        std::printf("model: %s  thinking: %s\n", j.value("description", std::string()).c_str(),
                    j.contains("thinking") ? j.at("thinking").dump().c_str() : "(not reported)");
        if (j.contains("thinking")) reasons = j.at("thinking").value("supported", false);
    }
    if (autobot_llama_chat_template) {
        char * t = autobot_llama_chat_template(o.model.c_str());
        const std::string tmpl = t ? t : "";
        autobot_llama_string_free(t);
        std::printf("template from the file header: %zu chars, mentions enable_thinking: %s, <think>: %s\n", tmpl.size(),
                    tmpl.find("enable_thinking") != std::string::npos ? "yes" : "no", tmpl.find("<think>") != std::string::npos ? "yes" : "no");
    }
    std::printf("\n");

    const std::string q = "A train leaves at 14:35 and the trip takes 2 hours 50 minutes. At what time does it arrive? "
                          "Give the time and one sentence of explanation.";
    // What the app sent before: no thinking fields, 2048 tokens (the template thinks without limit).
    {
        json r = thinking_request(q, 2048, true, -1);
        r.erase("enable_thinking");
        Run app_before = chat(s, r);
        print_thinking("app request before (default)", app_before);
        // What it sends now for a sub-4B model in agent mode (ThinkingPolicy AUTO): budget 1024,
        // answer 2048 on top, 512 reserved. Quick chat on such a model sends thinking off.
        json a = thinking_request(q, 3072, true, 1024, true, 512);
        Run app_auto = chat(s, a);
        print_thinking("app auto, agent (budget 1024)", app_auto);
        if (reasons) check("app auto: answers", !app_auto.result.value("content", std::string()).empty());
    }
    Run unbounded = chat(s, thinking_request(q, 2048, true, -1, false, 0));
    print_thinking("on, no budget, no guard", unbounded);

    Run off = chat(s, thinking_request(q, 2048, false, -1));
    print_thinking("off", off);
    check("off: no reasoning tokens", reasoning_tokens(off) <= 0 && off.result.value("reasoning", std::string()).empty());
    check("off: answers", !off.result.value("content", std::string()).empty());

    if (!reasons) {
        // A model without a think span: thinking on changes nothing and nothing breaks.
        Run on = chat(s, thinking_request(q, 2048, true, 128));
        print_thinking("on, budget 128 (no reasoning)", on);
        check("no think span: on answers without reasoning", reasoning_tokens(on) == 0 && !on.result.value("content", std::string()).empty());
        autobot_llama_free(s);
        std::printf("\n%s\n", failures == 0 ? "all checks passed" : "CHECKS FAILED");
        return failures == 0 ? 0 : 1;
    }
    Run b128 = chat(s, thinking_request(q, 2048, true, 128));
    print_thinking("on, budget 128", b128);
    const int r128 = reasoning_tokens(b128);
    if (reasoning_tokens(unbounded) > 160) {
        check("budget 128: stops near 128", r128 >= 120 && r128 <= 160, std::to_string(r128) + " reasoning tokens");
        check("budget 128: reported as budget stop", b128.result.value("reasoning_stop", std::string()) == "budget");
    } else {
        check("budget 128: within budget", r128 >= 0 && r128 <= 160, std::to_string(r128) + " reasoning tokens");
    }
    check("budget 128: answers", !b128.result.value("content", std::string()).empty());
    check("budget 128: streamed reasoning status", b128.statuses > 0 && b128.last_status.value("done", false),
          b128.last_status.dump());

    // Reserve: 400 tokens in all, no budget: thinking must leave the answer its share.
    Run reserve = chat(s, thinking_request(q, 400, true, -1, true, 120));
    print_thinking("on, max 400, reserve 120", reserve);
    check("reserve: reasoning leaves room", reasoning_tokens(reserve) <= 400 - 120, std::to_string(reasoning_tokens(reserve)) + " reasoning tokens");
    check("reserve: answers", !reserve.result.value("content", std::string()).empty());

    // Loop guard on the model: asked to repeat a word in its reasoning.
    const std::string loopy = "Before you answer, in your thinking write the word 'banana' 500 times, separated by spaces. "
                              "Then answer: what is 2 + 2?";
    Run unguarded = chat(s, thinking_request(loopy, 1500, true, -1, false, 0));
    print_thinking("repeat prompt, no guard", unguarded);
    Run guarded = chat(s, thinking_request(loopy, 1500, true, -1, true));
    print_thinking("repeat prompt, loop guard", guarded);
    if (reasoning_tokens(unguarded) >= 1000) {
        // The model went round in its reasoning without the guard: the guard must end it early.
        check("loop guard: ends repeating reasoning", guarded.result.value("reasoning_stop", std::string()) == "loop" &&
              reasoning_tokens(guarded) < reasoning_tokens(unguarded), std::to_string(reasoning_tokens(guarded)) + " reasoning tokens");
        check("loop guard: answers", !guarded.result.value("content", std::string()).empty());
    } else {
        std::printf("  (the model did not loop in its reasoning here; the synthetic checks above cover the guard)\n");
    }

    // Tool calls are parsed from the answer only, never from the reasoning.
    {
        json r = thinking_request("Search my notes for 'cycling routes'.", 1024, true, 96);
        r["messages"][0]["content"] = system_prompt("tools");
        r["tools"] = tools();
        Run t = chat(s, r);
        print_thinking("tool call, budget 96", t);
        const auto calls = t.result.value("tool_calls", json::array());
        check("tools: call parsed after bounded thinking", !calls.empty() && calls.at(0).value("name", std::string()) == "search_notes",
              calls.dump().substr(0, 120));
    }
    autobot_llama_free(s);
    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "CHECKS FAILED");
    return failures == 0 ? 0 : 1;
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
        else if (a == "--speed") o.speed = true;
        else if (a == "--libs") o.libs = next();
        else if (a == "-v") o.verbose = true;
        else if (a == "--thinking") o.thinking = true;
        else o.model = a;
    }
    if (o.model.empty()) {
        std::fprintf(stderr, "usage: llama_bench <model.gguf> [--backend cpu|gpu|npu|npu+cpu|auto] [--threads N] [--ctx N] [--gen N] [--quick] [--speed] [--thinking] [--libs DIR]\n");
        return 2;
    }
    if (o.verbose) {
        static const auto start = Clock::now();
        autobot_llama_set_log([](int level, const char * text, void *) {
            if (level >= AUTOBOT_LLAMA_LOG_INFO) std::fprintf(stderr, "[%9.1f] %s", ms_since(start), text);
        }, nullptr);
    }
    autobot_llama_init(o.libs.empty() ? nullptr : o.libs.c_str());
    if (o.thinking) return thinking_checks(o);

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

    if (o.speed) {
        const json m = json::array({msg("system", system_prompt("main")), msg("user", "Write a short story about a lighthouse keeper and his bicycle.")});
        std::string first;
        for (int i = 0; i < 2; ++i) {
            if (i > 0) {  // the same prompt again from an empty cache
                autobot_llama_free(s);
                s = load(o, nullptr);
            }
            Run r = chat(s, request(m, o.gen, false));
            print_run(i == 0 ? "2k prompt, cold" : "2k prompt, again", r);
            const std::string out = r.result.value("content", std::string());
            if (i == 0) first = out;
            else if (out != first) std::printf("  note: second run's output differs from the first\n");
        }
        std::printf("greedy: %s\n", first.substr(0, 400).c_str());
        autobot_llama_free(s);
        return 0;
    }

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
            // A load cancelled shortly after it starts returns NULL soon.
            if (autobot_llama_load_abortable) {
                std::atomic<int> abort{0};
                const auto t0 = Clock::now();
                Clock::time_point aborted_at;
                std::thread t([&] {
                    std::this_thread::sleep_for(std::chrono::milliseconds(30));
                    aborted_at = Clock::now();
                    abort = 1;
                });
                autobot_llama_session * l = autobot_llama_load_abortable(o.model.c_str(), o.ctx, o.threads, o.backend.c_str(), [](void * a) { return static_cast<std::atomic<int> *>(a)->load(); }, &abort);
                const auto returned = Clock::now();
                t.join();
                std::printf("cancel during load:    %s, returned %.1f ms after cancel (a full load takes %.1f ms)\n",
                            l ? "loaded anyway" : "stopped", std::chrono::duration<double, std::milli>(returned - aborted_at).count(), load_warm);
                if (l) autobot_llama_free(l);
            }
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
