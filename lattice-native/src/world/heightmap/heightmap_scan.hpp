/**
 * @file heightmap_scan.hpp
 * @brief Multi-section chunk column scanner for Minecraft's Heightmap rebuild.
 *
 * Mirrors the inner work of `net.minecraft.world.Heightmap.populateHeightmaps`
 * (`class_2902`) at the *index-level*, deliberately avoiding any
 * BlockState callback into Java. The caller is responsible for
 * pre-computing, for each section, a 256-bit "passing" mask:
 *
 *     passing_mask[i] == 1   iff   predicate(palette.get(i))
 *
 * where `palette.get(i)` is the BlockState the storage's packed index
 * `i` refers to. Java can produce this mask in O(palette size) by
 * walking the section's palette once.
 *
 * With the mask in hand, the scanner walks every (x, z) column from
 * the top section downward, extracts the packed index of each (x, y, z)
 * cell, looks up its bit in the mask, and records the y position of
 * the first matching cell — exactly what `populateHeightmaps` does
 * with the per-block predicate, but in pure bit-math instead of going
 * through `chunk.getBlockState(x, y, z)`.
 *
 * Section layout (matches PalettedContainer for 16-cube sections):
 *   index = (y * 16 + z) * 16 + x       (`Heightmap` uses z * 16 + x for
 *                                        the column index — we deliberately
 *                                        flip y to be the *outer* loop so
 *                                        the per-column inner stride is
 *                                        the same for every cell)
 *
 * Output: 256 ints, in `column_index = z * 16 + x` order. Each int is the
 * **world-Y value** of the highest passing cell in that column, or
 * `default_height` (typically `min_world_y - 1`) if none was found.
 *
 * The implementation degrades to the all-air short-circuit when:
 *   - section_storages[s] is null (Java's PalettedContainer.Data == null),
 *   - or the section's elementBits is 0 and palette[0]'s mask bit is 0
 *     (single-entry palette of air → no cell passes).
 *
 * A "completed" column (one that found its highest passing cell) is
 * removed from the active set so subsequent sections don't waste work
 * on it. Tracked with a 256-bit "still searching" bitmap (`remaining`).
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::heightmap {

inline constexpr int kColumnCount     = 256; // 16 × 16
inline constexpr int kSectionHeight   = 16;
struct SectionView {
    /// Packed storage for this section. Null means "all default
    /// palette index 0" — caller still provides a passing mask;
    /// `passing_mask[0] == 1` means every cell passes.
    const std::uint64_t* storage = nullptr;
    /// Storage length in longs. Unused when `storage == nullptr`.
    std::size_t  storage_longs   = 0;
    /// Bits per packed element in `storage`. 0 when storage is null
    /// (caller's "single-palette-entry, all index 0").
    int          element_bits    = 0;
    /// Variable-width passing mask. `mask[i / 64] & (1 << (i % 64))`
    /// indicates whether palette entry `i` passes the predicate.
    const std::uint64_t* passing_mask = nullptr;
};

namespace detail {

using MaskAnyFn = bool (*)(const std::uint64_t* mask, std::size_t mask_longs) noexcept;
using FillDefaultSectionFn = std::size_t (*)(std::uint64_t* remaining,
                                             std::int32_t* out_heights,
                                             std::int32_t y) noexcept;

bool mask_any_scalar(const std::uint64_t* mask, std::size_t mask_longs) noexcept;
std::size_t fill_default_section_scalar(std::uint64_t* remaining,
                                        std::int32_t* out_heights,
                                        std::int32_t y) noexcept;

std::size_t populate_with_mask_any(const SectionView* sections,
                                   std::size_t section_count,
                                   int section_base_y,
                                   std::size_t mask_longs,
                                   int default_height,
                                   std::int32_t* out_heights,
                                   MaskAnyFn mask_any_fn,
                                   FillDefaultSectionFn fill_default_section_fn) noexcept;

} // namespace detail

/**
 * Scan a stack of sections from the topmost down, filling
 * `out_heights[0..256)` with the world-Y of the highest passing cell
 * per column. Sections are presented in *ascending world-Y order*
 * (`sections[0]` is the lowest); the scanner internally walks them
 * from `sections[section_count - 1]` downward.
 *
 * @param sections           ordered low → high
 * @param section_count      number of sections (typically 24 for 1.21)
 * @param section_base_y     world Y of the bottom of `sections[0]`
 *                           (e.g. `-64` for the Overworld dimension)
 * @param mask_longs         number of 64-bit words in each section mask
 * @param default_height     value written to columns that find no
 *                           passing cell (typically `section_base_y - 1`)
 * @param out_heights        256-int buffer, `column = z * 16 + x` order
 *
 * Returns the number of columns that found a passing cell (0..256).
 */
std::size_t populate_scalar(const SectionView* sections,
                            std::size_t section_count,
                            int section_base_y,
                            std::size_t mask_longs,
                            int default_height,
                            std::int32_t* out_heights) noexcept;

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
std::size_t populate_avx2(const SectionView* sections,
                          std::size_t section_count,
                          int section_base_y,
                          std::size_t mask_longs,
                          int default_height,
                          std::int32_t* out_heights) noexcept;
#endif

#if defined(__aarch64__) || defined(_M_ARM64)
std::size_t populate_neon(const SectionView* sections,
                          std::size_t section_count,
                          int section_base_y,
                          std::size_t mask_longs,
                          int default_height,
                          std::int32_t* out_heights) noexcept;
#endif

std::size_t populate(const SectionView* sections,
                     std::size_t section_count,
                     int section_base_y,
                     std::size_t mask_longs,
                     int default_height,
                     std::int32_t* out_heights) noexcept;

void init_heightmap_dispatch() noexcept;

} // namespace lattice::world::heightmap
