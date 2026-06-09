// AVX2 specialisation of the visibility scan. Processes 4 players per
// iteration with `__m256d`. Compiled with -mavx2 (gcc/clang) or
// /arch:AVX2 (MSVC) into its own OBJECT library; selected at runtime
// when `lattice::cpu::features().avx2` is true.
//
// Determinism note: we explicitly avoid FMA (`_mm256_fmadd_pd`) so the
// squared-distance reduction is bit-exact with the scalar `dx*dx + dy*dy
// + dz*dz` chain. FMA would replace the second + with a fused multiply-
// add and change the rounding by ~1 ulp on some inputs — the
// diff-verify shadow on the Java side would then occasionally fail at
// the at-the-threshold boundary.

#include "world/entity/visibility_scan.hpp"

#include <cstring>  // std::memset

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

void scan_avx2(const double* entity_xyz, const double* entity_range_sq,
               std::size_t entity_count,
               const double* player_xyz,
               std::size_t player_count,
               std::uint64_t* visibility) noexcept {
    if (entity_count == 0 || !visibility) return;
    const std::size_t row_l = row_longs(player_count);
    std::memset(visibility, 0, row_l * entity_count * sizeof(std::uint64_t));
    if (!entity_xyz || !entity_range_sq || !player_xyz || player_count == 0) return;

    const std::size_t full_chunks = player_count / 4;
    const std::size_t tail        = player_count % 4;

    for (std::size_t i = 0; i < entity_count; ++i) {
        const __m256d ex = _mm256_set1_pd(entity_xyz[i * 3 + 0]);
        const __m256d ey = _mm256_set1_pd(entity_xyz[i * 3 + 1]);
        const __m256d ez = _mm256_set1_pd(entity_xyz[i * 3 + 2]);
        const __m256d r2 = _mm256_set1_pd(entity_range_sq[i]);
        std::uint64_t* row = visibility + i * row_l;

        for (std::size_t cj = 0; cj < full_chunks; ++cj) {
            const std::size_t base = cj * 4;
            // Load the 4 players' x/y/z into 4-lane vectors via gather-
            // style manual load. Player layout is AoS (x0,y0,z0,x1,y1,z1,…),
            // so each lane needs a separate scalar load. This is unavoidable
            // without changing the input layout to SoA.
            const __m256d px = _mm256_setr_pd(
                player_xyz[(base + 0) * 3 + 0],
                player_xyz[(base + 1) * 3 + 0],
                player_xyz[(base + 2) * 3 + 0],
                player_xyz[(base + 3) * 3 + 0]);
            const __m256d py = _mm256_setr_pd(
                player_xyz[(base + 0) * 3 + 1],
                player_xyz[(base + 1) * 3 + 1],
                player_xyz[(base + 2) * 3 + 1],
                player_xyz[(base + 3) * 3 + 1]);
            const __m256d pz = _mm256_setr_pd(
                player_xyz[(base + 0) * 3 + 2],
                player_xyz[(base + 1) * 3 + 2],
                player_xyz[(base + 2) * 3 + 2],
                player_xyz[(base + 3) * 3 + 2]);

            const __m256d dx = _mm256_sub_pd(px, ex);
            const __m256d dy = _mm256_sub_pd(py, ey);
            const __m256d dz = _mm256_sub_pd(pz, ez);

            // d² = dx*dx + dy*dy + dz*dz; deliberately no FMA — must
            // bit-match scalar.
            const __m256d dx2 = _mm256_mul_pd(dx, dx);
            const __m256d dy2 = _mm256_mul_pd(dy, dy);
            const __m256d dz2 = _mm256_mul_pd(dz, dz);
            const __m256d d2  = _mm256_add_pd(_mm256_add_pd(dx2, dy2), dz2);

            // `_mm256_cmp_pd(d2, r2, _CMP_LE_OQ)` returns -1 (all bits)
            // in each lane where d2 <= r2 and is ordered. The movemask
            // gives us a nibble where bit k = lane k passes.
            const __m256d le = _mm256_cmp_pd(d2, r2, _CMP_LE_OQ);
            const int mask4  = _mm256_movemask_pd(le); // 0..15

            if (mask4 != 0) {
                // Splat each bit of the 4-bit mask into the row bitmap.
                for (int lane = 0; lane < 4; ++lane) {
                    if ((mask4 >> lane) & 1) {
                        const std::size_t j = base + lane;
                        row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
                    }
                }
            }
        }

        // Tail (0..3 players).
        const double ex_s = entity_xyz[i * 3 + 0];
        const double ey_s = entity_xyz[i * 3 + 1];
        const double ez_s = entity_xyz[i * 3 + 2];
        const double r2_s = entity_range_sq[i];
        for (std::size_t j = full_chunks * 4; j < full_chunks * 4 + tail; ++j) {
            const double dx = player_xyz[j * 3 + 0] - ex_s;
            const double dy = player_xyz[j * 3 + 1] - ey_s;
            const double dz = player_xyz[j * 3 + 2] - ez_s;
            const double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2_s) {
                row[j / kBitsPerLong] |= std::uint64_t{1} << (j % kBitsPerLong);
            }
        }
    }
}

} // namespace lattice::world::entity
