#include "world/heightmap/heightmap_scan.hpp"

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

#if defined(_MSC_VER)
#  include <intrin.h>
#endif

namespace lattice::world::heightmap {

namespace {

int ctz64(std::uint64_t x) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_ctzll(x);
#elif defined(_MSC_VER)
    unsigned long idx = 0;
    _BitScanForward64(&idx, x);
    return static_cast<int>(idx);
#else
    int n = 0;
    while ((x & 1u) == 0) { x >>= 1; ++n; }
    return n;
#endif
}

int popcount64(std::uint64_t x) noexcept {
#if defined(__GNUC__) || defined(__clang__)
    return __builtin_popcountll(x);
#elif defined(_MSC_VER)
    return static_cast<int>(__popcnt64(x));
#else
    int n = 0;
    while (x) { x &= x - 1; ++n; }
    return n;
#endif
}

bool mask_any_neon(const std::uint64_t* mask, std::size_t mask_longs) noexcept {
    if (!mask) return false;
#if defined(__aarch64__) || defined(_M_ARM64)
    std::size_t i = 0;
    uint64x2_t acc = vdupq_n_u64(0);
    for (; i + 2 <= mask_longs; i += 2) {
        acc = vorrq_u64(acc, vld1q_u64(mask + i));
    }
    if ((vgetq_lane_u64(acc, 0) | vgetq_lane_u64(acc, 1)) != 0) return true;
    return detail::mask_any_scalar(mask + i, mask_longs - i);
#else
    return detail::mask_any_scalar(mask, mask_longs);
#endif
}

std::size_t fill_default_section_neon(std::uint64_t* remaining,
                                      std::int32_t* out_heights,
                                      std::int32_t y) noexcept {
    if (!remaining || !out_heights) return 0;
    std::size_t filled = 0;
#if defined(__aarch64__) || defined(_M_ARM64)
    const int32x4_t vy = vdupq_n_s32(y);
#endif
    for (int w = 0; w < 4; ++w) {
        std::uint64_t bits = remaining[w];
        if (bits == 0) continue;
        filled += static_cast<std::size_t>(popcount64(bits));
        std::int32_t* row = out_heights + w * 64;
#if defined(__aarch64__) || defined(_M_ARM64)
        if (bits == ~std::uint64_t{0}) {
            for (int i = 0; i < 64; i += 4) {
                vst1q_s32(row + i, vy);
            }
        } else
#endif
        {
            while (bits != 0) {
                const int bit = ctz64(bits);
                bits &= bits - 1;
                row[bit] = y;
            }
        }
        remaining[w] = 0;
    }
    return filled;
}

} // namespace

std::size_t populate_neon(const SectionView* sections,
                          std::size_t section_count,
                          int section_base_y,
                          std::size_t mask_longs,
                          int default_height,
                          std::int32_t* out_heights) noexcept {
    return detail::populate_with_mask_any(
        sections, section_count, section_base_y, mask_longs, default_height, out_heights,
        &mask_any_neon,
        &fill_default_section_neon);
}

} // namespace lattice::world::heightmap
