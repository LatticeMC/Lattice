// BMI2 + AVX2 specialisation of `bulk_get` / `bulk_set` for bit-packed
// `long[]` storage. Compiled with `-mbmi2 -mavx2` (gcc/clang) or
// `/arch:AVX2` (MSVC) into its own OBJECT library — see CMakeLists.txt.
//
// The runtime dispatcher only calls into this TU when
// `lattice::cpu::features().bmi2_fast` is true, which excludes AMD
// Zen 1/2 microcoded PEXT/PDEP. With `-mavx2 -O3` the compiler turns
// the templated peel/pack inner loops into densely-packed AVX2 code
// for the common element widths; we don't write explicit intrinsics
// because clean compiler output beats hand-tuning at our complexity
// level.
//
// Bit-exact equivalence with the scalar reference is verified by the
// Java diff-verify shadow (`-Dlattice.verify=true`).

#include "world/palette/packed_storage.hpp"
#include "world/palette/packed_storage_simd_inl.hpp"

#include <cstddef>
#include <cstdint>

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::palette {

void bulk_get_bmi2(const std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   std::uint32_t* out) noexcept {
    if (!data || !out || element_bits <= 0 || element_bits > 32 || count == 0) return;
    const int epl = 64 / element_bits;
    if (epl <= 0) return;
    if (!detail::is_specialised_bits(element_bits)) {
        bulk_get_scalar(data, element_bits, start_index, count, out);
        return;
    }

    const std::size_t epl_sz   = static_cast<std::size_t>(epl);
    const std::size_t end_idx  = start_index + count;
    const std::size_t first_aligned = ((start_index + epl_sz - 1) / epl_sz) * epl_sz;
    const std::size_t last_aligned  = (end_idx / epl_sz) * epl_sz;

    // Leading unaligned fragment.
    if (first_aligned > start_index) {
        const std::size_t n = first_aligned > end_idx
                            ? count
                            : first_aligned - start_index;
        bulk_get_scalar(data, element_bits, start_index, n, out);
        if (first_aligned >= end_idx) return;
        out += n;
    }

    // Aligned middle.
    if (last_aligned > first_aligned) {
        detail::dispatch_bulk_get_aligned(
            element_bits, data,
            first_aligned / epl_sz, last_aligned / epl_sz, out);
        out += (last_aligned - first_aligned);
    }

    // Trailing fragment.
    if (last_aligned < end_idx) {
        bulk_get_scalar(data, element_bits, last_aligned,
                        end_idx - last_aligned, out);
    }
}

void bulk_set_bmi2(std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   const std::uint32_t* in) noexcept {
    if (!data || !in || element_bits <= 0 || element_bits > 32 || count == 0) return;
    const int epl = 64 / element_bits;
    if (epl <= 0) return;
    if (!detail::is_specialised_bits(element_bits)) {
        bulk_set_scalar(data, element_bits, start_index, count, in);
        return;
    }

    const std::size_t epl_sz   = static_cast<std::size_t>(epl);
    const std::size_t end_idx  = start_index + count;
    const std::size_t first_aligned = ((start_index + epl_sz - 1) / epl_sz) * epl_sz;
    const std::size_t last_aligned  = (end_idx / epl_sz) * epl_sz;

    if (first_aligned > start_index) {
        const std::size_t n = first_aligned > end_idx
                            ? count
                            : first_aligned - start_index;
        bulk_set_scalar(data, element_bits, start_index, n, in);
        if (first_aligned >= end_idx) return;
        in += n;
    }

    if (last_aligned > first_aligned) {
        detail::dispatch_bulk_set_aligned(
            element_bits, data,
            first_aligned / epl_sz, last_aligned / epl_sz, in);
        in += (last_aligned - first_aligned);
    }

    if (last_aligned < end_idx) {
        bulk_set_scalar(data, element_bits, last_aligned,
                        end_idx - last_aligned, in);
    }
}

} // namespace lattice::world::palette
