// Samplers in k-diffusion form over the Stable Diffusion discrete noise schedule
// (scaled-linear betas 0.00085 → 0.012, 1000 training steps).

#pragma once

#include <cstdint>
#include <random>
#include <string>
#include <vector>

namespace npu {

enum class Sampler { EULER, EULER_A, DPMPP_2M, LCM };

Sampler sampler_from(const std::string & name);

class NoiseSchedule {
public:
    NoiseSchedule();

    /**
     * Noise levels for [steps] steps, highest first, ending with 0. [karras] spaces them like
     * Karras et al.; otherwise timesteps are spaced evenly ("trailing", best for few steps).
     */
    std::vector<float> sigmas(int steps, bool karras) const;

    /** Training timestep for a noise level (what the UNet's timestep input expects). */
    float timestep(float sigma) const;

    float sigma_min() const { return sigmas_.front(); }
    float sigma_max() const { return sigmas_.back(); }

private:
    std::vector<float> sigmas_;      // ascending, index = training timestep
    std::vector<float> log_sigmas_;
};

/** Gaussian noise from a seed (deterministic across runs and devices). */
class Noise {
public:
    explicit Noise(uint64_t seed) : rng_(seed) {}
    void fill(std::vector<float> & out);

private:
    std::mt19937_64 rng_;
    std::normal_distribution<float> dist_{0.0f, 1.0f};
};

/**
 * Advances latents [x] from sigma[i] to sigma[i + 1] given the model's [denoised] estimate.
 * Keeps per-run state (DPM++ 2M uses the previous estimate).
 */
class Stepper {
public:
    Stepper(Sampler sampler, Noise * noise) : sampler_(sampler), noise_(noise) {}
    void step(std::vector<float> & x, const std::vector<float> & denoised, const std::vector<float> & sigmas, int i);

private:
    Sampler sampler_;
    Noise * noise_;
    std::vector<float> old_denoised_;
    std::vector<float> scratch_;
};

}  // namespace npu
