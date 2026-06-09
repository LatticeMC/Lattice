/**
 * @file ore_vein.hpp
 * @brief Bit-exact port of `net.minecraft.world.gen.OreVeinSampler`.
 *
 * The OreVeinSampler decides whether a given (x, y, z) block should
 * be replaced by an ore-vein block (or one of the vein's "filler"
 * blocks). Vanilla composes it from three pre-built density
 * functions:
 *
 *   - `vein_toggle`  — sign chooses copper-vein vs iron-vein layer.
 *   - `vein_ridged`  — must be < 0 to commit to the vein.
 *   - `vein_gap`     — must be > -0.3 to allow the inner ore.
 *
 * plus a `RandomSplitter` (Xoroshiro128++ Splitter) seeded from the
 * world seed. We return an `OreVeinResult` enum the Java side maps
 * back to the actual `BlockState` values so this module stays
 * type-free of Minecraft's block registry.
 *
 * The output enum corresponds to vanilla's seven possible outputs:
 *   - kNone:           no override (return null in Java).
 *   - kCopperOre, kIronOre:                     `lv.ore`
 *   - kRawCopperBlock, kRawIronBlock:           `lv.rawOreBlock`
 *   - kCopperFiller, kIronFiller:               `lv.stone`
 *   - kDebugMarker:                             `Blocks.OAK_BUTTON`
 *
 * Constants and threshold curves all follow OreVeinSampler.java
 * 1.21.11.
 */

#pragma once

#include <cstdint>

#include "world/gen/rng/xoroshiro128pp.hpp"

namespace lattice::world::gen::orevein {

/// Vanilla VeinType y-extents (OreVeinSampler.java:62-63).
struct VeinType {
    int min_y;
    int max_y;
};

inline constexpr VeinType kCopper{0, 50};
inline constexpr VeinType kIron{-60, -8};

/// Numeric output codes. The Java side maps each to its registered
/// BlockState; layout is stable across releases.
///
/// We model the production output set only: vanilla also has a
/// debug-only path (gated on `SharedConstants.ORE_VEINS = true`)
/// that emits AIR for early-rejects and OAK_BUTTON for the filler
/// ring. That flag is always false in shipped builds, so we omit
/// those outputs here. If a future Mojang change flips the default
/// or exposes the flag, add `kDebugAir` / `kDebugButton` codes.
enum class OreVeinResult : std::uint8_t {
    kNone = 0,            // null in vanilla — block left alone.
    kCopperOre,           // Blocks.COPPER_ORE
    kIronOre,             // Blocks.DEEPSLATE_IRON_ORE
    kRawCopperBlock,      // Blocks.RAW_COPPER_BLOCK
    kRawIronBlock,        // Blocks.RAW_IRON_BLOCK
    kCopperFiller,        // Blocks.GRANITE
    kIronFiller,          // Blocks.TUFF
};

/// Tuning knobs from OreVeinSampler.java:16-24. Exposed so unit tests
/// can lock the constants if Mojang ever moves them.
inline constexpr float  kDensityThreshold        = 0.4f;
inline constexpr int    kMaxDensityIntrusion     = 20;
inline constexpr double kLiminalDensityReduction = 0.2;
inline constexpr float  kBlockGenerationChance   = 0.7f;
inline constexpr float  kMinOreChance            = 0.1f;
inline constexpr float  kMaxOreChance            = 0.3f;
inline constexpr float  kDensityForMaxOreChance  = 0.6f;
inline constexpr float  kRawOreBlockChance       = 0.02f;
inline constexpr float  kVeinGapThreshold        = -0.3f;

/// MathHelper.clampedMap(value, oldStart, oldEnd, newStart, newEnd) —
/// linearly remap with [newStart, newEnd] saturation outside the
/// input range. Identical to Mojang's helper, including the divide
/// (no zero-protection — vanilla doesn't guard either; oldEnd ==
/// oldStart is a programmer error).
constexpr double clamped_map(double value,
                             double old_start, double old_end,
                             double new_start, double new_end) noexcept {
    const double delta = (value - old_start) / (old_end - old_start);
    if (delta <= 0.0) return new_start;
    if (delta >= 1.0) return new_end;
    return new_start + delta * (new_end - new_start);
}

/// Pre-sampled density-function values at (blockX, blockY, blockZ).
/// The caller (typically via NativeOreVeinSampler) is responsible for
/// running the three DF trees against a ChunkNoiseSampler beforehand.
struct VeinSamples {
    double vein_toggle;
    double vein_ridged;
    double vein_gap;
};

/// Compute vanilla's OreVeinSampler output at one block. The
/// `splitter` parameter is the world-deriver's vein-specific
/// Splitter; pass it by const ref since it's stateless (the `split()`
/// call returns a fresh Random).
[[nodiscard]] OreVeinResult sample_at(const VeinSamples& samples,
                                      const rng::Splitter& splitter,
                                      int block_x, int block_y, int block_z) noexcept;

/// Batch variant over a regular block grid. Input sample arrays use the
/// flat index `(iy * nz + iz) * nx + ix`; output uses the same layout.
void sample_grid(const double* vein_toggle,
                 const double* vein_ridged,
                 const double* vein_gap,
                 int nx, int ny, int nz,
                 const rng::Splitter& splitter,
                 int block_x0, int block_y0, int block_z0,
                 int block_dx, int block_dy, int block_dz,
                 OreVeinResult* out) noexcept;

} // namespace lattice::world::gen::orevein
