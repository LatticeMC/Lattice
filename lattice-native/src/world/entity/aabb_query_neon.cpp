// NEON specialisation of the AABB intersect scan. 2-wide float64x2_t
// lanes — 2 entities per inner iteration. AArch64 baseline implies
// NEON; this TU is just an OBJECT-library separation so the runtime
// dispatcher has a per-architecture symbol to swap in.

#include "world/entity/aabb_query.hpp"

#include <cstring>

#if defined(__aarch64__) || defined(_M_ARM64)
#  include <arm_neon.h>
#endif

namespace lattice::world::entity {

#if defined(__aarch64__) || defined(_M_ARM64)

void aabb_scan_neon(const double* query_aabbs, std::size_t query_count,
                    const double* entity_aabbs, std::size_t entity_count,
                    std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || entity_count == 0 || !entity_aabbs) return;

    const std::size_t full_chunks = entity_count / 2;
    const std::size_t tail        = entity_count % 2;

    for (std::size_t q = 0; q < query_count; ++q) {
        const float64x2_t qMinX = vdupq_n_f64(query_aabbs[q * kAabbStride + 0]);
        const float64x2_t qMinY = vdupq_n_f64(query_aabbs[q * kAabbStride + 1]);
        const float64x2_t qMinZ = vdupq_n_f64(query_aabbs[q * kAabbStride + 2]);
        const float64x2_t qMaxX = vdupq_n_f64(query_aabbs[q * kAabbStride + 3]);
        const float64x2_t qMaxY = vdupq_n_f64(query_aabbs[q * kAabbStride + 4]);
        const float64x2_t qMaxZ = vdupq_n_f64(query_aabbs[q * kAabbStride + 5]);
        std::uint64_t* row = visibility + q * row_l;

        for (std::size_t ce = 0; ce < full_chunks; ++ce) {
            const std::size_t base = ce * 2;
            const double* p0 = entity_aabbs + (base + 0) * kAabbStride;
            const double* p1 = entity_aabbs + (base + 1) * kAabbStride;

            const float64x2_t eMinX = { p0[0], p1[0] };
            const float64x2_t eMinY = { p0[1], p1[1] };
            const float64x2_t eMinZ = { p0[2], p1[2] };
            const float64x2_t eMaxX = { p0[3], p1[3] };
            const float64x2_t eMaxY = { p0[4], p1[4] };
            const float64x2_t eMaxZ = { p0[5], p1[5] };

            // qMin <= eMax AND qMax >= eMin per axis.
            const uint64x2_t cmpXa = vcleq_f64(qMinX, eMaxX);
            const uint64x2_t cmpXb = vcgeq_f64(qMaxX, eMinX);
            const uint64x2_t cmpYa = vcleq_f64(qMinY, eMaxY);
            const uint64x2_t cmpYb = vcgeq_f64(qMaxY, eMinY);
            const uint64x2_t cmpZa = vcleq_f64(qMinZ, eMaxZ);
            const uint64x2_t cmpZb = vcgeq_f64(qMaxZ, eMinZ);

            const uint64x2_t ax  = vandq_u64(cmpXa, cmpXb);
            const uint64x2_t ay  = vandq_u64(cmpYa, cmpYb);
            const uint64x2_t az  = vandq_u64(cmpZa, cmpZb);
            const uint64x2_t hit = vandq_u64(vandq_u64(ax, ay), az);

            const std::uint64_t lane0 = vgetq_lane_u64(hit, 0) & 1u;
            const std::uint64_t lane1 = vgetq_lane_u64(hit, 1) & 1u;

            if (lane0) {
                const std::size_t e = base + 0;
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
            if (lane1) {
                const std::size_t e = base + 1;
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
        }

        if (tail) {
            const std::size_t e = full_chunks * 2;
            const double* p = entity_aabbs + e * kAabbStride;
            const double qMinX_s = query_aabbs[q * kAabbStride + 0];
            const double qMinY_s = query_aabbs[q * kAabbStride + 1];
            const double qMinZ_s = query_aabbs[q * kAabbStride + 2];
            const double qMaxX_s = query_aabbs[q * kAabbStride + 3];
            const double qMaxY_s = query_aabbs[q * kAabbStride + 4];
            const double qMaxZ_s = query_aabbs[q * kAabbStride + 5];
            const bool overlap =
                qMinX_s <= p[3] && qMaxX_s >= p[0] &&
                qMinY_s <= p[4] && qMaxY_s >= p[1] &&
                qMinZ_s <= p[5] && qMaxZ_s >= p[2];
            if (overlap) row[e >> 6] |= std::uint64_t{1} << (e & 63);
        }
    }
}

#endif // aarch64

} // namespace lattice::world::entity
