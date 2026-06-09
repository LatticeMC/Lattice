/**
 * @file packed_storage.hpp
 * @brief Bit-packed long[] operations matching Minecraft 1.21.11
 *        `net.minecraft.util.collection.PackedIntegerArray` (class_6490).
 *
 * Storage layout (matches vanilla exactly)
 * ----------------------------------------
 * `data` is an array of 64-bit longs. Each long holds `elementsPerLong =
 * 64 / elementBits` packed elements (floor division). Bits within a long
 * are little-endian: element 0 occupies the low `elementBits` bits of
 * data[0], element 1 the next `elementBits` bits, and so on.
 *
 * Crucially, an element never straddles two longs. The wasted bits at the
 * top of each long (`64 - elementsPerLong * elementBits`) are zero. So:
 *
 *   long_index   = index / elementsPerLong
 *   bit_offset   = (index % elementsPerLong) * elementBits
 *   value        = (data[long_index] >> bit_offset) & maxValue
 *   data[long_index] = (data[long_index] & ~(maxValue << bit_offset))
 *                    | ((value & maxValue) << bit_offset)
 *
 * Vanilla uses a magic-number divide table to compute `index /
 * elementsPerLong` faster than a real `idiv`. We use plain unsigned
 * division: on every CPU we care about, division of a 32-bit unsigned
 * by a small (≤ 64) divisor is ~5–20 cycles, and modern compilers will
 * often hoist the divide as `mulhi` automatically when `elementBits`
 * is constant-folded.
 *
 * Element bits range
 * ------------------
 * Vanilla uses `elementBits ∈ [1, 32]`, but in practice block-state and
 * biome containers use `[4, 8]`, with 4 / 5 / 6 / 8 being by far the most
 * common. We support the full range; SIMD specialisations are added only
 * for the common cases.
 *
 * Thread safety
 * -------------
 * None of these helpers synchronise — they are pure functions on a
 * `long[]` view. The caller (Java side) must hold whatever locks the
 * container provides. JNI callers pin the array via
 * GetPrimitiveArrayCritical for the duration of a single call.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::world::palette {

/// Read a single packed element. Returns 0 if `element_bits` or `index`
/// is out of range (validated by the JNI layer; this function trusts its
/// inputs for the hot path).
[[nodiscard]] std::uint32_t
get(const std::uint64_t* data, int element_bits, std::size_t index) noexcept;

/// Write a single packed element. Returns the previous value at that
/// index. The caller is responsible for ensuring `value < (1 << element_bits)`.
std::uint32_t
set(std::uint64_t* data, int element_bits,
    std::size_t index, std::uint32_t value) noexcept;

/// Read `count` elements starting at `start_index` into `out[0..count)`.
/// Dispatches at runtime to the fastest available variant for the host CPU.
void bulk_get(const std::uint64_t* data, int element_bits,
              std::size_t start_index, std::size_t count,
              std::uint32_t* out) noexcept;

/// Write `count` elements from `in[0..count)` starting at `start_index`.
/// Dispatches at runtime to the fastest available variant for the host CPU.
/// Caller guarantees inputs are within `[0, (1 << element_bits))`.
void bulk_set(std::uint64_t* data, int element_bits,
              std::size_t start_index, std::size_t count,
              const std::uint32_t* in) noexcept;

/// Count the number of times each palette entry appears in
/// `data[0..size)`. `histogram[i]` is incremented (not assigned) for each
/// occurrence of value `i`; values `>= histogram_size` are silently ignored.
/// Returns the number of values seen (i.e. `size`).
std::size_t count_unique(const std::uint64_t* data, int element_bits,
                         std::size_t size,
                         std::uint32_t* histogram,
                         std::size_t histogram_size) noexcept;

/// Random-access bulk get: for each `indices[i]`, write
/// `data[indices[i]/epl] >> bit_off & mask` to `out[i]`. Unlike
/// `bulk_get` (which reads sequentially), `indices` may point anywhere
/// in the storage. Used by NativeSpawnFilter and other consumers that
/// need to probe a scattered set of positions.
///
/// Indices that exceed `(epl * data_len_in_longs)` produce 0 in `out`
/// (no out-of-bounds read; the function uses the supplied `data_len`
/// to clamp).
void gather_get(const std::uint64_t* data, std::size_t data_len_longs,
                int element_bits,
                const std::uint32_t* indices, std::size_t count,
                std::uint32_t* out) noexcept;

// ---- Direct access to scalar implementations.  Exposed so SIMD TUs can
// reuse the bit math for unaligned-tail work without duplicating the
// logic, and so tests can compare SIMD output bit-by-bit against the
// scalar reference. Do not call these in hot paths — the public
// `bulk_get` / `bulk_set` above are runtime-dispatched and pick the
// fastest available variant. ----

void bulk_get_scalar(const std::uint64_t* data, int element_bits,
                     std::size_t start_index, std::size_t count,
                     std::uint32_t* out) noexcept;

void bulk_set_scalar(std::uint64_t* data, int element_bits,
                     std::size_t start_index, std::size_t count,
                     const std::uint32_t* in) noexcept;

// ---- SIMD variants.  Forward-declared in the public header so the
// runtime dispatcher in packed_storage.cpp can take their address.
// Defined in dedicated SIMD-flag-scoped TUs. ----

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)

/// BMI2 / AVX2-accelerated bulk_get. Pre-condition:
/// `lattice::cpu::features().bmi2 && bmi2_fast`. Defined in
/// packed_storage_bmi2.cpp, compiled with -mbmi2 -mavx2.
void bulk_get_bmi2(const std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   std::uint32_t* out) noexcept;

/// BMI2 / AVX2-accelerated bulk_set. Pre-condition: bmi2 + bmi2_fast.
void bulk_set_bmi2(std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   const std::uint32_t* in) noexcept;

#endif // x86

#if defined(__aarch64__) || defined(_M_ARM64)

/// NEON-friendly bulk_get for AArch64. Pre-condition:
/// `lattice::cpu::features().neon` (mandatory on AArch64, always true).
/// Defined in packed_storage_neon.cpp.
void bulk_get_neon(const std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   std::uint32_t* out) noexcept;

/// NEON-friendly bulk_set for AArch64.
void bulk_set_neon(std::uint64_t* data, int element_bits,
                   std::size_t start_index, std::size_t count,
                   const std::uint32_t* in) noexcept;

#endif // aarch64

// ---- Dispatcher initialisation hook ----
//
// Called once on first entry. Reads lattice::cpu::features() and selects
// the best variants. Subsequent calls are O(1) through cached function
// pointers. Safe to call from multiple threads; the underlying snapshot
// is built with relaxed atomics inside dispatch.hpp.

void init_palette_dispatch() noexcept;

/// Helpers callers may want for sizing a `long[]` ahead of time.
[[nodiscard]] constexpr int elements_per_long(int element_bits) noexcept {
    return element_bits <= 0 ? 0 : 64 / element_bits;
}
[[nodiscard]] constexpr std::size_t
required_long_count(int element_bits, std::size_t size) noexcept {
    const int epl = elements_per_long(element_bits);
    if (epl == 0) return 0;
    return (size + std::size_t(epl) - 1) / std::size_t(epl);
}
[[nodiscard]] constexpr std::uint64_t mask_for(int element_bits) noexcept {
    return element_bits >= 64 ? ~std::uint64_t{0}
                              : (std::uint64_t{1} << element_bits) - 1u;
}

} // namespace lattice::world::palette
