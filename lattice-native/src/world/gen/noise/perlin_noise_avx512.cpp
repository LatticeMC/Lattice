#include "world/gen/noise/perlin_noise.hpp"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <immintrin.h>

// Keep the ZMM arithmetic as separate multiply/add operations. The scalar
// reference and Java bytecode rely on their individual rounding points.
#if defined(__clang__)
#pragma clang fp contract(off)
#endif

namespace lattice::world::gen::noise {
namespace {

inline std::uint32_t pmap(const std::uint8_t* p, int input) noexcept {
    return p[input & 0xFF];
}

inline int floor_to_int(double x) noexcept {
    const int i = static_cast<int>(x);
    return (static_cast<double>(i) <= x) ? i : i - 1;
}

inline __m512d smooth_step(__m512d t) noexcept {
    const __m512d six = _mm512_set1_pd(6.0);
    const __m512d fifteen = _mm512_set1_pd(15.0);
    const __m512d ten = _mm512_set1_pd(10.0);
    const __m512d t2 = _mm512_mul_pd(t, t);
    const __m512d t3 = _mm512_mul_pd(t2, t);
    const __m512d inner = _mm512_sub_pd(_mm512_mul_pd(t, six), fifteen);
    return _mm512_mul_pd(t3, _mm512_add_pd(_mm512_mul_pd(t, inner), ten));
}

inline __m512d lerp(__m512d t, __m512d a, __m512d b) noexcept {
    return _mm512_add_pd(a, _mm512_mul_pd(t, _mm512_sub_pd(b, a)));
}

inline __m512d load_hashes(const int hashes[8]) noexcept {
    alignas(64) double h[8];
    for (int i = 0; i < 8; ++i) h[i] = static_cast<double>(hashes[i] & 15);
    return _mm512_load_pd(h);
}

inline __m512d grad8(const int hashes[8], __m512d x, __m512d y, __m512d z) noexcept {
    alignas(64) int h[8];
    for (int i = 0; i < 8; ++i) h[i] = hashes[i] & 15;
    const __m512d hv = load_hashes(h);
    const __mmask8 h_lt8 = _mm512_cmp_pd_mask(hv, _mm512_set1_pd(8.0), _CMP_LT_OQ);
    const __mmask8 h_lt4 = _mm512_cmp_pd_mask(hv, _mm512_set1_pd(4.0), _CMP_LT_OQ);
    const __mmask8 h_eq12 = _mm512_cmp_pd_mask(hv, _mm512_set1_pd(12.0), _CMP_EQ_OQ);
    const __mmask8 h_eq14 = _mm512_cmp_pd_mask(hv, _mm512_set1_pd(14.0), _CMP_EQ_OQ);
    const __mmask8 h_eq12or14 = h_eq12 | h_eq14;

    const __m512d u = _mm512_mask_blend_pd(h_lt8, y, x);
    const __m512d v_non_xy = _mm512_mask_blend_pd(h_eq12or14, z, x);
    const __m512d v = _mm512_mask_blend_pd(h_lt4, v_non_xy, y);

    __mmask8 neg_u = 0;
    __mmask8 neg_v = 0;
    for (int i = 0; i < 8; ++i) {
        if (h[i] & 1) neg_u |= static_cast<__mmask8>(1u << i);
        if (h[i] & 2) neg_v |= static_cast<__mmask8>(1u << i);
    }
    const __m512d sign_bit = _mm512_set1_pd(-0.0);
    const __m512d su = _mm512_mask_blend_pd(neg_u, u, _mm512_xor_pd(u, sign_bit));
    const __m512d sv = _mm512_mask_blend_pd(neg_v, v, _mm512_xor_pd(v, sign_bit));
    return _mm512_add_pd(su, sv);
}

inline __m512d floor_lanes(__m512d value, int out_i[8]) noexcept {
    alignas(64) double values[8];
    alignas(64) double floors[8];
    _mm512_store_pd(values, value);
    for (int i = 0; i < 8; ++i) {
        out_i[i] = floor_to_int(values[i]);
        floors[i] = static_cast<double>(out_i[i]);
    }
    return _mm512_load_pd(floors);
}

struct GradientSet {
    __m512d g000, g100, g010, g110;
    __m512d g001, g101, g011, g111;
};

inline GradientSet lattice_gradients(const std::uint8_t* p,
                                     const int xi[8], const int yi[8], const int zi[8],
                                     __m512d x0, __m512d y0, __m512d z0) noexcept {
    int h000[8], h100[8], h010[8], h110[8];
    int h001[8], h101[8], h011[8], h111[8];
    for (int lane = 0; lane < 8; ++lane) {
        const int a = static_cast<int>(pmap(p, xi[lane])) + yi[lane];
        const int aa = static_cast<int>(pmap(p, a)) + zi[lane];
        const int ab = static_cast<int>(pmap(p, a + 1)) + zi[lane];
        const int b = static_cast<int>(pmap(p, xi[lane] + 1)) + yi[lane];
        const int ba = static_cast<int>(pmap(p, b)) + zi[lane];
        const int bb = static_cast<int>(pmap(p, b + 1)) + zi[lane];
        h000[lane] = static_cast<int>(pmap(p, aa));
        h100[lane] = static_cast<int>(pmap(p, ba));
        h010[lane] = static_cast<int>(pmap(p, ab));
        h110[lane] = static_cast<int>(pmap(p, bb));
        h001[lane] = static_cast<int>(pmap(p, aa + 1));
        h101[lane] = static_cast<int>(pmap(p, ba + 1));
        h011[lane] = static_cast<int>(pmap(p, ab + 1));
        h111[lane] = static_cast<int>(pmap(p, bb + 1));
    }
    const __m512d x1 = _mm512_sub_pd(x0, _mm512_set1_pd(1.0));
    const __m512d y1 = _mm512_sub_pd(y0, _mm512_set1_pd(1.0));
    const __m512d z1 = _mm512_sub_pd(z0, _mm512_set1_pd(1.0));
    return GradientSet{
        grad8(h000, x0, y0, z0), grad8(h100, x1, y0, z0),
        grad8(h010, x0, y1, z0), grad8(h110, x1, y1, z0),
        grad8(h001, x0, y0, z1), grad8(h101, x1, y0, z1),
        grad8(h011, x0, y1, z1), grad8(h111, x1, y1, z1),
    };
}

inline GradientSet lattice_gradients_const_xz(const std::uint8_t* p,
                                              int xi, const int yi[8], int zi,
                                              __m512d x0, __m512d y0, __m512d z0) noexcept {
    const int px0 = static_cast<int>(pmap(p, xi));
    const int px1 = static_cast<int>(pmap(p, xi + 1));
    int h000[8], h100[8], h010[8], h110[8];
    int h001[8], h101[8], h011[8], h111[8];
    for (int lane = 0; lane < 8; ++lane) {
        const int a = px0 + yi[lane];
        const int aa = static_cast<int>(pmap(p, a)) + zi;
        const int ab = static_cast<int>(pmap(p, a + 1)) + zi;
        const int b = px1 + yi[lane];
        const int ba = static_cast<int>(pmap(p, b)) + zi;
        const int bb = static_cast<int>(pmap(p, b + 1)) + zi;
        h000[lane] = static_cast<int>(pmap(p, aa));
        h100[lane] = static_cast<int>(pmap(p, ba));
        h010[lane] = static_cast<int>(pmap(p, ab));
        h110[lane] = static_cast<int>(pmap(p, bb));
        h001[lane] = static_cast<int>(pmap(p, aa + 1));
        h101[lane] = static_cast<int>(pmap(p, ba + 1));
        h011[lane] = static_cast<int>(pmap(p, ab + 1));
        h111[lane] = static_cast<int>(pmap(p, bb + 1));
    }
    const __m512d x1 = _mm512_sub_pd(x0, _mm512_set1_pd(1.0));
    const __m512d y1 = _mm512_sub_pd(y0, _mm512_set1_pd(1.0));
    const __m512d z1 = _mm512_sub_pd(z0, _mm512_set1_pd(1.0));
    return GradientSet{
        grad8(h000, x0, y0, z0), grad8(h100, x1, y0, z0),
        grad8(h010, x0, y1, z0), grad8(h110, x1, y1, z0),
        grad8(h001, x0, y0, z1), grad8(h101, x1, y0, z1),
        grad8(h011, x0, y1, z1), grad8(h111, x1, y1, z1),
    };
}

inline void sample8(const PerlinNoiseSampler& s,
                    __m512d px, __m512d py, __m512d pz,
                    double y_scale, double y_max, bool scaled,
                    double* out) noexcept {
    int xi[8], yi[8], zi[8];
    const __m512d xfloor = floor_lanes(px, xi);
    const __m512d yfloor = floor_lanes(py, yi);
    const __m512d zfloor = floor_lanes(pz, zi);
    const __m512d local_x = _mm512_sub_pd(px, xfloor);
    const __m512d local_y_original = _mm512_sub_pd(py, yfloor);
    const __m512d local_z = _mm512_sub_pd(pz, zfloor);
    __m512d local_y = local_y_original;
    if (scaled && y_scale != 0.0) {
        const __m512d scale = _mm512_set1_pd(y_scale);
        const __m512d capped = y_max >= 0.0
            ? _mm512_min_pd(local_y_original, _mm512_set1_pd(y_max))
            : local_y_original;
        const __m512d quantized = _mm512_floor_pd(
            _mm512_add_pd(_mm512_div_pd(capped, scale), _mm512_set1_pd(1.0e-7)));
        local_y = _mm512_sub_pd(local_y_original, _mm512_mul_pd(quantized, scale));
    }
    const GradientSet g = lattice_gradients(s.permutation, xi, yi, zi,
                                             local_x, local_y, local_z);
    const __m512d u = smooth_step(local_x);
    const __m512d v = smooth_step(local_y_original);
    const __m512d w = smooth_step(local_z);
    const __m512d x00 = lerp(u, g.g000, g.g100);
    const __m512d x10 = lerp(u, g.g010, g.g110);
    const __m512d x01 = lerp(u, g.g001, g.g101);
    const __m512d x11 = lerp(u, g.g011, g.g111);
    const __m512d y0 = lerp(v, x00, x10);
    const __m512d y1 = lerp(v, x01, x11);
    _mm512_storeu_pd(out, lerp(w, y0, y1));
}

inline void sample8_arrays(const PerlinNoiseSampler& s,
                           const double* x, const double* y, const double* z,
                           double y_scale, double y_max, bool scaled,
                           double* out) noexcept {
    sample8(s,
            _mm512_add_pd(_mm512_loadu_pd(x), _mm512_set1_pd(s.origin_x)),
            _mm512_add_pd(_mm512_loadu_pd(y), _mm512_set1_pd(s.origin_y)),
            _mm512_add_pd(_mm512_loadu_pd(z), _mm512_set1_pd(s.origin_z)),
            y_scale, y_max, scaled, out);
}

inline void sample8_arrays_ymax(const PerlinNoiseSampler& s,
                                const double* x, const double* y, const double* z,
                                double y_scale, const double* y_max,
                                double* out) noexcept {
    const __m512d px = _mm512_add_pd(_mm512_loadu_pd(x), _mm512_set1_pd(s.origin_x));
    const __m512d py = _mm512_add_pd(_mm512_loadu_pd(y), _mm512_set1_pd(s.origin_y));
    const __m512d pz = _mm512_add_pd(_mm512_loadu_pd(z), _mm512_set1_pd(s.origin_z));
    int xi[8], yi[8], zi[8];
    const __m512d xfloor = floor_lanes(px, xi);
    const __m512d yfloor = floor_lanes(py, yi);
    const __m512d zfloor = floor_lanes(pz, zi);
    const __m512d local_x = _mm512_sub_pd(px, xfloor);
    const __m512d local_y_original = _mm512_sub_pd(py, yfloor);
    const __m512d local_z = _mm512_sub_pd(pz, zfloor);
    __m512d local_y = local_y_original;
    if (y_scale != 0.0) {
        const __m512d ymax = _mm512_loadu_pd(y_max);
        const __mmask8 use = _mm512_cmp_pd_mask(ymax, _mm512_setzero_pd(), _CMP_GE_OQ)
                           & _mm512_cmp_pd_mask(ymax, local_y_original, _CMP_LT_OQ);
        const __m512d capped = _mm512_mask_blend_pd(use, local_y_original, ymax);
        const __m512d scale = _mm512_set1_pd(y_scale);
        const __m512d quantized = _mm512_floor_pd(
            _mm512_add_pd(_mm512_div_pd(capped, scale), _mm512_set1_pd(1.0e-7)));
        local_y = _mm512_sub_pd(local_y_original, _mm512_mul_pd(quantized, scale));
    }
    const GradientSet g = lattice_gradients(s.permutation, xi, yi, zi,
                                             local_x, local_y, local_z);
    const __m512d u = smooth_step(local_x);
    const __m512d v = smooth_step(local_y_original);
    const __m512d w = smooth_step(local_z);
    const __m512d x00 = lerp(u, g.g000, g.g100);
    const __m512d x10 = lerp(u, g.g010, g.g110);
    const __m512d x01 = lerp(u, g.g001, g.g101);
    const __m512d x11 = lerp(u, g.g011, g.g111);
    const __m512d y0 = lerp(v, x00, x10);
    const __m512d y1 = lerp(v, x01, x11);
    _mm512_storeu_pd(out, lerp(w, y0, y1));
}

struct ColumnState { int xi, zi; double xf, zf; __m512d u, w; };

inline ColumnState make_column_state(const PerlinNoiseSampler& s, double x, double z) noexcept {
    const double px = x + s.origin_x;
    const double pz = z + s.origin_z;
    const int xi = floor_to_int(px);
    const int zi = floor_to_int(pz);
    const double xf = px - static_cast<double>(xi);
    const double zf = pz - static_cast<double>(zi);
    return ColumnState{xi, zi, xf, zf,
                       smooth_step(_mm512_set1_pd(xf)),
                       smooth_step(_mm512_set1_pd(zf))};
}

inline void sample8_const_xz(const PerlinNoiseSampler& s, const ColumnState& c,
                             __m512d y, double* out) noexcept {
    const __m512d py = _mm512_add_pd(y, _mm512_set1_pd(s.origin_y));
    int yi[8];
    const __m512d yfloor = floor_lanes(py, yi);
    const __m512d local_y = _mm512_sub_pd(py, yfloor);
    const GradientSet g = lattice_gradients_const_xz(s.permutation, c.xi, yi, c.zi,
                                                     _mm512_set1_pd(c.xf), local_y,
                                                     _mm512_set1_pd(c.zf));
    const __m512d v = smooth_step(local_y);
    const __m512d x00 = lerp(c.u, g.g000, g.g100);
    const __m512d x10 = lerp(c.u, g.g010, g.g110);
    const __m512d x01 = lerp(c.u, g.g001, g.g101);
    const __m512d x11 = lerp(c.u, g.g011, g.g111);
    _mm512_storeu_pd(out, lerp(c.w, lerp(v, x00, x10), lerp(v, x01, x11)));
}

inline void sample8_const_xz_ymax(const PerlinNoiseSampler& s, const ColumnState& c,
                                  __m512d y, double y_scale, const double* y_max,
                                  double* out) noexcept {
    const __m512d py = _mm512_add_pd(y, _mm512_set1_pd(s.origin_y));
    int yi[8];
    const __m512d yfloor = floor_lanes(py, yi);
    const __m512d local_y_original = _mm512_sub_pd(py, yfloor);
    __m512d local_y = local_y_original;
    if (y_scale != 0.0) {
        const __m512d ymax = _mm512_loadu_pd(y_max);
        const __mmask8 use = _mm512_cmp_pd_mask(ymax, _mm512_setzero_pd(), _CMP_GE_OQ)
                           & _mm512_cmp_pd_mask(ymax, local_y_original, _CMP_LT_OQ);
        const __m512d capped = _mm512_mask_blend_pd(use, local_y_original, ymax);
        const __m512d scale = _mm512_set1_pd(y_scale);
        const __m512d quantized = _mm512_floor_pd(
            _mm512_add_pd(_mm512_div_pd(capped, scale), _mm512_set1_pd(1.0e-7)));
        local_y = _mm512_sub_pd(local_y_original, _mm512_mul_pd(quantized, scale));
    }
    const GradientSet g = lattice_gradients_const_xz(s.permutation, c.xi, yi, c.zi,
                                                     _mm512_set1_pd(c.xf), local_y,
                                                     _mm512_set1_pd(c.zf));
    const __m512d v = smooth_step(local_y_original);
    const __m512d x00 = lerp(c.u, g.g000, g.g100);
    const __m512d x10 = lerp(c.u, g.g010, g.g110);
    const __m512d x01 = lerp(c.u, g.g001, g.g101);
    const __m512d x11 = lerp(c.u, g.g011, g.g111);
    _mm512_storeu_pd(out, lerp(c.w, lerp(v, x00, x10), lerp(v, x01, x11)));
}

inline void finish_avx512() noexcept { _mm256_zeroupper(); }

} // namespace

void sample_batch_avx512(const PerlinNoiseSampler& s, const double* x, const double* y,
                         const double* z, std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8)
        sample8_arrays(s, x + i, y + i, z + i, 0.0, 0.0, false, out + i);
    if (i < count) sample_batch_scalar(s, x + i, y + i, z + i, count - i, out + i);
    finish_avx512();
}

void sample_y_column_avx512(const PerlinNoiseSampler& s, double x, double y0, double z,
                            double dy, std::size_t count, double* out) noexcept {
    const ColumnState c = make_column_state(s, x, z);
    const __m512d offsets = _mm512_set_pd(7.0 * dy, 6.0 * dy, 5.0 * dy, 4.0 * dy,
                                          3.0 * dy, 2.0 * dy, dy, 0.0);
    const __m512d dy8 = _mm512_set1_pd(8.0 * dy);
    __m512d yv = _mm512_add_pd(_mm512_set1_pd(y0), offsets);
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8) {
        sample8_const_xz(s, c, yv, out + i);
        yv = _mm512_add_pd(yv, dy8);
    }
    if (i < count) sample_y_column_scalar(s, x, y0 + static_cast<double>(i) * dy, z, dy, count - i, out + i);
    finish_avx512();
}

void sample_y_array_avx512(const PerlinNoiseSampler& s, double x, const double* y, double z,
                           std::size_t count, double* out) noexcept {
    const ColumnState c = make_column_state(s, x, z);
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8) sample8_const_xz(s, c, _mm512_loadu_pd(y + i), out + i);
    if (i < count) sample_y_array_scalar(s, x, y + i, z, count - i, out + i);
    finish_avx512();
}

void sample_y_scaled_batch_avx512(const PerlinNoiseSampler& s, const double* x,
                                  const double* y, const double* z, double y_scale,
                                  double y_max, std::size_t count, double* out) noexcept {
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8)
        sample8_arrays(s, x + i, y + i, z + i, y_scale, y_max, true, out + i);
    if (i < count) sample_y_scaled_batch_scalar(s, x + i, y + i, z + i, y_scale, y_max, count - i, out + i);
    finish_avx512();
}

void sample_y_scaled_batch_ymax_avx512(const PerlinNoiseSampler& s, const double* x,
                                       const double* y, const double* z, double y_scale,
                                       const double* y_max, std::size_t count,
                                       double* out) noexcept {
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8)
        sample8_arrays_ymax(s, x + i, y + i, z + i, y_scale, y_max + i, out + i);
    if (i < count) sample_y_scaled_batch_ymax_scalar(s, x + i, y + i, z + i, y_scale, y_max + i, count - i, out + i);
    finish_avx512();
}

void sample_y_scaled_array_ymax_avx512(const PerlinNoiseSampler& s, double x, const double* y,
                                       double z, double y_scale, const double* y_max,
                                       std::size_t count, double* out) noexcept {
    const ColumnState c = make_column_state(s, x, z);
    std::size_t i = 0;
    for (; i + 8 <= count; i += 8)
        sample8_const_xz_ymax(s, c, _mm512_loadu_pd(y + i), y_scale, y_max + i, out + i);
    if (i < count) sample_y_scaled_array_ymax_scalar(s, x, y + i, z, y_scale, y_max + i, count - i, out + i);
    finish_avx512();
}

} // namespace lattice::world::gen::noise
