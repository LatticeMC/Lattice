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

    const std::size_t words = (count + (kMaskWordBits - 1)) / kMaskWordBits;
    for (std::size_t w = 0; w < words; ++w) {
        masks.passable[w] = 0;
        masks.standing[w] = 0;
    }

    std::size_t i = 0;
    for (; i + 32 <= count; i += 32) {
        const __m256i types = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(path_types + i));
        const __m256i is_blocked = _mm256_cmpeq_epi8(types, zero);
        const __m256i is_open = _mm256_cmpeq_epi8(types, one);
        const __m256i passable_bits = _mm256_andnot_si256(is_blocked, ones);
        const __m256i standing_bits = _mm256_andnot_si256(is_open, passable_bits);
        const std::uint32_t passable_mask = static_cast<std::uint32_t>(_mm256_movemask_epi8(passable_bits));
        const std::uint32_t standing_mask = static_cast<std::uint32_t>(_mm256_movemask_epi8(standing_bits));
        masks.passable[i >> 6] |= static_cast<std::uint64_t>(passable_mask) << (i & 63);
        masks.standing[i >> 6] |= static_cast<std::uint64_t>(standing_mask) << (i & 63);
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

} // namespace lattice::world::entity
