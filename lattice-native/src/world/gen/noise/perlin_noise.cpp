// Single-octave Perlin noise sampler. See perlin_noise.hpp.
//
// Algorithm verified against Mojang's PerlinNoiseSampler (class_3756)
// bytecode. The bytecode-level operations we mirror are, in order:
//
//   1. x += originX; y += originY; z += originZ        (`originX..Z` are
//                                                       baked into `s` ahead of time)
//   2. xi = floor(x); yi = floor(y); zi = floor(z)     (integer floor)
//   3. xf = x - xi; yf = y - yi; zf = z - zi
//   4. u = fade(xf); v = fade(yf); w = fade(zf)
//   5. Eight `grad()` invocations with hashed permutation indices
//   6. Three nested linear interpolations
//
// The smoothstep is `t*t*t*(t*(t*6 - 15) + 10)` matching MathHelper.smoothStep.
// The grad() function is the 12-edge-gradient table from Ken Perlin 2002.

#include "world/gen/noise/perlin_noise.hpp"

#include <cmath>

#include "lattice/dispatch.hpp"

namespace lattice::world::gen::noise {

namespace {

// Java's `int floor(double)` equivalent: floor toward -∞, NOT trunc.
inline int floor_to_int(double x) noexcept {
    const int i = static_cast<int>(x);
    return (static_cast<double>(i) <= x) ? i : i - 1;
}

inline double smooth_step(double t) noexcept {
    // 6t^5 - 15t^4 + 10t^3 = t*t*t*(t*(t*6 - 15) + 10)
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

// `map(input)`: vanilla's `(permutation[input & 255] & 255)` — a single
// permutation lookup. The double-mask is because Java byte is signed
// and Mojang stores int-promoted bytes; we use uint8_t which already
// gives the unsigned semantics, so the second & 255 is redundant.
inline std::uint32_t pmap(const std::uint8_t* perm, int input) noexcept {
    return perm[input & 0xFF];
}

// Ken Perlin's gradient function: select one of 12 edge vectors based
// on `hash & 0xF`, return dot product with (x, y, z).
//
//   h = hash & 15
//   u = (h < 8)              ? x : y
//   v = (h < 4)              ? y : ((h == 12 || h == 14) ? x : z)
//   return ((h & 1) ? -u : u) + ((h & 2) ? -v : v)
//
// Mojang's `method_16448` byte-for-byte matches this; the table-driven
// form some references show is equivalent but slower in practice.
inline double grad(int hash, double x, double y, double z) noexcept {
    const int    h = hash & 15;
    const double u = (h < 8) ? x : y;
    const double v = (h < 4) ? y : ((h == 12 || h == 14) ? x : z);
    return ((h & 1) ? -u : u) + ((h & 2) ? -v : v);
}

inline double lerp(double t, double a, double b) noexcept {
    return a + t * (b - a);
}

// Core lattice sampler — takes pre-computed integer corner indices,
// fractional-cell offsets, and the Y FADE coordinate separately from
// the geometric Y. They differ in the y-scaled overload below: the
// geometric Y feeds into the gradient dot products (so noise varies
// less rapidly along Y), while the fade Y still uses the original
// fractional position (so the cell-cell interpolation curve tracks
// the actual position).
//
// `section{X,Y,Z}` are the integer cell corner; `local{X,Y,Z}` are
// the in-cell fractional offsets used for gradients; `fade_local_y`
// is the smoothstep input for the Y axis.
inline double sample_lattice(const std::uint8_t* p,
                             int section_x, int section_y, int section_z,
                             double local_x, double local_y, double local_z,
                             double fade_local_y) noexcept {
    const int A  = static_cast<int>(pmap(p, section_x))     + section_y;
    const int AA = static_cast<int>(pmap(p, A))             + section_z;
    const int AB = static_cast<int>(pmap(p, A + 1))         + section_z;
    const int B  = static_cast<int>(pmap(p, section_x + 1)) + section_y;
    const int BA = static_cast<int>(pmap(p, B))             + section_z;
    const int BB = static_cast<int>(pmap(p, B + 1))         + section_z;

    const double g000 = grad(pmap(p, AA),     local_x,       local_y,       local_z);
    const double g100 = grad(pmap(p, BA),     local_x - 1.0, local_y,       local_z);
    const double g010 = grad(pmap(p, AB),     local_x,       local_y - 1.0, local_z);
    const double g110 = grad(pmap(p, BB),     local_x - 1.0, local_y - 1.0, local_z);
    const double g001 = grad(pmap(p, AA + 1), local_x,       local_y,       local_z - 1.0);
    const double g101 = grad(pmap(p, BA + 1), local_x - 1.0, local_y,       local_z - 1.0);
    const double g011 = grad(pmap(p, AB + 1), local_x,       local_y - 1.0, local_z - 1.0);
    const double g111 = grad(pmap(p, BB + 1), local_x - 1.0, local_y - 1.0, local_z - 1.0);

    const double u = smooth_step(local_x);
    const double v = smooth_step(fade_local_y);   // ← fade-y uses the un-shifted fractional
    const double w = smooth_step(local_z);

    const double x00 = lerp(u, g000, g100);
    const double x10 = lerp(u, g010, g110);
    const double x01 = lerp(u, g001, g101);
    const double x11 = lerp(u, g011, g111);
    const double y0  = lerp(v, x00, x10);
    const double y1  = lerp(v, x01, x11);
    return lerp(w, y0, y1);
}

// Core unscaled sampler. fade_local_y == local_y.
inline double sample_core(const PerlinNoiseSampler& s,
                          double x, double y, double z) noexcept {
    x += s.origin_x;
    y += s.origin_y;
    z += s.origin_z;
    const int    xi = floor_to_int(x);
    const int    yi = floor_to_int(y);
    const int    zi = floor_to_int(z);
    const double xf = x - xi;
    const double yf = y - yi;
    const double zf = z - zi;
    return sample_lattice(s.permutation, xi, yi, zi, xf, yf, zf, /*fade_local_y=*/yf);
}

} // namespace

double sample(const PerlinNoiseSampler& s,
              double x, double y, double z) noexcept {
    return sample_core(s, x, y, z);
}

void sample_batch_scalar(const PerlinNoiseSampler& s,
                         const double* x, const double* y, const double* z,
                         std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_core(s, x[i], y[i], z[i]);
    }
}

void sample_batch(const PerlinNoiseSampler& s,
                  const double* x, const double* y, const double* z,
                  std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_batch_avx512(s, x, y, z, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_batch_avx2(s, x, y, z, count, out);
        return;
    }
#endif
    sample_batch_scalar(s, x, y, z, count, out);
}

void sample_y_column_scalar(const PerlinNoiseSampler& s,
                            double x, double y0, double z, double dy,
                            std::size_t count, double* out) noexcept {
    if (!out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_core(s, x, y0 + static_cast<double>(i) * dy, z);
    }
}

void sample_y_column(const PerlinNoiseSampler& s,
                     double x, double y0, double z, double dy,
                     std::size_t count, double* out) noexcept {
    if (!out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_y_column_avx512(s, x, y0, z, dy, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_column_avx2(s, x, y0, z, dy, count, out);
        return;
    }
#endif
    sample_y_column_scalar(s, x, y0, z, dy, count, out);
}

void sample_y_array_scalar(const PerlinNoiseSampler& s,
                           double x, const double* y, double z,
                           std::size_t count, double* out) noexcept {
    if (!y || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_core(s, x, y[i], z);
    }
}

void sample_y_array(const PerlinNoiseSampler& s,
                    double x, const double* y, double z,
                    std::size_t count, double* out) noexcept {
    if (!y || !out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_y_array_avx512(s, x, y, z, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_array_avx2(s, x, y, z, count, out);
        return;
    }
#endif
    sample_y_array_scalar(s, x, y, z, count, out);
}

double sample_y_scaled(const PerlinNoiseSampler& s,
                       double x, double y, double z,
                       double y_scale, double y_max) noexcept {
    // Mojang's 5-arg PerlinNoiseSampler.sample (yarn-1.21.11
    // PerlinNoiseSampler.java:41-58):
    //
    //   i = x + originX; j = y + originY; k = z + originZ
    //   l = floor(i); m = floor(j); n = floor(k)
    //   o = i - l;    p = j - m;    q = k - n             (fractional cell offsets)
    //
    //   if (yScale != 0):
    //       r = (yMax >= 0 && yMax < p) ? yMax : p        (cap p by yMax)
    //       s = floor(r / yScale + 1.0e-7) * yScale       (lattice-quantise)
    //   else:
    //       s = 0
    //
    //   return sample(l, m, n, o, p - s, q, /*fadeLocalY=*/p)
    //
    // Critical details we previously missed:
    //   - The cap input is `p` (the cell-fractional Y), not the
    //     absolute `y`.
    //   - The +1.0e-7 epsilon biases the floor to round near-integer
    //     scaled values up; without it values like 1.999999... fall
    //     to the wrong lattice slot.
    //   - The smoothstep fade for Y uses `p` (un-shifted fractional)
    //     even though the gradient lookup uses `p - s` (shifted).
    constexpr double kEpsilon = 1.0e-7;

    const double i = x + s.origin_x;
    const double j = y + s.origin_y;
    const double k = z + s.origin_z;
    const int    l = floor_to_int(i);
    const int    m = floor_to_int(j);
    const int    n = floor_to_int(k);
    const double o = i - l;
    const double p = j - m;
    const double q = k - n;

    double s_offset = 0.0;
    if (y_scale != 0.0) {
        const double r = (y_max >= 0.0 && y_max < p) ? y_max : p;
        s_offset = std::floor(r / y_scale + kEpsilon) * y_scale;
    }

    return sample_lattice(s.permutation,
                          l, m, n,
                          o, p - s_offset, q,
                          /*fade_local_y=*/p);
}

void sample_y_scaled_batch_scalar(const PerlinNoiseSampler& s,
                                  const double* x, const double* y, const double* z,
                                  double y_scale, double y_max,
                                  std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_y_scaled(s, x[i], y[i], z[i], y_scale, y_max);
    }
}

void sample_y_scaled_batch_ymax_scalar(const PerlinNoiseSampler& s,
                                       const double* x, const double* y, const double* z,
                                       double y_scale, const double* y_max,
                                       std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !y_max || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_y_scaled(s, x[i], y[i], z[i], y_scale, y_max[i]);
    }
}

void sample_y_scaled_array_ymax_scalar(const PerlinNoiseSampler& s,
                                       double x, const double* y, double z,
                                       double y_scale, const double* y_max,
                                       std::size_t count, double* out) noexcept {
    if (!y || !y_max || !out) return;
    for (std::size_t i = 0; i < count; ++i) {
        out[i] = sample_y_scaled(s, x, y[i], z, y_scale, y_max[i]);
    }
}

void sample_y_scaled_batch(const PerlinNoiseSampler& s,
                           const double* x, const double* y, const double* z,
                           double y_scale, double y_max,
                           std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_y_scaled_batch_avx512(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_scaled_batch_avx2(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
    sample_y_scaled_batch_scalar(s, x, y, z, y_scale, y_max, count, out);
}

void sample_y_scaled_batch_ymax(const PerlinNoiseSampler& s,
                                const double* x, const double* y, const double* z,
                                double y_scale, const double* y_max,
                                std::size_t count, double* out) noexcept {
    if (!x || !y || !z || !y_max || !out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_y_scaled_batch_ymax_avx512(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_scaled_batch_ymax_avx2(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
    sample_y_scaled_batch_ymax_scalar(s, x, y, z, y_scale, y_max, count, out);
}

void sample_y_scaled_array_ymax(const PerlinNoiseSampler& s,
                                double x, const double* y, double z,
                                double y_scale, const double* y_max,
                                std::size_t count, double* out) noexcept {
    if (!y || !y_max || !out) return;
#if defined(LATTICE_HAS_PERLIN_AVX512)
    if (lattice::cpu::features().avx512f && lattice::cpu::features().avx512dq) {
        sample_y_scaled_array_ymax_avx512(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
#if defined(LATTICE_HAS_PERLIN_AVX2)
    if (lattice::cpu::features().avx2) {
        sample_y_scaled_array_ymax_avx2(s, x, y, z, y_scale, y_max, count, out);
        return;
    }
#endif
    sample_y_scaled_array_ymax_scalar(s, x, y, z, y_scale, y_max, count, out);
}

double sample_derivative(const PerlinNoiseSampler& s,
                         double x, double y, double z,
                         double out_dxdydz[3]) noexcept {
    // Analytical derivatives are reserved for a follow-up commit; the
    // chain-rule decomposition through smoothstep + 8-corner trilinear
    // interpolation is fiddly enough to deserve its own diff-verify
    // pass. For now this is implemented via central differences over a
    // small epsilon — only the *value* matches the scalar reference
    // bit-for-bit; the derivative is computed numerically.
    //
    // Hot-path callers should not use this; vanilla density functions
    // that need a gradient run analytically on the Java side. The
    // entry point exists so the public API surface is stable.
    constexpr double kEps = 1e-6;
    const double v0 = sample_core(s, x, y, z);
    if (out_dxdydz) {
        out_dxdydz[0] = (sample_core(s, x + kEps, y, z) - v0) / kEps;
        out_dxdydz[1] = (sample_core(s, x, y + kEps, z) - v0) / kEps;
        out_dxdydz[2] = (sample_core(s, x, y, z + kEps) - v0) / kEps;
    }
    return v0;
}

} // namespace lattice::world::gen::noise
