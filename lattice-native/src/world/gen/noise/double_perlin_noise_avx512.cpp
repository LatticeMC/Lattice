#include "world/gen/noise/double_perlin_noise.hpp"

#include <cstddef>
#include <immintrin.h>
#include <vector>

// Preserve the scalar reference's multiply/add rounding sequence.
#if defined(__clang__)
#pragma clang fp contract(off)
#endif

namespace lattice::world::gen::noise {
namespace {

struct Scratch {
    std::vector<double> x, y, z, second;
    void resize(std::size_t count) { x.resize(count); y.resize(count); z.resize(count); second.resize(count); }
    void resize_second(std::size_t count) { second.resize(count); }
};
thread_local Scratch g_scratch;


void scale8(const double* source, double* target, std::size_t count) noexcept {
    const __m512d scale = _mm512_set1_pd(kDomainScale);
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8) _mm512_storeu_pd(target + i, _mm512_mul_pd(_mm512_loadu_pd(source + i), scale));
    for (; i < count; ++i) target[i] = source[i] * kDomainScale;
}
void combine8(const double* second, double amplitude, std::size_t count, double* out) noexcept {
    const __m512d amp = _mm512_set1_pd(amplitude);
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8) _mm512_storeu_pd(out + i, _mm512_mul_pd(_mm512_add_pd(_mm512_loadu_pd(out + i), _mm512_loadu_pd(second + i)), amp));
    for (; i < count; ++i) out[i] = (out[i] + second[i]) * amplitude;
}
}

void sample_batch_avx512(const DoublePerlinNoiseSampler& s, const double* x, const double* y,
                         const double* z, std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out || count == 0) return;
    auto& scratch = g_scratch; scratch.resize(count);
    scale8(x, scratch.x.data(), count); scale8(y, scratch.y.data(), count); scale8(z, scratch.z.data(), count);
    sample_batch(s.first, x, y, z, count, out);
    sample_batch(s.second, scratch.x.data(), scratch.y.data(), scratch.z.data(), count, scratch.second.data());
    combine8(scratch.second.data(), s.amplitude, count, out);
    _mm256_zeroupper();
}

void sample_y_column_avx512(const DoublePerlinNoiseSampler& s, double x, double y0, double z,
                            double dy, std::size_t count, double* out) noexcept {
    if (!out || count == 0) return;
    auto& scratch = g_scratch; scratch.resize_second(count);
    sample_y_column(s.first, x, y0, z, dy, count, out);
    sample_y_column(s.second, x * kDomainScale, y0 * kDomainScale, z * kDomainScale,
                           dy * kDomainScale, count, scratch.second.data());
    combine8(scratch.second.data(), s.amplitude, count, out);
    _mm256_zeroupper();
}
} // namespace lattice::world::gen::noise
