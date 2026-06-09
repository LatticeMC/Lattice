/**
 * @file double_perlin_noise.hpp
 * @brief Two-octave-sampler Perlin noise matching Mojang's
 *        `DoublePerlinNoiseSampler` (class_5216).
 *
 * Wraps two `OctavePerlinNoiseSampler` instances; the second is
 * evaluated at coordinates scaled by `DOMAIN_SCALE` so its lattice
 * doesn't align with the first's. The two outputs are then summed
 * and scaled by a fixed `amplitude` that depends only on the octave
 * count.
 *
 *   DOMAIN_SCALE = 1.0181268882175227
 *
 * Used everywhere in the modern (1.18+) world generator — every
 * NoiseRouter density function that mentions "noise" ultimately
 * resolves to one of these. Cheap on top of the two underlying
 * octave samplers (one multiply + one add), but central to the hot
 * path.
 */

#pragma once

#include <cstddef>

#include "world/gen/noise/octave_perlin_noise.hpp"

namespace lattice::world::gen::noise {

inline constexpr double kDomainScale = 1.0181268882175227;

struct DoublePerlinNoiseSampler {
    OctavePerlinNoiseSampler first;
    OctavePerlinNoiseSampler second;
    double                   amplitude = 1.0;
};

[[nodiscard]] double sample(const DoublePerlinNoiseSampler& s,
                            double x, double y, double z) noexcept;

/// `method_27407 createAmplitude(octaves)` — Mojang's amplitude scaling
/// based on octave count. Mirrored exactly:
///
///   amplitude = (1 / 6) * octaves
///
/// or, equivalently, `0.16666666666666666 * octaves`.
[[nodiscard]] inline constexpr double create_amplitude(int octaves) noexcept {
    return 0.16666666666666666 * static_cast<double>(octaves);
}

} // namespace lattice::world::gen::noise
