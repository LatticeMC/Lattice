// AVX2 specialisation of the swept-AABB clamp. Processes 4 obstacles per
// iteration using `__m256d` lanes; reduces via min/max at the end.
//
// We re-use the scalar branch-and-test logic for adjust_movement: the
// per-axis loop is short (3 iterations) and is dominated by the inner
// calc_max_offset_avx2 SIMD batch. Bit-exact with the scalar reference
// because the final reduction is associative-and-commutative min/max.

#include "world/entity/collision_sweep.hpp"

#if defined(_MSC_VER)
#  include <intrin.h>
#else
#  include <immintrin.h>
#endif

#include <cmath>

namespace lattice::world::entity {

namespace {

// Lane-wise mask for the overlap test on a single cross axis.
// Returns a __m256d with -1 in lanes where the moving box overlaps the
// obstacle's interval, 0 otherwise. Strict-less-than as in vanilla.
inline __m256d overlap_mask(__m256d m_min, __m256d m_max,
                            __m256d o_min, __m256d o_max) noexcept {
    // Overlap iff !(m_max <= o_min) && !(m_min >= o_max)
    // i.e. m_max > o_min  &&  m_min < o_max
    const __m256d cmp1 = _mm256_cmp_pd(m_max, o_min, _CMP_GT_OQ);
    const __m256d cmp2 = _mm256_cmp_pd(m_min, o_max, _CMP_LT_OQ);
    return _mm256_and_pd(cmp1, cmp2);
}

inline __m256d gather4(const double* obstacles, std::size_t base,
                       int element_offset) noexcept {
    return _mm256_setr_pd(
        obstacles[(base + 0) * kCollisionAabbStride + element_offset],
        obstacles[(base + 1) * kCollisionAabbStride + element_offset],
        obstacles[(base + 2) * kCollisionAabbStride + element_offset],
        obstacles[(base + 3) * kCollisionAabbStride + element_offset]);
}

inline double clamp_axis_obstacle_scalar(int axis, const double* m,
                                         double desired, const double* o) noexcept {
    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;
    if (m[a1 + 3] <= o[a1] || m[a1] >= o[a1 + 3]) return desired;
    if (m[a2 + 3] <= o[a2] || m[a2] >= o[a2 + 3]) return desired;
    const double m_min = m[axis];
    const double m_max = m[axis + 3];
    const double o_min = o[axis];
    const double o_max = o[axis + 3];
    if (desired > 0.0 && m_max <= o_min) {
        const double gap = o_min - m_max;
        if (gap < desired) return gap;
    } else if (desired < 0.0 && m_min >= o_max) {
        const double gap = o_max - m_min;
        if (gap > desired) return gap;
    }
    return desired;
}

} // namespace

double calc_max_offset_avx2(int axis, const double* moving,
                            double desired,
                            const double* obstacles,
                            std::size_t obstacle_count) noexcept {
    if (!moving || axis < 0 || axis > 2 || !obstacles || obstacle_count == 0) return desired;
    if (desired == 0.0) return 0.0;

    const int a1 = (axis + 1) % 3;
    const int a2 = (axis + 2) % 3;

    // Broadcast the moving box's relevant components.
    const __m256d m_min_a  = _mm256_set1_pd(moving[axis]);
    const __m256d m_max_a  = _mm256_set1_pd(moving[axis + 3]);
    const __m256d m_min_b1 = _mm256_set1_pd(moving[a1]);
    const __m256d m_max_b1 = _mm256_set1_pd(moving[a1 + 3]);
    const __m256d m_min_b2 = _mm256_set1_pd(moving[a2]);
    const __m256d m_max_b2 = _mm256_set1_pd(moving[a2 + 3]);

    const bool moving_positive = desired > 0.0;
    __m256d acc = _mm256_set1_pd(desired);
    const std::size_t full = obstacle_count / 4;

    for (std::size_t i = 0; i < full; ++i) {
        const std::size_t base = i * 4;
        const __m256d o_min_a  = gather4(obstacles, base, axis);
        const __m256d o_max_a  = gather4(obstacles, base, axis + 3);
        const __m256d o_min_b1 = gather4(obstacles, base, a1);
        const __m256d o_max_b1 = gather4(obstacles, base, a1 + 3);
        const __m256d o_min_b2 = gather4(obstacles, base, a2);
        const __m256d o_max_b2 = gather4(obstacles, base, a2 + 3);

        const __m256d ov1 = overlap_mask(m_min_b1, m_max_b1, o_min_b1, o_max_b1);
        const __m256d ov2 = overlap_mask(m_min_b2, m_max_b2, o_min_b2, o_max_b2);
        const __m256d ovAll = _mm256_and_pd(ov1, ov2);

        if (moving_positive) {
            // Candidate clamp = o_min_a - m_max_a, but only valid when
            // m_max_a <= o_min_a (obstacle ahead of us) AND ovAll.
            const __m256d gap   = _mm256_sub_pd(o_min_a, m_max_a);
            const __m256d valid = _mm256_and_pd(ovAll,
                _mm256_cmp_pd(m_max_a, o_min_a, _CMP_LE_OQ));
            // For invalid lanes, replace with `desired` so the min is unchanged.
            const __m256d candidate = _mm256_blendv_pd(_mm256_set1_pd(desired), gap, valid);
            acc = _mm256_min_pd(acc, candidate);
        } else {
            const __m256d gap   = _mm256_sub_pd(o_max_a, m_min_a);
            const __m256d valid = _mm256_and_pd(ovAll,
                _mm256_cmp_pd(m_min_a, o_max_a, _CMP_GE_OQ));
            // For negative direction we want max, since gap is negative.
            const __m256d candidate = _mm256_blendv_pd(_mm256_set1_pd(desired), gap, valid);
            acc = _mm256_max_pd(acc, candidate);
        }
    }

    // Horizontal reduce.
    alignas(32) double lanes[4];
    _mm256_store_pd(lanes, acc);
    double r;
    if (moving_positive) {
        r = lanes[0];
        if (lanes[1] < r) r = lanes[1];
        if (lanes[2] < r) r = lanes[2];
        if (lanes[3] < r) r = lanes[3];
    } else {
        r = lanes[0];
        if (lanes[1] > r) r = lanes[1];
        if (lanes[2] > r) r = lanes[2];
        if (lanes[3] > r) r = lanes[3];
    }

    // Scalar tail.
    for (std::size_t i = full * 4; i < obstacle_count; ++i) {
        r = clamp_axis_obstacle_scalar(axis, moving, r,
                                       obstacles + i * kCollisionAabbStride);
    }
    return r;
}

void adjust_movement_avx2(const double* moving,
                          double* out_movement,
                          const double* obstacles,
                          std::size_t obstacle_count) noexcept {
    if (!moving || !out_movement) return;
    if (!obstacles || obstacle_count == 0) return;

    double mv[6];
    for (int k = 0; k < 6; ++k) mv[k] = moving[k];

    constexpr int axis_order[3] = { 1, 0, 2 };
    for (int idx = 0; idx < 3; ++idx) {
        const int axis = axis_order[idx];
        const double dv = calc_max_offset_avx2(
            axis, mv, out_movement[axis], obstacles, obstacle_count);
        out_movement[axis] = dv;
        if (dv != 0.0) {
            mv[axis]     += dv;
            mv[axis + 3] += dv;
        }
    }
}

} // namespace lattice::world::entity
