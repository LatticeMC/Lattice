/**
 * @file dispatch.hpp
 * @brief Runtime CPU-feature detection and function-pointer dispatch scaffolding.
 *
 * Design notes
 * ------------
 * Every SIMD specialisation lives in its own translation unit, compiled with the
 * minimum `-m<feature>` flags needed for the instructions it uses (see CMake
 * OBJECT libraries). The main library is compiled against a conservative
 * baseline ISA (x86-64-v1 + SSE2 on x86, armv8-a on AArch64) so the resulting
 * shared library is safe to load on any CPU we claim to support. Which
 * specialisation actually runs is decided once, at `JNI_OnLoad` time, by
 * inspecting `lattice::cpu::features()` and writing function pointers that the
 * hot paths use thereafter (no per-call branch).
 *
 * The `features()` singleton is populated by `detect.cpp`:
 *   - x86/x64: CPUID (leaves 1, 7/EBX, 7/ECX) + XGETBV
 *   - AArch64 Linux/Android: getauxval(AT_HWCAP / AT_HWCAP2)
 *   - Windows: IsProcessorFeaturePresent for a minimal subset
 *   - macOS on Apple Silicon: sysctlbyname("hw.optional.*")
 *
 * A small set of manual overrides is exposed via environment variables for
 * debugging and for disabling paths that misbehave on specific hardware
 * (e.g. BMI2 PEXT on AMD Zen 1 / Zen 2, where it's microcoded and slow).
 *
 *   LATTICE_CPU_DISABLE=avx512,bmi2   -> force-off those features
 *   LATTICE_CPU_FORCE_SCALAR=1        -> ignore all SIMD features
 *   -Dlattice.nativeCpu=auto|avx2|avx512|scalar -> JVM-side tier ceiling
 */

#pragma once

#include <cstdint>

namespace lattice::cpu {

enum class RequestedTier : uint8_t {
    Auto,
    Scalar,
    Avx2,
    Avx512,
};

struct Features {
    // --- x86 / x86-64 ----------------------------------------------------
    bool sse2         : 1 = false;
    bool sse3         : 1 = false;
    bool ssse3        : 1 = false;
    bool sse41        : 1 = false;
    bool sse42        : 1 = false;
    bool popcnt       : 1 = false;
    bool avx          : 1 = false;
    bool avx2         : 1 = false;
    bool fma3         : 1 = false;
    bool bmi1         : 1 = false;
    bool bmi2         : 1 = false;   // PEXT/PDEP — slow on Zen 1 / Zen 2
    bool bmi2_fast    : 1 = false;   // true only if PEXT/PDEP are native-fast
    bool avx512f      : 1 = false;
    bool avx512bw     : 1 = false;
    bool avx512dq     : 1 = false;
    bool avx512vl     : 1 = false;
    bool avx512vbmi   : 1 = false;
    bool avx512vbmi2  : 1 = false;
    bool avx512vpopcnt: 1 = false;

    // --- AArch64 ---------------------------------------------------------
    bool neon         : 1 = false;
    bool crc32        : 1 = false;
    bool aes          : 1 = false;
    bool sve          : 1 = false;
    bool sve2         : 1 = false;

    // --- Global overrides ------------------------------------------------
    bool forced_scalar : 1 = false;  // LATTICE_CPU_FORCE_SCALAR=1
    RequestedTier requested_tier = RequestedTier::Auto;

    // --- Vendor info for logging / tuning decisions ----------------------
    enum class Vendor : uint8_t { Unknown, Intel, AMD, Apple, Arm, Other };
    Vendor vendor = Vendor::Unknown;
    uint32_t family = 0;
    uint32_t model  = 0;
};

/// Immutable feature snapshot. Populated exactly once by `initialize()`
/// (called from JNI_OnLoad or explicitly by a host). Safe to read from any
/// thread after initialisation.
const Features& features() noexcept;

/// Explicit initialisation hook. Idempotent. Returns `features()` after
/// populating the singleton from hardware and environment overrides.
const Features& initialize() noexcept;

/// Select the highest x86 SIMD tier before `initialize()` runs. Accepted
/// values are `auto`, `scalar`, `avx2`, and `avx512` (case-insensitive).
/// `avx512` remains a preference: unavailable CPU/OS state safely falls back.
/// Returns false for an invalid value or a call after initialization.
bool configure_requested_tier(const char* value) noexcept;

/// Human-readable one-line summary for logging.
const char* summary() noexcept;

} // namespace lattice::cpu
