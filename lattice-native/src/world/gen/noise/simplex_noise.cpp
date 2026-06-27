// Simplex noise sampler (Ken Perlin 2001), Mojang-flavoured. See
// simplex_noise.hpp.
//
// References:
//   - Ken Perlin, "Noise Hardware" 2001 chapter
//   - Stefan Gustavson, "Simplex noise demystified" 2005
//   - Mojang's SimplexNoiseSampler (class_3541)
//
// The Mojang implementation uses a specific 12-vector gradient table
// (same as 3D Perlin), with the simplex topology to avoid the lattice
// artifacts of classic Perlin noise.

#include "world/gen/noise/simplex_noise.hpp"

#include <array>
#include <cmath>

namespace lattice::world::gen::noise {

namespace {

// 12 unit vectors in 3D (same as 3D Perlin gradients).
constexpr int kGradients[12][3] = {
    { 1, 1, 0}, {-1, 1, 0}, { 1,-1, 0}, {-1,-1, 0},
    { 1, 0, 1}, {-1, 0, 1}, { 1, 0,-1}, {-1, 0,-1},
    { 0, 1, 1}, { 0,-1, 1}, { 0, 1,-1}, { 0,-1,-1},
};

constexpr std::array<int, 256> make_permutation_mod12() noexcept {
    std::array<int, 256> out{};
    for (int i = 0; i < 256; ++i) {
        out[static_cast<std::size_t>(i)] = i % 12;
    }
    return out;
}

constexpr auto kPermutationMod12 = make_permutation_mod12();

// Mojang constants. SKEW = 0.5 * (sqrt(3) - 1), UNSKEW = (3 - sqrt(3)) / 6.
// We compute them at compile time from std::sqrt is technically not
// constexpr in C++20 — use the closed-form expansion below.
inline double SKEW()   noexcept {
    // 0.5 * (sqrt(3) - 1) ≈ 0.36602540378443864
    return 0.36602540378443864;
}
inline double UNSKEW() noexcept {
    // (3 - sqrt(3)) / 6 ≈ 0.21132486540518713
    return 0.21132486540518713;
}

inline int floor_to_int(double x) noexcept {
    const int i = static_cast<int>(x);
    return (static_cast<double>(i) <= x) ? i : i - 1;
}

// pmap from PerlinNoise's style — but here `permutation[]` is int, not
// byte. Mojang stores int because it bakes the `& 255` and additional
// arithmetic into precomputed values.
inline int pmap(const SimplexNoiseSampler& s, int input) noexcept {
    return s.permutation[input & 0xFF];
}

inline int pmap_mod12(const SimplexNoiseSampler& s, int input) noexcept {
    return kPermutationMod12[static_cast<std::uint8_t>(pmap(s, input))];
}

// `dot` of a gradient index with (x, y, z).
inline double dot3(const int* g, double x, double y, double z) noexcept {
    return g[0] * x + g[1] * y + g[2] * z;
}

inline double dot2(const int* g, double x, double y) noexcept {
    return g[0] * x + g[1] * y;
}

} // namespace

double sample_2d(const SimplexNoiseSampler& s, double x, double y) noexcept {
    // Skew the input space.
    const double F2 = SKEW();
    const double G2 = UNSKEW();
    const double sk = (x + y) * F2;
    const int    i  = floor_to_int(x + sk);
    const int    j  = floor_to_int(y + sk);
    const double t  = (i + j) * G2;
    const double X0 = i - t;
    const double Y0 = j - t;
    const double x0 = x - X0;
    const double y0 = y - Y0;

    // Determine which simplex (which sub-triangle) we're in.
    int i1, j1;
    if (x0 > y0) { i1 = 1; j1 = 0; }
    else         { i1 = 0; j1 = 1; }

    // Offsets for middle and last corner in (x, y) coords.
    const double x1 = x0 - i1 + G2;
    const double y1 = y0 - j1 + G2;
    const double x2 = x0 - 1.0 + 2.0 * G2;
    const double y2 = y0 - 1.0 + 2.0 * G2;

    // Hashed gradient indices of the three corners. pmap returns
    // [0, 255], so we can use a precomputed mod-12 lookup directly.
    const int gi0 = pmap_mod12(s, i + pmap(s, j));
    const int gi1 = pmap_mod12(s, i + i1 + pmap(s, j + j1));
    const int gi2 = pmap_mod12(s, i + 1 + pmap(s, j + 1));

    auto corner = [](int gi, double xc, double yc) noexcept {
        double t_ = std::max(0.0, 0.5 - xc * xc - yc * yc);
        t_ *= t_;
        return t_ * t_ * dot2(kGradients[gi], xc, yc);
    };

    const double n0 = corner(gi0, x0, y0);
    const double n1 = corner(gi1, x1, y1);
    const double n2 = corner(gi2, x2, y2);

    // 70.0 scales the result to roughly [-1, 1].
    return 70.0 * (n0 + n1 + n2);
}

double sample_3d(const SimplexNoiseSampler& s, double x, double y, double z) noexcept {
    // Apply per-sampler origin offsets first.
    x += s.origin_x;
    y += s.origin_y;
    z += s.origin_z;

    // Skew factors for 3D: F3 = 1/3, G3 = 1/6.
    constexpr double F3 = 1.0 / 3.0;
    constexpr double G3 = 1.0 / 6.0;

    const double sk = (x + y + z) * F3;
    const int    i  = floor_to_int(x + sk);
    const int    j  = floor_to_int(y + sk);
    const int    k  = floor_to_int(z + sk);
    const double t  = (i + j + k) * G3;
    const double X0 = i - t;
    const double Y0 = j - t;
    const double Z0 = k - t;
    const double x0 = x - X0;
    const double y0 = y - Y0;
    const double z0 = z - Z0;

    // Determine which of the 6 simplex sub-tetrahedra we're in.
    int i1, j1, k1;
    int i2, j2, k2;
    if (x0 >= y0) {
        if (y0 >= z0)      { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
        else               { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
    } else {
        if (y0 < z0)        { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
        else if (x0 < z0)   { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
        else                { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
    }

    // Offsets for the four corners.
    const double x1 = x0 - i1 + G3;
    const double y1 = y0 - j1 + G3;
    const double z1 = z0 - k1 + G3;
    const double x2 = x0 - i2 + 2.0 * G3;
    const double y2 = y0 - j2 + 2.0 * G3;
    const double z2 = z0 - k2 + 2.0 * G3;
    const double x3 = x0 - 1.0 + 3.0 * G3;
    const double y3 = y0 - 1.0 + 3.0 * G3;
    const double z3 = z0 - 1.0 + 3.0 * G3;

    // Mojang's hashing: through permutation table.
    const int gi0 = pmap_mod12(s, i + pmap(s, j + pmap(s, k)));
    const int gi1 = pmap_mod12(s, i + i1 + pmap(s, j + j1 + pmap(s, k + k1)));
    const int gi2 = pmap_mod12(s, i + i2 + pmap(s, j + j2 + pmap(s, k + k2)));
    const int gi3 = pmap_mod12(s, i + 1 + pmap(s, j + 1 + pmap(s, k + 1)));

    auto contrib = [](int gi, double xc, double yc, double zc) noexcept {
        double t_ = std::max(0.0, 0.6 - xc * xc - yc * yc - zc * zc);
        t_ *= t_;
        return t_ * t_ * dot3(kGradients[gi], xc, yc, zc);
    };

    const double n0 = contrib(gi0, x0, y0, z0);
    const double n1 = contrib(gi1, x1, y1, z1);
    const double n2 = contrib(gi2, x2, y2, z2);
    const double n3 = contrib(gi3, x3, y3, z3);

    // 32.0 scales the result to roughly [-1, 1].
    return 32.0 * (n0 + n1 + n2 + n3);
}

} // namespace lattice::world::gen::noise
