// CLIP byte-level BPE tokenizer, loaded from a Hugging Face tokenizer.json.

#pragma once

#include <string>
#include <unordered_map>
#include <vector>

namespace npu {

class ClipTokenizer {
public:
    static constexpr int BOS = 49406;
    static constexpr int EOS = 49407;
    static constexpr int MAX_LEN = 77;

    bool load(const std::string & path, std::string * error);

    /**
     * BOS + tokens (truncated to MAX_LEN - 2) + EOS, padded with [pad] to MAX_LEN. [eos_index]
     * receives the position of the EOS token (SDXL pools the text embedding there).
     */
    std::vector<int> encode(const std::string & text, int pad, int * eos_index = nullptr) const;

private:
    std::unordered_map<std::string, int> vocab_;
    std::unordered_map<std::string, int> merges_;  // "a b" -> rank
    mutable std::unordered_map<std::string, std::vector<int>> cache_;
    std::string byte_to_unicode_[256];

    std::vector<int> bpe(const std::string & word) const;
};

}  // namespace npu
