/**
 * @file spawn_filter.hpp
 * @brief Batched filter for mob-spawn candidate positions.
 *
 * Composes three native primitives that already exist in `lattice-native`:
 *
 *   1. **Palette mask check** — for each candidate position, look up the
 *      packed palette index in the appropriate ChunkSection and test
 *      against a 256-bit "is acceptable spawn block" mask. The caller
 *      pre-walks each section's palette and builds the mask, exactly
 *      like `NativeHeightmap`.
 *
 *   2. **Entity clearance** — for each candidate position, treat it as
 *      a 1×1×1 AABB and test against the list of nearby entity AABBs.
 *      A candidate is rejected if any entity occupies it.
 *
 *   3. **Player distance** — a candidate is rejected unless at least one
 *      player is within `maxSpawnDistanceSq` (squared 3D distance).
 *
 * Mirrors the predicate logic of
 * {@code net.minecraft.world.SpawnHelper.isAcceptableSpawnPosition}
 * minus the BlockState-specific extras (luminance check, biome blacklist,
 * mob-type spawning rules) which stay in Java.
 *
 * Input/output layout
 * -------------------
 *
 *   candidate_xyz[3 * N]            : (x, y, z) per candidate, world-space
 *   section_storages[sectionCount]  : per-section packed long[]
 *   section_element_bits[sectionCount]
 *   section_pass_masks[sectionCount * 4] : 256-bit palette masks
 *   section_base_y                  : world Y of sections[0]'s bottom
 *   entity_aabbs[6 * E]
 *   player_xyz[3 * P]
 *   max_spawn_distance_sq           : double
 *
 *   acceptable[ceil(N/64)] : bitmap, bit i set iff candidate i passed all checks
 *
 * Returns the number of accepted candidates.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::entity {

inline constexpr std::size_t kSpawnSectionMaskLongs = 4; // 256 bits

struct SpawnFilterInputs {
    const double* candidate_xyz   = nullptr;
    std::size_t   candidate_count = 0;

    /// Per-candidate entity dimensions: `candidate_dims[i*2+0]` = half-width,
    /// `candidate_dims[i*2+1]` = height. If null, falls back to the default
    /// 0.5 half-width / 1.0 height (i.e. a 1×1×1 AABB) for all candidates.
    /// Vanilla uses `entityType.getSpawnAABB(x, y, z)` which varies per mob.
    const double* candidate_dims  = nullptr;

    // Per-section storage. `section_storages[s]` may be null to mean
    // "single-entry palette of index 0" (all-air sections).
    const std::uint64_t* const* section_storages   = nullptr;
    const std::size_t*          section_storage_lens = nullptr;
    const int*                  section_element_bits = nullptr;
    // Flat 256-bit pass masks, sections × 4 longs.
    const std::uint64_t*        section_pass_masks  = nullptr;
    std::size_t                 section_count       = 0;
    int                         section_base_y      = 0;

    const double* entity_aabbs  = nullptr;
    std::size_t   entity_count  = 0;

    const double* player_xyz    = nullptr;
    std::size_t   player_count  = 0;

    double max_spawn_distance_sq = 0.0;
};

/// Filter the candidates. `acceptable[0..ceil(N/64))` is overwritten.
/// Returns the count of accepted candidates.
std::size_t filter_spawn_candidates(const SpawnFilterInputs& inputs,
                                    std::uint64_t* acceptable) noexcept;

} // namespace lattice::world::entity
