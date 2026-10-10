#include "sd_pipeline.h"

#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#include "rapidjson/document.h"
#include "scheduler.h"

#define TAG "autobot-sd-npu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace npu {
namespace {

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t) { return std::chrono::duration<double, std::milli>(Clock::now() - t).count(); }

std::string quote(const std::string & s) {
    std::string o = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        o += (unsigned char) c < 0x20 ? ' ' : c;
    }
    return o + "\"";
}

std::string error_json(const std::string & code, const std::string & message) {
    return "{\"error\":{\"code\":" + quote(code) + ",\"message\":" + quote(message) + "}}";
}

bool read_file(const std::string & path, std::vector<uint8_t> * out) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    const auto size = in.tellg();
    in.seekg(0);
    out->resize((size_t) size);
    in.read(reinterpret_cast<char *>(out->data()), size);
    return (bool) in;
}

bool write_file(const std::string & path, const uint8_t * data, size_t size) {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char *>(data), (std::streamsize) size);
    return (bool) out;
}

std::string str(const rapidjson::Value & v, const char * key, const std::string & fallback = {}) {
    return v.HasMember(key) && v[key].IsString() ? std::string(v[key].GetString(), v[key].GetStringLength()) : fallback;
}

double num(const rapidjson::Value & v, const char * key, double fallback) {
    return v.HasMember(key) && v[key].IsNumber() ? v[key].GetDouble() : fallback;
}

bool flag(const rapidjson::Value & v, const char * key, bool fallback = false) {
    return v.HasMember(key) && v[key].IsBool() ? v[key].GetBool() : fallback;
}

/** RGB bytes (HWC) → planar floats in [-1, 1] (CHW). */
std::vector<float> to_planar(const std::vector<uint8_t> & rgb, int h, int w) {
    std::vector<float> out((size_t) 3 * h * w);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            for (int c = 0; c < 3; c++) out[(size_t) c * h * w + (size_t) y * w + x] = rgb[((size_t) y * w + x) * 3 + c] / 127.5f - 1.0f;
    return out;
}

/** Planar floats in [-1, 1] → RGB bytes. */
std::vector<uint8_t> to_rgb(const std::vector<float> & planar, int h, int w) {
    std::vector<uint8_t> out((size_t) 3 * h * w);
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
            for (int c = 0; c < 3; c++) {
                const float v = (planar[(size_t) c * h * w + (size_t) y * w + x] + 1.0f) * 127.5f;
                out[((size_t) y * w + x) * 3 + c] = (uint8_t) std::clamp((int) std::lround(v), 0, 255);
            }
    return out;
}

// ---------------------------------------------------------------------------------------------
// QNN (NPU) models

class QnnUnet : public Unet {
public:
    explicit QnnUnet(QnnModel * m) : m_(m) {
        const auto & g = m_->graphs()[0];
        for (size_t i = 0; i < g.inputs.size(); i++) {
            const auto & s = g.inputs[i];
            const size_t rank = s.dims.size();
            if (rank == 4) sample_ = (int) i;
            else if (s.elements() == 1) t_ = (int) i;
            else if (rank == 3) hidden_ = (int) i;
            else if (rank == 2 && s.dims.back() == 6) time_ids_ = (int) i;
            else if (rank == 2) pooled_ = (int) i;
            in_.emplace_back(s.bytes());
        }
        for (const auto & s : g.outputs) out_.emplace_back(s.bytes());
    }

    bool valid() const { return sample_ >= 0 && t_ >= 0 && hidden_ >= 0; }

    std::vector<int> latent_shape() const override {
        const auto & d = m_->graphs()[0].inputs[sample_].dims;
        return {(int) d[1], (int) d[2], (int) d[3]};
    }

    const char * backend() const override { return "npu"; }

    bool run(const std::vector<float> & x, float t, const std::vector<float> & hidden, const std::vector<float> & pooled,
             const std::vector<float> & time_ids, int, int, std::vector<float> * out, std::string * error) override {
        const auto & g = m_->graphs()[0];
        to_native(g.inputs[sample_], x.data(), in_[sample_].data());
        const float tv = t;
        to_native(g.inputs[t_], &tv, in_[t_].data());
        if (hidden.size() != g.inputs[hidden_].elements()) {
            *error = "text embedding size mismatch";
            return false;
        }
        to_native(g.inputs[hidden_], hidden.data(), in_[hidden_].data());
        if (pooled_ >= 0) to_native(g.inputs[pooled_], pooled.data(), in_[pooled_].data());
        if (time_ids_ >= 0) to_native(g.inputs[time_ids_], time_ids.data(), in_[time_ids_].data());
        std::vector<void *> ins, outs;
        for (auto & b : in_) ins.push_back(b.data());
        for (auto & b : out_) outs.push_back(b.data());
        if (!m_->execute(0, ins, outs, error)) return false;
        out->resize(g.outputs[0].elements());
        from_native(g.outputs[0], out_[0].data(), out->data());
        return true;
    }

private:
    QnnModel * m_;
    int sample_ = -1, t_ = -1, hidden_ = -1, pooled_ = -1, time_ids_ = -1;
    std::vector<std::vector<uint8_t>> in_, out_;
};

class QnnVae : public Vae {
public:
    QnnVae(QnnModel * dec, QnnModel * enc) : dec_(dec), enc_(enc) {}

    bool decode(const std::vector<float> & z, int, int, std::vector<float> * rgb, std::string * error) override {
        const auto & g = dec_->graphs()[0];
        std::vector<uint8_t> in(g.inputs[0].bytes()), out(g.outputs[0].bytes());
        to_native(g.inputs[0], z.data(), in.data());
        if (!dec_->execute(0, {in.data()}, {out.data()}, error)) return false;
        rgb->resize(g.outputs[0].elements());
        from_native(g.outputs[0], out.data(), rgb->data());
        return true;
    }

    bool encode(const std::vector<float> & rgb, int, int, std::vector<float> * mean, std::vector<float> * stdv, std::string * error) override {
        if (!enc_) {
            *error = "this package has no VAE encoder (img2img unavailable)";
            return false;
        }
        const auto & g = enc_->graphs()[0];
        std::vector<uint8_t> in(g.inputs[0].bytes());
        to_native(g.inputs[0], rgb.data(), in.data());
        std::vector<std::vector<uint8_t>> outs;
        std::vector<void *> ptrs;
        for (const auto & s : g.outputs) outs.emplace_back(s.bytes());
        for (auto & b : outs) ptrs.push_back(b.data());
        if (!enc_->execute(0, {in.data()}, ptrs, error)) return false;
        int mi = 0, si = g.outputs.size() > 1 ? 1 : -1;
        for (size_t i = 0; i < g.outputs.size(); i++) {
            if (g.outputs[i].name.find("mean") != std::string::npos) mi = (int) i;
            if (g.outputs[i].name.find("std") != std::string::npos) si = (int) i;
        }
        mean->resize(g.outputs[mi].elements());
        from_native(g.outputs[mi], outs[mi].data(), mean->data());
        stdv->assign(mean->size(), 0.0f);
        if (si >= 0) from_native(g.outputs[si], outs[si].data(), stdv->data());
        return true;
    }

private:
    QnnModel * dec_;
    QnnModel * enc_;
};

// ---------------------------------------------------------------------------------------------
// MNN (GPU / CPU) models: dynamic resolution

class MnnUnet : public Unet {
public:
    explicit MnnUnet(MnnModel * m) : m_(m) {}
    std::vector<int> latent_shape() const override { return {4, 0, 0}; }
    const char * backend() const override { return m_->on_gpu() ? "gpu" : "cpu"; }

    bool run(const std::vector<float> & x, float t, const std::vector<float> & hidden, const std::vector<float> &,
             const std::vector<float> &, int lh, int lw, std::vector<float> * out, std::string * error) override {
        const int tokens = (int) (hidden.size() / 768);
        const int ts = (int) std::lround(t);
        return m_->set_input("sample", {1, 4, lh, lw}, x.data(), error) &&
               m_->set_input_int("timestep", {1}, &ts, error) &&
               m_->set_input("encoder_hidden_states", {1, tokens, 768}, hidden.data(), error) &&
               m_->run(error) && m_->output("out_sample", out, nullptr, error);
    }

private:
    MnnModel * m_;
};

class MnnVae : public Vae {
public:
    MnnVae(MnnModel * dec, MnnModel * enc) : dec_(dec), enc_(enc) {}

    bool decode(const std::vector<float> & z, int lh, int lw, std::vector<float> * rgb, std::string * error) override {
        return dec_->set_input("latent_sample", {1, 4, lh, lw}, z.data(), error) && dec_->run(error) &&
               dec_->output("sample", rgb, nullptr, error);
    }

    bool encode(const std::vector<float> & rgb, int h, int w, std::vector<float> * mean, std::vector<float> * stdv, std::string * error) override {
        if (!enc_) {
            *error = "this package has no VAE encoder (img2img unavailable)";
            return false;
        }
        return enc_->set_input("input", {1, 3, h, w}, rgb.data(), error) && enc_->run(error) &&
               enc_->output("mean", mean, nullptr, error) && enc_->output("std", stdv, nullptr, error);
    }

private:
    MnnModel * dec_;
    MnnModel * enc_;
};

}  // namespace

// ---------------------------------------------------------------------------------------------
// Embedding tables

bool EmbeddingTable::load(const std::string & token_path, const std::string & pos_path, std::string * error) {
    std::vector<uint8_t> pos;
    std::vector<uint8_t> tok;
    if (!read_file(pos_path, &pos) || !read_file(token_path, &tok)) {
        *error = "cannot read embedding tables";
        return false;
    }
    if (pos.size() % (77 * 4) != 0) {
        *error = "unexpected position table size";
        return false;
    }
    dim = (int) (pos.size() / (77 * 4));
    positions.resize(77 * (size_t) dim);
    std::memcpy(positions.data(), pos.data(), pos.size());
    const size_t rows16 = tok.size() / ((size_t) dim * 2);
    const size_t rows32 = tok.size() / ((size_t) dim * 4);
    if (rows16 == 49408 && tok.size() == rows16 * dim * 2) {
        half = true;
        tokens16.resize(tok.size() / 2);
        std::memcpy(tokens16.data(), tok.data(), tok.size());
    } else if (rows32 == 49408) {
        half = false;
        tokens32.resize(tok.size() / 4);
        std::memcpy(tokens32.data(), tok.data(), tok.size());
    } else {
        *error = "unexpected token table size";
        return false;
    }
    return true;
}

void EmbeddingTable::embed(const std::vector<int> & ids, std::vector<float> * out) const {
    out->assign(ids.size() * (size_t) dim, 0.0f);
    for (size_t i = 0; i < ids.size(); i++) {
        const size_t row = (size_t) std::clamp(ids[i], 0, 49407) * dim;
        float * dst = out->data() + i * dim;
        const float * p = positions.data() + std::min<size_t>(i, 76) * dim;
        for (int k = 0; k < dim; k++) dst[k] = (half ? f16_to_f32(tokens16[row + k]) : tokens32[row + k]) + p[k];
    }
}

// ---------------------------------------------------------------------------------------------
// Engine

QnnModel * Engine::qnn_model(const std::string & path, std::string * error) {
    auto it = qnn_models_.find(path);
    if (it != qnn_models_.end()) return it->second.get();
    if (!qnn_ || !qnn_->ready()) {
        *error = "the NPU is not available on this phone";
        return nullptr;
    }
    auto m = qnn_->load(path, error);
    if (!m) return nullptr;
    auto * raw = m.get();
    qnn_models_[path] = std::move(m);
    return raw;
}

MnnModel * Engine::mnn_model(const std::string & path, bool gpu, std::string * error) {
    const std::string key = path + (gpu ? "#gpu" : "#cpu");
    auto it = mnn_models_.find(key);
    if (it != mnn_models_.end()) return it->second.get();
    auto m = std::make_unique<MnnModel>();
    const int threads = std::max(2, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2);
    if (!m->load(path, gpu, threads, cache_dir_, error)) return nullptr;
    auto * raw = m.get();
    mnn_models_[key] = std::move(m);
    return raw;
}

ClipTokenizer * Engine::tokenizer(const std::string & path, std::string * error) {
    auto it = tokenizers_.find(path);
    if (it != tokenizers_.end()) return it->second.get();
    auto t = std::make_unique<ClipTokenizer>();
    if (!t->load(path, error)) return nullptr;
    auto * raw = t.get();
    tokenizers_[path] = std::move(t);
    return raw;
}

EmbeddingTable * Engine::table(const std::string & tokens, const std::string & positions, std::string * error) {
    const std::string key = tokens + "|" + positions;
    auto it = tables_.find(key);
    if (it != tables_.end()) return it->second.get();
    auto t = std::make_unique<EmbeddingTable>();
    if (!t->load(tokens, positions, error)) return nullptr;
    auto * raw = t.get();
    tables_[key] = std::move(t);
    return raw;
}

void Engine::unload() {
    qnn_models_.clear();
    mnn_models_.clear();
    tables_.clear();
    tokenizers_.clear();
}

std::string Engine::run(const std::string & request, const Events & events) {
    cancel_.store(false);
    rapidjson::Document doc;
    doc.Parse(request.c_str(), request.size());
    if (doc.HasParseError() || !doc.IsObject()) return error_json("BAD_REQUEST", "invalid request");
    const std::string op = str(doc, "op", "generate");
    if (op == "upscale") return upscale(request, events);
    if (op == "unload") {
        unload();
        return "{}";
    }
    return generate(request, events);
}

std::string Engine::generate(const std::string & request, const Events & events) {
    rapidjson::Document doc;
    doc.Parse(request.c_str(), request.size());
    const auto & files = doc["files"];
    const bool sdxl = str(doc, "arch", "sd15") == "sdxl";
    const bool vpred = flag(doc, "vpred");
    const bool use_mnn = str(doc, "runtime", "qnn") == "mnn";
    const bool gpu = flag(doc, "gpu", true);
    const std::string prompt = str(doc, "prompt");
    const std::string negative = str(doc, "negative");
    const int steps = std::clamp((int) num(doc, "steps", 20), 1, 150);
    const float cfg = (float) num(doc, "cfg", 7.0);
    const int batch = std::clamp((int) num(doc, "batch", 1), 1, 8);
    const int64_t seed = (int64_t) num(doc, "seed", 0);
    const Sampler sampler = sampler_from(str(doc, "sampler", "euler a"));
    const bool karras = flag(doc, "karras", sampler == Sampler::DPMPP_2M);
    const std::string out_dir = str(doc, "out_dir");
    const float scale_factor = sdxl ? 0.13025f : 0.18215f;
    std::string error;

    auto t_total = Clock::now();
    events.status("loading");
    auto t_load = Clock::now();

    // ---- models
    ClipTokenizer * tok = tokenizer(str(files, "tokenizer"), &error);
    EmbeddingTable * tab = tok ? table(str(files, "token_emb"), str(files, "pos_emb"), &error) : nullptr;
    MnnModel * text = tab ? mnn_model(str(files, "text_encoder"), false, &error) : nullptr;
    if (!text) return error_json("LOAD_FAILED", error);
    EmbeddingTable * tab2 = nullptr;
    MnnModel * text2 = nullptr;
    if (sdxl) {
        tab2 = table(str(files, "token_emb_2"), str(files, "pos_emb_2"), &error);
        text2 = tab2 ? mnn_model(str(files, "text_encoder_2"), false, &error) : nullptr;
        if (!text2) return error_json("LOAD_FAILED", error);
    }
    std::unique_ptr<Unet> unet;
    std::unique_ptr<Vae> vae;
    if (use_mnn) {
        MnnModel * u = mnn_model(str(files, "unet"), gpu, &error);
        MnnModel * d = u ? mnn_model(str(files, "vae_decoder"), gpu, &error) : nullptr;
        if (!d) return error_json("LOAD_FAILED", error);
        MnnModel * e = files.HasMember("vae_encoder") ? mnn_model(str(files, "vae_encoder"), gpu, &error) : nullptr;
        unet = std::make_unique<MnnUnet>(u);
        vae = std::make_unique<MnnVae>(d, e);
    } else {
        QnnModel * u = qnn_model(str(files, "unet"), &error);
        QnnModel * d = u ? qnn_model(str(files, "vae_decoder"), &error) : nullptr;
        if (!d) return error_json("LOAD_FAILED", error);
        QnnModel * e = files.HasMember("vae_encoder") ? qnn_model(str(files, "vae_encoder"), &error) : nullptr;
        auto qu = std::make_unique<QnnUnet>(u);
        if (!qu->valid()) return error_json("UNSUPPORTED", "unrecognised UNet inputs: " + u->describe());
        unet = std::move(qu);
        vae = std::make_unique<QnnVae>(d, e);
    }
    const double load_ms = ms_since(t_load);

    // ---- size
    auto shape = unet->latent_shape();
    int lh = shape[1], lw = shape[2];
    if (lh == 0 || lw == 0) {
        lh = std::max(32, (int) num(doc, "height", 512) / 8);
        lw = std::max(32, (int) num(doc, "width", 512) / 8);
    }
    const int channels = shape[0];
    const int h = lh * 8, w = lw * 8;
    const size_t latent_n = (size_t) channels * lh * lw;

    // ---- text
    if (qnn_) qnn_->set_performance(true);
    events.status("encoding");
    auto t_text = Clock::now();
    const bool with_uncond = cfg > 1.0f;
    std::vector<float> cond, uncond, pooled_c, pooled_u, ids_tensor;
    auto encode = [&](const std::string & text_in, bool is_uncond, std::vector<float> * hidden, std::vector<float> * pooled) -> bool {
        std::vector<float> emb, out;
        std::vector<int> shp;
        if (sdxl && is_uncond && text_in.empty()) {
            // SDXL convention: an empty negative prompt is all zeros (not the encoding of "").
            hidden->assign((size_t) 77 * (tab->dim + tab2->dim), 0.0f);
            pooled->assign((size_t) tab2->dim, 0.0f);
            return true;
        }
        int eos = 0;
        auto ids = tok->encode(text_in, ClipTokenizer::EOS, &eos);
        // Most packages feed summed embeddings; some text encoders take the token ids themselves.
        const auto names = text->input_names();
        const bool takes_ids = std::find(names.begin(), names.end(), "input_ids") != names.end();
        bool fed;
        if (takes_ids) {
            fed = text->set_input_int("input_ids", {1, 77}, ids.data(), &error);
        } else {
            tab->embed(ids, &emb);
            fed = text->set_input("input_embedding", {1, 77, tab->dim}, emb.data(), &error);
        }
        if (!fed || !text->run(&error)) return false;
        if (!text->output("last_hidden_state", &out, &shp, &error)) {
            error.clear();
            if (!text->output("", &out, &shp, &error)) return false;
        }
        if (!sdxl) {
            *hidden = out;
            return true;
        }
        // SDXL: [CLIP-L penultimate | bigG penultimate] per token; pooled = bigG projection at EOS.
        std::vector<float> emb2, out2, pooled_all;
        auto ids2 = tok->encode(text_in, 0, &eos);
        tab2->embed(ids2, &emb2);
        if (!text2->set_input("input_embedding", {1, 77, tab2->dim}, emb2.data(), &error) || !text2->run(&error) ||
            !text2->output("last_hidden_state", &out2, nullptr, &error) || !text2->output("pooled_output", &pooled_all, nullptr, &error)) return false;
        const int d1 = tab->dim, d2 = tab2->dim;
        hidden->resize((size_t) 77 * (d1 + d2));
        for (int i = 0; i < 77; i++) {
            std::memcpy(hidden->data() + (size_t) i * (d1 + d2), out.data() + (size_t) i * d1, d1 * sizeof(float));
            std::memcpy(hidden->data() + (size_t) i * (d1 + d2) + d1, out2.data() + (size_t) i * d2, d2 * sizeof(float));
        }
        if (pooled_all.size() == (size_t) d2) *pooled = pooled_all;
        else pooled->assign(pooled_all.begin() + (size_t) eos * d2, pooled_all.begin() + (size_t) (eos + 1) * d2);
        return true;
    };
    if (!encode(prompt, false, &cond, &pooled_c)) return error_json("ENCODE_FAILED", error);
    if (with_uncond && !encode(negative, true, &uncond, &pooled_u)) return error_json("ENCODE_FAILED", error);
    const std::vector<float> time_ids = {(float) h, (float) w, 0.0f, 0.0f, (float) h, (float) w};
    const double text_ms = ms_since(t_text);

    // ---- img2img / inpaint inputs
    std::vector<float> init_latent_mean, init_latent_std, mask_latent;
    std::vector<uint8_t> init_rgb, mask_px;
    const std::string init_path = str(doc, "init_image");
    const float strength = std::clamp((float) num(doc, "strength", 0.6), 0.0f, 1.0f);
    if (!init_path.empty()) {
        if (!read_file(init_path, &init_rgb) || init_rgb.size() != (size_t) 3 * h * w) {
            return error_json("BAD_IMAGE", "init image must be " + std::to_string(w) + "x" + std::to_string(h) + " RGB");
        }
        if (!vae->encode(to_planar(init_rgb, h, w), h, w, &init_latent_mean, &init_latent_std, &error)) return error_json("ENCODE_FAILED", error);
        const std::string mask_path = str(doc, "mask");
        if (!mask_path.empty()) {
            if (!read_file(mask_path, &mask_px) || mask_px.size() != (size_t) h * w) return error_json("BAD_IMAGE", "mask size mismatch");
            mask_latent.assign((size_t) lh * lw, 0.0f);
            for (int y = 0; y < lh; y++)
                for (int x = 0; x < lw; x++) {
                    int sum = 0;
                    for (int dy = 0; dy < 8; dy++)
                        for (int dx = 0; dx < 8; dx++) sum += mask_px[(size_t) (y * 8 + dy) * w + x * 8 + dx];
                    mask_latent[(size_t) y * lw + x] = sum > 0 ? 1.0f : 0.0f;  // any painted pixel repaints the cell
                }
        }
    }

    // ---- denoise
    NoiseSchedule schedule;
    const std::vector<float> sigmas = schedule.sigmas(steps, karras);
    int start = 0;
    if (!init_latent_mean.empty()) start = std::clamp((int) std::lround(steps * (1.0f - strength)), 0, steps - 1);
    const int total_steps = (steps - start) * batch;
    int done = 0;
    double unet_ms = 0, decode_ms = 0;
    int unet_calls = 0;
    std::string images_json;

    for (int b = 0; b < batch; b++) {
        const int64_t item_seed = seed + b;
        Noise noise((uint64_t) item_seed);
        std::vector<float> x(latent_n), eps(latent_n), base_noise(latent_n);
        noise.fill(base_noise);
        std::vector<float> init_latent;
        if (!init_latent_mean.empty()) {
            init_latent.resize(latent_n);
            std::vector<float> e(latent_n);
            noise.fill(e);
            for (size_t k = 0; k < latent_n; k++) init_latent[k] = (init_latent_mean[k] + init_latent_std[k] * e[k]) * scale_factor;
            for (size_t k = 0; k < latent_n; k++) x[k] = init_latent[k] + base_noise[k] * sigmas[start];
        } else {
            for (size_t k = 0; k < latent_n; k++) x[k] = base_noise[k] * sigmas[0];
        }
        Stepper stepper(sampler, &noise);
        std::vector<float> xin(latent_n), out_c, out_u, denoised(latent_n);
        events.status("denoising");
        for (int i = start; i < steps; i++) {
            if (cancel_.load()) {
                if (qnn_) qnn_->set_performance(false);
                return error_json("CANCELLED", "cancelled");
            }
            const float s = sigmas[i];
            const float c_in = 1.0f / std::sqrt(s * s + 1.0f);
            for (size_t k = 0; k < latent_n; k++) xin[k] = x[k] * c_in;
            const float t = std::round(schedule.timestep(s));
            auto t_unet = Clock::now();
            if (!unet->run(xin, t, cond, pooled_c, time_ids, lh, lw, &out_c, &error)) return error_json("UNET_FAILED", error);
            if (with_uncond && !unet->run(xin, t, uncond, pooled_u, time_ids, lh, lw, &out_u, &error)) return error_json("UNET_FAILED", error);
            unet_ms += ms_since(t_unet);
            unet_calls += 1;
            for (size_t k = 0; k < latent_n; k++) {
                const float m = with_uncond ? out_u[k] + cfg * (out_c[k] - out_u[k]) : out_c[k];
                if (vpred) {
                    const float c_skip = 1.0f / (s * s + 1.0f);
                    const float c_out = -s / std::sqrt(s * s + 1.0f);
                    denoised[k] = x[k] * c_skip + m * c_out;
                } else {
                    denoised[k] = x[k] - s * m;
                }
            }
            stepper.step(x, denoised, sigmas, i);
            if (!mask_latent.empty()) {
                // Keep the unmasked area on the original image's noise trajectory.
                const float next = sigmas[i + 1];
                for (int c = 0; c < channels; c++)
                    for (size_t p = 0; p < (size_t) lh * lw; p++) {
                        const size_t k = (size_t) c * lh * lw + p;
                        const float keep = 1.0f - mask_latent[p];
                        x[k] = x[k] * (1.0f - keep) + (init_latent[k] + base_noise[k] * next) * keep;
                    }
            }
            events.progress(++done, total_steps);
        }

        // ---- decode
        events.status("decoding");
        auto t_dec = Clock::now();
        std::vector<float> z(latent_n), rgb;
        for (size_t k = 0; k < latent_n; k++) z[k] = x[k] / scale_factor;
        if (!vae->decode(z, lh, lw, &rgb, &error)) return error_json("DECODE_FAILED", error);
        decode_ms += ms_since(t_dec);
        std::vector<uint8_t> pixels = to_rgb(rgb, h, w);
        if (!mask_px.empty()) {
            // Outside the mask, keep the original pixels exactly.
            for (size_t p = 0; p < (size_t) h * w; p++)
                if (mask_px[p] == 0) std::memcpy(&pixels[p * 3], &init_rgb[p * 3], 3);
        }
        const std::string path = out_dir + "/npu-" + std::to_string(item_seed) + "-" + std::to_string(b) + ".rgb";
        if (!write_file(path, pixels.data(), pixels.size())) return error_json("IO", "cannot write output");
        events.image(b, path, w, h, item_seed);
    }
    if (qnn_) qnn_->set_performance(false);

    std::ostringstream o;
    o << "{\"backend\":\"" << unet->backend() << "\",\"width\":" << w << ",\"height\":" << h << ",\"timings\":{"
      << "\"load_ms\":" << load_ms << ",\"text_ms\":" << text_ms << ",\"step_ms\":" << (unet_calls ? unet_ms / unet_calls : 0)
      << ",\"decode_ms\":" << (batch ? decode_ms / batch : 0) << ",\"total_ms\":" << ms_since(t_total) << "}}";
    LOGI("generated %d image(s): %s", batch, o.str().c_str());
    return o.str();
}

std::string Engine::upscale(const std::string & request, const Events & events) {
    rapidjson::Document doc;
    doc.Parse(request.c_str(), request.size());
    std::string error;
    events.status("loading");
    QnnModel * m = qnn_model(str(doc, "model"), &error);
    if (!m) return error_json("LOAD_FAILED", error);
    const auto & g = m->graphs()[0];
    if (g.inputs.size() != 1 || g.outputs.size() != 1 || g.inputs[0].dims.size() != 4) return error_json("UNSUPPORTED", "not an upscaler");
    const int tile = (int) g.inputs[0].dims[2];
    const int factor = (int) (g.outputs[0].dims[2] / g.inputs[0].dims[2]);
    const int w = (int) num(doc, "width", 0), h = (int) num(doc, "height", 0);
    std::vector<uint8_t> src;
    if (!read_file(str(doc, "image"), &src) || src.size() != (size_t) 3 * w * h || w < 8 || h < 8) return error_json("BAD_IMAGE", "invalid input image");

    const int overlap = std::min(16, tile / 8);
    const int stride = tile - 2 * overlap;
    const int W = w * factor, H = h * factor;
    std::vector<uint8_t> dst((size_t) 3 * W * H);
    std::vector<float> in((size_t) 3 * tile * tile), out(g.outputs[0].elements());
    std::vector<uint8_t> nin(g.inputs[0].bytes()), nout(g.outputs[0].bytes());
    const int tiles_x = (w + stride - 1) / stride, tiles_y = (h + stride - 1) / stride;
    int done = 0;
    if (qnn_) qnn_->set_performance(true);
    events.status("upscaling");
    for (int ty = 0; ty < tiles_y; ty++) {
        for (int tx = 0; tx < tiles_x; tx++) {
            if (cancel_.load()) {
                if (qnn_) qnn_->set_performance(false);
                return error_json("CANCELLED", "cancelled");
            }
            // Tile origin in the source, shifted so the tile stays inside the image (edge-clamped).
            const int ox = tx * stride - overlap, oy = ty * stride - overlap;
            for (int c = 0; c < 3; c++)
                for (int y = 0; y < tile; y++)
                    for (int x = 0; x < tile; x++) {
                        const int sx = std::clamp(ox + x, 0, w - 1), sy = std::clamp(oy + y, 0, h - 1);
                        in[(size_t) c * tile * tile + (size_t) y * tile + x] = src[((size_t) sy * w + sx) * 3 + c] / 255.0f;
                    }
            to_native(g.inputs[0], in.data(), nin.data());
            if (!m->execute(0, {nin.data()}, {nout.data()}, &error)) return error_json("UPSCALE_FAILED", error);
            from_native(g.outputs[0], nout.data(), out.data());
            const int T = tile * factor;
            // Copy the tile's centre (without the overlap) into the output.
            for (int y = overlap * factor; y < (tile - overlap) * factor; y++) {
                const int dy = oy * factor + y;
                if (dy < 0 || dy >= H) continue;
                for (int x = overlap * factor; x < (tile - overlap) * factor; x++) {
                    const int dx = ox * factor + x;
                    if (dx < 0 || dx >= W) continue;
                    for (int c = 0; c < 3; c++) {
                        const float v = out[(size_t) c * T * T + (size_t) y * T + x];
                        dst[((size_t) dy * W + dx) * 3 + c] = (uint8_t) std::clamp((int) std::lround(v * 255.0f), 0, 255);
                    }
                }
            }
            events.progress(++done, tiles_x * tiles_y);
        }
    }
    if (qnn_) qnn_->set_performance(false);
    const std::string path = str(doc, "out_dir") + "/upscaled-" + std::to_string(W) + "x" + std::to_string(H) + ".rgb";
    if (!write_file(path, dst.data(), dst.size())) return error_json("IO", "cannot write output");
    events.image(0, path, W, H, 0);
    return "{\"backend\":\"npu\",\"width\":" + std::to_string(W) + ",\"height\":" + std::to_string(H) + "}";
}

}  // namespace npu
