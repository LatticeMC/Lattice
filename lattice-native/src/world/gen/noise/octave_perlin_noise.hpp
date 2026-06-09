/**
 * @file octave_perlin_noise.hpp
 * @brief Multi-octave Perlin noise sampler matching Mojang's
 *        `OctavePerlinNoiseSampler` (class_3537).
 *
 * An octave sampler holds an ordered list of `N` single-octave
 * `PerlinNoiseSampler` instances plus their amplitudes and a shared
 * lacunarity / persistence pair. `sample(x, y, z)` evaluates each
 * octave at the same (x, y, z) scaled by `lacunarity * 2^i`, weights
 * by `persistence / 2^i`, and sums.
 *
 * Vanilla detail: any octave whose `amplitude` is exactly 0 is skipped
 * (its sampler may legitimately be null in the Java side; we just
 * read amplitude[i] == 0 here). The skipped octave still occupies a
 * slot, so the (lacunarity, persistence) progression is unchanged.
 *
 * `maintainPrecision(value)` (Mojang's `method_16452`) wraps the
 * coordinate to keep it in a precision-friendly band:
 *
 *   maintainPrecision(v) = v - round(v / 3.3554432e7) * 3.3554432e7
 *
 * This is applied to each axis BEFORE handing off to the underlying
 * single-octave sampler. The magic number is exactly 2^25.
 */

#pragma once

#include <cmath>     // std::floor
#include <cstddef>
#include <cstdint>

#include "world/gen/noise/perlin_noise.hpp"

namespace lattice::world::gen::noise {

struct OctavePerlinNoiseSampler {
    /// `field_15744 octaveSamplers`. May contain "logical nulls" when
    /// the corresponding amplitude is 0 — represented here as
    /// permutations of zeros + origins of zero. The dispatching loop
    /// short-circuits on `amplitudes[i] == 0`.
    const PerlinNoiseSampler* octaves = nullptr;
    /// `field_26445 amplitudes`. Length = octave_count.
    const double*             amplitudes = nullptr;
    std::size_t               octave_count = 0;
    double                    lacunarity   = 1.0;   // `field_20660`
    double                    persistence  = 1.0;   // `field_20659`
};

/// `method_15416 sample(x, y, z)` — the standard multi-octave sample.
[[nodiscard]] double sample(const OctavePerlinNoiseSampler& s,
                            double x, double y, double z) noexcept;

/// `method_16453 sample(x, y, z, yScale, yMax, useOrigin)`.
/// `useOrigin == false` removes the per-octave origin offset, which
/// vanilla uses for terrain noise to keep the frequency multiplication
/// from amplifying origin drift.
[[nodiscard]] double sample_full(const OctavePerlinNoiseSampler& s,
                                 double x, double y, double z,
                                 double y_scale, double y_max,
                                 bool use_origin) noexcept;

/// `method_16452 maintainPrecision(value)`. Public so callers can use
/// the same numerics elsewhere.
[[nodiscard]] inline double maintain_precision(double v) noexcept {
    constexpr double kPrecisionWrap = 3.3554432e7; // 2^25
    return v - std::floor(v / kPrecisionWrap + 0.5) * kPrecisionWrap;
}

} // namespace lattice::world::gen::noise
