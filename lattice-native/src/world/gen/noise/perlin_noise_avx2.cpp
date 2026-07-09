#include "world/gen/noise/perlin_noise.hpp"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <immintrin.h>

namespace lattice::world::gen::noise {
namespace {

inline std::uint32_t pmap(const std::uint8_t* perm, int input) noexcept {
    return perm[input & 0xFF];
}

inline int floor_to_int(double x) noexcept {
    const int i = static_cast<int>(x);
    return (static_cast<double>(i) <= x) ? i : i - 1;
}

inline __m256d smooth_step(__m256d t) noexcept {
    const __m256d six = _mm256_set1_pd(6.0);
    const __m256d fifteen = _mm256_set1_pd(15.0);
    const __m256d ten = _mm256_set1_pd(10.0);
    const __m256d t2 = _mm256_mul_pd(t, t);
    const __m256d t3 = _mm256_mul_pd(t2, t);
    const __m256d inner = _mm256_sub_pd(_mm256_mul_pd(t, six), fifteen);
    return _mm256_mul_pd(t3, _mm256_add_pd(_mm256_mul_pd(t, inner), ten));
}

inline __m256d lerp(__m256d t, __m256d a, __m256d b) noexcept {
    return _mm256_add_pd(a, _mm256_mul_pd(t, _mm256_sub_pd(b, a)));
}

inline __m256d mask_i32_to_pd(__m128i mask) noexcept {
    return _mm256_castsi256_pd(_mm256_cvtepi32_epi64(mask));
}

inline __m256d grad4(__m128i h, __m256d x, __m256d y, __m256d z) noexcept {
    const __m256d sign_bit = _mm256_set1_pd(-0.0);
    h = _mm_and_si128(h, _mm_set1_epi32(15));

    const __m256d h_lt_8 = mask_i32_to_pd(_mm_cmplt_epi32(h, _mm_set1_epi32(8)));
    const __m256d h_lt_4 = mask_i32_to_pd(_mm_cmplt_epi32(h, _mm_set1_epi32(4)));
    const __m128i h_eq_12_or_14 = _mm_or_si128(_mm_cmpeq_epi32(h, _mm_set1_epi32(12)),
                                               _mm_cmpeq_epi32(h, _mm_set1_epi32(14)));
    const __m256d h_eq_12_or_14_pd = mask_i32_to_pd(h_eq_12_or_14);

    const __m256d u = _mm256_blendv_pd(y, x, h_lt_8);
    const __m256d v_non_xy = _mm256_blendv_pd(z, x, h_eq_12_or_14_pd);
    const __m256d v = _mm256_blendv_pd(v_non_xy, y, h_lt_4);

    const __m256d sign_u = mask_i32_to_pd(_mm_cmpeq_epi32(_mm_and_si128(h, _mm_set1_epi32(1)), _mm_set1_epi32(1)));
    const __m256d sign_v = mask_i32_to_pd(_mm_cmpeq_epi32(_mm_and_si128(h, _mm_set1_epi32(2)), _mm_set1_epi32(2)));
    const __m256d su = _mm256_blendv_pd(u, _mm256_xor_pd(u, sign_bit), sign_u);
    const __m256d sv = _mm256_blendv_pd(v, _mm256_xor_pd(v, sign_bit), sign_v);
    return _mm256_add_pd(su, sv);
}

inline __m128i load_hash4(const int hashes[4]) noexcept {
    return _mm_load_si128(reinterpret_cast<const __m128i*>(hashes));
}

inline __m256d floor_lanes(__m256d value, int out_i[4]) noexcept {
    const __m128i truncated = _mm256_cvttpd_epi32(value);
    const __m256d back = _mm256_cvtepi32_pd(truncated);
    const __m256d needs_adjust_pd = _mm256_cmp_pd(back, value, _CMP_GT_OQ);
    const int needs_adjust = _mm256_movemask_pd(needs_adjust_pd);
    _mm_store_si128(reinterpret_cast<__m128i*>(out_i), truncated);
    out_i[0] -= (needs_adjust >> 0) & 1;
    out_i[1] -= (needs_adjust >> 1) & 1;
    out_i[2] -= (needs_adjust >> 2) & 1;
    out_i[3] -= (needs_adjust >> 3) & 1;
    const __m128i floor_i = _mm_load_si128(reinterpret_cast<const __m128i*>(out_i));
    const __m256d floor_d = _mm256_cvtepi32_pd(floor_i);
    return floor_d;
}

struct GradientSet {
    __m256d g000;
    __m256d g100;
    __m256d g010;
    __m256d g110;
    __m256d g001;
    __m256d g101;
    __m256d g011;
    __m256d g111;
};

inline GradientSet lattice_gradients(const std::uint8_t* p,
                                     const int xi[4], const int yi[4], const int zi[4],
                                     __m256d x0, __m256d y0, __m256d z0) noexcept {
    alignas(16) int h000[4], h100[4], h010[4], h110[4], h001[4], h101[4], h011[4], h111[4];
    for (int lane = 0; lane < 4; ++lane) {
        const int A = static_cast<int>(pmap(p, xi[lane])) + yi[lane];
        const int AA = static_cast<int>(pmap(p, A)) + zi[lane];
        const int AB = static_cast<int>(pmap(p, A + 1)) + zi[lane];
        const int B = static_cast<int>(pmap(p, xi[lane] + 1)) + yi[lane];
        const int BA = static_cast<int>(pmap(p, B)) + zi[lane];
        const int BB = static_cast<int>(pmap(p, B + 1)) + zi[lane];
        h000[lane] = static_cast<int>(pmap(p, AA));
        h100[lane] = static_cast<int>(pmap(p, BA));
        h010[lane] = static_cast<int>(pmap(p, AB));
        h110[lane] = static_cast<int>(pmap(p, BB));
        h001[lane] = static_cast<int>(pmap(p, AA + 1));
        h101[lane] = static_cast<int>(pmap(p, BA + 1));
        h011[lane] = static_cast<int>(pmap(p, AB + 1));
        h111[lane] = static_cast<int>(pmap(p, BB + 1));
    }
    const __m256d x1 = _mm256_sub_pd(x0, _mm256_set1_pd(1.0));
    const __m256d y1 = _mm256_sub_pd(y0, _mm256_set1_pd(1.0));
    const __m256d z1 = _mm256_sub_pd(z0, _mm256_set1_pd(1.0));
    return GradientSet{
        grad4(load_hash4(h000), x0, y0, z0),
        grad4(load_hash4(h100), x1, y0, z0),
        grad4(load_hash4(h010), x0, y1, z0),
        grad4(load_hash4(h110), x1, y1, z0),
        grad4(load_hash4(h001), x0, y0, z1),
        grad4(load_hash4(h101), x1, y0, z1),
        grad4(load_hash4(h011), x0, y1, z1),
        grad4(load_hash4(h111), x1, y1, z1),
    };
}

inline GradientSet lattice_gradients_const_xz(const std::uint8_t* p,
                                              int xi, const int yi[4], int zi,
                                              __m256d x0, __m256d y0, __m256d z0) noexcept {
    const int px0 = static_cast<int>(pmap(p, xi));
    const int px1 = static_cast<int>(pmap(p, xi + 1));
    alignas(16) int h000[4], h100[4], h010[4], h110[4], h001[4], h101[4], h011[4], h111[4];
    for (int lane = 0; lane < 4; ++lane) {
        const int A = px0 + yi[lane];
        const int AA = static_cast<int>(pmap(p, A)) + zi;
        const int AB = static_cast<int>(pmap(p, A + 1)) + zi;
        const int B = px1 + yi[lane];
        const int BA = static_cast<int>(pmap(p, B)) + zi;
        const int BB = static_cast<int>(pmap(p, B + 1)) + zi;
        h000[lane] = static_cast<int>(pmap(p, AA));
        h100[lane] = static_cast<int>(pmap(p, BA));
        h010[lane] = static_cast<int>(pmap(p, AB));
        h110[lane] = static_cast<int>(pmap(p, BB));
        h001[lane] = static_cast<int>(pmap(p, AA + 1));
        h101[lane] = static_cast<int>(pmap(p, BA + 1));
        h011[lane] = static_cast<int>(pmap(p, AB + 1));
        h111[lane] = static_cast<int>(pmap(p, BB + 1));
    }
    const __m256d x1 = _mm256_sub_pd(x0, _mm256_set1_pd(1.0));
    const __m256d y1 = _mm256_sub_pd(y0, _mm256_set1_pd(1.0));
    const __m256d z1 = _mm256_sub_pd(z0, _mm256_set1_pd(1.0));
    return GradientSet{
        grad4(load_hash4(h000), x0, y0, z0),
        grad4(load_hash4(h100), x1, y0, z0),
        grad4(load_hash4(h010), x0, y1, z0),
        grad4(load_hash4(h110), x1, y1, z0),
        grad4(load_hash4(h001), x0, y0, z1),
        grad4(load_hash4(h101), x1, y0, z1),
        grad4(load_hash4(h011), x0, y1, z1),
        grad4(load_hash4(h111), x1, y1, z1),
    };
}

inline void sample4(const PerlinNoiseSampler& s,
                    __m256d px, __m256d py, __m256d pz,
                    double y_scale, double y_max, bool scaled,
                    double* out) noexcept {
    alignas(16) int xi[4], yi[4], zi[4];
    const __m256d xfloor = floor_lanes(px, xi);
    const __m256d yfloor = floor_lanes(py, yi);
    const __m256d zfloor = floor_lanes(pz, zi);

    const __m256d local_x = _mm256_sub_pd(px, xfloor);
    const __m256d local_y_original = _mm256_sub_pd(py, yfloor);
    const __m256d local_z = _mm256_sub_pd(pz, zfloor);
    __m256d local_y = local_y_original;

    if (scaled && y_scale != 0.0) {
        const __m256d y_scale_v = _mm256_set1_pd(y_scale);
        const __m256d epsilon = _mm256_set1_pd(1.0e-7);
        const __m256d capped = y_max >= 0.0
            ? _mm256_min_pd(local_y_original, _mm256_set1_pd(y_max))
            : local_y_original;
        const __m256d scaled_offset = _mm256_mul_pd(
            _mm256_floor_pd(_mm256_add_pd(_mm256_div_pd(capped, y_scale_v), epsilon)),
            y_scale_v);
        local_y = _mm256_sub_pd(local_y_original, scaled_offset);
    }

    const GradientSet g = lattice_gradients(s.permutation, xi, yi, zi, local_x, local_y, local_z);

    const __m256d u = smooth_step(local_x);
    const __m256d v = smooth_step(local_y_original);
    const __m256d w = smooth_step(local_z);
    const __m256d x00 = lerp(u, g.g000, g.g100);
    const __m256d x10 = lerp(u, g.g010, g.g110);
    const __m256d x01 = lerp(u, g.g001, g.g101);
    const __m256d x11 = lerp(u, g.g011, g.g111);
    const __m256d y0 = lerp(v, x00, x10);
    const __m256d y1 = lerp(v, x01, x11);
    _mm256_storeu_pd(out, lerp(w, y0, y1));
}

inline void sample4_arrays(const PerlinNoiseSampler& s,
                           const double* x, const double* y, const double* z,
                           double y_scale, double y_max, bool scaled,
                           double* out) noexcept {
    sample4(s,
            _mm256_add_pd(_mm256_loadu_pd(x), _mm256_set1_pd(s.origin_x)),
            _mm256_add_pd(_mm256_loadu_pd(y), _mm256_set1_pd(s.origin_y)),
            _mm256_add_pd(_mm256_loadu_pd(z), _mm256_set1_pd(s.origin_z)),
            y_scale, y_max, scaled, out);
}

inline void sample4_const_xz(const PerlinNoiseSampler& s,
                             int xi, int zi,
                             double xf, double zf,
                             __m256d u, __m256d w,
                             __m256d y,
                             double* out) noexcept {
    const __m256d py = _mm256_add_pd(y, _mm256_set1_pd(s.origin_y));

    alignas(16) int yi[4];
    const __m256d yfloor = floor_lanes(py, yi);
    const __m256d local_y = _mm256_sub_pd(py, yfloor);
    const GradientSet g = lattice_gradients_const_xz(s.permutation, xi, yi, zi,
                                                     _mm256_set1_pd(xf), local_y, _mm256_set1_pd(zf));

    const __m256d v = smooth_step(local_y);
    const __m256d x00 = lerp(u, g.g000, g.g100);
    const __m256d x10 = lerp(u, g.g010, g.g110);
    const __m256d x01 = lerp(u, g.g001, g.g101);
    const __m256d x11 = lerp(u, g.g011, g.g111);
    const __m256d y0 = lerp(v, x00, x10);
    const __m256d y1 = lerp(v, x01, x11);
    _mm256_storeu_pd(out, lerp(w, y0, y1));
}

#if defined(_MSC_VER)
#pragma warning(push)
#pragma warning(disable: 4324) // structure padded due to alignment specifier - intentional for AVX2
#endif
struct ColumnXZState {
    int xi;
    int zi;
    double xf;
    double zf;
    __m256d u;
    __m256d w;
};
#if defined(_MSC_VER)
#pragma warning(pop)
#endif

inline ColumnXZState make_column_xz_state(const PerlinNoiseSampler& s, double x, double z) noexcept {
    const double px = x + s.origin_x;
    const double pz = z + s.origin_z;
    const int xi0 = floor_to_int(px);
    const int zi0 = floor_to_int(pz);
    const double xf0 = px - static_cast<double>(xi0);
    const double zf0 = pz - static_cast<double>(zi0);
    ColumnXZState state{xi0, zi0, xf0, zf0, smooth_step(_mm256_set1_pd(xf0)), smooth_step(_mm256_set1_pd(zf0))};
    return state;
}

} // namespace

void sample_batch_avx2(const PerlinNoiseSampler& s,
                       const double* x, const double* y, const double* z,
                       std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) sample4_arrays(s, x + i, y + i, z + i, 0.0, 0.0, false, out + i);
    if (i < count) sample_batch_scalar(s, x + i, y + i, z + i, count - i, out + i);
}

void sample_y_column_avx2(const PerlinNoiseSampler& s,
                          double x, double y0, double z, double dy,
                          std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    const ColumnXZState state = make_column_xz_state(s, x, z);
    const __m256d offsets = _mm256_set_pd(3.0 * dy, 2.0 * dy, dy, 0.0);
    const __m256d dy4 = _mm256_set1_pd(4.0 * dy);
    __m256d yv = _mm256_add_pd(_mm256_set1_pd(y0), offsets);
    for (; i + 4 <= count; i += 4) {
        sample4_const_xz(s, state.xi, state.zi, state.xf, state.zf, state.u, state.w, yv, out + i);
        yv = _mm256_add_pd(yv, dy4);
    }
    if (i < count) sample_y_column_scalar(s, x, y0 + static_cast<double>(i) * dy, z, dy, count - i, out + i);
}

void sample_y_array_avx2(const PerlinNoiseSampler& s,
                         double x, const double* y, double z,
                         std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    const ColumnXZState state = make_column_xz_state(s, x, z);
    for (; i + 4 <= count; i += 4) {
        sample4_const_xz(s, state.xi, state.zi, state.xf, state.zf, state.u, state.w, _mm256_loadu_pd(y + i), out + i);
    }
    if (i < count) sample_y_array_scalar(s, x, y + i, z, count - i, out + i);
}

void sample_y_scaled_batch_avx2(const PerlinNoiseSampler& s,
                                const double* x, const double* y, const double* z,
                                double y_scale, double y_max,
                                std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 4 <= count; i += 4) sample4_arrays(s, x + i, y + i, z + i, y_scale, y_max, true, out + i);
    if (i < count) sample_y_scaled_batch_scalar(s, x + i, y + i, z + i, y_scale, y_max, count - i, out + i);
}

} // namespace lattice::world::gen::noise
