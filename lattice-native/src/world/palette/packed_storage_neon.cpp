// AArch64 NEON specialisation of `bulk_get` / `bulk_set`. Compiled into
// its own OBJECT library by CMakeLists.txt — the AArch64 baseline ISA
// already includes NEON, so no extra flags are needed; this TU exists
// just to give us a logically separate compilation unit that the
// runtime dispatcher can swap in.
//
// As with the BMI2 TU, the speedup over the scalar reference comes
// almost entirely from the compiler having `element_bits` as a
// compile-time constant in the per-bit-width specialisations: with
// -O3 the inner loops fully unroll, the masks become immediates, and
// the auto-vectoriser emits NEON shifts + bitwise-and over `uint32x4_t`
// lanes for the wider element widths.
//
// On AArch64 there is no equivalent of x86 BMI2's PEXT/PDEP, so we
// rely on `USHL` / `UMOVE` / `BIC` style NEON shifts that the compiler
// chooses naturally. Explicit `arm_neon.h` intrinsics would be a future
// micro-optimisation; the diff-verify shadow on the Java side keeps us
// honest against the scalar reference.

#include "world/palette/packed_storage.hpp"
#include "world/palette/packed_storage_simd_inl.hpp"

#include <cstddef>
#include <cstdint>

namespace lattice::world::palette {

void bulk_get_neon(const std::uint64_t* data, int element_bits,
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

    if (first_aligned > start_index) {
        const std::size_t n = first_aligned > end_idx
                            ? count
                            : first_aligned - start_index;
        bulk_get_scalar(data, element_bits, start_index, n, out);
        if (first_aligned >= end_idx) return;
        out += n;
    }

    if (last_aligned > first_aligned) {
        detail::dispatch_bulk_get_aligned(
            element_bits, data,
            first_aligned / epl_sz, last_aligned / epl_sz, out);
        out += (last_aligned - first_aligned);
    }

    if (last_aligned < end_idx) {
        bulk_get_scalar(data, element_bits, last_aligned,
                        end_idx - last_aligned, out);
    }
}

void bulk_set_neon(std::uint64_t* data, int element_bits,
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
