#include "world/heightmap/heightmap_scan.hpp"

#include <immintrin.h>

#if defined(_MSC_VER)
#  include <intrin.h>
#endif

namespace lattice::world::heightmap {

namespace {

constexpr int kRemainingLongs = 4;

bool can_use_4bit_path(const SectionView* sections, std::size_t section_count) noexcept {
    if (!sections || section_count == 0) return false;
    for (std::size_t i = 0; i < section_count; ++i) {
        const SectionView& sv = sections[i];
        if (!sv.passing_mask) return false;
        if (sv.storage == nullptr || sv.element_bits != 4) return false;
    }
    return true;
}

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

bool mask_any_avx2(const std::uint64_t* mask, std::size_t mask_longs) noexcept {
    if (!mask) return false;
    const __m256i zero = _mm256_setzero_si256();
    std::size_t i = 0;
    for (; i + 4 <= mask_longs; i += 4) {
        const __m256i v = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(mask + i));
        const __m256i eq = _mm256_cmpeq_epi64(v, zero);
        if (_mm256_movemask_pd(_mm256_castsi256_pd(eq)) != 0xF) return true;
    }
    return detail::mask_any_scalar(mask + i, mask_longs - i);
}

bool mask_bit(const std::uint64_t* mask,
              std::size_t mask_longs,
              std::uint32_t bit_index) noexcept {
    const std::size_t word = bit_index / 64u;
    if (word >= mask_longs) return false;
    return (mask[word] >> (bit_index % 64)) & 1ULL;
}

void pass_mask_for_4bit(const SectionView& sv,
                        int y_local,
                        std::size_t mask_longs,
                        std::uint64_t* passing_columns) noexcept {
    if (!passing_columns) return;
    for (int i = 0; i < kRemainingLongs; ++i) passing_columns[i] = 0;
    const std::size_t row_base = static_cast<std::size_t>(y_local) * 16u;
    for (int z = 0; z < 16; ++z) {
        const std::size_t long_base = row_base + static_cast<std::size_t>(z);
        const std::uint64_t packed = sv.storage[long_base];
        for (int x = 0; x < 16; ++x) {
            const std::uint32_t pal_idx = static_cast<std::uint32_t>((packed >> (x * 4)) & 0xFULL);
            if (mask_bit(sv.passing_mask, mask_longs, pal_idx)) {
                const int column = z * 16 + x;
                passing_columns[column >> 6] |= std::uint64_t{1} << (column & 63);
            }
        }
    }
}

std::size_t fill_default_section_avx2(std::uint64_t* remaining,
                                      std::int32_t* out_heights,
                                      std::int32_t y) noexcept {
    if (!remaining || !out_heights) return 0;
    const __m256i vy = _mm256_set1_epi32(y);
    std::size_t filled = 0;
    for (int w = 0; w < 4; ++w) {
        std::uint64_t bits = remaining[w];
        if (bits == 0) continue;
        filled += static_cast<std::size_t>(popcount64(bits));
        std::int32_t* row = out_heights + w * 64;
        if (bits == ~std::uint64_t{0}) {
            for (int i = 0; i < 64; i += 8) {
                _mm256_storeu_si256(reinterpret_cast<__m256i*>(row + i), vy);
            }
        } else {
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

std::size_t populate_4bit_avx2(const SectionView* sections,
                               std::size_t section_count,
                               int section_base_y,
                               std::size_t mask_longs,
                               int default_height,
                               std::int32_t* out_heights) noexcept {
    if (!out_heights) return 0;
    for (int i = 0; i < kColumnCount; ++i) out_heights[i] = default_height;
    if (!sections || section_count == 0 || mask_longs == 0) return 0;

    std::uint64_t remaining[kRemainingLongs] = {
        ~std::uint64_t{0}, ~std::uint64_t{0},
        ~std::uint64_t{0}, ~std::uint64_t{0}
    };
    std::size_t remaining_count = kColumnCount;

    for (std::size_t s_iter = section_count; s_iter > 0; --s_iter) {
        const std::size_t s = s_iter - 1;
        const SectionView& sv = sections[s];
        if (!mask_any_avx2(sv.passing_mask, mask_longs)) continue;

        const int section_world_y_floor = section_base_y + static_cast<int>(s) * kSectionHeight;
        for (int y_local = kSectionHeight - 1; y_local >= 0; --y_local) {
            const std::int32_t world_y = section_world_y_floor + y_local;
            std::uint64_t row_pass[kRemainingLongs];
            pass_mask_for_4bit(sv, y_local, mask_longs, row_pass);
            for (int w = 0; w < kRemainingLongs; ++w) {
                std::uint64_t hits = remaining[w] & row_pass[w];
                while (hits != 0) {
                    const int bit = ctz64(hits);
                    hits &= hits - 1;
                    out_heights[w * 64 + bit] = world_y;
                    remaining[w] &= ~(std::uint64_t{1} << bit);
                    --remaining_count;
                }
            }
            if (remaining_count == 0) break;
        }
        if (remaining_count == 0) break;
    }

    return static_cast<std::size_t>(kColumnCount - remaining_count);
}

} // namespace

std::size_t populate_avx2(const SectionView* sections,
                          std::size_t section_count,
                          int section_base_y,
                          std::size_t mask_longs,
                          int default_height,
                          std::int32_t* out_heights) noexcept {
    if (can_use_4bit_path(sections, section_count)) {
        return populate_4bit_avx2(sections, section_count, section_base_y, mask_longs, default_height, out_heights);
    }
    return detail::populate_with_mask_any(
        sections, section_count, section_base_y, mask_longs, default_height, out_heights,
        &mask_any_avx2,
        &fill_default_section_avx2);
}

} // namespace lattice::world::heightmap
