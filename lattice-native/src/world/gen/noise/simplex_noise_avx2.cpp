#include "world/gen/noise/simplex_noise.hpp"

#include <cstddef>
#include <cstdint>
#include <immintrin.h>

namespace lattice::world::gen::noise {
namespace {

constexpr int kGradients[12][3] = {
    { 1, 1, 0}, {-1, 1, 0}, { 1,-1, 0}, {-1,-1, 0},
    { 1, 0, 1}, {-1, 0, 1}, { 1, 0,-1}, {-1, 0,-1},
    { 0, 1, 1}, { 0,-1, 1}, { 0, 1,-1}, { 0,-1,-1},
};

inline int floor_to_int(double x) noexcept {
    const int i = static_cast<int>(x);
    return (static_cast<double>(i) <= x) ? i : i - 1;
}

inline int pmap(const SimplexNoiseSampler& s, int input) noexcept {
    return s.permutation[input & 0xFF];
}

inline int pmap_mod12(const SimplexNoiseSampler& s, int input) noexcept {
    return static_cast<std::uint8_t>(pmap(s, input)) % 12;
}

inline double dot2(int gi, double x, double y) noexcept {
    return static_cast<double>(kGradients[gi][0]) * x + static_cast<double>(kGradients[gi][1]) * y;
}

inline double dot3(int gi, double x, double y, double z) noexcept {
    return static_cast<double>(kGradients[gi][0]) * x
         + static_cast<double>(kGradients[gi][1]) * y
         + static_cast<double>(kGradients[gi][2]) * z;
}

inline double corner(int gi, double x, double y) noexcept {
    double t = 0.5 - x * x - y * y;
    if (t < 0.0) return 0.0;
    t *= t;
    return t * t * dot2(gi, x, y);
}

inline double contrib3(int gi, double x, double y, double z) noexcept {
    double t = 0.6 - x * x - y * y - z * z;
    if (t < 0.0) return 0.0;
    t *= t;
    return t * t * dot3(gi, x, y, z);
}

inline void sample4_2d(const SimplexNoiseSampler& s, const double* x, const double* y, double* out) noexcept {
    constexpr double F2 = 0.36602540378443864;
    constexpr double G2 = 0.21132486540518713;
    const __m256d vx = _mm256_loadu_pd(x);
    const __m256d vy = _mm256_loadu_pd(y);
    const __m256d skew = _mm256_mul_pd(_mm256_add_pd(vx, vy), _mm256_set1_pd(F2));

    alignas(32) double sx_lanes[4];
    alignas(32) double sy_lanes[4];
    alignas(32) double x0_lanes[4];
    alignas(32) double y0_lanes[4];
    _mm256_store_pd(sx_lanes, _mm256_add_pd(vx, skew));
    _mm256_store_pd(sy_lanes, _mm256_add_pd(vy, skew));

    int ii[4];
    int jj[4];
    for (int lane = 0; lane < 4; ++lane) {
        ii[lane] = floor_to_int(sx_lanes[lane]);
        jj[lane] = floor_to_int(sy_lanes[lane]);
        const double t = static_cast<double>(ii[lane] + jj[lane]) * G2;
        x0_lanes[lane] = x[lane] - (static_cast<double>(ii[lane]) - t);
        y0_lanes[lane] = y[lane] - (static_cast<double>(jj[lane]) - t);
    }

    for (int lane = 0; lane < 4; ++lane) {
        const double x0 = x0_lanes[lane];
        const double y0 = y0_lanes[lane];
        const int i1 = x0 > y0 ? 1 : 0;
        const int j1 = x0 > y0 ? 0 : 1;
        const double x1 = x0 - static_cast<double>(i1) + G2;
        const double y1 = y0 - static_cast<double>(j1) + G2;
        const double x2 = x0 - 1.0 + 2.0 * G2;
        const double y2 = y0 - 1.0 + 2.0 * G2;
        const int gi0 = pmap_mod12(s, ii[lane] + pmap(s, jj[lane]));
        const int gi1 = pmap_mod12(s, ii[lane] + i1 + pmap(s, jj[lane] + j1));
        const int gi2 = pmap_mod12(s, ii[lane] + 1 + pmap(s, jj[lane] + 1));
        out[lane] = 70.0 * (corner(gi0, x0, y0) + corner(gi1, x1, y1) + corner(gi2, x2, y2));
    }
}

inline void sample4_3d(const SimplexNoiseSampler& s,
                       const double* x, const double* y, const double* z,
                       double* out) noexcept {
    constexpr double F3 = 1.0 / 3.0;
    constexpr double G3 = 1.0 / 6.0;
    const __m256d vx = _mm256_add_pd(_mm256_loadu_pd(x), _mm256_set1_pd(s.origin_x));
    const __m256d vy = _mm256_add_pd(_mm256_loadu_pd(y), _mm256_set1_pd(s.origin_y));
    const __m256d vz = _mm256_add_pd(_mm256_loadu_pd(z), _mm256_set1_pd(s.origin_z));
    const __m256d skew = _mm256_mul_pd(_mm256_add_pd(_mm256_add_pd(vx, vy), vz), _mm256_set1_pd(F3));

    alignas(32) double sx_lanes[4];
    alignas(32) double sy_lanes[4];
    alignas(32) double sz_lanes[4];
    alignas(32) double xx[4];
    alignas(32) double yy[4];
    alignas(32) double zz[4];
    _mm256_store_pd(sx_lanes, _mm256_add_pd(vx, skew));
    _mm256_store_pd(sy_lanes, _mm256_add_pd(vy, skew));
    _mm256_store_pd(sz_lanes, _mm256_add_pd(vz, skew));
    _mm256_store_pd(xx, vx);
    _mm256_store_pd(yy, vy);
    _mm256_store_pd(zz, vz);

    for (int lane = 0; lane < 4; ++lane) {
        const int i = floor_to_int(sx_lanes[lane]);
        const int j = floor_to_int(sy_lanes[lane]);
        const int k = floor_to_int(sz_lanes[lane]);
        const double t = static_cast<double>(i + j + k) * G3;
        const double x0 = xx[lane] - (static_cast<double>(i) - t);
        const double y0 = yy[lane] - (static_cast<double>(j) - t);
        const double z0 = zz[lane] - (static_cast<double>(k) - t);

        int i1, j1, k1;
        int i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0)      { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
            else               { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
        } else {
            if (y0 < z0)       { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
            else if (x0 < z0)  { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
            else               { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        }

        const double x1 = x0 - static_cast<double>(i1) + G3;
        const double y1 = y0 - static_cast<double>(j1) + G3;
        const double z1 = z0 - static_cast<double>(k1) + G3;
        const double x2 = x0 - static_cast<double>(i2) + 2.0 * G3;
        const double y2 = y0 - static_cast<double>(j2) + 2.0 * G3;
        const double z2 = z0 - static_cast<double>(k2) + 2.0 * G3;
        const double x3 = x0 - 1.0 + 3.0 * G3;
        const double y3 = y0 - 1.0 + 3.0 * G3;
        const double z3 = z0 - 1.0 + 3.0 * G3;

        const int gi0 = pmap_mod12(s, i + pmap(s, j + pmap(s, k)));
        const int gi1 = pmap_mod12(s, i + i1 + pmap(s, j + j1 + pmap(s, k + k1)));
        const int gi2 = pmap_mod12(s, i + i2 + pmap(s, j + j2 + pmap(s, k + k2)));
        const int gi3 = pmap_mod12(s, i + 1 + pmap(s, j + 1 + pmap(s, k + 1)));

        out[lane] = 32.0 * (contrib3(gi0, x0, y0, z0)
                          + contrib3(gi1, x1, y1, z1)
                          + contrib3(gi2, x2, y2, z2)
                          + contrib3(gi3, x3, y3, z3));
    }
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
