#include "world/entity/pathfinder.hpp"

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)
namespace {

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
    const uint8x16_t one_u8 = vdupq_n_u8(1);

    std::size_t i = 0;
    for (; i + 16 <= count; i += 16) {
        const int8x16_t types = vld1q_s8(path_types + i);
        const uint8x16_t is_blocked = vceqq_s8(types, zero);
        const uint8x16_t is_open = vceqq_s8(types, one);
        const uint8x16_t passable = vandq_u8(vmvnq_u8(is_blocked), one_u8);
        const uint8x16_t standing = vandq_u8(vandq_u8(vmvnq_u8(is_open), vmvnq_u8(is_blocked)), one_u8);
        vst1q_u8(masks.passable + i, passable);
        vst1q_u8(masks.standing + i, standing);
    }

    for (; i < count; ++i) {
        const bool passable = path_types[i] != 0;
        masks.passable[i] = passable ? 1 : 0;
        masks.standing[i] = passable && path_types[i] != 1 ? 1 : 0;
    }
}
#endif

} // namespace lattice::world::entity
