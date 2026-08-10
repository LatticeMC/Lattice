/**
 * @file xoroshiro128pp.hpp
 * @brief Bit-exact port of Mojang's
 *        `net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom`
 *        and `Xoroshiro128PlusPlusRandomImpl`.
 *
 * Mojang's modern world-gen RNG. Two 64-bit state words; `next()`
 * returns a 64-bit word. The implementation here matches the JVM
 * exactly, including:
 *
 *   - The (-7046029254386353131L, 7640891576956012809L) all-zero
 *     fallback in the constructor.
 *   - Java's `Long.rotateLeft` which is left-rotate by `n & 63`.
 *   - The 5.9604645e-8f / 1.110223e-16 multipliers used by
 *     `nextFloat`/`nextDouble`.
 *
 * The Splitter variant (Xoroshiro128PlusPlusRandom.Splitter) is also
 * bundled, since it's the only Splitter form chunk-gen actually uses
 * (`Xoroshiro128PlusPlusRandom.nextSplitter`). Mojang's
 * `Mth.getSeed(int, int, int)` is replicated for `split(x,y,z)`.
 *
 * No state is shared globally; every instance is owned by its caller
 * and is single-threaded.
 */

#pragma once

#include <bit>
#include <cstdint>

namespace lattice::world::gen::rng {

/// Java `Long.rotateLeft(value, distance)`.
constexpr std::uint64_t java_rotate_left(std::uint64_t value, int distance) noexcept {
    // Java: distance is masked with 0x3F before the shift.
    const unsigned d = static_cast<unsigned>(distance) & 63u;
    if (d == 0) return value;
    return (value << d) | (value >> (64u - d));
}

/// `Mth.getSeed(int, int, int)` — a deterministic 64-bit scramble of
/// three signed 32-bit coordinates. Used by the splitter to derive a
/// per-(x, y, z) seed.
constexpr std::int64_t math_helper_hash_code(int x, int y, int z) noexcept {
    // Java evaluates x * 3129871 as a 32-bit int before promoting it to
    // long for the XOR. Keep every intermediate in unsigned arithmetic so
    // the required two's-complement modulo-2^N overflow is well-defined.
    const std::uint32_t x_product =
        static_cast<std::uint32_t>(x) * static_cast<std::uint32_t>(3129871);
    const std::uint64_t x_long =
        (x_product & 0x80000000u) != 0
            ? (0xFFFFFFFF00000000ULL | static_cast<std::uint64_t>(x_product))
            : static_cast<std::uint64_t>(x_product);
    const std::uint64_t z_long =
        static_cast<std::uint64_t>(static_cast<std::int64_t>(z));
    const std::uint64_t y_long =
        static_cast<std::uint64_t>(static_cast<std::int64_t>(y));
    const std::uint64_t l_init =
        (x_long ^ (z_long * 116129781ULL) ^ y_long);
    const std::uint64_t l =
        l_init * l_init * 42317861ULL + l_init * 11ULL;

    // Java's `>> 16` is arithmetic. Reconstruct the sign extension in
    // unsigned space, then copy the resulting bits into int64_t.
    std::uint64_t shifted = l >> 16;
    if ((l & 0x8000000000000000ULL) != 0) {
        shifted |= 0xFFFF000000000000ULL;
    }
    return std::bit_cast<std::int64_t>(shifted);
}

/// The plain Xoroshiro128++ generator state. `next()` is identical
/// to `Xoroshiro128PlusPlusRandomImpl.next()`.
struct Xoroshiro128PlusPlusImpl {
    std::uint64_t seed_lo;
    std::uint64_t seed_hi;

    /// Construct from two seed words. If both are zero, the JVM
    /// substitutes a fixed pair (matches
    /// `Xoroshiro128PlusPlusRandomImpl(long, long)`).
    constexpr Xoroshiro128PlusPlusImpl(std::uint64_t lo, std::uint64_t hi) noexcept
        : seed_lo(lo), seed_hi(hi) {
        if ((seed_lo | seed_hi) == 0) {
            seed_lo = static_cast<std::uint64_t>(-7046029254386353131LL);
            seed_hi = static_cast<std::uint64_t>(7640891576956012809LL);
        }
    }

    /// Advance the state and return the next 64-bit word.
    constexpr std::uint64_t next() noexcept {
        const std::uint64_t l = seed_lo;
        std::uint64_t       m = seed_hi;
        const std::uint64_t n = java_rotate_left(l + m, 17) + l;
        m       ^= l;
        seed_lo  = java_rotate_left(l, 49) ^ m ^ (m << 21);
        seed_hi  = java_rotate_left(m, 28);
        return n;
    }
};

/// User-facing Random. Wraps an Impl + the small derived-API methods
/// chunk-gen actually calls (nextLong / nextFloat / nextInt(bound) /
/// nextDouble).
struct Xoroshiro128PlusPlus {
    Xoroshiro128PlusPlusImpl impl;

    constexpr Xoroshiro128PlusPlus(std::uint64_t lo, std::uint64_t hi) noexcept
        : impl(lo, hi) {}

    [[nodiscard]] constexpr std::int64_t next_long() noexcept {
        return static_cast<std::int64_t>(impl.next());
    }

    /// Mojang's `nextInt(int bound)` — Lemire's unbiased scaling. Bound
    /// must be > 0; the C++ port treats `bound <= 0` as a programmer
    /// error and returns 0 rather than throwing (matches the project's
    /// `-fno-exceptions` policy).
    [[nodiscard]] constexpr std::int32_t next_int(std::int32_t bound) noexcept {
        if (bound <= 0) return 0;
        const std::uint32_t b = static_cast<std::uint32_t>(bound);
        std::uint64_t l = static_cast<std::uint32_t>(impl.next()); // low 32 bits, unsigned
        std::uint64_t m = l * static_cast<std::uint64_t>(b);
        std::uint64_t n = m & 0xFFFFFFFFULL;
        if (n < b) {
            const std::uint32_t threshold =
                static_cast<std::uint32_t>(((~b) + 1u) % b);
            while (n < threshold) {
                l = static_cast<std::uint32_t>(impl.next());
                m = l * static_cast<std::uint64_t>(b);
                n = m & 0xFFFFFFFFULL;
            }
        }
        return static_cast<std::int32_t>(m >> 32);
    }

    [[nodiscard]] constexpr float next_float() noexcept {
        // Mojang: float bits come from the top 24 bits of next().
        const std::uint64_t bits24 = impl.next() >> (64 - 24);
        return static_cast<float>(bits24) * 5.9604645e-8f;
    }

    [[nodiscard]] constexpr double next_double() noexcept {
        // Mojang: top 53 bits, multiplied by `(double)1.110223E-16f`.
        // The constant is a *float* literal that's promoted to double
        // — using the double literal `1.110223e-16` yields a slightly
        // different value. Match the JVM bit-for-bit.
        const std::uint64_t bits53 = impl.next() >> (64 - 53);
        return static_cast<double>(bits53)
             * static_cast<double>(1.110223e-16f);
    }

    [[nodiscard]] constexpr bool next_boolean() noexcept {
        return (impl.next() & 1ULL) != 0;
    }
};

/// `Xoroshiro128PlusPlusRandom.Splitter` — derives child Randoms from
/// (x, y, z) coordinates or from a single 64-bit `seed`. Identical
/// to vanilla including the (l ^ seedLo, seedHi) word selection.
struct Splitter {
    std::uint64_t seed_lo;
    std::uint64_t seed_hi;

    constexpr Splitter(std::uint64_t lo, std::uint64_t hi) noexcept
        : seed_lo(lo), seed_hi(hi) {}

    /// `Splitter.split(int x, int y, int z)` — returns a fresh Random
    /// whose state is `(hash(x,y,z) ^ seedLo, seedHi)`.
    [[nodiscard]] constexpr Xoroshiro128PlusPlus split(int x, int y, int z) const noexcept {
        const std::int64_t h = math_helper_hash_code(x, y, z);
        return Xoroshiro128PlusPlus(static_cast<std::uint64_t>(h) ^ seed_lo,
                                    seed_hi);
    }

    /// `Splitter.split(long seed)`.
    [[nodiscard]] constexpr Xoroshiro128PlusPlus split(std::int64_t seed) const noexcept {
        const std::uint64_t s = static_cast<std::uint64_t>(seed);
        return Xoroshiro128PlusPlus(s ^ seed_lo, s ^ seed_hi);
    }
};

} // namespace lattice::world::gen::rng
