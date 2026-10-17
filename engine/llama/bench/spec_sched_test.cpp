// Unit tests for the speculative decoding schedule (core/speculative_sched.h): no model needed.
//
//   cmake --build <build> --target spec_sched_test && <build>/spec_sched_test
//
// Exits non-zero when a check fails.

#include "speculative_sched.h"

#include <cmath>
#include <cstdio>
#include <random>
#include <utility>
#include <vector>

namespace {

int failures = 0;

void check(const char * what, bool ok, double value = NAN) {
    if (std::isnan(value)) std::printf("  %-62s %s\n", what, ok ? "ok" : "FAILED");
    else std::printf("  %-62s %s  (%.3f)\n", what, ok ? "ok" : "FAILED", value);
    if (!ok) ++failures;
}

// Simulates a decode with per-token acceptance `alpha` and the given costs (ms); returns the
// realised speedup over plain decoding and leaves the schedule's state in `s`.
double simulate(SpeculativeSchedule & s, double alpha, double t_plain, double t_draft, double t_extra, int tokens, uint32_t seed = 7) {
    std::mt19937 rng(seed);
    std::uniform_real_distribution<double> u(0.0, 1.0);
    double spent = 0;
    int made = 0;
    while (made < tokens) {
        const int k = s.speculate_now() ? s.next_k(tokens - made - 1) : 0;
        if (k <= 0) {
            s.on_plain_step(t_plain, 0.0);
            spent += t_plain;
            ++made;
            continue;
        }
        int acc = 0;
        while (acc < k && u(rng) < alpha) ++acc;
        const double verify = t_plain + k * t_extra;
        s.on_round(k, acc, k * t_draft, verify, true, 0.0, acc + 1);
        spent += k * t_draft + verify;
        made += acc + 1;
    }
    return (made * t_plain) / spent;
}

}  // namespace

int main() {
    std::printf("speculative schedule\n");

    // Expected tokens per round.
    check("E(k): alpha 0 gives one token", std::fabs(SpeculativeSchedule::expected_tokens(0.0, 5) - 1.0) < 1e-9);
    check("E(k): alpha 1 gives k + 1 tokens", std::fabs(SpeculativeSchedule::expected_tokens(1.0, 4) - 5.0) < 1e-9);
    check("E(k): alpha 0.5, k 2 gives 1.75", std::fabs(SpeculativeSchedule::expected_tokens(0.5, 2) - 1.75) < 1e-9);

    {
        SpeculativeSchedule s;
        check("warmup: plain steps first", !s.speculate_now());
        s.on_plain_step(20);
        s.on_plain_step(20);
        check("warmup: then speculates", s.speculate_now());
        check("t_plain measured", std::fabs(s.t_plain - 20) < 1e-9, s.t_plain);
    }

    {
        // A cheap draft that is almost always right: k grows towards k_max and it never pauses.
        SpeculativeSchedule s;
        const double gain = simulate(s, 0.95, 25, 2, 0.5, 600);
        check("easy text: drafts long", s.k >= 6, s.k);
        check("easy text: never paused", !s.paused_once);
        check("easy text: real speedup > 2x", gain > 2.0, gain);
        check("easy text: acceptance estimate near 0.95", std::fabs(s.alpha() - 0.95) < 0.08, s.alpha());
    }

    {
        // A draft that is rarely right and not cheap: auto mode pauses and loses little.
        SpeculativeSchedule s;
        const double gain = simulate(s, 0.2, 25, 9, 2, 600);
        check("hard text: pauses", s.paused_once);
        check("hard text: loses under 10%", gain > 0.9, gain);
        check("hard text: speculated in few rounds", s.rounds < 20, s.rounds);
    }

    {
        // The same with "on": keeps speculating, with short drafts.
        SpeculativeSchedule s;
        s.always = true;
        simulate(s, 0.2, 25, 9, 2, 600);
        check("on: never pauses", !s.paused_once && s.rounds > 100, s.rounds);
        check("on: short drafts when they miss", s.k <= 2, s.k);
    }

    {
        // Middle ground, like the 4B + 0.6B pair on a Mac GPU (plain 26 ms, draft 8 ms per token).
        SpeculativeSchedule s;
        const double gain = simulate(s, 0.75, 26, 8, 1.5, 800);
        check("typical pair: speeds up", gain > 1.2, gain);
        check("typical pair: k between 2 and 6", s.k >= 2 && s.k <= 6, s.k);
    }

    {
        // Text that turns easy after a hard start (a list after an intro): retries after the pause.
        SpeculativeSchedule s;
        simulate(s, 0.1, 25, 9, 2, 200, 1);
        const int rounds_hard = s.rounds;
        const double gain = simulate(s, 0.95, 25, 9, 2, 1000, 2);
        check("recovers: speculates again on easy text", s.rounds > rounds_hard + 50, s.rounds - rounds_hard);
        check("recovers: speedup on the easy part", gain > 1.5, gain);
    }

    {
        // Room limits the draft (context, output limit).
        SpeculativeSchedule s;
        s.k = 6;
        check("room: capped by the room left", s.next_k(3) == 3 && s.next_k(0) == 0 && s.next_k(-2) == 0);
    }

    {
        // Rounds cut short by a stop do not count as a miss.
        SpeculativeSchedule a, b;
        a.on_plain_step(20);
        b.on_plain_step(20);
        a.on_round(4, 1, 8, 22, true);
        b.on_round(4, 1, 8, 22, false);
        check("stop: no miss counted", b.alpha() > a.alpha());
    }

    {
        // Host work per token (sampling, streaming) shrinks the gain: k comes down with it.
        SpeculativeSchedule light, heavy;
        for (auto * s : {&light, &heavy}) {
            s->t_plain = 20;
            s->t_draft = 4;
            s->t_extra = 1;
            s->acc_hits = 7;
            s->acc_misses = 3;
        }
        heavy.t_host = 15;
        check("host cost: lowers the expected gain", heavy.gain(4) < light.gain(4), heavy.gain(4));
    }

    {
        // The draft's window over the conversation (draft_window_plan).
        auto range = [](int from, int to, int offset = 0) {
            std::vector<int> v;
            for (int i = from; i < to; ++i) v.push_back(i + offset);
            return v;
        };
        const size_t n_ctx = 1024, window = 512;
        // A continuing conversation keeps its window and all but the last cached token.
        const std::vector<int> seq = range(0, 700);
        const std::vector<int> cached(seq.begin() + 188, seq.begin() + 690);
        DraftPlan p = draft_window_plan<int>(188, cached, n_ctx, window, 8, seq);
        check("window: a continuing chat keeps its base", p.base == 188);
        check("window: and reuses what the draft holds", p.keep == 502);
        // A new chat longer than the old window's start: none of the cache matches. Keeping the
        // old base would leave the draft the last 12 tokens to go on.
        const std::vector<int> other = range(0, 600, 100000);
        p = draft_window_plan<int>(588, range(588, 1100), n_ctx, window, 8, other);
        check("window: a new chat starts a fresh window", p.base == 88 && p.keep == 0);
        // A new chat exactly as long as the old base: not an empty window.
        p = draft_window_plan<int>(588, range(588, 1100), n_ctx, window, 8, range(0, 588, 100000));
        check("window: never empty", p.base == 76 && p.keep == 0);
        // A short new chat starts at its beginning.
        p = draft_window_plan<int>(588, range(588, 1100), n_ctx, window, 8, range(0, 300, 100000));
        check("window: a short chat starts at 0", p.base == 0 && p.keep == 0);
        // Full: slides to a new window.
        p = draft_window_plan<int>(0, range(0, 1000), n_ctx, window, 8, range(0, 1020));
        check("window: slides when it no longer fits", p.base == 508 && p.keep == 0);
        // The first request (nothing cached yet).
        p = draft_window_plan<int>(0, std::vector<int>(), n_ctx, window, 8, range(0, 40));
        check("window: first request from 0", p.base == 0 && p.keep == 0);
    }

    std::printf("draft pick (greedy token and confidence over a vocabulary):\n");
    {
        // Against the full computation: argmax (first of equal maxima) and softmax(top) >= p_min.
        auto full = [](const std::vector<float> & l, float p_min) {
            int best = 0;
            for (int i = 1; i < (int) l.size(); ++i) if (l[i] > l[best]) best = i;
            double sum = 0;
            for (float v : l) sum += std::exp((double) v - l[best]);
            return std::make_pair(best, 1.0 / sum >= p_min);
        };
        std::mt19937 rng(11);
        std::normal_distribution<float> noise(0.0f, 3.0f);
        int same_token = 0, same_sure = 0, close_calls = 0, cases = 0;
        for (int n : {1, 7, 8, 9, 1000, 151936}) {
            for (int t = 0; t < 60; ++t) {
                std::vector<float> l(n);
                for (auto & v : l) v = noise(rng);
                // From flat to sure: a boosted token, sometimes two tied or near-tied ones.
                const int a = (int) (rng() % n);
                l[a] += (float) (t % 12) * 3.0f;
                if (t % 5 == 0) l[(a + n / 2) % n] = l[a];
                for (float p : {0.3f, 0.5f}) {
                    const auto [tok, sure] = full(l, p);
                    const DraftPick pick = draft_pick(l.data(), n, true, p);
                    ++cases;
                    same_token += pick.token == tok;
                    // A softmax within 1e-3 of the threshold may land either side (float sums).
                    double sum = 0;
                    for (float v : l) sum += std::exp((double) v - l[tok]);
                    const bool close = std::fabs(1.0 / sum - p) < 1e-3;
                    close_calls += close;
                    same_sure += pick.sure == sure || close;
                    if (!draft_pick(l.data(), n, false, p).sure) --same_sure;  // unchecked picks are sure
                }
            }
        }
        check("draft pick: same token as a full argmax", same_token == cases, same_token);
        check("draft pick: same confidence verdict as a full softmax", same_sure == cases, same_sure);
        std::vector<float> tie = {1.0f, 5.0f, 2.0f, 5.0f};
        check("draft pick: ties go to the first token", draft_pick(tie.data(), 4, true, 0.3f).token == 1);
        check("draft pick: an even tie is not sure at 0.5", !draft_pick(tie.data(), 4, true, 0.5f).sure);
        // Non-finite logits: a NaN first logit does not hide the maximum; a vocabulary with no
        // finite logit is never a confident draft.
        const float nan = std::nanf("");
        std::vector<float> nan_first = {nan, 1.0f, 9.0f, 2.0f, 0.5f, 0.1f, 0.2f, 0.3f, 0.4f, 8.5f};
        const DraftPick np = draft_pick(nan_first.data(), (int) nan_first.size(), true, 0.3f);
        check("draft pick: a NaN first logit does not hide the top", np.token == 2 && np.sure);
        std::vector<float> nan_mid = {1.0f, nan, 30.0f, nan, 2.0f};
        check("draft pick: NaN elsewhere is skipped", draft_pick(nan_mid.data(), 5, true, 0.3f).token == 2);
        std::vector<float> all_nan(12, nan);
        const DraftPick an = draft_pick(all_nan.data(), 12, true, 0.3f);
        check("draft pick: all NaN is token 0, not sure", an.token == 0 && !an.sure);
        std::vector<float> all_ninf(5, -INFINITY);
        check("draft pick: all -inf is not sure", !draft_pick(all_ninf.data(), 5, true, 0.3f).sure);
        std::vector<float> pinf = {0.0f, INFINITY, 1.0f};
        check("draft pick: +inf is not a confident draft", !draft_pick(pinf.data(), 3, true, 0.3f).sure);
        std::vector<float> small = {0.0f, 5.0f, 0.5f};  // fewer logits than lanes
        const DraftPick sp = draft_pick(small.data(), 3, true, 0.3f);
        check("draft pick: a vocabulary smaller than the lanes", sp.token == 1 && sp.sure);
        std::vector<float> neg = {-50.0f, -40.0f, -45.0f, -41.0f, -60.0f, -70.0f, -42.0f, -43.0f, -44.0f};
        check("draft pick: all-negative logits", draft_pick(neg.data(), 9, true, 0.3f).token == 1);
    }

    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "CHECKS FAILED");
    return failures == 0 ? 0 : 1;
}
