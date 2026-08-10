#include "world/entity/aabb_query.hpp"

#include <cstring>
#include <immintrin.h>

namespace lattice::world::entity {
namespace {

inline __mmask8 intersect_mask(__m512d q_min_x, __m512d q_max_x,
                               __m512d q_min_y, __m512d q_max_y,
                               __m512d q_min_z, __m512d q_max_z,
                               __m512d e_min_x, __m512d e_max_x,
                               __m512d e_min_y, __m512d e_max_y,
                               __m512d e_min_z, __m512d e_max_z) noexcept {
    const __mmask8 x = _mm512_cmp_pd_mask(q_min_x, e_max_x, _CMP_LT_OQ)
                      & _mm512_cmp_pd_mask(q_max_x, e_min_x, _CMP_GT_OQ);
    const __mmask8 y = _mm512_cmp_pd_mask(q_min_y, e_max_y, _CMP_LT_OQ)
                      & _mm512_cmp_pd_mask(q_max_y, e_min_y, _CMP_GT_OQ);
    const __mmask8 z = _mm512_cmp_pd_mask(q_min_z, e_max_z, _CMP_LT_OQ)
                      & _mm512_cmp_pd_mask(q_max_z, e_min_z, _CMP_GT_OQ);
    return x & y & z;
}

inline void write_mask(std::uint64_t* row, std::size_t base, __mmask8 mask) noexcept {
    if (!mask) return;
    row[base >> 6] |= static_cast<std::uint64_t>(mask) << (base & 63);
}

} // namespace

void aabb_scan_avx512(const double* query_aabbs, std::size_t query_count,
                      const double* entity_aabbs, std::size_t entity_count,
                      std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || !entity_aabbs || entity_count == 0) return;

    const std::size_t full = entity_count / 8;
    for (std::size_t q = 0; q < query_count; ++q) {
        const double* query = query_aabbs + q * kAabbStride;
        const __m512d min_x = _mm512_set1_pd(query[0]);
        const __m512d min_y = _mm512_set1_pd(query[1]);
        const __m512d min_z = _mm512_set1_pd(query[2]);
        const __m512d max_x = _mm512_set1_pd(query[3]);
        const __m512d max_y = _mm512_set1_pd(query[4]);
        const __m512d max_z = _mm512_set1_pd(query[5]);
        std::uint64_t* row = visibility + q * row_l;
        for (std::size_t chunk = 0; chunk < full; ++chunk) {
            const std::size_t base = chunk * 8;
            const double* p0 = entity_aabbs + (base + 0) * kAabbStride;
            const double* p1 = entity_aabbs + (base + 1) * kAabbStride;
            const double* p2 = entity_aabbs + (base + 2) * kAabbStride;
            const double* p3 = entity_aabbs + (base + 3) * kAabbStride;
            const double* p4 = entity_aabbs + (base + 4) * kAabbStride;
            const double* p5 = entity_aabbs + (base + 5) * kAabbStride;
            const double* p6 = entity_aabbs + (base + 6) * kAabbStride;
            const double* p7 = entity_aabbs + (base + 7) * kAabbStride;
            const __mmask8 hit = intersect_mask(
                min_x, max_x, min_y, max_y, min_z, max_z,
                _mm512_setr_pd(p0[0], p1[0], p2[0], p3[0], p4[0], p5[0], p6[0], p7[0]),
                _mm512_setr_pd(p0[3], p1[3], p2[3], p3[3], p4[3], p5[3], p6[3], p7[3]),
                _mm512_setr_pd(p0[1], p1[1], p2[1], p3[1], p4[1], p5[1], p6[1], p7[1]),
                _mm512_setr_pd(p0[4], p1[4], p2[4], p3[4], p4[4], p5[4], p6[4], p7[4]),
                _mm512_setr_pd(p0[2], p1[2], p2[2], p3[2], p4[2], p5[2], p6[2], p7[2]),
                _mm512_setr_pd(p0[5], p1[5], p2[5], p3[5], p4[5], p5[5], p6[5], p7[5]));
            write_mask(row, base, hit);
        }
        for (std::size_t e = full * 8; e < entity_count; ++e) {
            const double* p = entity_aabbs + e * kAabbStride;
            if (query[0] < p[3] && query[3] > p[0] && query[1] < p[4]
                    && query[4] > p[1] && query[2] < p[5] && query[5] > p[2]) {
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
        }
    }
}

void aabb_scan_soa_avx512(const double* query_aabbs, std::size_t query_count,
                          const double* entity_aabbs, std::size_t entity_count, std::size_t entity_stride,
                          std::uint64_t* visibility) noexcept {
    if (query_count == 0 || !visibility) return;
    const std::size_t row_l = aabb_row_longs(entity_count);
    std::memset(visibility, 0, row_l * query_count * sizeof(std::uint64_t));
    if (!query_aabbs || !entity_aabbs || entity_count == 0) return;
    const double* min_x = entity_aabbs;
    const double* min_y = min_x + entity_stride;
    const double* min_z = min_y + entity_stride;
    const double* max_x = min_z + entity_stride;
    const double* max_y = max_x + entity_stride;
    const double* max_z = max_y + entity_stride;
    const std::size_t full = entity_count / 8;
    for (std::size_t q = 0; q < query_count; ++q) {
        const double* query = query_aabbs + q * kAabbStride;
        const __m512d q_min_x = _mm512_set1_pd(query[0]);
        const __m512d q_min_y = _mm512_set1_pd(query[1]);
        const __m512d q_min_z = _mm512_set1_pd(query[2]);
        const __m512d q_max_x = _mm512_set1_pd(query[3]);
        const __m512d q_max_y = _mm512_set1_pd(query[4]);
        const __m512d q_max_z = _mm512_set1_pd(query[5]);
        std::uint64_t* row = visibility + q * row_l;
        for (std::size_t chunk = 0; chunk < full; ++chunk) {
            const std::size_t base = chunk * 8;
            const __mmask8 hit = intersect_mask(
                q_min_x, q_max_x, q_min_y, q_max_y, q_min_z, q_max_z,
                _mm512_loadu_pd(min_x + base), _mm512_loadu_pd(max_x + base),
                _mm512_loadu_pd(min_y + base), _mm512_loadu_pd(max_y + base),
                _mm512_loadu_pd(min_z + base), _mm512_loadu_pd(max_z + base));
            write_mask(row, base, hit);
        }
        for (std::size_t e = full * 8; e < entity_count; ++e) {
            if (query[0] < max_x[e] && query[3] > min_x[e] && query[1] < max_y[e]
                    && query[4] > min_y[e] && query[2] < max_z[e] && query[5] > min_z[e]) {
                row[e >> 6] |= std::uint64_t{1} << (e & 63);
            }
        }
    }
}

} // namespace lattice::world::entity
