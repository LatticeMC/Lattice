// AVX2 specialisation of the AABB intersect scan. Processes 4 entities
// per iteration with `__m256d` lanes. Compiled with -mavx2 (gcc/clang)
// or /arch:AVX2 (MSVC) into its own OBJECT library.
//
// The entity AABBs are AoS (6 doubles per entity), so each 4-wide load
// is manual (gather via _mm256_setr_pd). AVX2 has VGATHERDPS but not
// stride-6 gathers, so manual load is comparable speed and avoids the
// gather-vs-scalar performance cliff some Intel generations have.

#include "world/entity/aabb_query.hpp"

#include <cstring>

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

namespace lattice::world::entity {

void aabb_scan_avx2(const double* query_aabbs, std::size_t query_count,
                    const double* entity_aabbs, std::size_t entity_count,
                    std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || entity_count == 0 || !entity_aabbs) return;

    const std::size_t full_chunks = entity_count / 4;
    const std::size_t tail        = entity_count % 4;

    for (std::size_t q = 0; q < query_count; ++q) {
        const __m256d qMinX = _mm256_set1_pd(query_aabbs[q * kAabbStride + 0]);
        const __m256d qMinY = _mm256_set1_pd(query_aabbs[q * kAabbStride + 1]);
        const __m256d qMinZ = _mm256_set1_pd(query_aabbs[q * kAabbStride + 2]);
        const __m256d qMaxX = _mm256_set1_pd(query_aabbs[q * kAabbStride + 3]);
        const __m256d qMaxY = _mm256_set1_pd(query_aabbs[q * kAabbStride + 4]);
        const __m256d qMaxZ = _mm256_set1_pd(query_aabbs[q * kAabbStride + 5]);
        std::uint64_t* row = visibility + q * row_l;

        for (std::size_t ce = 0; ce < full_chunks; ++ce) {
            const std::size_t base = ce * 4;
            const double* p0 = entity_aabbs + (base + 0) * kAabbStride;
            const double* p1 = entity_aabbs + (base + 1) * kAabbStride;
            const double* p2 = entity_aabbs + (base + 2) * kAabbStride;
            const double* p3 = entity_aabbs + (base + 3) * kAabbStride;

            const __m256d eMinX = _mm256_setr_pd(p0[0], p1[0], p2[0], p3[0]);
            const __m256d eMinY = _mm256_setr_pd(p0[1], p1[1], p2[1], p3[1]);
            const __m256d eMinZ = _mm256_setr_pd(p0[2], p1[2], p2[2], p3[2]);
            const __m256d eMaxX = _mm256_setr_pd(p0[3], p1[3], p2[3], p3[3]);
            const __m256d eMaxY = _mm256_setr_pd(p0[4], p1[4], p2[4], p3[4]);
            const __m256d eMaxZ = _mm256_setr_pd(p0[5], p1[5], p2[5], p3[5]);

            // Intersection: per-axis qMin < eMax AND qMax > eMin.
            const __m256d cmpXa = _mm256_cmp_pd(qMinX, eMaxX, _CMP_LT_OQ);
            const __m256d cmpXb = _mm256_cmp_pd(qMaxX, eMinX, _CMP_GT_OQ);
            const __m256d cmpYa = _mm256_cmp_pd(qMinY, eMaxY, _CMP_LT_OQ);
            const __m256d cmpYb = _mm256_cmp_pd(qMaxY, eMinY, _CMP_GT_OQ);
            const __m256d cmpZa = _mm256_cmp_pd(qMinZ, eMaxZ, _CMP_LT_OQ);
            const __m256d cmpZb = _mm256_cmp_pd(qMaxZ, eMinZ, _CMP_GT_OQ);

            const __m256d ax = _mm256_and_pd(cmpXa, cmpXb);
            const __m256d ay = _mm256_and_pd(cmpYa, cmpYb);
            const __m256d az = _mm256_and_pd(cmpZa, cmpZb);
            const __m256d hit = _mm256_and_pd(_mm256_and_pd(ax, ay), az);

            const int mask4 = _mm256_movemask_pd(hit); // 0..15
            if (mask4 != 0) {
                for (int lane = 0; lane < 4; ++lane) {
                    if ((mask4 >> lane) & 1) {
                        const std::size_t e = base + lane;
                        row[e >> 6] |= std::uint64_t{1} << (e & 63);
                    }
                }
            }
        }

        // Tail (0..3 entities).
        const double qMinX_s = query_aabbs[q * kAabbStride + 0];
        const double qMinY_s = query_aabbs[q * kAabbStride + 1];
        const double qMinZ_s = query_aabbs[q * kAabbStride + 2];
        const double qMaxX_s = query_aabbs[q * kAabbStride + 3];
        const double qMaxY_s = query_aabbs[q * kAabbStride + 4];
        const double qMaxZ_s = query_aabbs[q * kAabbStride + 5];
        for (std::size_t e = full_chunks * 4; e < full_chunks * 4 + tail; ++e) {
            const double* p = entity_aabbs + e * kAabbStride;
            const bool overlap =
                qMinX_s < p[3] && qMaxX_s > p[0] &&
                qMinY_s < p[4] && qMaxY_s > p[1] &&
                qMinZ_s < p[5] && qMaxZ_s > p[2];
            if (overlap) row[e >> 6] |= std::uint64_t{1} << (e & 63);
        }
    }
}

void aabb_scan_soa_avx2(const double* query_aabbs, std::size_t query_count,
                        const double* entity_aabbs, std::size_t entity_count, std::size_t entity_stride,
                        std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || entity_count == 0 || !entity_aabbs) return;

    const double* min_x = entity_aabbs;
    const double* min_y = min_x + entity_stride;
    const double* min_z = min_y + entity_stride;
    const double* max_x = min_z + entity_stride;
    const double* max_y = max_x + entity_stride;
    const double* max_z = max_y + entity_stride;
    const std::size_t full_chunks = entity_count / 4;
    for (std::size_t q = 0; q < query_count; ++q) {
        const __m256d qMinX = _mm256_set1_pd(query_aabbs[q * kAabbStride + 0]);
        const __m256d qMinY = _mm256_set1_pd(query_aabbs[q * kAabbStride + 1]);
        const __m256d qMinZ = _mm256_set1_pd(query_aabbs[q * kAabbStride + 2]);
        const __m256d qMaxX = _mm256_set1_pd(query_aabbs[q * kAabbStride + 3]);
        const __m256d qMaxY = _mm256_set1_pd(query_aabbs[q * kAabbStride + 4]);
        const __m256d qMaxZ = _mm256_set1_pd(query_aabbs[q * kAabbStride + 5]);
        std::uint64_t* row = visibility + q * row_l;
        for (std::size_t ce = 0; ce < full_chunks; ++ce) {
            const std::size_t base = ce * 4;
            const __m256d x = _mm256_and_pd(
                _mm256_cmp_pd(qMinX, _mm256_loadu_pd(max_x + base), _CMP_LT_OQ),
                _mm256_cmp_pd(qMaxX, _mm256_loadu_pd(min_x + base), _CMP_GT_OQ));
            const __m256d y = _mm256_and_pd(
                _mm256_cmp_pd(qMinY, _mm256_loadu_pd(max_y + base), _CMP_LT_OQ),
                _mm256_cmp_pd(qMaxY, _mm256_loadu_pd(min_y + base), _CMP_GT_OQ));
            const __m256d z = _mm256_and_pd(
                _mm256_cmp_pd(qMinZ, _mm256_loadu_pd(max_z + base), _CMP_LT_OQ),
                _mm256_cmp_pd(qMaxZ, _mm256_loadu_pd(min_z + base), _CMP_GT_OQ));
            const __m256d hit = _mm256_and_pd(_mm256_and_pd(x, y), z);
            const int mask = _mm256_movemask_pd(hit);
            if (mask != 0) row[base >> 6] |= static_cast<std::uint64_t>(mask) << (base & 63);
        }
        for (std::size_t e = full_chunks * 4; e < entity_count; ++e) {
            if (query_aabbs[q * kAabbStride] < max_x[e] && query_aabbs[q * kAabbStride + 3] > min_x[e]
                    && query_aabbs[q * kAabbStride + 1] < max_y[e] && query_aabbs[q * kAabbStride + 4] > min_y[e]
                    && query_aabbs[q * kAabbStride + 2] < max_z[e] && query_aabbs[q * kAabbStride + 5] > min_z[e]) {
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
        }
    }
}

} // namespace lattice::world::entity
