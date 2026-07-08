/**
 * @file interpolated_noise.hpp
 * @brief Bit-exact port of `net.minecraft.util.math.noise.InterpolatedNoiseSampler`
 *        (yarn-1.21.11), the legacy 1.16-style "blended noise" used by
 *        `old_blended_noise` density-function nodes.
 *
 * Mojang's InterpolatedNoiseSampler composes three OctavePerlinNoise-
 * Sampler trees:
 *
 *   - `lowerInterpolatedNoise`  (16 octaves, range -15..0)
 *   - `upperInterpolatedNoise`  (16 octaves, range -15..0)
 *   - `interpolationNoise`      (8  octaves, range -7..0)
 *
 * plus five tuning doubles:
 *
 *   - `xzScale`, `yScale`, `xzFactor`, `yFactor`, `smearScaleMultiplier`
 *
 * Each `sample(blockX, blockY, blockZ)` call:
 *
 *   1. Iterates 8 octaves of `interpolationNoise` to compute a blend
 *      ratio `q ∈ [0, 1]` (clamped through a `(n/10 + 1)/2` and
 *      `clampedLerp`).
 *   2. Iterates 16 octaves of lower/upper noise (skipping lower when
 *      q >= 1, upper when q <= 0) to compute `l` and `m`.
 *   3. Returns `clampedLerp(q, l/512, m/512) / 128`.
 *
 * Each individual octave call is `PerlinNoiseSampler.sample(x, y, z,
 * yScale, yMax)` — the 5-arg `sample_y_scaled` overload our perlin
 * port already supports.
 *
 * This module is stateless w.r.t. the sampler (the OctavePerlinNoise-
 * Sampler instances are kept alive by the JVM caller); we just hold
 * pointers + the 5 doubles.
 *
 * Reference: yarn-1.21.11
 *   `net.minecraft.util.math.noise.InterpolatedNoiseSampler.sample`.
 */

#pragma once

#include <cstddef>

#include "world/gen/noise/octave_perlin_noise.hpp"

namespace lattice::world::gen::noise {

/// Stateless config for one interpolated-noise sampler. The three
/// `*_noise` pointers must outlive any call into `sample`. Vanilla's
/// `getOctave(p)` returns `octaveSamplers[length - 1 - p]`, so we
/// look up by reversed index in our flat octave array.
struct InterpolatedNoiseSampler {
    const OctavePerlinNoiseSampler* lower_interpolated_noise = nullptr;
    const OctavePerlinNoiseSampler* upper_interpolated_noise = nullptr;
    const OctavePerlinNoiseSampler* interpolation_noise      = nullptr;
    double xz_scale  = 0.0;
    double y_scale   = 0.0;
    double xz_factor = 0.0;
    double y_factor  = 0.0;
    double smear_scale_multiplier = 0.0;
};

/// `sample(pos.blockX, pos.blockY, pos.blockZ)` — the only Mojang
/// entry point on this sampler. Block coordinates are integer in
/// vanilla; we accept doubles to avoid an unnecessary cast at the
/// JNI boundary.
[[nodiscard]] double sample(const InterpolatedNoiseSampler& s,
                            double block_x, double block_y,
                            double block_z) noexcept;

void sample_batch(const InterpolatedNoiseSampler& s,
                  const double* block_x, const double* block_y,
                  const double* block_z,
                  std::size_t count, double* out) noexcept;

void sample_y_column(const InterpolatedNoiseSampler& s,
                     double block_x, double block_y0,
                     double block_z, double dy,
                     std::size_t count, double* out) noexcept;

} // namespace lattice::world::gen::noise
