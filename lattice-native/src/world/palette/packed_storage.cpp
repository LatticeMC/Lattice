// Scalar reference implementation for bit-packed long[] storage, plus
// the runtime dispatcher. This TU is compiled at the baseline ISA (SSE2
// on x86, armv8-a on ARM) and is always valid to run. SIMD specialisations
// live in sibling TUs (packed_storage_bmi2.cpp, …) compiled with extra
// flags; the dispatcher decides at first call which to use.

#include "world/palette/packed_storage.hpp"

#include <atomic>

#include "lattice/dispatch.hpp"

namespace lattice::world::palette {

// ---- Single-element get/set ----------------------------------------------
//
// Trivial implementation. When `element_bits` is known at compile time at
// the call site (uncommon for our hot paths, but true for tests), compilers
// replace the division with a constant-folded magic-number multiply. When
// it isn't, the divide is ~5–20 cycles — negligible next to the JNI call
// overhead, so we don't bother with the vanilla magic-number table.

std::uint32_t get(const std::uint64_t* data, int element_bits,
                  std::size_t index) noexcept {
    if (data == nullptr || element_bits <= 0 || element_bits > 32) return 0;
    const int epl = elements_per_long(element_bits);
    if (epl == 0) return 0;
    const std::size_t long_index = index / static_cast<std::size_t>(epl);
    const int         bit_off    = int(index % static_cast<std::size_t>(epl)) * element_bits;
    const std::uint64_t mask     = mask_for(element_bits);
    return static_cast<std::uint32_t>((data[long_index] >> bit_off) & mask);
}

std::uint32_t set(std::uint64_t* data, int element_bits,
                  std::size_t index, std::uint32_t value) noexcept {
    if (data == nullptr || element_bits <= 0 || element_bits > 32) return 0;
    const int epl = elements_per_long(element_bits);
    if (epl == 0) return 0;
    const std::size_t long_index = index / static_cast<std::size_t>(epl);
    const int         bit_off    = int(index % static_cast<std::size_t>(epl)) * element_bits;
    const std::uint64_t mask     = mask_for(element_bits);

    const std::uint64_t word = data[long_index];
    const std::uint32_t old  = static_cast<std::uint32_t>((word >> bit_off) & mask);
    const std::uint64_t v64  = static_cast<std::uint64_t>(value) & mask;
    data[long_index] = (word & ~(mask << bit_off)) | (v64 << bit_off);
    return old;
}

// ---- Bulk get/set: scalar reference --------------------------------------
//
// Iterate longs, extracting the packed elements in sequence. Already much
// faster than a get()-loop because the divide and the mask computation are
// hoisted out of the per-element step.

void bulk_get_scalar(const std::uint64_t* data, int element_bits,
                     std::size_t start_index, std::size_t count,
                     std::uint32_t* out) noexcept {
    if (!data || !out || element_bits <= 0 || element_bits > 32 || count == 0) return;
    const int epl   = elements_per_long(element_bits);
    if (epl == 0) return;
    const std::uint64_t mask = mask_for(element_bits);

    std::size_t long_index = start_index / static_cast<std::size_t>(epl);
    int         slot       = int(start_index % static_cast<std::size_t>(epl));
    std::uint64_t word     = data[long_index];
    std::size_t produced   = 0;

    // Drain the leading partial long, then process full longs, then the tail.
    while (produced < count) {
        const int bit_off = slot * element_bits;
        out[produced++]   = static_cast<std::uint32_t>((word >> bit_off) & mask);
        if (++slot == epl) {
            if (produced == count) break;
            slot = 0;
            ++long_index;
            word = data[long_index];
        }
    }
}

void bulk_set_scalar(std::uint64_t* data, int element_bits,
                     std::size_t start_index, std::size_t count,
                     const std::uint32_t* in) noexcept {
    if (!data || !in || element_bits <= 0 || element_bits > 32 || count == 0) return;
    const int epl   = elements_per_long(element_bits);
    if (epl == 0) return;
    const std::uint64_t mask = mask_for(element_bits);

    std::size_t long_index = start_index / static_cast<std::size_t>(epl);
    int         slot       = int(start_index % static_cast<std::size_t>(epl));
    std::uint64_t word     = data[long_index];
    std::size_t consumed   = 0;

    while (consumed < count) {
        const int bit_off = slot * element_bits;
        const std::uint64_t v = static_cast<std::uint64_t>(in[consumed++]) & mask;
        word = (word & ~(mask << bit_off)) | (v << bit_off);
        if (++slot == epl) {
            data[long_index] = word;
            if (consumed == count) return;
            slot = 0;
            ++long_index;
            word = data[long_index];
        }
    }
    // Flush the partial trailing long.
    data[long_index] = word;
}

// ---- Runtime dispatch -----------------------------------------------------
//
// `bulk_get` / `bulk_set` look up the implementation function pointer once
// (lazy first-call init) and tail-call through it for every subsequent
// call. The dispatcher only ever swaps from the scalar default to a
// faster implementation; it never downgrades, so a relaxed atomic load is
// sufficient for the hot path.

namespace {

using BulkGetFn = void (*)(const std::uint64_t*, int, std::size_t,
                           std::size_t, std::uint32_t*) noexcept;
using BulkSetFn = void (*)(std::uint64_t*, int, std::size_t,
                           std::size_t, const std::uint32_t*) noexcept;

std::atomic<BulkGetFn> g_bulk_get{&bulk_get_scalar};
std::atomic<BulkSetFn> g_bulk_set{&bulk_set_scalar};
std::atomic<bool>      g_dispatch_initialised{false};

} // namespace

void init_palette_dispatch() noexcept {
    // Idempotent — only ever picks the same implementation per CPU, so
    // racing initialisers settle on the same answer.
    if (g_dispatch_initialised.load(std::memory_order_acquire)) return;

    BulkGetFn get_fn = &bulk_get_scalar;
    BulkSetFn set_fn = &bulk_set_scalar;

    const auto& f = lattice::cpu::features();
    (void)f; // referenced under at least one of the arch branches below

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    // PEXT / PDEP are usable as long as BMI2 is present; the "fast" flag
    // gates whether we *prefer* them over the scalar path. On Zen 1/2 the
    // microcoded PEXT is ~18 cycles, slower than a tuned scalar loop, so
    // we don't enable the BMI2 path there even though the instructions
    // would technically execute correctly.
    if (f.bmi2 && f.bmi2_fast) {
        get_fn = &bulk_get_bmi2;
        set_fn = &bulk_set_bmi2;
    }
#elif defined(__aarch64__) || defined(_M_ARM64)
    // NEON is mandatory on AArch64, but we still gate on the feature
    // flag so a forced-scalar override (`LATTICE_CPU_FORCE_SCALAR=1`)
    // disables this path the same way it would disable AVX2 on x86.
    if (f.neon) {
        get_fn = &bulk_get_neon;
        set_fn = &bulk_set_neon;
    }
#endif

    g_bulk_get.store(get_fn, std::memory_order_release);
    g_bulk_set.store(set_fn, std::memory_order_release);
    g_dispatch_initialised.store(true, std::memory_order_release);
}

void bulk_get(const std::uint64_t* data, int element_bits,
              std::size_t start_index, std::size_t count,
              std::uint32_t* out) noexcept {
    if (!g_dispatch_initialised.load(std::memory_order_acquire)) {
        init_palette_dispatch();
    }
    g_bulk_get.load(std::memory_order_acquire)(
        data, element_bits, start_index, count, out);
}

void bulk_set(std::uint64_t* data, int element_bits,
              std::size_t start_index, std::size_t count,
              const std::uint32_t* in) noexcept {
    if (!g_dispatch_initialised.load(std::memory_order_acquire)) {
        init_palette_dispatch();
    }
    g_bulk_set.load(std::memory_order_acquire)(
        data, element_bits, start_index, count, in);
}

// ---- Random-access gather ------------------------------------------------
//
// Simple scalar implementation. SIMD specialisation would help marginally
// (manual gather + parallel shift+mask), but the JNI cost of one call
// already amortises across many indices, so the inner loop being a tight
// shift+mask sequence is usually fast enough.

void gather_get(const std::uint64_t* data, std::size_t data_len_longs,
                int element_bits,
                const std::uint32_t* indices, std::size_t count,
                std::uint32_t* out) noexcept {
    if (!data || !indices || !out || count == 0) return;
    if (element_bits <= 0 || element_bits > 32) {
        for (std::size_t i = 0; i < count; ++i) out[i] = 0;
        return;
    }
    const int          epl  = elements_per_long(element_bits);
    const std::uint64_t mask = mask_for(element_bits);
    const std::size_t   max_element = data_len_longs * static_cast<std::size_t>(epl);

    for (std::size_t i = 0; i < count; ++i) {
        const std::uint32_t idx = indices[i];
        if (idx >= max_element) {
            out[i] = 0;
            continue;
        }
        const std::size_t long_idx = idx / static_cast<std::size_t>(epl);
        const int         bit_off  = int(idx % static_cast<std::size_t>(epl)) * element_bits;
        out[i] = static_cast<std::uint32_t>((data[long_idx] >> bit_off) & mask);
    }
}

// ---- Histogram ------------------------------------------------------------

std::size_t count_unique(const std::uint64_t* data, int element_bits,
                         std::size_t size,
                         std::uint32_t* histogram,
                         std::size_t histogram_size) noexcept {
    if (!data || !histogram || element_bits <= 0 || element_bits > 32 || size == 0) {
        return 0;
    }
    const int epl = elements_per_long(element_bits);
    if (epl == 0) return 0;
    const std::uint64_t mask = mask_for(element_bits);

    // Walk full longs first, then drain the tail. Never speculatively loads
    // a trailing long past the valid range.
    std::size_t i = 0;
    std::size_t long_index = 0;
    const std::size_t full_longs = size / static_cast<std::size_t>(epl);
    for (std::size_t L = 0; L < full_longs; ++L) {
        const std::uint64_t word = data[long_index++];
        for (int slot = 0; slot < epl; ++slot) {
            const std::uint32_t v = static_cast<std::uint32_t>(
                (word >> (slot * element_bits)) & mask);
            if (static_cast<std::size_t>(v) < histogram_size) {
                ++histogram[v];
            }
        }
        i += static_cast<std::size_t>(epl);
    }
    // Tail: the last long that isn't fully packed.
    if (i < size) {
        const std::uint64_t word = data[long_index];
        int slot = 0;
        while (i < size) {
            const std::uint32_t v = static_cast<std::uint32_t>(
                (word >> (slot * element_bits)) & mask);
            if (static_cast<std::size_t>(v) < histogram_size) {
                ++histogram[v];
            }
            ++slot;
            ++i;
        }
    }
    return size;
}

} // namespace lattice::world::palette
