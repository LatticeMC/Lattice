/**
 * @file simplex_noise.hpp
 * @brief Mojang-compatible Simplex noise sampler.
 *
 * Mirrors `net.minecraft.util.math.noise.SimplexNoiseSampler`
 * (class_3541). Improved Perlin simplex noise — different topology
 * than the Perlin lattice noise we already ship, used mainly by the
 * legacy world generators and by EndIslands terrain.
 *
 * The 2D entry (`sample(x, y)`) is the heavily-used one; 3D
 * (`sample(x, y, z)`) appears in a few other contexts.
 *
 * Same RNG-boundary policy as PerlinNoiseSampler: the 256-int
 * permutation and the 3 stored origin offsets are computed Java-side and
 * handed off; Mojang's 2D and 3D sample methods do not apply those offsets.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::gen::noise {

struct SimplexNoiseSampler {
    double       origin_x;
    double       origin_y;
    double       origin_z;
    /// Mojang stores ints here; we keep them as ints because the
    /// permutation may be modulo-anything (vanilla writes them as
    /// already-modulo'd ints to avoid the `& 0xFF` overhead).
    std::int32_t permutation[256];
};

/// 2D Simplex sample. Stored origin offsets are unused.
[[nodiscard]] double sample_2d(const SimplexNoiseSampler& s,
                               double x, double y) noexcept;

void sample_2d_batch(const SimplexNoiseSampler& s,
                     const double* x, const double* y,
                     std::size_t count, double* out) noexcept;

void sample_2d_batch_scalar(const SimplexNoiseSampler& s,
                            const double* x, const double* y,
                            std::size_t count, double* out) noexcept;

void sample_2d_batch_avx2(const SimplexNoiseSampler& s,
                          const double* x, const double* y,
                          std::size_t count, double* out) noexcept;

/// 3D Simplex sample. Stored origin offsets are unused.
[[nodiscard]] double sample_3d(const SimplexNoiseSampler& s,
                               double x, double y, double z) noexcept;

void sample_3d_batch(const SimplexNoiseSampler& s,
                     const double* x, const double* y, const double* z,
                     std::size_t count, double* out) noexcept;

void sample_3d_batch_scalar(const SimplexNoiseSampler& s,
                            const double* x, const double* y, const double* z,
                            std::size_t count, double* out) noexcept;

void sample_3d_batch_avx2(const SimplexNoiseSampler& s,
                          const double* x, const double* y, const double* z,
                          std::size_t count, double* out) noexcept;

} // namespace lattice::world::gen::noise
