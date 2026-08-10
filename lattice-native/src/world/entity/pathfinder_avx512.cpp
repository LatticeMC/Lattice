#include "world/entity/pathfinder.hpp"

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

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

void build_pathfinder_masks_avx512(const std::int8_t* path_types,
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

    const std::size_t words = (count + (kMaskWordBits - 1)) / kMaskWordBits;
    for (std::size_t word = 0; word < words; ++word) {
        masks.passable[word] = 0;
        masks.standing[word] = 0;
    }

    const __m512i zero = _mm512_setzero_si512();
    const __m512i one = _mm512_set1_epi8(1);
    std::size_t i = 0;
    for (; i + 64 <= count; i += 64) {
        const __m512i types = _mm512_loadu_si512(path_types + i);
        const std::uint64_t blocked = static_cast<std::uint64_t>(
            _mm512_cmpeq_epi8_mask(types, zero));
        const std::uint64_t open = static_cast<std::uint64_t>(
            _mm512_cmpeq_epi8_mask(types, one));
        const std::uint64_t passable = ~blocked;
        masks.passable[i >> 6] = passable;
        masks.standing[i >> 6] = passable & ~open;
    }

    for (; i < count; ++i) {
        const bool passable = path_types[i] != 0;
        if (passable) masks.passable[i >> 6] |= std::uint64_t{1} << (i & 63);
        if (passable && path_types[i] != 1) {
            masks.standing[i >> 6] |= std::uint64_t{1} << (i & 63);
        }
    }
}

} // namespace lattice::world::entity
