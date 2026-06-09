/**
 * @file random_tick_filter.hpp
 * @brief Batched filter for "does this position need a random tick?".
 *
 * On every tick, vanilla picks `randomTickSpeed` (default 3) random
 * positions per ChunkSection per world, then calls `getBlockState(x,y,z)`
 * and tests `state.hasRandomTicks()`. The vast majority fail; the few
 * that pass invoke `state.randomTick(...)`.
 *
 * This filter front-loads the palette-index + mask test in C++ so Java
 * only iterates the positions that actually need random-tick work. The
 * pattern mirrors `NativeSpawnFilter`'s palette check, applied to a
 * different mask (the "has random ticks" mask).
 *
 * Input shape
 * -----------
 *
 *   candidates_packed[N] : (uint32) bit layout
 *       bits  0..11   : local_idx = y*256 + z*16 + x  (within a section)
 *       bits 12..31   : section_idx (0..sectionCount)
 *
 *   section_storages[S]  : per-section packed long[]; null = single-entry palette
 *   section_storage_lens[S]
 *   section_element_bits[S]
 *   section_tick_masks[S * 4] : 256-bit "has random ticks" pass mask per section
 *
 * Output
 * ------
 *
 *   out_accepted_indices[N] : an unsorted list of indices into
 *                             candidates_packed for the candidates that
 *                             passed (subset of [0, N)). At most N
 *                             entries are written.
 *
 * Returns the count of accepted candidates.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::tick {

struct RandomTickFilterInputs {
    const std::uint32_t* candidates_packed = nullptr;
    std::size_t          candidate_count   = 0;

    // Per-section arrays. Lengths all equal section_count.
    const std::uint64_t* const* section_storages    = nullptr;
    const std::size_t*          section_storage_lens = nullptr;
    const int*                  section_element_bits = nullptr;
    const std::uint64_t*        section_tick_masks   = nullptr;
    std::size_t                 mask_longs_per_section = 0;
    std::size_t                 section_count        = 0;
};

/// Apply the filter. Writes the accepted indices into
/// `out_accepted_indices[0..returned-count)`. The output buffer must be
/// large enough for `candidate_count` indices; the function will not
/// write past that.
std::size_t filter_random_ticks(const RandomTickFilterInputs& in,
                                std::uint32_t* out_accepted_indices) noexcept;

} // namespace lattice::world::tick
