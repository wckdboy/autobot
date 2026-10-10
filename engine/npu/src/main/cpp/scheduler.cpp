#include "scheduler.h"

#include <algorithm>
#include <cmath>

namespace npu {

Sampler sampler_from(const std::string & name) {
    std::string n;
    for (char c : name) n += (char) std::tolower((unsigned char) c);
    if (n.find("lcm") != std::string::npos) return Sampler::LCM;
    if (n.find("dpm") != std::string::npos) return Sampler::DPMPP_2M;
    if (n.find("euler a") != std::string::npos || n.find("euler_a") != std::string::npos || n.find("ancestral") != std::string::npos) return Sampler::EULER_A;
    return Sampler::EULER;
}

NoiseSchedule::NoiseSchedule() {
    const int n = 1000;
    const double b0 = std::sqrt(0.00085), b1 = std::sqrt(0.012);
    double prod = 1.0;
    sigmas_.resize(n);
    log_sigmas_.resize(n);
    for (int t = 0; t < n; t++) {
        const double b = b0 + (b1 - b0) * t / (n - 1);
        prod *= 1.0 - b * b;
        sigmas_[t] = (float) std::sqrt((1.0 - prod) / prod);
        log_sigmas_[t] = std::log(sigmas_[t]);
    }
}

float NoiseSchedule::timestep(float sigma) const {
    const float ls = std::log(std::max(sigma, 1e-10f));
    if (ls <= log_sigmas_.front()) return 0.0f;
    if (ls >= log_sigmas_.back()) return (float) (log_sigmas_.size() - 1);
    const auto hi = std::upper_bound(log_sigmas_.begin(), log_sigmas_.end(), ls) - log_sigmas_.begin();
    const auto lo = hi - 1;
    const float w = (ls - log_sigmas_[lo]) / (log_sigmas_[hi] - log_sigmas_[lo]);
    return (float) lo + w;
}

std::vector<float> NoiseSchedule::sigmas(int steps, bool karras) const {
    std::vector<float> out;
    steps = std::max(1, steps);
    if (karras) {
        const float rho = 7.0f;
        const float mn = std::pow(sigma_min(), 1.0f / rho), mx = std::pow(sigma_max(), 1.0f / rho);
        for (int i = 0; i < steps; i++) {
            const float r = steps == 1 ? 0.0f : (float) i / (steps - 1);
            out.push_back(std::pow(mx + r * (mn - mx), rho));
        }
    } else {
        // Trailing spacing: t = 999, 999 - 1000/steps, ...
        const float stride = 1000.0f / steps;
        for (int i = 0; i < steps; i++) {
            const float t = std::round(999.0f - i * stride);
            const int lo = (int) std::floor(t);
            out.push_back(sigmas_[std::clamp(lo, 0, 999)]);
        }
    }
    out.push_back(0.0f);
    return out;
}

void Noise::fill(std::vector<float> & out) {
    for (auto & v : out) v = dist_(rng_);
}

void Stepper::step(std::vector<float> & x, const std::vector<float> & denoised, const std::vector<float> & sigmas, int i) {
    const float s = sigmas[i];
    const float next = sigmas[i + 1];
    const size_t n = x.size();
    switch (sampler_) {
        case Sampler::EULER: {
            for (size_t k = 0; k < n; k++) {
                const float d = (x[k] - denoised[k]) / s;
                x[k] += d * (next - s);
            }
            break;
        }
        case Sampler::EULER_A: {
            const float up = next == 0.0f ? 0.0f : std::min(next, std::sqrt(next * next * (s * s - next * next) / (s * s)));
            const float down = std::sqrt(std::max(0.0f, next * next - up * up));
            for (size_t k = 0; k < n; k++) {
                const float d = (x[k] - denoised[k]) / s;
                x[k] += d * (down - s);
            }
            if (up > 0.0f) {
                scratch_.resize(n);
                noise_->fill(scratch_);
                for (size_t k = 0; k < n; k++) x[k] += scratch_[k] * up;
            }
            break;
        }
        case Sampler::DPMPP_2M: {
            // DPM-Solver++(2M), Lu et al. 2022, in the k-diffusion formulation.
            const float t = -std::log(s);
            const float t_next = next == 0.0f ? 0.0f : -std::log(next);
            const float h = next == 0.0f ? 0.0f : t_next - t;
            if (next == 0.0f) {
                x = denoised;
            } else if (old_denoised_.empty()) {
                const float a = next / s, b = -std::expm1(-h);
                for (size_t k = 0; k < n; k++) x[k] = a * x[k] + b * denoised[k];
            } else {
                const float h_last = t - (-std::log(sigmas[i - 1]));
                const float r = h_last / h;
                const float a = next / s, b = -std::expm1(-h);
                for (size_t k = 0; k < n; k++) {
                    const float d = (1.0f + 1.0f / (2.0f * r)) * denoised[k] - (1.0f / (2.0f * r)) * old_denoised_[k];
                    x[k] = a * x[k] + b * d;
                }
            }
            old_denoised_ = denoised;
            break;
        }
        case Sampler::LCM: {
            // Consistency sampling: jump to the estimate, then re-noise to the next level.
            x = denoised;
            if (next > 0.0f) {
                scratch_.resize(n);
                noise_->fill(scratch_);
                for (size_t k = 0; k < n; k++) x[k] += scratch_[k] * next;
            }
            break;
        }
    }
}

}  // namespace npu
