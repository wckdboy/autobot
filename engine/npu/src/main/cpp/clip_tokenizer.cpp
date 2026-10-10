#include "clip_tokenizer.h"

#include <climits>
#include <cstdio>
#include <fstream>
#include <sstream>

#include "rapidjson/document.h"

namespace npu {
namespace {

void append_utf8(std::string & out, uint32_t cp) {
    if (cp < 0x80) {
        out += (char) cp;
    } else if (cp < 0x800) {
        out += (char) (0xC0 | (cp >> 6));
        out += (char) (0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        out += (char) (0xE0 | (cp >> 12));
        out += (char) (0x80 | ((cp >> 6) & 0x3F));
        out += (char) (0x80 | (cp & 0x3F));
    } else {
        out += (char) (0xF0 | (cp >> 18));
        out += (char) (0x80 | ((cp >> 12) & 0x3F));
        out += (char) (0x80 | ((cp >> 6) & 0x3F));
        out += (char) (0x80 | (cp & 0x3F));
    }
}

// Decodes one UTF-8 code point at s[i] (advancing i); invalid bytes decode as themselves.
uint32_t next_cp(const std::string & s, size_t & i) {
    const auto c = (unsigned char) s[i];
    int n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
    if (i + n > s.size()) n = 1;
    uint32_t cp = n == 1 ? c : n == 2 ? (c & 0x1F) : n == 3 ? (c & 0x0F) : (c & 0x07);
    for (int k = 1; k < n; k++) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3F);
    i += n;
    return cp;
}

bool is_space(uint32_t cp) { return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C || cp == 0xA0 || cp == 0x3000; }
bool is_digit(uint32_t cp) { return (cp >= '0' && cp <= '9') || (cp >= 0xFF10 && cp <= 0xFF19); }

// \p{L} approximation: ASCII letters, Latin-1/extended letters and every other script, minus the
// common punctuation and symbol blocks.
bool is_letter(uint32_t cp) {
    if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')) return true;
    if (cp < 0xC0) return cp == 0xAA || cp == 0xB5 || cp == 0xBA;
    if (cp == 0xD7 || cp == 0xF7) return false;
    if (cp >= 0x2000 && cp <= 0x2BFF) return false;  // punctuation, symbols, arrows, math, box drawing
    if (cp >= 0x3000 && cp <= 0x303F) return false;  // CJK punctuation
    if (cp >= 0xFE30 && cp <= 0xFE6F) return false;
    if (cp >= 0xFF00 && cp <= 0xFF20) return false;  // fullwidth punctuation
    if (cp >= 0x1F000 && cp <= 0x1FAFF) return false;  // emoji
    return true;
}

uint32_t lower(uint32_t cp) {
    if (cp >= 'A' && cp <= 'Z') return cp + 32;
    if (cp >= 0xC0 && cp <= 0xDE && cp != 0xD7) return cp + 32;
    if (cp >= 0x391 && cp <= 0x3A9) return cp + 32;  // Greek
    if (cp >= 0x410 && cp <= 0x42F) return cp + 32;  // Cyrillic
    return cp;
}

}  // namespace

bool ClipTokenizer::load(const std::string & path, std::string * error) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        *error = "cannot read " + path;
        return false;
    }
    std::stringstream buf;
    buf << in.rdbuf();
    const std::string json = buf.str();
    rapidjson::Document doc;
    doc.Parse(json.c_str(), json.size());
    if (doc.HasParseError() || !doc.HasMember("model")) {
        *error = "invalid tokenizer.json";
        return false;
    }
    const auto & model = doc["model"];
    if (!model.HasMember("vocab") || !model.HasMember("merges")) {
        *error = "tokenizer.json has no BPE vocab";
        return false;
    }
    for (auto it = model["vocab"].MemberBegin(); it != model["vocab"].MemberEnd(); ++it) {
        vocab_[std::string(it->name.GetString(), it->name.GetStringLength())] = it->value.GetInt();
    }
    int rank = 0;
    for (const auto & m : model["merges"].GetArray()) {
        if (m.IsString()) {
            merges_[std::string(m.GetString(), m.GetStringLength())] = rank++;
        } else if (m.IsArray() && m.Size() == 2) {
            merges_[std::string(m[0].GetString()) + " " + m[1].GetString()] = rank++;
        }
    }
    // GPT-2 byte <-> unicode table: printable bytes map to themselves, the rest to U+0100+.
    int extra = 0;
    for (int b = 0; b < 256; b++) {
        const bool printable = (b >= 33 && b <= 126) || (b >= 161 && b <= 172) || (b >= 174 && b <= 255);
        byte_to_unicode_[b].clear();
        append_utf8(byte_to_unicode_[b], printable ? (uint32_t) b : (uint32_t) (256 + extra++));
    }
    return !vocab_.empty();
}

std::vector<int> ClipTokenizer::bpe(const std::string & word) const {
    auto hit = cache_.find(word);
    if (hit != cache_.end()) return hit->second;

    std::vector<std::string> parts;
    for (unsigned char c : word) parts.push_back(byte_to_unicode_[c]);
    if (parts.empty()) return {};
    parts.back() += "</w>";

    while (parts.size() > 1) {
        int best = INT_MAX;
        size_t at = 0;
        for (size_t i = 0; i + 1 < parts.size(); i++) {
            auto m = merges_.find(parts[i] + " " + parts[i + 1]);
            if (m != merges_.end() && m->second < best) {
                best = m->second;
                at = i;
            }
        }
        if (best == INT_MAX) break;
        const std::string first = parts[at];
        const std::string second = parts[at + 1];
        std::vector<std::string> merged;
        for (size_t i = 0; i < parts.size();) {
            if (i + 1 < parts.size() && parts[i] == first && parts[i + 1] == second) {
                merged.push_back(first + second);
                i += 2;
            } else {
                merged.push_back(parts[i]);
                i += 1;
            }
        }
        parts.swap(merged);
    }
    std::vector<int> ids;
    for (const auto & p : parts) {
        auto v = vocab_.find(p);
        ids.push_back(v != vocab_.end() ? v->second : EOS);
    }
    if (cache_.size() < 20000) cache_[word] = ids;
    return ids;
}

std::vector<int> ClipTokenizer::encode(const std::string & text, int pad, int * eos_index) const {
    // Normalize: lowercase, collapse whitespace.
    std::vector<uint32_t> cps;
    for (size_t i = 0; i < text.size();) cps.push_back(lower(next_cp(text, i)));

    // Pre-tokenize like CLIP: contractions, letter runs, single digits, other symbol runs.
    std::vector<std::string> words;
    static const char * contractions[] = {"'s", "'t", "'re", "'ve", "'m", "'ll", "'d"};
    for (size_t i = 0; i < cps.size();) {
        const uint32_t cp = cps[i];
        if (is_space(cp)) { i++; continue; }
        std::string w;
        if (cp == '\'') {
            bool matched = false;
            for (const char * c : contractions) {
                size_t len = std::char_traits<char>::length(c);
                bool ok = i + len <= cps.size();
                for (size_t k = 0; ok && k < len; k++) ok = cps[i + k] == (uint32_t) (unsigned char) c[k];
                if (ok) {
                    w = c;
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (matched) { words.push_back(w); continue; }
        }
        if (is_letter(cp)) {
            while (i < cps.size() && is_letter(cps[i])) append_utf8(w, cps[i++]);
        } else if (is_digit(cp)) {
            append_utf8(w, cps[i++]);
        } else {
            while (i < cps.size() && !is_space(cps[i]) && !is_letter(cps[i]) && !is_digit(cps[i])) append_utf8(w, cps[i++]);
        }
        words.push_back(w);
    }

    std::vector<int> ids{BOS};
    for (const auto & w : words) {
        for (int id : bpe(w)) {
            if ((int) ids.size() >= MAX_LEN - 1) break;
            ids.push_back(id);
        }
    }
    if (eos_index) *eos_index = (int) ids.size();
    ids.push_back(EOS);
    while ((int) ids.size() < MAX_LEN) ids.push_back(pad);
    return ids;
}

}  // namespace npu
