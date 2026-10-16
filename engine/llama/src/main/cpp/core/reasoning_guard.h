// Loop detection for a model's reasoning (the span between its think tags). Small models in
// particular can reason in circles until the output limit: the same sentence, or the same few
// tokens, over and over. The guard sees every reasoning token and says when the trace has stopped
// making progress, so the engine can end the reasoning and let the model answer.
//
// Two signals, both on token ids (no detokenizing):
//  - an exact cycle: the last tokens repeat with some period p (1..MAX_PERIOD) for at least
//    REPEATS copies and at least MIN_RUN tokens ("banana banana banana…", a paragraph pasted 3×);
//  - a stale window: in the last WINDOW tokens, most NGRAM-grams already occurred earlier in that
//    window (paraphrased circles with small variations between copies).
//
// Header-only and free of llama.cpp types so the host bench can test it on synthetic input.

#ifndef AUTOBOT_REASONING_GUARD_H
#define AUTOBOT_REASONING_GUARD_H

#include <cstddef>
#include <cstdint>
#include <deque>
#include <unordered_map>
#include <vector>

class ReasoningLoopGuard {
public:
    static constexpr size_t MAX_PERIOD = 256;  // longest repeated span recognised as a cycle (tokens)
    static constexpr size_t REPEATS = 3;       // copies of the span in a row
    static constexpr size_t MIN_RUN = 48;      // repeated tokens before a cycle counts (ignores "...." runs)
    static constexpr size_t WINDOW = 512;      // stale-window size (tokens)
    static constexpr size_t NGRAM = 12;        // n-gram length for the stale window
    static constexpr double STALE = 0.6;       // fraction of repeated n-grams that marks the window stale

    /** Adds the next reasoning token; returns true once the reasoning is looping (and stays true). */
    bool push(int32_t token) {
        if (looping_) return true;
        tokens_.push_back(token);
        const size_t n = tokens_.size();
        // Exact cycles: run_[p] counts how many tokens in a row equal the token p positions earlier.
        if (run_.size() < MAX_PERIOD + 1) run_.resize(MAX_PERIOD + 1, 0);
        for (size_t p = 1; p <= MAX_PERIOD && p < n; ++p) {
            if (tokens_[n - 1] == tokens_[n - 1 - p]) {
                const size_t r = ++run_[p];
                if (r >= p * (REPEATS - 1) && r >= MIN_RUN) {
                    looping_ = true;
                    reason_ = "cycle";
                    return true;
                }
            } else {
                run_[p] = 0;
            }
        }
        // Stale window over n-gram hashes.
        if (n >= NGRAM) {
            uint64_t h = 1469598103934665603ULL;
            for (size_t i = n - NGRAM; i < n; ++i) h = (h ^ (uint32_t) tokens_[i]) * 1099511628211ULL;
            const size_t seen = counts_[h]++;
            window_.push_back(h);
            if (seen > 0) ++repeated_;
            if (window_.size() > WINDOW) {
                const uint64_t old = window_.front();
                window_.pop_front();
                auto it = counts_.find(old);
                // The n-gram that leaves was a repeat iff a copy of it remains after it.
                if (it != counts_.end()) {
                    if (--it->second == 0) counts_.erase(it);
                    else if (repeated_ > 0) --repeated_;
                }
            }
            if (window_.size() >= WINDOW && (double) repeated_ >= STALE * (double) window_.size()) {
                looping_ = true;
                reason_ = "stale";
                return true;
            }
        }
        // Keep the token history bounded (cycles need MAX_PERIOD * REPEATS, n-grams NGRAM).
        if (tokens_.size() > 4 * MAX_PERIOD) tokens_.erase(tokens_.begin(), tokens_.begin() + (long) MAX_PERIOD);
        return false;
    }

    bool looping() const { return looping_; }
    const char * reason() const { return reason_; }

    void reset() {
        tokens_.clear();
        run_.assign(run_.size(), 0);
        window_.clear();
        counts_.clear();
        repeated_ = 0;
        looping_ = false;
        reason_ = "";
    }

private:
    std::vector<int32_t> tokens_;
    std::vector<size_t> run_;
    std::deque<uint64_t> window_;
    std::unordered_map<uint64_t, size_t> counts_;
    size_t repeated_ = 0;
    bool looping_ = false;
    const char * reason_ = "";
};

#endif  // AUTOBOT_REASONING_GUARD_H
