/**
 * @file config.hpp
 * @brief Lattice library compile-time configuration.
 *
 * Runtime tunables (cache sizes, thread-pool sizes, …) are intentionally
 * *not* here: each module owns its own configuration to avoid the kind of
 * cross-module coupling that produced the legacy code archived under
 * `attic/`. Add new flags in this header only when they genuinely affect
 * every translation unit.
 */

#pragma once

#include <cstddef>
#include <cstdint>

namespace lattice::config {

// Whether SIMD specialisations are compiled in. Even when ON, the actual
// implementation chosen at runtime is selected by `lattice::cpu::features()`.
inline constexpr bool enable_simd =
#ifdef LATTICE_DISABLE_SIMD
    false;
#else
    true;
#endif

// Java-side may set `-Dlattice.verify=true` to run native and Java paths
// side-by-side and bit-exact compare results. The native side respects the
// same flag and may emit additional bookkeeping. Off by default (zero cost).
inline constexpr bool verify_default = false;

} // namespace lattice::config
