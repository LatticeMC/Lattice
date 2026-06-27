// Multi-section column scanner. See heightmap_scan.hpp for the contract.
//
// Algorithm:
//   - Keep a 256-bit "remaining" bitmap of columns still searching.
//     Initial state: all 1s.
//   - For each section, top-down:
//       - Quick check: if passing_mask is all zeros, the section
//         contributes nothing — skip to the next.
//       - For each y in [15..0]:
//           For each column in `remaining`:
//             Read packed index from storage (or 0 if null).
//             If passing_mask has bit set for that index:
//               Record world Y, clear the column bit in `remaining`.
//       - If remaining hit zero, return early.
//
// Storage access uses the same scalar bit-math as palette/packed_storage;
// `element_bits` is small (usually 4..8), so the divide and shift are
// hoisted out of the inner loop by branching on the known cases.
//
// The "256 bits as 4 u64" bitmap matches the passing-mask layout, so
// every per-column iteration is just bitscan-style work over those
// 4 words. With `-O3` GCC and Clang turn the inner loop into a tight
// shift+and+test sequence; on AArch64 the same code path uses NEON
// when the auto-vectoriser can prove the access pattern.

#include "world/heightmap/heightmap_scan.hpp"

#if defined(_MSC_VER)
#  include <intrin.h>
#endif

namespace lattice::world::heightmap {

namespace {

// Portable count-trailing-zeros for 64-bit. Caller guarantees x != 0.
[[nodiscard]] inline int ctz64(std::uint64_t x) noexcept {
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

[[nodiscard]] inline int popcount64(std::uint64_t x) noexcept {
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


// Packed storage index read. Same algorithm as PackedIntegerArray.get.
// `storage` is null only when `element_bits == 0` — handled separately by
// callers so we don't pay the null-check here.
template <int ElementBits>
[[nodiscard]] inline std::uint32_t read_packed_bits(const std::uint64_t* storage,
                                                    std::size_t index) noexcept {
    static_assert(ElementBits > 0 && ElementBits <= 32);
    constexpr int kElementsPerLong = 64 / ElementBits;
    constexpr std::uint64_t kMask = (std::uint64_t{1} << ElementBits) - 1u;
    const std::size_t long_index = index / static_cast<std::size_t>(kElementsPerLong);
    const int bit_off = int(index % static_cast<std::size_t>(kElementsPerLong)) * ElementBits;
    return static_cast<std::uint32_t>((storage[long_index] >> bit_off) & kMask);
}

[[nodiscard]] inline std::uint32_t read_packed_fallback(const std::uint64_t* storage,
                                                        int element_bits,
                                                        std::size_t index) noexcept {
    const int epl = 64 / element_bits;
    const std::size_t long_index = index / static_cast<std::size_t>(epl);
    const int bit_off = int(index % static_cast<std::size_t>(epl)) * element_bits;
    const std::uint64_t mask = (std::uint64_t{1} << element_bits) - 1u;
    return static_cast<std::uint32_t>((storage[long_index] >> bit_off) & mask);
}

[[nodiscard]] inline std::uint32_t read_packed(const std::uint64_t* storage,
                                               int element_bits,
                                               std::size_t index) noexcept {
    switch (element_bits) {
        case 1:  return read_packed_bits<1>(storage, index);
        case 2:  return read_packed_bits<2>(storage, index);
        case 3:  return read_packed_bits<3>(storage, index);
        case 4:  return read_packed_bits<4>(storage, index);
        case 5:  return read_packed_bits<5>(storage, index);
        case 6:  return read_packed_bits<6>(storage, index);
        case 8:  return read_packed_bits<8>(storage, index);
        case 16: return read_packed_bits<16>(storage, index);
        case 32: return read_packed_bits<32>(storage, index);
        default: return read_packed_fallback(storage, element_bits, index);
    }
}

// Test passing_mask[bit_index].
[[nodiscard]] inline bool mask_bit(const std::uint64_t* mask,
                                   std::size_t mask_longs,
                                   std::uint32_t bit_index) noexcept {
    const std::size_t word = bit_index / 64u;
    if (word >= mask_longs) return false;
    return (mask[word] >> (bit_index % 64)) & 1ULL;
}

// Returns true if `mask` has any bit set.
[[nodiscard]] inline bool mask_any(const std::uint64_t* mask,
                                   std::size_t mask_longs) noexcept {
    for (std::size_t i = 0; i < mask_longs; ++i) {
        if (mask[i] != 0) return true;
    }
    return false;
}

} // namespace

std::size_t populate(const SectionView* sections,
                     std::size_t section_count,
                     int section_base_y,
                     std::size_t mask_longs,
                     int default_height,
                     std::int32_t* out_heights) noexcept {
    if (!out_heights) return 0;
    for (int i = 0; i < kColumnCount; ++i) out_heights[i] = default_height;
    if (!sections || section_count == 0 || mask_longs == 0) return 0;

    // "Still searching" bitmap: bit `c` (c = z*16 + x, 0..255) set means
    // column c hasn't found its top passing cell yet. We use 4 u64s,
    // little-endian: word 0 covers columns 0..63, word 3 covers 192..255.
    constexpr int kRemainingLongs = 4;
    std::uint64_t remaining[kRemainingLongs] = {
        ~std::uint64_t{0}, ~std::uint64_t{0},
        ~std::uint64_t{0}, ~std::uint64_t{0}
    };
    std::size_t remaining_count = kColumnCount;

    for (std::size_t s_iter = section_count; s_iter > 0; --s_iter) {
        const std::size_t s = s_iter - 1;
        const SectionView& sv = sections[s];

        // Short-circuit: section's passing mask has no bits set.
        if (!sv.passing_mask || !mask_any(sv.passing_mask, mask_longs)) continue;

        const int section_world_y_floor = section_base_y +
            static_cast<int>(s) * kSectionHeight;

        // All-default short-circuit: when storage is null/element_bits == 0,
        // every cell has palette index 0. Either the whole section passes
        // (bit 0 of mask set) or none of it does.
        const bool is_default_section = (sv.storage == nullptr) || (sv.element_bits == 0);
        if (is_default_section) {
            if (!mask_bit(sv.passing_mask, mask_longs, 0)) continue;
            // Every cell in this section passes. The topmost matching y
            // for each *still-remaining* column is y = section_world_y_floor + 15.
            const std::int32_t y = section_world_y_floor + kSectionHeight - 1;
            for (int w = 0; w < kRemainingLongs; ++w) {
                std::uint64_t bits = remaining[w];
                if (bits == 0) continue;
                while (bits != 0) {
                    const int bit = ctz64(bits);
                    bits &= bits - 1;
                    out_heights[w * 64 + bit] = y;
                }
                remaining_count -= popcount64(remaining[w]);
                remaining[w] = 0;
            }
            if (remaining_count == 0) break;
            continue;
        }

        // Walk every y in this section from top to bottom.
        for (int y_local = kSectionHeight - 1; y_local >= 0; --y_local) {
            const std::int32_t world_y = section_world_y_floor + y_local;

            // Iterate set bits of remaining[].
            for (int w = 0; w < kRemainingLongs; ++w) {
                std::uint64_t bits = remaining[w];
                while (bits != 0) {
                    const int     bit_off = ctz64(bits);
                    bits &= bits - 1;
                    const int column  = w * 64 + bit_off;
                    const int x       = column & 0xF;
                    const int z       = (column >> 4) & 0xF;
                    // Heightmap-layout index inside the section storage:
                    //   PalettedContainer is YZX-ordered in chunk sections, i.e.
                    //   storage_index = (y_local * 16 + z) * 16 + x.
                    const std::size_t storage_index =
                        (static_cast<std::size_t>(y_local) * 16u + z) * 16u + x;
                    const std::uint32_t pal_idx = read_packed(
                        sv.storage, sv.element_bits, storage_index);
                    if (mask_bit(sv.passing_mask, mask_longs, pal_idx)) {
                        out_heights[column] = world_y;
                        // Clear this column from `remaining`.
                        remaining[w] &= ~(std::uint64_t{1} << bit_off);
                        --remaining_count;
                    }
                }
            }

            if (remaining_count == 0) break;
        }
        if (remaining_count == 0) break;
    }
    return static_cast<std::size_t>(kColumnCount - remaining_count);
}

} // namespace lattice::world::heightmap
