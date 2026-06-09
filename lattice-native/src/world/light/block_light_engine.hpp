/**
 * @file block_light_engine.hpp
 * @brief Snapshot-based scalar block-light propagation core.
 *
 * This is the first main-path replacement for the old callback-only light
 * prototype. Java prepares compact per-section arrays and native performs the
 * propagation without calling back into the JVM for every neighbour.
 *
 * Supported fast path, by design:
 *   - one chunk column (16x16 XZ footprint, N stacked sections);
 *   - block light only;
 *   - full rebuild from emission sources;
 *   - no shape-occlusion blocks in the column;
 *   - optional X/Z propagation into the four directly-adjacent chunk columns;
 *   - no diagonal or wider cross-chunk propagation yet.
 *
 * Java must precompute:
 *   opacity[i]  = max(1, BlockState#getLightBlock()) for cell i;
 *   emission[i] = BlockState#getLightEmission() for cell i;
 *   flags[i]    = kRequiresShapeOcclusion when vanilla would need
 *                 Shapes.faceShapeOccludes for any adjacent pair.
 *
 * Cell index layout matches LevelChunkSection / PalettedContainer:
 *   index = (y * 16 + z) * 16 + x
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::light {

inline constexpr int kBlockLightSectionWidth  = 16;
inline constexpr int kBlockLightSectionHeight = 16;
inline constexpr int kBlockLightSectionVolume = 4096;
inline constexpr int kBlockLightSectionNibbleBytes = kBlockLightSectionVolume / 2;
inline constexpr std::uint8_t kBlockLightMaxLevel = 15;

inline constexpr std::uint8_t kLightFlagRequiresShapeOcclusion = 1u << 0;

struct BlockLightSectionView {
    /// 4096 entries, one byte each. Required.
    const std::uint8_t* opacity = nullptr;
    /// 4096 entries, one byte each. Required.
    const std::uint8_t* emission = nullptr;
    /// Optional 4096-entry flags array. Null means all flags are zero.
    const std::uint8_t* flags = nullptr;
    /// 4096 entries, one byte each. Required output. Overwritten.
    std::uint8_t* light = nullptr;
};

struct BlockLightColumnView {
    /// Sections are ordered low -> high.
    const BlockLightSectionView* sections = nullptr;
    std::size_t section_count = 0;
};

struct BlockLightNeighborhoodView {
    /// Required output column.
    const BlockLightColumnView* center = nullptr;
    /// Optional read-only neighbour snapshots.
    const BlockLightColumnView* west = nullptr;
    const BlockLightColumnView* east = nullptr;
    const BlockLightColumnView* north = nullptr;
    const BlockLightColumnView* south = nullptr;
};

enum class BlockLightStatus : std::uint8_t {
    Ok = 0,
    NullInput,
    MismatchedSectionCount,
    UnsupportedShapeOcclusion,
    IncompleteNeighborhood,
    OutOfMemory,
};

struct BlockLightResult {
    BlockLightStatus status = BlockLightStatus::Ok;
    /// Number of center-column cells whose final light value is non-zero.
    std::size_t lit_cells = 0;
    /// Number of center-column emission sources written during initial seeding.
    std::size_t emission_sources = 0;
    /// Number of successful center-column level writes after initial emission seeding.
    std::size_t propagated_writes = 0;
};

/**
 * Rebuild block light for one chunk column from emission sources.
 *
 * The function implements vanilla's no-shape fast path:
 *   candidate = sourceLevel - max(1, targetOpacity)
 *   if candidate > storedTargetLevel: store and continue propagation.
 *
 * If any cell has kLightFlagRequiresShapeOcclusion, this function returns
 * UnsupportedShapeOcclusion before mutating any light[] buffer. If native
 * scratch allocation fails, this function returns OutOfMemory. The caller
 * must fall back to vanilla or a future shape-aware native path.
 */
BlockLightResult rebuild_block_light_column(const BlockLightColumnView& column) noexcept;

/**
 * Rebuild the center column while allowing propagation through up to four
 * directly-adjacent X/Z neighbour snapshots.
 *
 * Only center.light[] is mutated on success. Neighbour columns are treated as
 * read-only snapshots used to seed emission and carry propagation across the
 * chunk boundary. If propagation needs data outside the supplied neighborhood,
 * this function returns IncompleteNeighborhood without mutating center.light[].
 * If native scratch allocation fails, this function returns OutOfMemory
 * without mutating center.light[].
 */
BlockLightResult rebuild_block_light_neighborhood(const BlockLightNeighborhoodView& neighborhood) noexcept;

/// Convenience wrapper for a single section.
BlockLightResult rebuild_block_light_section(const BlockLightSectionView& section) noexcept;

/**
 * Pack 4096 byte-per-cell light levels into a vanilla-compatible 2048-byte
 * nibble section array. Values above 15 are clamped.
 */
BlockLightStatus pack_block_light_section_nibbles(const std::uint8_t* light,
                                                  std::uint8_t* nibbles) noexcept;

/**
 * Unpack a 2048-byte nibble section array into 4096 byte-per-cell levels.
 */
BlockLightStatus unpack_block_light_section_nibbles(const std::uint8_t* nibbles,
                                                    std::uint8_t* light) noexcept;

} // namespace lattice::world::light
