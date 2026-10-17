// Speculative decoding schedule: how many tokens the draft model proposes per round, and whether
// speculating pays at all. Pure arithmetic (no llama.cpp), so it is unit-tested on the host
// (engine/llama/bench/spec_sched_test.cpp).
//
// Model: each drafted token is accepted with probability `alpha` until the first miss (greedy
// target: the draft's argmax equals the target's; sampled target: the target samples the draft's
// token). A round with k drafts then yields E(k) = (1 - alpha^(k+1)) / (1 - alpha) tokens (the
// accepted drafts plus the target's own next token) for k draft steps and one batched target
// step. With t_plain the cost of a normal decode step, t_draft the cost of one draft step,
// t_extra the extra cost of each additional token in the target's batch and t_host the work done
// per emitted token either way (sampling, the reasoning guard, streaming), the expected speedup is
//
//     gain(k) = E(k) * (t_plain + t_host) / (k * t_draft + t_plain + k * t_extra + E(k) * t_host)
//
// The schedule measures alpha (a geometric estimate over recent rounds) and the three costs on
// this device as it goes, picks the k with the best gain, and in "auto" mode stops speculating
// when even the best k does not beat plain decoding, trying again after a pause (the text may
// become easier to predict: code, lists, quoted context). A retry drafts at least `probe_k`
// tokens and weighs the older rounds less, so easy text is recognised in a round or two; pauses
// grow while retries keep failing.

#ifndef AUTOBOT_SPECULATIVE_SCHED_H
#define AUTOBOT_SPECULATIVE_SCHED_H

#include <algorithm>
#include <cmath>

struct SpeculativeSchedule {
    // ---- configuration
    int k_max = 8;              // most draft tokens per round
    bool always = false;        // "on": keep speculating even when it does not pay
    int warmup_plain = 2;       // plain steps first, to measure t_plain
    double decay = 0.9;         // weight of older rounds in the acceptance estimate
    double min_gain = 1.02;     // auto mode pauses below this expected speedup
    int pause_min = 16;         // plain tokens before the first retry; doubles up to pause_max
    int pause_max = 256;
    int probe_k = 4;            // drafts in a retry after a pause

    // ---- measurements
    double acc_hits = 1.2;      // decayed accepted drafts (prior: alpha ~ 0.6)
    double acc_misses = 0.8;    // decayed rejections
    double t_plain = 0;         // ms per plain step (EMA), 0 = not measured yet
    double t_draft = 0;         // ms per draft step (EMA), 0 = not measured yet
    double t_extra = 0;         // extra ms per additional verified token (EMA)
    double t_host = 0;          // ms per emitted token outside the models (EMA)
    int plain_steps = 0;

    // ---- state
    int k = 4;                  // drafts in the next round
    int pause_left = 0;         // plain tokens until the next try
    int pause_len = 0;
    bool paused_once = false;
    bool probing = false;       // the next round is a retry after a pause

    // ---- totals (reported)
    int rounds = 0;
    int drafted = 0;
    int accepted = 0;

    double alpha() const {
        const double a = acc_hits / std::max(1e-9, acc_hits + acc_misses);
        return std::min(0.995, std::max(0.0, a));
    }

    // Expected tokens from a round of k drafts at acceptance a.
    static double expected_tokens(double a, int k) {
        if (a >= 0.999) return k + 1.0;
        return (1.0 - std::pow(a, k + 1)) / (1.0 - a);
    }

    double gain(int kk) const {
        const double tp = t_plain > 0 ? t_plain : 1.0;
        const double td = t_draft > 0 ? t_draft : 0.25 * tp;  // prior: a draft ~4x smaller
        const double e = expected_tokens(alpha(), kk);
        const double cost = kk * td + tp + kk * t_extra + e * t_host;
        return e * (tp + t_host) / cost;
    }

    int best_k() const {
        int best = 1;
        double best_gain = gain(1);
        for (int kk = 2; kk <= k_max; ++kk) {
            const double g = gain(kk);
            if (g > best_gain + 1e-9) {
                best = kk;
                best_gain = g;
            }
        }
        return best;
    }

    // Whether the next token should come from a speculative round.
    bool speculate_now() const {
        return plain_steps >= warmup_plain && pause_left <= 0;
    }

    void note_host(double ms_per_token) {
        if (ms_per_token >= 0) t_host = t_host > 0 ? 0.8 * t_host + 0.2 * ms_per_token : ms_per_token;
    }

    // A plain decode step took `ms` in the model and `host_ms` around it (sampling, handling).
    void on_plain_step(double ms, double host_ms = 0) {
        if (ms > 0) t_plain = t_plain > 0 ? 0.8 * t_plain + 0.2 * ms : ms;
        note_host(host_ms);
        ++plain_steps;
        if (pause_left > 0 && --pause_left == 0) probing = true;
    }

    // A round drafted `n_drafted` tokens (in `draft_ms`), verified them in one target batch
    // (`verify_ms` in the model, n_drafted + 1 tokens) and accepted `n_accepted` of them
    // (0..n_drafted), spending `host_ms` on the `emitted` tokens it produced. Rounds cut short by
    // a stop (end of text, a stop string) are passed with `count_miss` false: the drafts that were
    // not checked say nothing about the draft.
    void on_round(int n_drafted, int n_accepted, double draft_ms, double verify_ms, bool count_miss = true,
                  double host_ms = -1, int emitted = 0) {
        if (n_drafted <= 0) return;
        if (host_ms >= 0 && emitted > 0) note_host(host_ms / emitted);
        const bool probe = probing;
        probing = false;
        if (probe) {
            // The text may have changed since the pause: this round counts more than the old ones.
            acc_hits *= 0.25;
            acc_misses *= 0.25;
        }
        ++rounds;
        drafted += n_drafted;
        accepted += n_accepted;
        acc_hits = acc_hits * decay + n_accepted;
        acc_misses = acc_misses * decay + ((count_miss && n_accepted < n_drafted) ? 1.0 : 0.0);
        // The first round's timings include one-time set-up (graphs for new batch sizes): only
        // its acceptance counts.
        if (rounds == 1) {
            k = best_k();
            return;
        }
        if (draft_ms > 0) {
            const double per = draft_ms / n_drafted;
            t_draft = t_draft > 0 ? 0.8 * t_draft + 0.2 * per : per;
        }
        if (verify_ms > 0 && t_plain > 0) {
            const double extra = std::max(0.0, (verify_ms - t_plain) / n_drafted);
            t_extra = t_extra > 0 ? 0.8 * t_extra + 0.2 * extra : extra;
        }
        k = best_k();
        if (!always && gain(k) < min_gain) {
            // A retry that mostly hit restarts the pauses short; failing retries back off.
            const bool hopeful = probe && 2 * n_accepted >= n_drafted;
            pause_len = pause_len == 0 || hopeful ? pause_min : std::min(pause_max, pause_len * 2);
            pause_left = pause_len;
            paused_once = true;
        } else if (gain(k) >= min_gain) {
            pause_len = 0;  // it pays again: the next pause starts short
        }
    }

    // The k for a round that may draft at most `room` tokens (context, output limit).
    int next_k(int room) const {
        const int want = probing ? std::max(k, std::min(probe_k, k_max)) : k;
        return std::max(0, std::min(want, room));
    }
};

#endif  // AUTOBOT_SPECULATIVE_SCHED_H
