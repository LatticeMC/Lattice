#include "world/entity/pathfinder.hpp"

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

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

void build_pathfinder_masks_avx2(const std::int8_t* path_types,
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

    const __m256i zero = _mm256_setzero_si256();
    const __m256i one = _mm256_set1_epi8(1);
    const __m256i ones = _mm256_set1_epi8(static_cast<char>(0xFF));

    std::size_t i = 0;
    for (; i + 32 <= count; i += 32) {
        const __m256i types = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(path_types + i));
        const __m256i is_blocked = _mm256_cmpeq_epi8(types, zero);
        const __m256i is_open = _mm256_cmpeq_epi8(types, one);
        const __m256i passable_bits = _mm256_andnot_si256(is_blocked, ones);
        const __m256i standing_bits = _mm256_andnot_si256(is_open, passable_bits);
        const __m256i passable = _mm256_and_si256(passable_bits, one);
        const __m256i standing = _mm256_and_si256(standing_bits, one);
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(masks.passable + i), passable);
        _mm256_storeu_si256(reinterpret_cast<__m256i*>(masks.standing + i), standing);
    }

    for (; i < count; ++i) {
        const bool passable = path_types[i] != 0;
        masks.passable[i] = passable ? 1 : 0;
        masks.standing[i] = passable && path_types[i] != 1 ? 1 : 0;
    }
}

} // namespace lattice::world::entity
