// Benchmark and correctness check for the shared whisper engine core (the code both apps run).
//
//   whisper_bench <model.bin> <speech.wav> [--threads N] [--reps N] [--step-ms N] [--first-ms N]
//
// Replays streaming dictation's passes over a recording (16 kHz mono WAV, 16-bit or float): a
// partial pass over the first --first-ms of audio, then one every --step-ms as the window grows
// (up to dictation's 20 s window), then the final pass over the whole recording. Each partial
// window is transcribed as a partial pass (AUTOBOT_WHISPER_PARTIAL: encoder context sized to the
// window) and as before (full 30 s context), interleaved, median of --reps; prints both times
// and whether the texts match, the time of the first partial (what the user waits for before the
// first words) and the total engine time of the partials. Exits non-zero when the final pass
// differs from a plain transcription or a pass fails. Everything stays on this machine.

#include "autobot_whisper.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

int failures = 0;

void check(const std::string & what, bool ok, const std::string & detail = "") {
    std::printf("  check %-52s %s %s\n", what.c_str(), ok ? "ok" : "FAILED", detail.c_str());
    if (!ok) ++failures;
}

// 16 kHz mono WAV, PCM 16-bit or IEEE float 32-bit.
bool read_wav(const char * path, std::vector<float> & out) {
    FILE * f = std::fopen(path, "rb");
    if (!f) return false;
    std::vector<uint8_t> data;
    uint8_t buf[65536];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) data.insert(data.end(), buf, buf + n);
    std::fclose(f);
    if (data.size() < 12 || std::memcmp(data.data(), "RIFF", 4) != 0 || std::memcmp(data.data() + 8, "WAVE", 4) != 0) return false;
    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t rate = 0;
    size_t pos = 12;
    while (pos + 8 <= data.size()) {
        uint32_t size;
        std::memcpy(&size, data.data() + pos + 4, 4);
        const uint8_t * body = data.data() + pos + 8;
        if (std::memcmp(data.data() + pos, "fmt ", 4) == 0 && size >= 16) {
            std::memcpy(&format, body, 2);
            std::memcpy(&channels, body + 2, 2);
            std::memcpy(&rate, body + 4, 4);
            std::memcpy(&bits, body + 14, 2);
        } else if (std::memcmp(data.data() + pos, "data", 4) == 0) {
            if (channels != 1 || rate != 16000) return false;
            const size_t len = std::min<size_t>(size, data.size() - pos - 8);
            if (format == 1 && bits == 16) {
                for (size_t i = 0; i + 1 < len; i += 2) {
                    int16_t v;
                    std::memcpy(&v, body + i, 2);
                    out.push_back(v / 32768.0f);
                }
            } else if (format == 3 && bits == 32) {
                for (size_t i = 0; i + 3 < len; i += 4) {
                    float v;
                    std::memcpy(&v, body + i, 4);
                    out.push_back(v);
                }
            } else {
                return false;
            }
            return true;
        }
        pos += 8 + size + (size & 1);
    }
    return false;
}

// The "text" field of the engine's JSON (enough for the core's own output: quotes and
// backslashes escaped, newlines as \n).
std::string text_of(const std::string & json, bool & error) {
    error = json.find("\"error\"") != std::string::npos;
    const auto start = json.find("\"text\":\"");
    if (start == std::string::npos) return "";
    std::string out;
    for (size_t i = start + 8; i < json.size(); ++i) {
        if (json[i] == '\\' && i + 1 < json.size()) {
            out += json[i + 1] == 'n' ? '\n' : json[i + 1];
            ++i;
        } else if (json[i] == '"') {
            break;
        } else {
            out += json[i];
        }
    }
    return out;
}

struct Pass {
    std::string text;
    double ms = 0;
    bool error = false;
};

Pass run(const std::string & model, const float * pcm, size_t n, int flags) {
    const auto t0 = Clock::now();
    char * r = autobot_whisper_transcribe_ex(model.c_str(), pcm, n, "en", 0, flags);
    Pass p;
    p.ms = std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
    const std::string json = r ? r : "";
    autobot_whisper_string_free(r);
    p.text = text_of(json, p.error);
    return p;
}

// ---- dictation's word agreement (shared/.../voice/StreamingDictation.kt: Stitch, LocalAgreement)

std::string norm(const std::string & w) {
    std::string o;
    for (unsigned char c : w) {
        if (std::isalnum(c) || c >= 0x80) o += (char) std::tolower(c);
    }
    return o;
}

std::vector<std::string> words(const std::string & text) {
    std::vector<std::string> out;
    std::string cur;
    for (char c : text) {
        if (std::isspace((unsigned char) c)) {
            if (!cur.empty()) out.push_back(cur);
            cur.clear();
        } else {
            cur += c;
        }
    }
    if (!cur.empty()) out.push_back(cur);
    return out;
}

std::string joined(const std::vector<std::string> & w) {
    std::string o;
    for (const auto & x : w) o += (o.empty() ? "" : " ") + x;
    return o;
}

std::string stitch(const std::string & a, const std::string & b) {
    const auto aw = words(a), bw = words(b);
    if (aw.empty()) return joined(bw);
    if (bw.empty()) return joined(aw);
    size_t overlap = 0;
    for (size_t k = std::min<size_t>(6, std::min(aw.size(), bw.size())); k >= 1; --k) {
        bool same = true;
        for (size_t i = 0; i < k && same; ++i) {
            const std::string w = norm(aw[aw.size() - k + i]);
            same = !w.empty() && w == norm(bw[i]);
        }
        if (same && (k > 1 || norm(bw[0]).size() >= 5)) {
            overlap = k;
            break;
        }
    }
    std::vector<std::string> all = aw;
    all.insert(all.end(), bw.begin() + (long) overlap, bw.end());
    return joined(all);
}

struct Agreement {
    std::vector<std::string> committed, previous;

    void update(const std::string & hypothesis) {
        std::vector<std::string> w = words(hypothesis);
        const size_t c = committed.size();
        if (c > 0) {
            const std::string last = norm(committed.back());
            size_t k = c;
            for (long t : {(long) c, (long) c + 1, (long) c - 1, (long) c + 2, (long) c - 2}) {
                if (t >= 1 && t <= (long) w.size() && norm(w[t - 1]) == last) {
                    k = (size_t) t;
                    break;
                }
            }
            w.erase(w.begin(), w.begin() + (long) std::min(k, w.size()));
        }
        size_t n = 0;
        while (n < w.size() && n < previous.size() && norm(w[n]) == norm(previous[n])) ++n;
        committed.insert(committed.end(), w.begin(), w.begin() + (long) n);
        previous.assign(w.begin() + (long) n, w.end());
    }

    // The window's final text after its last pass heard `heard`.
    std::string close(const std::string & heard) {
        if (!heard.empty()) update(heard);
        return stitch(joined(committed), joined(previous));
    }
};

std::string normalized(const std::string & text) {
    std::string o;
    for (const auto & w : words(text)) {
        const std::string n = norm(w);
        if (!n.empty()) o += (o.empty() ? "" : " ") + n;
    }
    return o;
}

double median(std::vector<double> v) {
    std::sort(v.begin(), v.end());
    return v.empty() ? 0 : v[v.size() / 2];
}

}  // namespace

int main(int argc, char ** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s <model.bin> <speech.wav> [--reps N] [--step-ms N] [--first-ms N]\n", argv[0]);
        return 2;
    }
    const std::string model = argv[1];
    int reps = 3, step_ms = 700, first_ms = 1000;
    for (int i = 3; i < argc; ++i) {
        const std::string a = argv[i];
        const char * v = i + 1 < argc ? argv[i + 1] : "0";
        if (a == "--reps") reps = std::max(1, std::atoi(v)), ++i;
        else if (a == "--step-ms") step_ms = std::max(100, std::atoi(v)), ++i;
        else if (a == "--first-ms") first_ms = std::max(100, std::atoi(v)), ++i;
    }
    std::vector<float> pcm;
    if (!read_wav(argv[2], pcm)) {
        std::fprintf(stderr, "cannot read %s (16 kHz mono WAV, 16-bit or float)\n", argv[2]);
        return 2;
    }
    std::printf("audio %.2f s, %d reps\n", pcm.size() / 16000.0, reps);
    run(model, pcm.data(), std::min<size_t>(pcm.size(), 16000), 0);  // load and warm up

    std::vector<size_t> windows;
    const size_t max_window = 20 * 16000;
    for (size_t n = (size_t) first_ms * 16; n < std::min(pcm.size(), max_window); n += (size_t) step_ms * 16) windows.push_back(n);

    std::printf("\n%8s %6s %10s %10s %8s  %s\n", "window", "ctx", "full ms", "partial ms", "speedup", "same words");
    double total_full = 0, total_partial = 0, first_full = 0, first_partial = 0;
    int same = 0;
    Agreement with_full, with_partial;  // dictation's live text from each kind of partial
    for (size_t w = 0; w < windows.size(); ++w) {
        const size_t n = windows[w];
        std::vector<double> tf, tp;
        Pass full, part;
        for (int r = 0; r < reps; ++r) {
            full = run(model, pcm.data(), n, 0);
            part = run(model, pcm.data(), n, AUTOBOT_WHISPER_PARTIAL);
            tf.push_back(full.ms);
            tp.push_back(part.ms);
        }
        const double mf = median(tf), mp = median(tp);
        total_full += mf;
        total_partial += mp;
        if (w == 0) first_full = mf, first_partial = mp;
        with_full.update(full.text);
        with_partial.update(part.text);
        const bool eq = normalized(full.text) == normalized(part.text);
        same += eq;
        std::printf("%6.1f s %6d %10.1f %10.1f %7.2fx  %s\n", n / 16000.0, autobot_whisper_partial_audio_ctx(n), mf, mp, mf / std::max(1e-9, mp),
                    eq ? "yes" : "NO");
        if (!eq) std::printf("           full:    %s\n           partial: %s\n", full.text.c_str(), part.text.c_str());
        if (full.error || part.error) check("pass at " + std::to_string(n / 16) + " ms", false, "engine error");
    }
    std::printf("\nfirst partial (%.1f s of audio): %.1f ms -> %.1f ms (%.2fx)\n", windows.empty() ? 0.0 : windows[0] / 16000.0, first_full, first_partial,
                first_full / std::max(1e-9, first_partial));
    std::printf("all %zu partials: %.0f ms -> %.0f ms of engine time (%.2fx), %d/%zu with the same words\n", windows.size(), total_full, total_partial,
                total_full / std::max(1e-9, total_partial), same, windows.size());

    // The final pass keeps the full context: the same text as the plain entry point.
    const Pass fin = run(model, pcm.data(), pcm.size(), 0);
    char * r = autobot_whisper_transcribe(model.c_str(), pcm.data(), pcm.size(), "en", 0);
    bool err = false;
    const std::string plain = text_of(r ? r : "", err);
    autobot_whisper_string_free(r);
    std::printf("final (%.1f ms): %s\n", fin.ms, fin.text.c_str());
    // Dictation's transcript: the words the partials agreed on, then the final pass's rest.
    const std::string dict_full = with_full.close(fin.text), dict_partial = with_partial.close(fin.text);
    std::printf("dictation transcript, full-context partials: %s\n", dict_full.c_str());
    std::printf("dictation transcript, partial passes:        %s\n", dict_partial.c_str());
    std::printf("dictation transcripts: %s\n", normalized(dict_full) == normalized(dict_partial) ? "same words" : "DIFFER");
    check("final pass: same text as a plain transcription", !fin.error && !err && fin.text == plain);
    check("partial pass over 30 s or more: full context", autobot_whisper_partial_audio_ctx(30 * 16000) == 0);
    check("partial context covers the window", autobot_whisper_partial_audio_ctx(5 * 16000) >= 250);
    autobot_whisper_free();
    std::printf("\n%s\n", failures == 0 ? "all checks passed" : "CHECKS FAILED");
    return failures == 0 ? 0 : 1;
}
