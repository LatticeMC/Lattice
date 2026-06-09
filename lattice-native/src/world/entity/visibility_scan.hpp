/**
 * @file visibility_scan.hpp
 * @brief O(N×M) visibility scan: for each entity, which players are within
 *        its tracking range?
 *
 * Mirrors the inner work of
 * `net.minecraft.server.world.ServerChunkLoadingManager$TrackedEntity.updateTrackedStatus(List)`,
 * minus the side effects (packet sending, listener bookkeeping). The
 * vanilla loop computes, for every (entity, player) pair, the squared
 * 3D distance and compares against the entity's per-type max-range
 * squared. We surface exactly that result as a flat bitmap so Java can
 * decide which pairs need actual `startTracking` / `stopTracking` work.
 *
 * Input
 * -----
 *   entity_positions   - flat Vec3 array, length 3*N (x, y, z per entity)
 *   entity_range_sq    - per-entity squared range (max-range²)
 *   player_positions   - flat Vec3 array, length 3*M
 *
 * Output
 * ------
 *   visibility[i * row_longs + (j/64)] has bit (j%64) set iff
 *     squared_distance(entity_i, player_j) <= entity_range_sq[i]
 *   where row_longs = ceil(M / 64).
 *
 * Threshold semantics match vanilla: use `<=` so an entity exactly at
 * `maxRange` is still visible. (Vanilla's `Entity.getMaxRange()` returns
 * a double in blocks; we square it on the Java side before passing in.)
 *
 * Determinism
 * -----------
 * The implementation does *not* use FMA or `-ffast-math` reassociation,
 * so the squared-distance computation is bit-exact with a `(dx*dx + dy*dy + dz*dz)`
 * reduction in IEEE 754 round-to-nearest. The diff-verify shadow on the
 * Java side relies on this — any subnormals or NaN inputs produce the
 * same false-comparison result on both paths.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

inline constexpr std::size_t kBitsPerLong = 64;

/// `ceil(player_count / 64)` — number of longs in each entity's row of
/// the output bitmap.
[[nodiscard]] inline constexpr std::size_t row_longs(std::size_t player_count) noexcept {
    return (player_count + kBitsPerLong - 1) / kBitsPerLong;
}

/// Scalar reference implementation. Always available; never picks SIMD.
void scan_scalar(const double* entity_xyz, const double* entity_range_sq,
                 std::size_t entity_count,
                 const double* player_xyz,
                 std::size_t player_count,
                 std::uint64_t* visibility) noexcept;

// ---- SIMD variants ----

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)

/// AVX2-accelerated scan. Processes 4 players per entity per iteration
/// using `__m256d` lanes. Pre-condition: `lattice::cpu::features().avx2`.
void scan_avx2(const double* entity_xyz, const double* entity_range_sq,
               std::size_t entity_count,
               const double* player_xyz,
               std::size_t player_count,
               std::uint64_t* visibility) noexcept;

#endif

#if defined(__aarch64__) || defined(_M_ARM64)

/// NEON-accelerated scan. Processes 2 players per entity per iteration
/// using `float64x2_t` lanes.
void scan_neon(const double* entity_xyz, const double* entity_range_sq,
               std::size_t entity_count,
               const double* player_xyz,
               std::size_t player_count,
               std::uint64_t* visibility) noexcept;

#endif

/// Runtime-dispatched entry point. First call selects the best variant
/// via `lattice::cpu::features()`; subsequent calls go through a
/// cached function pointer.
void scan(const double* entity_xyz, const double* entity_range_sq,
          std::size_t entity_count,
          const double* player_xyz,
          std::size_t player_count,
          std::uint64_t* visibility) noexcept;

/// Idempotent dispatcher init. Safe to call from JNI_OnLoad.
void init_visibility_dispatch() noexcept;

} // namespace lattice::world::entity
