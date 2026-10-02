#include "world/gen/noise/simplex_noise.hpp"

#include <cstddef>
#include <cstdint>
#include <immintrin.h>

// Keep SIMD results bit-compatible with the scalar reference. clang-cl may
// enable contraction when compiling /arch:AVX2, changing the final rounding.
#if defined(__clang__)
#pragma clang fp contract(off)
#endif

namespace lattice::world::gen::noise {
namespace {

constexpr std::int32_t kGradients[] = {
     1,  1,  0,  -1,  1,  0,   1, -1,  0,  -1, -1,  0,
     1,  0,  1,  -1,  0,  1,   1,  0, -1,  -1,  0, -1,
     0,  1,  1,   0, -1,  1,   0,  1, -1,   0, -1, -1,
};

inline __m128i floor_to_i32(__m256d value) noexcept {
    return _mm256_cvttpd_epi32(_mm256_floor_pd(value));
}

inline __m128i mask_to_i32(__m256d mask) noexcept {
    return _mm256_cvttpd_epi32(_mm256_and_pd(mask, _mm256_set1_pd(1.0)));
}

inline __m128i pmap4(const SimplexNoiseSampler& s, __m128i input) noexcept {
    const __m128i index = _mm_and_si128(input, _mm_set1_epi32(0xFF));
    return _mm_i32gather_epi32(s.permutation, index, sizeof(std::int32_t));
}

inline __m128i pmap_mod12_4(const SimplexNoiseSampler& s, __m128i input) noexcept {
    const __m128i value = _mm_and_si128(pmap4(s, input), _mm_set1_epi32(0xFF));
    // For 0..255, floor(value / 12) == (value * 0xAAAB) >> 19.
    const __m128i quotient = _mm_srli_epi32(
        _mm_mullo_epi32(value, _mm_set1_epi32(0xAAAB)), 19);
    return _mm_sub_epi32(value, _mm_mullo_epi32(quotient, _mm_set1_epi32(12)));
}

inline __m256d gradient_component4(__m128i gradient, int component) noexcept {
    const __m128i offset = _mm_add_epi32(_mm_add_epi32(gradient, gradient), gradient);
    return _mm256_cvtepi32_pd(
        _mm_i32gather_epi32(kGradients + component, offset, sizeof(std::int32_t)));
}

inline __m256d dot2_4(__m128i gradient, __m256d x, __m256d y) noexcept {
    const __m256d gx = gradient_component4(gradient, 0);
    const __m256d gy = gradient_component4(gradient, 1);
    return _mm256_add_pd(_mm256_mul_pd(gx, x), _mm256_mul_pd(gy, y));
}

inline __m256d dot3_4(__m128i gradient, __m256d x, __m256d y, __m256d z) noexcept {
    const __m256d gx = gradient_component4(gradient, 0);
    const __m256d gy = gradient_component4(gradient, 1);
    const __m256d gz = gradient_component4(gradient, 2);
    return _mm256_add_pd(
        _mm256_add_pd(_mm256_mul_pd(gx, x), _mm256_mul_pd(gy, y)),
        _mm256_mul_pd(gz, z));
}

inline __m256d corner4(__m128i gradient, __m256d x, __m256d y) noexcept {
    const __m256d zero = _mm256_setzero_pd();
    const __m256d attenuation = _mm256_sub_pd(
        _mm256_sub_pd(_mm256_set1_pd(0.5), _mm256_mul_pd(x, x)),
        _mm256_mul_pd(y, y));
    const __m256d positive = _mm256_cmp_pd(attenuation, zero, _CMP_GT_OQ);
    const __m256d t = _mm256_blendv_pd(zero, attenuation, positive);
    const __m256d t2 = _mm256_mul_pd(t, t);
    return _mm256_mul_pd(_mm256_mul_pd(t2, t2), dot2_4(gradient, x, y));
}

inline __m256d contribution3_4(__m128i gradient,
                               __m256d x, __m256d y, __m256d z) noexcept {
    const __m256d zero = _mm256_setzero_pd();
    const __m256d attenuation = _mm256_sub_pd(
        _mm256_sub_pd(
            _mm256_sub_pd(_mm256_set1_pd(0.6), _mm256_mul_pd(x, x)),
            _mm256_mul_pd(y, y)),
        _mm256_mul_pd(z, z));
    const __m256d positive = _mm256_cmp_pd(attenuation, zero, _CMP_GT_OQ);
    const __m256d t = _mm256_blendv_pd(zero, attenuation, positive);
    const __m256d t2 = _mm256_mul_pd(t, t);
    return _mm256_mul_pd(_mm256_mul_pd(t2, t2), dot3_4(gradient, x, y, z));
}

inline void sample4_2d(const SimplexNoiseSampler& s,
                       const double* x, const double* y,
                       double* out) noexcept {
    constexpr double F2 = 0.36602540378443864;
    constexpr double G2 = 0.21132486540518713;
    const __m256d vx = _mm256_loadu_pd(x);
    const __m256d vy = _mm256_loadu_pd(y);
    const __m256d skew = _mm256_mul_pd(_mm256_add_pd(vx, vy), _mm256_set1_pd(F2));

    const __m128i ii = floor_to_i32(_mm256_add_pd(vx, skew));
    const __m128i jj = floor_to_i32(_mm256_add_pd(vy, skew));
    const __m256d ii_pd = _mm256_cvtepi32_pd(ii);
    const __m256d jj_pd = _mm256_cvtepi32_pd(jj);
    const __m256d t = _mm256_mul_pd(
        _mm256_cvtepi32_pd(_mm_add_epi32(ii, jj)), _mm256_set1_pd(G2));
    const __m256d x0 = _mm256_sub_pd(vx, _mm256_sub_pd(ii_pd, t));
    const __m256d y0 = _mm256_sub_pd(vy, _mm256_sub_pd(jj_pd, t));

    const __m256d x_gt_y = _mm256_cmp_pd(x0, y0, _CMP_GT_OQ);
    const __m128i i1 = mask_to_i32(x_gt_y);
    const __m128i j1 = _mm_sub_epi32(_mm_set1_epi32(1), i1);
    const __m256d i1_pd = _mm256_cvtepi32_pd(i1);
    const __m256d j1_pd = _mm256_cvtepi32_pd(j1);

    const __m256d x1 = _mm256_add_pd(_mm256_sub_pd(x0, i1_pd), _mm256_set1_pd(G2));
    const __m256d y1 = _mm256_add_pd(_mm256_sub_pd(y0, j1_pd), _mm256_set1_pd(G2));
    const __m256d x2 = _mm256_add_pd(_mm256_sub_pd(x0, _mm256_set1_pd(1.0)),
                                     _mm256_set1_pd(2.0 * G2));
    const __m256d y2 = _mm256_add_pd(_mm256_sub_pd(y0, _mm256_set1_pd(1.0)),
                                     _mm256_set1_pd(2.0 * G2));

    const __m128i gi0 = pmap_mod12_4(s, _mm_add_epi32(ii, pmap4(s, jj)));
    const __m128i gi1 = pmap_mod12_4(
        s, _mm_add_epi32(_mm_add_epi32(ii, i1), pmap4(s, _mm_add_epi32(jj, j1))));
    const __m128i ones = _mm_set1_epi32(1);
    const __m128i gi2 = pmap_mod12_4(
        s, _mm_add_epi32(_mm_add_epi32(ii, ones), pmap4(s, _mm_add_epi32(jj, ones))));

    const __m256d sum = _mm256_add_pd(
        _mm256_add_pd(corner4(gi0, x0, y0), corner4(gi1, x1, y1)),
        corner4(gi2, x2, y2));
    _mm256_storeu_pd(out, _mm256_mul_pd(_mm256_set1_pd(70.0), sum));
}

inline void sample4_3d(const SimplexNoiseSampler& s,
                       const double* x, const double* y, const double* z,
                       double* out) noexcept {
    constexpr double F3 = 1.0 / 3.0;
    constexpr double G3 = 1.0 / 6.0;
    const __m256d vx = _mm256_loadu_pd(x);
    const __m256d vy = _mm256_loadu_pd(y);
    const __m256d vz = _mm256_loadu_pd(z);
    const __m256d skew = _mm256_mul_pd(
        _mm256_add_pd(_mm256_add_pd(vx, vy), vz), _mm256_set1_pd(F3));

    const __m128i ii = floor_to_i32(_mm256_add_pd(vx, skew));
    const __m128i jj = floor_to_i32(_mm256_add_pd(vy, skew));
    const __m128i kk = floor_to_i32(_mm256_add_pd(vz, skew));
    const __m256d ii_pd = _mm256_cvtepi32_pd(ii);
    const __m256d jj_pd = _mm256_cvtepi32_pd(jj);
    const __m256d kk_pd = _mm256_cvtepi32_pd(kk);
    const __m256d t = _mm256_mul_pd(
        _mm256_cvtepi32_pd(_mm_add_epi32(_mm_add_epi32(ii, jj), kk)),
        _mm256_set1_pd(G3));
    const __m256d x0 = _mm256_sub_pd(vx, _mm256_sub_pd(ii_pd, t));
    const __m256d y0 = _mm256_sub_pd(vy, _mm256_sub_pd(jj_pd, t));
    const __m256d z0 = _mm256_sub_pd(vz, _mm256_sub_pd(kk_pd, t));

    const __m256d x_ge_y = _mm256_cmp_pd(x0, y0, _CMP_GE_OQ);
    const __m256d x_ge_z = _mm256_cmp_pd(x0, z0, _CMP_GE_OQ);
    const __m256d x_lt_y = _mm256_cmp_pd(x0, y0, _CMP_LT_OQ);
    const __m256d y_ge_z = _mm256_cmp_pd(y0, z0, _CMP_GE_OQ);
    const __m256d y_gt_x = _mm256_cmp_pd(y0, x0, _CMP_GT_OQ);
    const __m256d z_gt_x = _mm256_cmp_pd(z0, x0, _CMP_GT_OQ);
    const __m256d z_gt_y = _mm256_cmp_pd(z0, y0, _CMP_GT_OQ);

    const __m128i i1 = mask_to_i32(_mm256_and_pd(x_ge_y, x_ge_z));
    const __m128i j1 = mask_to_i32(_mm256_and_pd(x_lt_y, y_ge_z));
    const __m128i k1 = _mm_sub_epi32(_mm_sub_epi32(_mm_set1_epi32(1), i1), j1);
    // The second corner has an X step for the x>=y branch and for the
    // x<y, y>=z, x>=z branch. The y>=z guard distinguishes that latter
    // case from the x<y, y<z ordering.
    const __m256d xlt_y_and_yge_z_and_xge_z = _mm256_and_pd(
        _mm256_and_pd(x_lt_y, y_ge_z), x_ge_z);
    const __m128i i2 = mask_to_i32(
        _mm256_or_pd(x_ge_y, xlt_y_and_yge_z_and_xge_z));
    const __m128i j2 = mask_to_i32(_mm256_or_pd(y_ge_z, y_gt_x));
    const __m128i k2 = mask_to_i32(_mm256_or_pd(z_gt_x, z_gt_y));

    const __m256d i1_pd = _mm256_cvtepi32_pd(i1);
    const __m256d j1_pd = _mm256_cvtepi32_pd(j1);
    const __m256d k1_pd = _mm256_cvtepi32_pd(k1);
    const __m256d i2_pd = _mm256_cvtepi32_pd(i2);
    const __m256d j2_pd = _mm256_cvtepi32_pd(j2);
    const __m256d k2_pd = _mm256_cvtepi32_pd(k2);
    const __m256d g3 = _mm256_set1_pd(G3);
    const __m256d two_g3 = _mm256_set1_pd(2.0 * G3);
    const __m256d three_g3 = _mm256_set1_pd(3.0 * G3);
    const __m256d one = _mm256_set1_pd(1.0);
    const __m256d x1 = _mm256_add_pd(_mm256_sub_pd(x0, i1_pd), g3);
    const __m256d y1 = _mm256_add_pd(_mm256_sub_pd(y0, j1_pd), g3);
    const __m256d z1 = _mm256_add_pd(_mm256_sub_pd(z0, k1_pd), g3);
    const __m256d x2 = _mm256_add_pd(_mm256_sub_pd(x0, i2_pd), two_g3);
    const __m256d y2 = _mm256_add_pd(_mm256_sub_pd(y0, j2_pd), two_g3);
    const __m256d z2 = _mm256_add_pd(_mm256_sub_pd(z0, k2_pd), two_g3);
    const __m256d x3 = _mm256_add_pd(_mm256_sub_pd(x0, one), three_g3);
    const __m256d y3 = _mm256_add_pd(_mm256_sub_pd(y0, one), three_g3);
    const __m256d z3 = _mm256_add_pd(_mm256_sub_pd(z0, one), three_g3);

    const __m128i gi0 = pmap_mod12_4(
        s, _mm_add_epi32(ii, pmap4(s, _mm_add_epi32(jj, pmap4(s, kk)))));
    const __m128i gi1 = pmap_mod12_4(
        s, _mm_add_epi32(
               _mm_add_epi32(ii, i1),
               pmap4(s, _mm_add_epi32(
                   _mm_add_epi32(jj, j1), pmap4(s, _mm_add_epi32(kk, k1))))));
    const __m128i gi2 = pmap_mod12_4(
        s, _mm_add_epi32(
               _mm_add_epi32(ii, i2),
               pmap4(s, _mm_add_epi32(
                   _mm_add_epi32(jj, j2), pmap4(s, _mm_add_epi32(kk, k2))))));
    const __m128i ones = _mm_set1_epi32(1);
    const __m128i gi3 = pmap_mod12_4(
        s, _mm_add_epi32(
               _mm_add_epi32(ii, ones),
               pmap4(s, _mm_add_epi32(
                   _mm_add_epi32(jj, ones), pmap4(s, _mm_add_epi32(kk, ones))))));

    const __m256d sum = _mm256_add_pd(
        _mm256_add_pd(
            _mm256_add_pd(contribution3_4(gi0, x0, y0, z0),
                          contribution3_4(gi1, x1, y1, z1)),
            contribution3_4(gi2, x2, y2, z2)),
        contribution3_4(gi3, x3, y3, z3));
    _mm256_storeu_pd(out, _mm256_mul_pd(_mm256_set1_pd(32.0), sum));
}

} // namespace

void sample_2d_batch_avx2(const SimplexNoiseSampler& s,
                          const double* x, const double* y,
                          std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) sample4_2d(s, x + i, y + i, out + i);
    if (i < count) sample_2d_batch_scalar(s, x + i, y + i, count - i, out + i);
}

void sample_3d_batch_avx2(const SimplexNoiseSampler& s,
                          const double* x, const double* y, const double* z,
                          std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) sample4_3d(s, x + i, y + i, z + i, out + i);
    if (i < count) sample_3d_batch_scalar(s, x + i, y + i, z + i, count - i, out + i);
}

} // namespace lattice::world::gen::noise
