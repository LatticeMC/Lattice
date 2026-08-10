// AVX-512 specialisation of the visibility scan. It retains the scalar
// rounding sequence and processes eight player positions per entity.

#include "world/entity/visibility_scan.hpp"

#include <cstring>

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

void scan_avx512(const double* entity_xyz, const double* entity_range_sq,
                 std::size_t entity_count,
                 const double* player_xyz,
                 std::size_t player_count,
                 std::uint64_t* visibility) noexcept {
    if (entity_count == 0 || !visibility) return;
    const std::size_t row_l = row_longs(player_count);
    std::memset(visibility, 0, row_l * entity_count * sizeof(std::uint64_t));
    if (!entity_xyz || !entity_range_sq || !player_xyz || player_count == 0) return;

    const std::size_t full_chunks = player_count / 8;
    const std::size_t tail = player_count % 8;

    for (std::size_t i = 0; i < entity_count; ++i) {
        const __m512d ex = _mm512_set1_pd(entity_xyz[i * 3 + 0]);
        const __m512d ey = _mm512_set1_pd(entity_xyz[i * 3 + 1]);
        const __m512d ez = _mm512_set1_pd(entity_xyz[i * 3 + 2]);
        const __m512d r2 = _mm512_set1_pd(entity_range_sq[i]);
        std::uint64_t* row = visibility + i * row_l;

        for (std::size_t chunk = 0; chunk < full_chunks; ++chunk) {
            const std::size_t base = chunk * 8;
            const __m512d px = _mm512_setr_pd(
                player_xyz[(base + 0) * 3], player_xyz[(base + 1) * 3],
                player_xyz[(base + 2) * 3], player_xyz[(base + 3) * 3],
                player_xyz[(base + 4) * 3], player_xyz[(base + 5) * 3],
                player_xyz[(base + 6) * 3], player_xyz[(base + 7) * 3]);
            const __m512d py = _mm512_setr_pd(
                player_xyz[(base + 0) * 3 + 1], player_xyz[(base + 1) * 3 + 1],
                player_xyz[(base + 2) * 3 + 1], player_xyz[(base + 3) * 3 + 1],
                player_xyz[(base + 4) * 3 + 1], player_xyz[(base + 5) * 3 + 1],
                player_xyz[(base + 6) * 3 + 1], player_xyz[(base + 7) * 3 + 1]);
            const __m512d pz = _mm512_setr_pd(
                player_xyz[(base + 0) * 3 + 2], player_xyz[(base + 1) * 3 + 2],
                player_xyz[(base + 2) * 3 + 2], player_xyz[(base + 3) * 3 + 2],
                player_xyz[(base + 4) * 3 + 2], player_xyz[(base + 5) * 3 + 2],
                player_xyz[(base + 6) * 3 + 2], player_xyz[(base + 7) * 3 + 2]);

            const __m512d dx = _mm512_sub_pd(px, ex);
            const __m512d dy = _mm512_sub_pd(py, ey);
            const __m512d dz = _mm512_sub_pd(pz, ez);
            const __m512d d2 = _mm512_add_pd(
                _mm512_add_pd(_mm512_mul_pd(dx, dx), _mm512_mul_pd(dy, dy)),
                _mm512_mul_pd(dz, dz));
            const std::uint8_t mask = static_cast<std::uint8_t>(
                _mm512_cmp_pd_mask(d2, r2, _CMP_LE_OQ));
            if (mask != 0) {
                row[base >> 6] |= static_cast<std::uint64_t>(mask) << (base & 63);
            }
        }

        const double ex_s = entity_xyz[i * 3 + 0];
        const double ey_s = entity_xyz[i * 3 + 1];
        const double ez_s = entity_xyz[i * 3 + 2];
        const double r2_s = entity_range_sq[i];
        for (std::size_t j = full_chunks * 8; j < full_chunks * 8 + tail; ++j) {
            const double dx = player_xyz[j * 3 + 0] - ex_s;
            const double dy = player_xyz[j * 3 + 1] - ey_s;
            const double dz = player_xyz[j * 3 + 2] - ez_s;
            const double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 <= r2_s) row[j >> 6] |= std::uint64_t{1} << (j & 63);
        }
    }
}

} // namespace lattice::world::entity
