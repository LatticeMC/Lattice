/**
 * @file packed_info.hpp
 * @brief Bit-packed work item used by ChunkLightProvider's BFS.
 *
 * Mirrors `net.minecraft.world.chunk.light.ChunkLightProvider$PackedInfo`
 * (intermediary `class_8531`). The packed long has the layout:
 *
 *   bits  0..3  : light level [0..15]
 *   bits  4..9  : direction bits — one per `Direction`. A bit is set when
 *                 the BFS *should not* enter the cell from that direction
 *                 (i.e. the source already cleared it).
 *   bit  10     : trivial flag — opacity is direction-independent for the
 *                 corresponding block state, allowing a fast path.
 *   bit  11     : force-set flag — propagate even if the new level equals
 *                 the existing stored level (used for re-checks).
 *
 * Direction ordinal mapping matches `net.minecraft.util.math.Direction`:
 *   0 = DOWN,  1 = UP,  2 = NORTH,  3 = SOUTH,  4 = WEST,  5 = EAST.
 *
 * All operations are constexpr and trivially inlinable. They perform pure
 * 64-bit bit math; there is no SIMD specialisation because the per-call
 * cost is already a couple of cycles and the JNI boundary dominates.
 */

#pragma once

#include <cstdint>

namespace lattice::world::light {

namespace packed_info {

inline constexpr int       kLightLevelBits      = 4;
inline constexpr std::uint64_t kLightLevelMask  = 0xFULL;
inline constexpr int       kDirectionBitOffset  = 4;
inline constexpr int       kDirectionBitsCount  = 6;
inline constexpr std::uint64_t kDirectionBitMask =
    ((1ULL << kDirectionBitsCount) - 1ULL) << kDirectionBitOffset;        // 0x3F0
inline constexpr std::uint64_t kTrivialFlag      = 1ULL << 10;
inline constexpr std::uint64_t kForceSetFlag     = 1ULL << 11;

/// Direction enum mirroring `net.minecraft.util.math.Direction.ordinal()`.
enum Direction : int {
    kDown  = 0,
    kUp    = 1,
    kNorth = 2,
    kSouth = 3,
    kWest  = 4,
    kEast  = 5,
};

/// Returns the direction whose neighbour you reach by *moving in* it; the
/// ordinal of the *opposite* direction (used to mark the inbound edge as
/// already-visited when enqueuing a neighbour).
[[nodiscard]] constexpr Direction opposite(Direction d) noexcept {
    // DOWN<->UP, NORTH<->SOUTH, WEST<->EAST → flip low bit
    return static_cast<Direction>(static_cast<int>(d) ^ 1);
}

// ---- pack ----------------------------------------------------------------

/// `method_51571 packWithAllDirectionsSet(int)` — every direction bit is
/// pre-set so the seed value doesn't try to revisit any neighbour.
[[nodiscard]] constexpr std::uint64_t pack_with_all_directions_set(int light_level) noexcept {
    return (static_cast<std::uint64_t>(light_level) & kLightLevelMask)
         | kDirectionBitMask;
}

/// `method_51572 packWithOneDirectionCleared(int, Direction)` — every
/// direction *except* `cleared` is set. The cleared-direction bit being 0
/// signals "the BFS may explore this neighbour".
[[nodiscard]] constexpr std::uint64_t
pack_with_one_direction_cleared(int light_level, Direction cleared) noexcept {
    const std::uint64_t bit = 1ULL << (kDirectionBitOffset + static_cast<int>(cleared));
    return (static_cast<std::uint64_t>(light_level) & kLightLevelMask)
         | (kDirectionBitMask & ~bit);
}

/// `method_51573 packWithForce(int, boolean)` — value used by force-set
/// recomputations: optionally encodes the trivial flag.
[[nodiscard]] constexpr std::uint64_t
pack_with_force(int light_level, bool trivial) noexcept {
    std::uint64_t v = (static_cast<std::uint64_t>(light_level) & kLightLevelMask)
                    | kForceSetFlag;
    if (trivial) v |= kTrivialFlag;
    return v;
}

/// `method_51574 packWithOneDirectionCleared(int, boolean, Direction)`.
[[nodiscard]] constexpr std::uint64_t
pack_with_one_direction_cleared(int light_level, bool trivial, Direction cleared) noexcept {
    std::uint64_t v = pack_with_one_direction_cleared(light_level, cleared);
    if (trivial) v |= kTrivialFlag;
    return v;
}

/// `method_51579 packWithRepropagate(int, boolean, Direction)` — same as
/// pack_with_one_direction_cleared but additionally sets the force-set flag.
[[nodiscard]] constexpr std::uint64_t
pack_with_repropagate(int light_level, bool trivial, Direction cleared) noexcept {
    return pack_with_one_direction_cleared(light_level, trivial, cleared) | kForceSetFlag;
}

// ---- unpack --------------------------------------------------------------

[[nodiscard]] constexpr int get_light_level(std::uint64_t packed) noexcept {
    return static_cast<int>(packed & kLightLevelMask);
}

[[nodiscard]] constexpr std::uint64_t with_light_level(std::uint64_t packed,
                                                       int light_level) noexcept {
    return (packed & ~kLightLevelMask)
         | (static_cast<std::uint64_t>(light_level) & kLightLevelMask);
}

[[nodiscard]] constexpr bool is_direction_bit_set(std::uint64_t packed,
                                                  Direction d) noexcept {
    return (packed & (1ULL << (kDirectionBitOffset + static_cast<int>(d)))) != 0;
}

[[nodiscard]] constexpr std::uint64_t set_direction_bit(std::uint64_t packed,
                                                        Direction d) noexcept {
    return packed | (1ULL << (kDirectionBitOffset + static_cast<int>(d)));
}

[[nodiscard]] constexpr std::uint64_t clear_direction_bit(std::uint64_t packed,
                                                          Direction d) noexcept {
    return packed & ~(1ULL << (kDirectionBitOffset + static_cast<int>(d)));
}

[[nodiscard]] constexpr bool is_trivial(std::uint64_t packed) noexcept {
    return (packed & kTrivialFlag) != 0;
}

[[nodiscard]] constexpr bool force_set(std::uint64_t packed) noexcept {
    return (packed & kForceSetFlag) != 0;
}

/// `method_51578 packSkyLightPropagation(boolean down, boolean north,
///   boolean south, boolean west, boolean east)` — sky light variant
/// used by ChunkSkyLightProvider; bits are set for every direction whose
/// boolean argument is *true* (i.e. blocked).
///
/// Note vanilla's signature has 5 booleans (down, north, south, west, east),
/// not 6 — UP is implicit (sky light always propagates up to a fully
/// transparent column). The packed value has a 0 in the UP bit.
[[nodiscard]] constexpr std::uint64_t
pack_sky_light_propagation(bool down, bool north, bool south,
                           bool west, bool east) noexcept {
    std::uint64_t v = 0;
    if (down)  v |= 1ULL << (kDirectionBitOffset + kDown);
    if (north) v |= 1ULL << (kDirectionBitOffset + kNorth);
    if (south) v |= 1ULL << (kDirectionBitOffset + kSouth);
    if (west)  v |= 1ULL << (kDirectionBitOffset + kWest);
    if (east)  v |= 1ULL << (kDirectionBitOffset + kEast);
    // UP bit stays 0 — sky light always permits propagation upwards.
    return v;
}

} // namespace packed_info
} // namespace lattice::world::light
