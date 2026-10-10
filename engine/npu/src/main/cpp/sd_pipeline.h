// Stable Diffusion 1.5 / SDXL pipeline over precompiled NPU (QNN) or GPU (MNN) models.
//
// Text encoders run with MNN on the CPU (token + position embeddings are summed here and fed as
// `input_embedding`); the UNet and VAE run on the Hexagon NPU from QNN context binaries, or on
// the GPU/CPU from MNN models. Tensor roles are found by shape, because input names differ
// between packages.

#pragma once

#include <atomic>
#include <functional>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "clip_tokenizer.h"
#include "mnn_model.h"
#include "qnn_runtime.h"

namespace npu {

struct Events {
    std::function<void(const std::string &)> status;
    std::function<void(int, int)> progress;
    std::function<void(int, const std::string &, int, int, int64_t)> image;
};

/** fp16 or fp32 token table plus fp32 position table, as shipped next to the text encoders. */
struct EmbeddingTable {
    int dim = 0;
    bool half = false;
    std::vector<uint16_t> tokens16;
    std::vector<float> tokens32;
    std::vector<float> positions;  // [77, dim]

    bool load(const std::string & token_path, const std::string & pos_path, std::string * error);
    void embed(const std::vector<int> & ids, std::vector<float> * out) const;
};

/** A denoiser (UNet) on either runtime. */
class Unet {
public:
    virtual ~Unet() = default;
    /** Latent shape [C, H, W] the model was compiled for (H/W may be 0 = any, MNN only). */
    virtual std::vector<int> latent_shape() const = 0;
    virtual bool run(const std::vector<float> & x, float t, const std::vector<float> & hidden, const std::vector<float> & pooled,
                     const std::vector<float> & time_ids, int latent_h, int latent_w, std::vector<float> * out, std::string * error) = 0;
    virtual const char * backend() const = 0;
};

class Vae {
public:
    virtual ~Vae() = default;
    virtual bool decode(const std::vector<float> & z, int latent_h, int latent_w, std::vector<float> * rgb, std::string * error) = 0;
    virtual bool encode(const std::vector<float> & rgb, int h, int w, std::vector<float> * mean, std::vector<float> * std, std::string * error) = 0;
};

class Engine {
public:
    Engine(QnnRuntime * qnn, std::string cache_dir) : qnn_(qnn), cache_dir_(std::move(cache_dir)) {}

    /** Runs a JSON job ({op: "generate"|"upscale", ...}); returns the result JSON. */
    std::string run(const std::string & request, const Events & events);

    void cancel() { cancel_.store(true); }
    void unload();

private:
    QnnRuntime * qnn_;
    std::string cache_dir_;
    std::atomic<bool> cancel_{false};

    std::map<std::string, std::unique_ptr<QnnModel>> qnn_models_;
    std::map<std::string, std::unique_ptr<MnnModel>> mnn_models_;
    std::map<std::string, std::unique_ptr<ClipTokenizer>> tokenizers_;
    std::map<std::string, std::unique_ptr<EmbeddingTable>> tables_;

    QnnModel * qnn_model(const std::string & path, std::string * error);
    MnnModel * mnn_model(const std::string & path, bool gpu, std::string * error);
    ClipTokenizer * tokenizer(const std::string & path, std::string * error);
    EmbeddingTable * table(const std::string & tokens, const std::string & positions, std::string * error);

    std::string generate(const std::string & request, const Events & events);
    std::string upscale(const std::string & request, const Events & events);
};

}  // namespace npu
