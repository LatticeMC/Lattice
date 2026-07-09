/**
 * @file perlin_noise.hpp
 * @brief Single-octave Perlin noise sampler matching Mojang's
 *        `net.minecraft.util.math.noise.PerlinNoiseSampler` (class_3756).
 *
 * The algorithm is Ken Perlin's "Improved Noise" (2002): a 3D lattice
 * gradient noise with the smoothstep `6t^5 - 15t^4 + 10t^3` fade and
 * the 12-edge-gradient `grad` table. Mojang's class is a literal
 * implementation of that, plus an arbitrary `(originX, originY, originZ)`
 * offset that the RNG-driven constructor produces.
 *
 * Bit-exactness with Mojang's Java version
 * -----------------------------------------
 *
 * The sampler is **table-driven**: once a `permutation` array (256
 * bytes) and the three origins are produced, the whole sample()
 * function is a deterministic chain of doubles. The RNG itself
 * (Xoroshiro128++ or LegacyRandom depending on context) is therefore
 * left in Java; the native API takes the prepared permutation +
 * origins and operates from there.
 *
 * The implementation deliberately avoids:
 *   - FMA (`std::fma`): Mojang's bytecode uses separate mul + add,
 *     and FMA fuses them with a different rounding behaviour at the
 *     final ULP. With the "near-strict" verification mode we ship
 *     (≤ 1 ULP tolerance) this is acceptable, but we still avoid FMA
 *     in the reference scalar to keep the diff-verify shadow trivial.
 *   - `-ffast-math` and any reassociation: the build flags already
 *     forbid these globally.
 *
 * Java fallback
 * -------------
 *
 * Callers should keep a Java implementation of the same algorithm as
 * a fallback when the native library isn't loaded, exactly as the
 * other modules do. See lattice-server's `NativePerlinNoise` wrapper.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::gen::noise {

/// Immutable state of one Perlin noise sampler.
///
/// `permutation[i]` is in `[0, 255]`; Mojang's `<init>` shuffles a
/// 256-byte array via Fisher–Yates using its RNG of choice. The
/// `origin_{x,y,z}` come from `nextDouble() * 256.0` (also via the
/// RNG). Both are computed Java-side and handed off.
struct PerlinNoiseSampler {
    double       origin_x;
    double       origin_y;
    double       origin_z;
    std::uint8_t permutation[256];
};

/// `sample(x, y, z)` — the simple 3-arg sampler.
[[nodiscard]] double sample(const PerlinNoiseSampler& s,
                            double x, double y, double z) noexcept;

/// Batch `sample(x, y, z)` over SoA coordinate arrays. The dispatcher uses
/// AVX2 on supported x86 CPUs and falls back to the scalar reference for the
/// tail or unsupported hosts. Pointers may be unaligned.
void sample_batch(const PerlinNoiseSampler& s,
                  const double* x, const double* y, const double* z,
                  std::size_t count, double* out) noexcept;

/// Batch a Y column at constant X/Z: out[i] = sample(x, y0 + i*dy, z).
void sample_y_column(const PerlinNoiseSampler& s,
                     double x, double y0, double z, double dy,
                     std::size_t count, double* out) noexcept;

/// Batch with constant X/Z and a precomputed Y array.
void sample_y_array(const PerlinNoiseSampler& s,
                    double x, const double* y, double z,
                    std::size_t count, double* out) noexcept;

/// `sample(x, y, z, yScale, yMax)` — Mojang's y-axis-scaled overload.
///
/// Vanilla uses this to compress the Y direction's "lattice fade" so
/// noise varies less rapidly vertically than horizontally. The exact
/// behaviour matches `method_16447`:
///
///     y_offset = (yScale != 0 && yMax != 0)
///                ? floor(y / yScale) * yScale
///                : 0
///     y_in     = y - y_offset
///     ... sample(x, y_in, z) ...
///
/// In other words, `y` is decomposed into `lattice-quantised` portion
/// (added to the origin via the floor() trick) plus a residual that
/// the sampler treats as Y. `yMax` clamps the offset above.
[[nodiscard]] double sample_y_scaled(const PerlinNoiseSampler& s,
                                     double x, double y, double z,
                                     double y_scale, double y_max) noexcept;

/// Batch form of Mojang's y-scaled overload.
void sample_y_scaled_batch(const PerlinNoiseSampler& s,
                           const double* x, const double* y, const double* z,
                           double y_scale, double y_max,
                           std::size_t count, double* out) noexcept;

/// Batch form with per-lane yMax. Used by InterpolatedNoise, where yMax is
/// derived from the column Y coordinate and therefore changes for every lane.
void sample_y_scaled_batch_ymax(const PerlinNoiseSampler& s,
                                const double* x, const double* y, const double* z,
                                double y_scale, const double* y_max,
                                std::size_t count, double* out) noexcept;

/// Batch y-scaled samples for one X/Z column with per-lane yMax.
void sample_y_scaled_array_ymax(const PerlinNoiseSampler& s,
                                double x, const double* y, double z,
                                double y_scale, const double* y_max,
                                std::size_t count, double* out) noexcept;

/// `sampleDerivative(x, y, z, out_dxdydz)` — returns the value AND
/// writes the 3-component derivative to `out_dxdydz[0..3)`. Vanilla's
/// `method_35477` uses this for density-function gradient terms.
double sample_derivative(const PerlinNoiseSampler& s,
                         double x, double y, double z,
                         double out_dxdydz[3]) noexcept;

void sample_batch_scalar(const PerlinNoiseSampler& s,
                         const double* x, const double* y, const double* z,
                         std::size_t count, double* out) noexcept;

void sample_y_column_scalar(const PerlinNoiseSampler& s,
                            double x, double y0, double z, double dy,
                            std::size_t count, double* out) noexcept;
void sample_y_array_scalar(const PerlinNoiseSampler& s,
                           double x, const double* y, double z,
                           std::size_t count, double* out) noexcept;

void sample_y_scaled_batch_scalar(const PerlinNoiseSampler& s,
                                   const double* x, const double* y, const double* z,
                                   double y_scale, double y_max,
                                   std::size_t count, double* out) noexcept;

void sample_y_scaled_batch_ymax_scalar(const PerlinNoiseSampler& s,
                                       const double* x, const double* y, const double* z,
                                       double y_scale, const double* y_max,
                                       std::size_t count, double* out) noexcept;

void sample_y_scaled_array_ymax_scalar(const PerlinNoiseSampler& s,
                                       double x, const double* y, double z,
                                       double y_scale, const double* y_max,
                                       std::size_t count, double* out) noexcept;

void sample_batch_avx2(const PerlinNoiseSampler& s,
                       const double* x, const double* y, const double* z,
                       std::size_t count, double* out) noexcept;

void sample_y_column_avx2(const PerlinNoiseSampler& s,
                          double x, double y0, double z, double dy,
                          std::size_t count, double* out) noexcept;
void sample_y_array_avx2(const PerlinNoiseSampler& s,
                         double x, const double* y, double z,
                         std::size_t count, double* out) noexcept;

void sample_y_scaled_batch_avx2(const PerlinNoiseSampler& s,
                                 const double* x, const double* y, const double* z,
                                 double y_scale, double y_max,
                                 std::size_t count, double* out) noexcept;

void sample_y_scaled_batch_ymax_avx2(const PerlinNoiseSampler& s,
                                     const double* x, const double* y, const double* z,
                                     double y_scale, const double* y_max,
                                     std::size_t count, double* out) noexcept;

void sample_y_scaled_array_ymax_avx2(const PerlinNoiseSampler& s,
                                     double x, const double* y, double z,
                                     double y_scale, const double* y_max,
                                     std::size_t count, double* out) noexcept;

} // namespace lattice::world::gen::noise
