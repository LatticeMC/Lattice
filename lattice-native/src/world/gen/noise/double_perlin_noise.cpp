// Two-sampler Perlin noise. See double_perlin_noise.hpp.

#include "world/gen/noise/double_perlin_noise.hpp"

#include <cstddef>
#include <vector>

#include "lattice/dispatch.hpp"

namespace lattice::world::gen::noise {

namespace {
constexpr std::size_t kAvx512MinimumBatchSize = 129;

inline bool can_use_avx512(std::size_t count) noexcept {
    const auto& f = lattice::cpu::features();
    return count >= kAvx512MinimumBatchSize
        && f.requested_tier == lattice::cpu::RequestedTier::Avx512
        && f.avx512f && f.avx512dq && f.avx512vl;
}

struct DoublePerlinBatchScratch {
    std::vector<double> scaled_x;
    std::vector<double> scaled_y;
    std::vector<double> scaled_z;
    std::vector<double> second;

    void resize(std::size_t count) {
        scaled_x.resize(count);
        scaled_y.resize(count);
        scaled_z.resize(count);
        second.resize(count);
    }

    void resize_second(std::size_t count) {
        second.resize(count);
    }
};

thread_local DoublePerlinBatchScratch g_double_perlin_batch_scratch;
} // namespace

double sample(const DoublePerlinNoiseSampler& s,
              double x, double y, double z) noexcept {
    const double sx = x * kDomainScale;
    const double sy = y * kDomainScale;
    const double sz = z * kDomainScale;
    const double v1 = sample(s.first,  x,  y,  z);
    const double v2 = sample(s.second, sx, sy, sz);
    return (v1 + v2) * s.amplitude;
}

void sample_batch_scalar(const DoublePerlinNoiseSampler& s,
                         const double* x, const double* y, const double* z,
                         std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
    if (count == 0) return;

    DoublePerlinBatchScratch& scratch = g_double_perlin_batch_scratch;
    scratch.resize(count);
    for (std::size_t i = 0; i < count; ++i) {
        scratch.scaled_x[i] = x[i] * kDomainScale;
        scratch.scaled_y[i] = y[i] * kDomainScale;
        scratch.scaled_z[i] = z[i] * kDomainScale;
    }
    sample_batch(s.first, x, y, z, count, out);
    sample_batch(s.second, scratch.scaled_x.data(), scratch.scaled_y.data(), scratch.scaled_z.data(), count, scratch.second.data());
    for (std::size_t i = 0; i < count; ++i) out[i] = (out[i] + scratch.second[i]) * s.amplitude;
}

void sample_batch(const DoublePerlinNoiseSampler& s,
                  const double* x, const double* y, const double* z,
                  std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
#if defined(LATTICE_HAS_DOUBLE_PERLIN_AVX512)
    if (can_use_avx512(count)) {
        sample_batch_avx512(s, x, y, z, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_DOUBLE_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_batch_avx2(s, x, y, z, count, out);
        return;
    }
#endif
    sample_batch_scalar(s, x, y, z, count, out);
}

void sample_y_column_scalar(const DoublePerlinNoiseSampler& s,
                            double x, double y0, double z, double dy,
                            std::size_t count, double* out) noexcept {
    if (!out) return;
    if (count == 0) return;

    DoublePerlinBatchScratch& scratch = g_double_perlin_batch_scratch;
    scratch.resize_second(count);
    sample_y_column(s.first, x, y0, z, dy, count, out);
    sample_y_column(s.second,
                    x * kDomainScale,
                    y0 * kDomainScale,
                    z * kDomainScale,
                    dy * kDomainScale,
                    count,
                    scratch.second.data());
    for (std::size_t i = 0; i < count; ++i) out[i] = (out[i] + scratch.second[i]) * s.amplitude;
}

void sample_y_column(const DoublePerlinNoiseSampler& s,
                     double x, double y0, double z, double dy,
                     std::size_t count, double* out) noexcept {
    if (!out) return;
#if defined(LATTICE_HAS_DOUBLE_PERLIN_AVX512)
    if (can_use_avx512(count)) {
        sample_y_column_avx512(s, x, y0, z, dy, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_DOUBLE_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_column_avx2(s, x, y0, z, dy, count, out);
        return;
    }
#endif
    sample_y_column_scalar(s, x, y0, z, dy, count, out);
}

} // namespace lattice::world::gen::noise
