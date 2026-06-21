#include "world/entity/pathfinder.hpp"

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)
namespace {

constexpr std::size_t kMaskWordBits = 64;

[[nodiscard]] bool can_use_simple_mask(const std::int8_t* path_types,
                                       std::size_t count,
                                       const float* pathfinding_malus,
                                       int pathfinding_malus_count) noexcept {
    if (!path_types || !pathfinding_malus || pathfinding_malus_count <= 2) return false;
    if (pathfinding_malus[1] < 0.0F || pathfinding_malus[2] < 0.0F) return false;
    for (std::size_t i = 0; i < count; ++i) {
        const int type = static_cast<int>(path_types[i]);
        if (type < 0 || type > 2) return false;
    }
    return true;
}

} // namespace

void build_pathfinder_masks_neon(const std::int8_t* path_types,
                                 std::size_t count,
                                 const float* pathfinding_malus,
                                 int pathfinding_malus_count,
                                 PathfinderMasks masks) noexcept {
    if (!masks.passable || !masks.standing
            || !can_use_simple_mask(path_types, count, pathfinding_malus, pathfinding_malus_count)) {
        build_pathfinder_masks_scalar(path_types, count, pathfinding_malus,
                                      pathfinding_malus_count, masks);
        return;
    }

    const int8x16_t zero = vdupq_n_s8(0);
    const int8x16_t one = vdupq_n_s8(1);

    const std::size_t words = (count + (kMaskWordBits - 1)) / kMaskWordBits;
    for (std::size_t w = 0; w < words; ++w) {
        masks.passable[w] = 0;
        masks.standing[w] = 0;
    }

    std::size_t i = 0;
    for (; i + 16 <= count; i += 16) {
        const int8x16_t types = vld1q_s8(path_types + i);
        const uint8x16_t is_blocked = vceqq_s8(types, zero);
        const uint8x16_t is_open = vceqq_s8(types, one);
        const uint8x16_t passable_bytes = vmvnq_u8(is_blocked);
        const uint64x2_t passable_u64 = vreinterpretq_u64_u8(passable_bytes);
        const uint64x2_t open_u64 = vreinterpretq_u64_u8(is_open);
        const std::uint64_t passable_lo = vgetq_lane_u64(passable_u64, 0);
        const std::uint64_t passable_hi = vgetq_lane_u64(passable_u64, 1);
        const std::uint64_t open_lo = vgetq_lane_u64(open_u64, 0);
        const std::uint64_t open_hi = vgetq_lane_u64(open_u64, 1);
        std::uint64_t passable_mask = 0;
        std::uint64_t standing_mask = 0;
        for (int lane = 0; lane < 8; ++lane) {
            if ((passable_lo >> (lane * 8)) & 0xFFULL) passable_mask |= std::uint64_t{1} << lane;
            if ((passable_hi >> (lane * 8)) & 0xFFULL) passable_mask |= std::uint64_t{1} << (lane + 8);
            if (((passable_lo >> (lane * 8)) & 0xFFULL) && !((open_lo >> (lane * 8)) & 0xFFULL)) {
                standing_mask |= std::uint64_t{1} << lane;
            }
            if (((passable_hi >> (lane * 8)) & 0xFFULL) && !((open_hi >> (lane * 8)) & 0xFFULL)) {
                standing_mask |= std::uint64_t{1} << (lane + 8);
            }
        }
        masks.passable[i >> 6] |= passable_mask << (i & 63);
        masks.standing[i >> 6] |= standing_mask << (i & 63);
    }

    for (; i < count; ++i) {
        const bool passable = path_types[i] != 0;
        if (passable) {
            masks.passable[i >> 6] |= std::uint64_t{1} << (i & 63);
        }
        if (passable && path_types[i] != 1) {
            masks.standing[i >> 6] |= std::uint64_t{1} << (i & 63);
        }
    }
}
#endif

} // namespace lattice::world::entity
