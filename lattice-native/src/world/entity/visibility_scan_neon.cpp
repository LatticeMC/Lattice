// NEON specialisation of the visibility scan. AArch64 NEON has 2-wide
// float64 lanes (`float64x2_t`), so we process 2 players per iteration.
// The AArch64 baseline already implies NEON; this TU is compiled with
// the baseline ISA + `-O3` for the OBJECT-library setup.
//
// Same determinism guarantee as the AVX2 path: no fused multiply-add,
// so the squared-distance computation is bit-exact with the scalar
// `dx*dx + dy*dy + dz*dz` chain.

#include "world/entity/visibility_scan.hpp"

#include <cstring>  // std::memset

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)

void scan_neon(const double* entity_xyz, const double* entity_range_sq,
               std::size_t entity_count,
               const double* player_xyz,
               std::size_t player_count,
               std::uint64_t* visibility) noexcept {
    if (entity_count == 0 || !visibility) return;
    const std::size_t row_l = row_longs(player_count);
    std::memset(visibility, 0, row_l * entity_count * sizeof(std::uint64_t));
    if (!entity_xyz || !entity_range_sq || !player_xyz || player_count == 0) return;

    const std::size_t full_chunks = player_count / 2;
    const std::size_t tail        = player_count % 2;

    for (std::size_t i = 0; i < entity_count; ++i) {
        const float64x2_t ex = vdupq_n_f64(entity_xyz[i * 3 + 0]);
        const float64x2_t ey = vdupq_n_f64(entity_xyz[i * 3 + 1]);
        const float64x2_t ez = vdupq_n_f64(entity_xyz[i * 3 + 2]);
        const float64x2_t r2 = vdupq_n_f64(entity_range_sq[i]);
        std::uint64_t* row = visibility + i * row_l;

        for (std::size_t cj = 0; cj < full_chunks; ++cj) {
            const std::size_t base = cj * 2;
            const float64x2_t px = {
                player_xyz[(base + 0) * 3 + 0],
                player_xyz[(base + 1) * 3 + 0]
            };
            const float64x2_t py = {
                player_xyz[(base + 0) * 3 + 1],
                player_xyz[(base + 1) * 3 + 1]
            };
            const float64x2_t pz = {
                player_xyz[(base + 0) * 3 + 2],
                player_xyz[(base + 1) * 3 + 2]
            };

            const float64x2_t dx = vsubq_f64(px, ex);
            const float64x2_t dy = vsubq_f64(py, ey);
            const float64x2_t dz = vsubq_f64(pz, ez);

            const float64x2_t dx2 = vmulq_f64(dx, dx);
            const float64x2_t dy2 = vmulq_f64(dy, dy);
            const float64x2_t dz2 = vmulq_f64(dz, dz);
            const float64x2_t d2  = vaddq_f64(vaddq_f64(dx2, dy2), dz2);

            // vcleq_f64 returns all-ones lanes for d2 <= r2.
            const uint64x2_t le = vcleq_f64(d2, r2);
            // Extract per-lane 0 / 1 from the 2-lane comparison.
            const std::uint64_t lane0 = vgetq_lane_u64(le, 0) & 1u;
            const std::uint64_t lane1 = vgetq_lane_u64(le, 1) & 1u;

            if (lane0) {
                const std::size_t j = base + 0;
                row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
            }
            if (lane1) {
                const std::size_t j = base + 1;
                row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
            }
        }

        // Tail (at most 1 player on AArch64 since width is 2).
        if (tail) {
            const std::size_t j = full_chunks * 2;
            const double dx = player_xyz[j * 3 + 0] - entity_xyz[i * 3 + 0];
            const double dy = player_xyz[j * 3 + 1] - entity_xyz[i * 3 + 1];
            const double dz = player_xyz[j * 3 + 2] - entity_xyz[i * 3 + 2];
            const double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= entity_range_sq[i]) {
                row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
            }
        }
    }
}

#endif // aarch64

} // namespace lattice::world::entity
