#include "world/gen/noise/double_perlin_noise.hpp"

#include <cstddef>
#include <immintrin.h>
#include <vector>

namespace lattice::world::gen::noise {
namespace {

struct DoublePerlinAvx2Scratch {
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

thread_local DoublePerlinAvx2Scratch g_double_perlin_avx2_scratch;

inline void combine_avx2(const double* second, double amplitude,
                         std::size_t count, double* out) noexcept {
    const __m256d amp = _mm256_set1_pd(amplitude);
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) {
        const __m256d a = _mm256_loadu_pd(out + i);
        const __m256d b = _mm256_loadu_pd(second + i);
        _mm256_storeu_pd(out + i, _mm256_mul_pd(_mm256_add_pd(a, b), amp));
    }
    for (; i < count; ++i) out[i] = (out[i] + second[i]) * amplitude;
}

} // namespace

void sample_batch_avx2(const DoublePerlinNoiseSampler& s,
                       const double* x, const double* y, const double* z,
                       std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
    if (count == 0) return;

    DoublePerlinAvx2Scratch& scratch = g_double_perlin_avx2_scratch;
    scratch.resize(count);
    const __m256d domain_scale = _mm256_set1_pd(kDomainScale);
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) {
        _mm256_storeu_pd(scratch.scaled_x.data() + i, _mm256_mul_pd(_mm256_loadu_pd(x + i), domain_scale));
        _mm256_storeu_pd(scratch.scaled_y.data() + i, _mm256_mul_pd(_mm256_loadu_pd(y + i), domain_scale));
        _mm256_storeu_pd(scratch.scaled_z.data() + i, _mm256_mul_pd(_mm256_loadu_pd(z + i), domain_scale));
    }
    for (; i < count; ++i) {
        scratch.scaled_x[i] = x[i] * kDomainScale;
        scratch.scaled_y[i] = y[i] * kDomainScale;
        scratch.scaled_z[i] = z[i] * kDomainScale;
    }

    sample_batch(s.first, x, y, z, count, out);
    sample_batch(s.second, scratch.scaled_x.data(), scratch.scaled_y.data(), scratch.scaled_z.data(), count, scratch.second.data());
    combine_avx2(scratch.second.data(), s.amplitude, count, out);
}

void sample_y_column_avx2(const DoublePerlinNoiseSampler& s,
                          double x, double y0, double z, double dy,
                          std::size_t count, double* out) noexcept {
    if (!out) return;
    if (count == 0) return;

    DoublePerlinAvx2Scratch& scratch = g_double_perlin_avx2_scratch;
    scratch.resize_second(count);
    sample_y_column(s.first, x, y0, z, dy, count, out);
    sample_y_column(s.second,
                    x * kDomainScale,
                    y0 * kDomainScale,
                    z * kDomainScale,
                    dy * kDomainScale,
                    count,
                    scratch.second.data());
    combine_avx2(scratch.second.data(), s.amplitude, count, out);
}

} // namespace lattice::world::gen::noise
