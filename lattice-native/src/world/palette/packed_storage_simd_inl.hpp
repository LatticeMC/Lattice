/**
 * @file packed_storage_simd_inl.hpp
 * @brief Shared header for the SIMD-friendly aligned-middle inner loops of
 *        `bulk_get` / `bulk_set`. Included by both `packed_storage_bmi2.cpp`
 *        (compiled with -mbmi2 -mavx2) and `packed_storage_neon.cpp`
 *        (compiled with the AArch64 baseline + NEON).
 *
 * Why a header? The per-element peel and pack templates are pure C++
 * bit-math that depend on `element_bits` being a compile-time constant
 * for the compiler to fully unroll the inner loop. We instantiate them
 * via a `switch (element_bits)` for the common bit widths (4, 5, 6, …,
 * 16). The resulting code shape is identical on x86 and AArch64; only
 * the instruction mix differs because the per-TU compile flags select
 * a different ISA target.
 *
 * Anything declared here lives in an anonymous-namespace-equivalent
 * (header-internal-linkage) inside `lattice::world::palette::detail`, so
 * the same symbol can appear in both SIMD TUs without an ODR clash.
 */

#pragma once

#include <cstddef>
#include <cstdint>

#if defined(_MSC_VER)
#  define LATTICE_PALETTE_ALWAYS_INLINE __forceinline
#else
#  define LATTICE_PALETTE_ALWAYS_INLINE [[gnu::always_inline]] inline
#endif

namespace lattice::world::palette::detail {

template <int kElementBits, int kElementsPerLong>
LATTICE_PALETTE_ALWAYS_INLINE void peel_long(std::uint64_t word,
                                             std::uint32_t* out) noexcept {
    constexpr std::uint64_t kMask = (std::uint64_t{1} << kElementBits) - 1u;
    for (int i = 0; i < kElementsPerLong; ++i) {
        out[i] = static_cast<std::uint32_t>((word >> (i * kElementBits)) & kMask);
    }
}

template <int kElementBits, int kElementsPerLong>
LATTICE_PALETTE_ALWAYS_INLINE std::uint64_t pack_long(const std::uint32_t* in) noexcept {
    constexpr std::uint64_t kMask = (std::uint64_t{1} << kElementBits) - 1u;
    std::uint64_t word = 0;
    for (int i = 0; i < kElementsPerLong; ++i) {
        word |= (static_cast<std::uint64_t>(in[i]) & kMask) << (i * kElementBits);
    }
    return word;
}

template <int kElementBits>
inline void bulk_get_aligned(const std::uint64_t* data,
                             std::size_t first_long, std::size_t last_long_exclusive,
                             std::uint32_t* out) noexcept {
    constexpr int kEpl = 64 / kElementBits;
    for (std::size_t L = first_long; L < last_long_exclusive; ++L) {
        peel_long<kElementBits, kEpl>(data[L], out);
        out += kEpl;
    }
}

template <int kElementBits>
inline void bulk_set_aligned(std::uint64_t* data,
                             std::size_t first_long, std::size_t last_long_exclusive,
                             const std::uint32_t* in) noexcept {
    constexpr int kEpl = 64 / kElementBits;
    for (std::size_t L = first_long; L < last_long_exclusive; ++L) {
        data[L] = pack_long<kElementBits, kEpl>(in);
        in += kEpl;
    }
}

// The element_bits-keyed switch lifted out so both SIMD TUs share the
// dispatch table. The wrapping concrete `bulk_get_*` / `bulk_set_*`
// public functions live in each TU.

LATTICE_PALETTE_ALWAYS_INLINE void
dispatch_bulk_get_aligned(int element_bits,
                          const std::uint64_t* data,
                          std::size_t first_long, std::size_t last_long_exclusive,
                          std::uint32_t* out) noexcept {
    switch (element_bits) {
        case 4:  bulk_get_aligned<4> (data, first_long, last_long_exclusive, out); break;
        case 5:  bulk_get_aligned<5> (data, first_long, last_long_exclusive, out); break;
        case 6:  bulk_get_aligned<6> (data, first_long, last_long_exclusive, out); break;
        case 7:  bulk_get_aligned<7> (data, first_long, last_long_exclusive, out); break;
        case 8:  bulk_get_aligned<8> (data, first_long, last_long_exclusive, out); break;
        case 9:  bulk_get_aligned<9> (data, first_long, last_long_exclusive, out); break;
        case 10: bulk_get_aligned<10>(data, first_long, last_long_exclusive, out); break;
        case 11: bulk_get_aligned<11>(data, first_long, last_long_exclusive, out); break;
        case 12: bulk_get_aligned<12>(data, first_long, last_long_exclusive, out); break;
        case 16: bulk_get_aligned<16>(data, first_long, last_long_exclusive, out); break;
        default:
            // For unusual bit widths we fall through to the scalar
            // reference; the caller passes its scalar implementation.
            break;
    }
}

LATTICE_PALETTE_ALWAYS_INLINE void
dispatch_bulk_set_aligned(int element_bits,
                          std::uint64_t* data,
                          std::size_t first_long, std::size_t last_long_exclusive,
                          const std::uint32_t* in) noexcept {
    switch (element_bits) {
        case 4:  bulk_set_aligned<4> (data, first_long, last_long_exclusive, in); break;
        case 5:  bulk_set_aligned<5> (data, first_long, last_long_exclusive, in); break;
        case 6:  bulk_set_aligned<6> (data, first_long, last_long_exclusive, in); break;
        case 7:  bulk_set_aligned<7> (data, first_long, last_long_exclusive, in); break;
        case 8:  bulk_set_aligned<8> (data, first_long, last_long_exclusive, in); break;
        case 9:  bulk_set_aligned<9> (data, first_long, last_long_exclusive, in); break;
        case 10: bulk_set_aligned<10>(data, first_long, last_long_exclusive, in); break;
        case 11: bulk_set_aligned<11>(data, first_long, last_long_exclusive, in); break;
        case 12: bulk_set_aligned<12>(data, first_long, last_long_exclusive, in); break;
        case 16: bulk_set_aligned<16>(data, first_long, last_long_exclusive, in); break;
        default: break;
    }
}

/// Returns true if `element_bits` is one of the hand-specialised cases
/// above. SIMD TU wrappers use this to decide whether to enter the
/// aligned middle path or just defer to the scalar reference.
[[nodiscard]] LATTICE_PALETTE_ALWAYS_INLINE bool
is_specialised_bits(int element_bits) noexcept {
    switch (element_bits) {
        case 4: case 5: case 6: case 7: case 8:
        case 9: case 10: case 11: case 12: case 16:
            return true;
        default:
            return false;
    }
}

} // namespace lattice::world::palette::detail
